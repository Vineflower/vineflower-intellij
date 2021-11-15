package net.earthcomputer.quiltflowerintellij

import com.intellij.execution.filters.LineNumbersMapping
import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.Strings
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.compiled.ClassFileDecompilers
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.function.BiConsumer
import java.util.function.Consumer

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

        val classLoader = runCatching { QuiltflowerState.getInstance().getQuiltflowerClassLoader()?.get() }.getOrNull()
            ?: return LoadTextUtil.loadText(file)

        try {
            try {
                val mask = "${file.nameWithoutExtension}$"
                val files = listOf(file) + file.parent.children.filter { it.name.startsWith(mask) && it.fileType === JavaClassFileType.INSTANCE }

                val decompilerClass = classLoader.loadClass("org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler")
                val myBytecodeProviderClass = classLoader.loadClass("net.earthcomputer.quiltflowerintellij.impl.MyBytecodeProvider")
                val myBytecodeProviderCtor = myBytecodeProviderClass.getDeclaredConstructor(java.util.Map::class.java)
                myBytecodeProviderCtor.isAccessible = true
                val bytecodeProvider = myBytecodeProviderCtor.newInstance(files.associate {
                    File(it.path).absolutePath to it.contentsToByteArray(false)
                })
                val myResultSaverClass = classLoader.loadClass("net.earthcomputer.quiltflowerintellij.impl.MyResultSaver")
                val myResultSaverCtor = myResultSaverClass.getDeclaredConstructor()
                myResultSaverCtor.isAccessible = true
                val resultSaver = myResultSaverCtor.newInstance()
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

                val decompilerCtor = decompilerClass.getConstructor(
                    classLoader.loadClass("org.jetbrains.java.decompiler.main.extern.IBytecodeProvider"),
                    classLoader.loadClass("org.jetbrains.java.decompiler.main.extern.IResultSaver"),
                    java.util.Map::class.java,
                    classLoader.loadClass("org.jetbrains.java.decompiler.main.extern.IFernflowerLogger")
                )
                decompilerCtor.isAccessible = true
                val decompiler = decompilerCtor.newInstance(bytecodeProvider, resultSaver, options, logger)
                val addSourceMethod = decompilerClass.getDeclaredMethod("addSource", File::class.java)
                files.forEach { addSourceMethod.invoke(decompiler, File(it.path)) }
                decompilerClass.getMethod("decompileContext").invoke(decompiler)

                val mappingField = myResultSaverClass.getDeclaredField("myMapping")
                mappingField.isAccessible = true
                val mapping = mappingField.get(resultSaver) as IntArray?
                if (mapping != null) {
                    file.putUserData(LineNumbersMapping.LINE_NUMBERS_MAPPING_KEY, LineNumbersMapping.ArrayBasedMapping(mapping))
                }

                val resultField = myResultSaverClass.getDeclaredField("myResult")
                resultField.isAccessible = true
                return (resultField.get(resultSaver) as CharSequence)
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
}
