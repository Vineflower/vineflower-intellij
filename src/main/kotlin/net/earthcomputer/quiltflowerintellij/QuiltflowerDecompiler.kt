package net.earthcomputer.quiltflowerintellij

import com.intellij.execution.filters.LineNumbersMapping
import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.Strings
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readBytes
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.psi.search.GlobalSearchScope
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Predicate

class QuiltflowerDecompiler : ClassFileDecompilers.Light() {
    companion object {
        private val LOGGER = logger<QuiltflowerDecompiler>()
    }

    override fun accepts(file: VirtualFile): Boolean {
        val state = QuiltflowerState.getInstance()
        return state.enabled && !state.hadError
    }

    override fun getText(file: VirtualFile): CharSequence {
        val indicator = ProgressManager.getInstance().progressIndicator
        if (indicator != null) {
            indicator.text = "Decompiling ${file.name}"
        }

        val classLoader = runCatching { QuiltflowerState.getInstance().getQuiltflowerClassLoader().get() }.getOrNull()
            ?: return LoadTextUtil.loadText(file)

        try {
            try {
                return doDecompile(file, classLoader)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            if (e.javaClass.name == "net.earthcomputer.quiltflowerintellij.impl.MyLogger\$InternalException" && e.cause is IOException) {
                LOGGER.warn(file.url, e)
                return Strings.EMPTY_CHAR_SEQUENCE
            }
            if (ApplicationManager.getApplication().isUnitTestMode) {
                throw AssertionError(file.url, e)
            }
            throw CannotDecompileException(file.url, e)
        }
    }

    private fun doDecompile(file: VirtualFile, classLoader: URLClassLoader): CharSequence {
        val mask = "${file.nameWithoutExtension}$"
        val files = listOf(file) + file.parent.children.filter { it.name.startsWith(mask) && it.fileType === JavaClassFileType.INSTANCE }

        // construct the bytecode provider
        val myBytecodeProviderClass = classLoader.loadClass("net.earthcomputer.quiltflowerintellij.impl.MyBytecodeProvider")
        val myBytecodeProviderCtor = myBytecodeProviderClass.getDeclaredConstructor(java.util.Map::class.java)
        myBytecodeProviderCtor.isAccessible = true
        val bytecodeProvider = myBytecodeProviderCtor.newInstance(files.associate {
            File(it.path).absolutePath to it.contentsToByteArray(false)
        })

        // construct the result saver
        val myResultSaverClass = classLoader.loadClass("net.earthcomputer.quiltflowerintellij.impl.MyResultSaver")
        val myResultSaverCtor = myResultSaverClass.getDeclaredConstructor()
        myResultSaverCtor.isAccessible = true
        val resultSaver = myResultSaverCtor.newInstance()

        // construct the logger
        val myLoggerClass = classLoader.loadClass("net.earthcomputer.quiltflowerintellij.impl.MyLogger")
        val myLoggerCtor = myLoggerClass.getDeclaredConstructor(
                Consumer::class.java,
                BiConsumer::class.java,
                BiConsumer::class.java,
                BiConsumer::class.java,
                Consumer::class.java
        )
        myLoggerCtor.isAccessible = true
        val logger = myLoggerCtor.newInstance(
                Consumer<String> { LOGGER.error(it) },
                BiConsumer<String, Throwable?> { text, t -> if (t == null) LOGGER.warn(text) else LOGGER.warn(text, t) },
                BiConsumer<String, Throwable?> { text, t -> if (t == null) LOGGER.info(text) else LOGGER.info(text, t) },
                BiConsumer<String, Throwable?> { text, t -> if (t == null) LOGGER.debug(text) else LOGGER.debug(text, t) },
                Consumer<Throwable> { throw ProcessCanceledException(it) }
        )

        // gather the options, enforce overrides
        val options = QuiltflowerState.getInstance().quiltflowerSettings.toMutableMap()
        options.keys.removeAll(QuiltflowerPreferences.ignoredPreferences)
        for ((k, v) in QuiltflowerPreferences.defaultOverrides) {
            options.putIfAbsent(k, v.toString())
        }
        options.compute("ind") { _, v -> if (v == null) null else " ".repeat(v.toInt()) } // indent
        if (Registry.`is`("decompiler.use.line.mapping")) {
            options["bsm"] = "1" // bytecode source mapping
        }
        if (Registry.`is`("decompiler.dump.original.lines")) {
            options["__dump_original_lines__"] = "1"
        }

        // construct the decompiler
        val decompilerClass = classLoader.loadClass("org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler")
        val decompilerCtor = decompilerClass.getConstructor(
                classLoader.loadClass("org.jetbrains.java.decompiler.main.extern.IBytecodeProvider"),
                classLoader.loadClass("org.jetbrains.java.decompiler.main.extern.IResultSaver"),
                java.util.Map::class.java,
                classLoader.loadClass("org.jetbrains.java.decompiler.main.extern.IFernflowerLogger")
        )
        decompilerCtor.isAccessible = true
        val decompiler = decompilerCtor.newInstance(bytecodeProvider, resultSaver, options, logger)

        // add sources to the decompiler
        val addSourceMethod = decompilerClass.getDeclaredMethod("addSource", File::class.java)
        files.forEach { addSourceMethod.invoke(decompiler, File(it.path)) }

        // check if library API is available
        try {
            val iContextSourceClass = classLoader.loadClass("org.jetbrains.java.decompiler.main.extern.IContextSource")
            iContextSourceClass.getMethod("isLazy")

            val project = ProjectLocator.getInstance().guessProjectForFile(file)
            if (project != null) {
                // construct a context source
                val myContextSourceClass = classLoader.loadClass("net.earthcomputer.quiltflowerintellij.impl.MyContextSource")
                val myContextSourceCtor = myContextSourceClass.getDeclaredConstructor(
                        Predicate::class.java,
                        java.util.function.Function::class.java
                )
                myContextSourceCtor.isAccessible = true
                val myContextSource = myContextSourceCtor.newInstance(
                        Predicate<String> { doesClassExist(project, it) },
                        java.util.function.Function<String, ByteArray?> { getClassBytes(project, it) },
                )

                decompilerClass.getMethod("addLibrary", iContextSourceClass).invoke(decompiler, myContextSource)
            }
        } catch (ignore: ClassNotFoundException) {
        } catch (ignore: NoSuchMethodException) {
        }

        // invoke the decompiler
        decompilerClass.getMethod("decompileContext").invoke(decompiler)

        // extract line mappings
        val mappingField = myResultSaverClass.getDeclaredField("myMapping")
        mappingField.isAccessible = true
        val mapping = mappingField.get(resultSaver) as IntArray?
        if (mapping != null) {
            file.putUserData(LineNumbersMapping.LINE_NUMBERS_MAPPING_KEY, LineNumbersMapping.ArrayBasedMapping(mapping))
        }

        // extract the decompiled text
        val resultField = myResultSaverClass.getDeclaredField("myResult")
        resultField.isAccessible = true
        return (resultField.get(resultSaver) as CharSequence)
    }

    private fun doesClassExist(project: Project, className: String): Boolean {
        return ReadAction.compute<Boolean, Throwable> { findClass(project, className) != null }
    }

    private fun getClassBytes(project: Project, className: String): ByteArray? {
        return ReadAction.compute<ByteArray?, Throwable> {
            doGetClassBytes(project, className)
        }
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
}
