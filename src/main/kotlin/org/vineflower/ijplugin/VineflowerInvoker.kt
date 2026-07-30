package org.vineflower.ijplugin

import com.intellij.execution.filters.LineNumbersMapping
import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readBytes
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Predicate

private typealias ReadActionRunner = (() -> Unit) -> Unit

class VineflowerInvoker(classLoader: ClassLoader) {
    companion object {
        private val LOGGER = logger<VineflowerInvoker>()
    }

    private val myBytecodeProviderCtor = classLoader.loadClass("org.vineflower.ijplugin.impl.MyBytecodeProvider")
        .getDeclaredConstructor(java.util.Map::class.java)
        .apply { isAccessible = true }

    private val myResultSaverClass = classLoader.loadClass("org.vineflower.ijplugin.impl.MyResultSaver")
    private val myResultSaverCtor = myResultSaverClass
        .getDeclaredConstructor()
        .apply { isAccessible = true }
    private val myResultSaverMapping = myResultSaverClass.getDeclaredField("myMapping")
        .apply { isAccessible = true }
    private val myResultSaverResult = myResultSaverClass.getDeclaredField("myResult")
        .apply { isAccessible = true }

    private val myLogger = classLoader.loadClass("org.vineflower.ijplugin.impl.MyLogger")
        .getDeclaredConstructor(
            Consumer::class.java,
            BiConsumer::class.java,
            BiConsumer::class.java,
            BiConsumer::class.java,
            Consumer::class.java,
        )
        .apply { isAccessible = true }
        .newInstance(
            Consumer<String> { LOGGER.error(it) },
            BiConsumer<String, Throwable?> { text, t -> if (t == null) LOGGER.warn(text) else LOGGER.warn(text, t) },
            BiConsumer<String, Throwable?> { text, t -> if (t == null) LOGGER.info(text) else LOGGER.info(text, t) },
            BiConsumer<String, Throwable?> { text, t -> if (t == null) LOGGER.debug(text) else LOGGER.debug(text, t) },
            Consumer<Throwable> { throw ProcessCanceledException(it) }
        )


    private val baseDecompilerClass = classLoader.loadClass("org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler")
    private val baseDecompilerCtor = baseDecompilerClass.getConstructor(
        classLoader.loadClass("org.jetbrains.java.decompiler.main.extern.IBytecodeProvider"),
        classLoader.loadClass("org.jetbrains.java.decompiler.main.extern.IResultSaver"),
        java.util.Map::class.java,
        classLoader.loadClass("org.jetbrains.java.decompiler.main.extern.IFernflowerLogger"),
    ).apply { isAccessible = true }
    private val baseDecompilerAddSource = baseDecompilerClass.getMethod("addSource", File::class.java)
    private val baseDecompilerDecompileContext = baseDecompilerClass.getMethod("decompileContext")

    private val contextSourceFeature = runCatching { ContextSourceFeature(classLoader, baseDecompilerClass) }.getOrNull()
    private val languageFeature = runCatching { LanguageFeature(classLoader) }.getOrNull()

    fun getLanguage(file: VirtualFile): String {
        return languageFeature?.getLanguage(file.readBytes()) ?: "java"
    }

    fun decompile(file: VirtualFile): String {
        val mask = "${file.nameWithoutExtension}$"
        val files = listOf(file) + file.parent.children.filter { it.name.startsWith(mask) && it.fileType === JavaClassFileType.INSTANCE }

        // construct the bytecode provider
        val bytecodeProvider = myBytecodeProviderCtor.newInstance(files.associate {
            File(it.path).absolutePath to it.contentsToByteArray(false)
        })

        // construct the result saver
        val resultSaver = myResultSaverCtor.newInstance()

        // gather the options, enforce overrides
        val options = VineflowerState.getInstance().vineflowerSettings.toMutableMap()
        options.keys.removeAll(ClassicVineflowerPreferences.ignoredPreferences)
        for ((k, v) in ClassicVineflowerPreferences.defaultOverrides) {
            options.putIfAbsent(k, v.toString())
        }
        options.compute("ind") { _, v -> if (v == null) null else " ".repeat(v.toInt()) } // indent
        if (Registry.`is`("decompiler.use.line.mapping")) {
            options["bsm"] = "1" // bytecode source mapping
        }
        if (Registry.`is`("decompiler.dump.original.lines")) {
            options["__dump_original_lines__"] = "1"
        }

        fun doDecompile(readActionRunner: ReadActionRunner, project: Project?) {
            // construct the decompiler
            val decompiler = baseDecompilerCtor.newInstance(bytecodeProvider, resultSaver, options, myLogger)

            // add sources to the decompiler
            files.forEach { baseDecompilerAddSource.invoke(decompiler, File(it.path)) }

            // check if library API is available
            if (contextSourceFeature != null) {
                if (project != null) {
                    val myContextSource = contextSourceFeature.myContextSourceCtor.newInstance(
                            Predicate<String> { doesClassExist(project, it, readActionRunner) },
                            java.util.function.Function<String, ByteArray?> { getClassBytes(project, it, readActionRunner) },
                    )
                    contextSourceFeature.baseDecompilerAddLibraryMethod.invoke(decompiler, myContextSource)
                }
            }

            baseDecompilerDecompileContext.invoke(decompiler)
        }

        // Invoke the decompiler
        // Read actions from the same thread as the decompiler's main thread may create a deadlock.
        // The reasoning is hard to figure out, but it seems to be related to threading done by the decompiler.
        // As a fix, run the main decompiler thread separately and queue reads to be done on the main thread instead.
        // TODO: Investigate the deadlock issue further

        val decompileFuture = CompletableFuture<Unit>()
        val queue = LinkedBlockingQueue<() -> Unit>()
        val canReadOnOwn = ApplicationManager.getApplication().isReadAccessAllowed
        val project = ProjectLocator.getInstance().guessProjectForFile(file)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                doDecompile ({ readTask ->
                    val future = CompletableFuture<Unit>()
                    queue.add {
                        try {
                            if (canReadOnOwn) {
                                readTask()
                            } else {
                                ApplicationManager.getApplication().runReadAction(readTask)
                            }
                            future.complete(Unit)
                        } catch (e: Throwable) {
                            future.completeExceptionally(e)
                        }
                    }
                    future.join()
                }, project)

                queue.add {
                    decompileFuture.complete(Unit)
                }
            } catch (e: Throwable) {
                queue.add {
                    decompileFuture.completeExceptionally(e)
                }
            }
        }

        while (!decompileFuture.isDone) {
            queue.take()()
        }
        decompileFuture.join()

        // extract line mappings
        val mapping = myResultSaverMapping.get(resultSaver) as IntArray?
        if (mapping != null) {
            file.putUserData(LineNumbersMapping.LINE_NUMBERS_MAPPING_KEY, LineNumbersMapping.ArrayBasedMapping(mapping))
        }

        // extract the decompiled text
        return myResultSaverResult.get(resultSaver) as String
    }

    private fun doesClassExist(project: Project, className: String, readActionRunner: ReadActionRunner): Boolean {
        var result: Boolean? = null
        readActionRunner {
            result = findClass(project, className) != null
        }
        return result!!
    }

    private fun getClassBytes(project: Project, className: String, readActionRunner: ReadActionRunner): ByteArray? {
        var result: ByteArray? = null
        readActionRunner {
            result = doGetClassBytes(project, className)
        }
        return result
    }

    private fun doGetClassBytes(project: Project, className: String): ByteArray? {
        val clazz = findClass(project, className) ?: return null
        val virtualFile = clazz.containingFile.virtualFile ?: return null
        if (virtualFile.extension != "class") {
            return null
        }

        // try to find class file based on the actual class name, solves the problem of inner classes
        val nameWithoutPackage = className.substringAfterLast('/')
        val actualFile = virtualFile.parent?.findChild("$nameWithoutPackage.class") ?: virtualFile
        return actualFile.readBytes()
    }

    private fun findClass(project: Project, className: String): PsiClass? {
        return DumbService.getInstance(project).computeWithAlternativeResolveEnabled<PsiClass?, Throwable> {
            val dottyName = className.replace('/', '.').replace('$', '.')
            JavaPsiFacade.getInstance(project).findClass(dottyName, GlobalSearchScope.allScope(project))
        }
    }

    private class ContextSourceFeature(classLoader: ClassLoader, baseDecompilerClass: Class<*>) {
        private val iContextSourceClass = classLoader.loadClass("org.jetbrains.java.decompiler.main.extern.IContextSource")

        init {
            // check that IContextSource.isLazy() exists
            iContextSourceClass.getMethod("isLazy")
        }

        val myContextSourceCtor = classLoader.loadClass("org.vineflower.ijplugin.impl.MyContextSource")
            .getDeclaredConstructor(
                Predicate::class.java,
                java.util.function.Function::class.java
            )
            .apply { isAccessible = true }

        val baseDecompilerAddLibraryMethod = baseDecompilerClass.getMethod("addLibrary", iContextSourceClass)
    }

    private inner class LanguageFeature(classLoader: ClassLoader) {
        private val dataInputFullStreamClass = classLoader.loadClass("org.jetbrains.java.decompiler.util.DataInputFullStream")
        private val dataInputFullStreamCtor = dataInputFullStreamClass.getConstructor(ByteArray::class.java)

        private val structClassClass = classLoader.loadClass("org.jetbrains.java.decompiler.struct.StructClass")
        private val structClassCreate = structClassClass.getMethod("create", dataInputFullStreamClass, java.lang.Boolean.TYPE)

        private val pluginContextClass = classLoader.loadClass("org.jetbrains.java.decompiler.main.plugins.PluginContext")
        private val getCurrentPluginContext = pluginContextClass.getMethod("getCurrentContext")
        private val pluginContextGetLanguageSpec = pluginContextClass.getMethod("getLanguageSpec", structClassClass)

        private val languageSpecClass = classLoader.loadClass("org.jetbrains.java.decompiler.api.plugin.LanguageSpec")
        private val languageSpecName = languageSpecClass.getField("name")

        private val getCurrentDecompContext = classLoader.loadClass("org.jetbrains.java.decompiler.main.DecompilerContext")
            .getMethod("getCurrentContext")

        fun getLanguage(bytes: ByteArray): String {
            // ensure a decompilation context is available (otherwise the structclass constructor will fail)
            if (getCurrentDecompContext.invoke(null) == null) {
                val empty = emptyMap<Nothing, Nothing>()
                val bytecodeProvider = myBytecodeProviderCtor.newInstance(empty)
                val resultSaver = myResultSaverCtor.newInstance()

                baseDecompilerCtor.newInstance(bytecodeProvider, resultSaver, empty, myLogger)
            }

            val inputStream = dataInputFullStreamCtor.newInstance(bytes)
            val structClass = structClassCreate.invoke(null, inputStream, true)
            val pluginContext = getCurrentPluginContext.invoke(null)
            val languageSpec = pluginContextGetLanguageSpec.invoke(pluginContext, structClass) ?: return "java"
            return languageSpecName.get(languageSpec) as String
        }
    }
}
