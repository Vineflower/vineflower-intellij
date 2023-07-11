package net.earthcomputer.quiltflowerintellij

import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.psi.impl.compiled.ClsFileImpl
import java.io.IOException
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException

abstract class QuiltflowerDecompilerBase : ClassFileDecompilers.Full() {
    companion object {
        private val LOGGER = logger<QuiltflowerDecompilerBase>()
        private val FILE_LANGUAGE_KEY = Key.create<Pair<WeakReference<QuiltflowerInvoker>, String>>("quiltflower.language")

        fun getText(file: VirtualFile): String {
            val indicator = ProgressManager.getInstance().progressIndicator
            if (indicator != null) {
                indicator.text = "Decompiling ${file.name}"
            }

            val quiltflowerInvoker = runCatching { QuiltflowerState.getInstance().getQuiltflowerInvoker().get() }.getOrNull()
                    ?: return LoadTextUtil.loadText(file).toString()

            try {
                try {
                    return quiltflowerInvoker.decompile(file)
                } catch (e: InvocationTargetException) {
                    throw e.cause ?: e
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                if (e.javaClass.name == "net.earthcomputer.quiltflowerintellij.impl.MyLogger\$InternalException" && e.cause is IOException) {
                    LOGGER.warn(file.url, e)
                    return ""
                }
                if (ApplicationManager.getApplication().isUnitTestMode) {
                    throw AssertionError(file.url, e)
                }
                return "// \$QF: cannot decompile\n${e.stackTraceToString().prependIndent("// ")}\n\n${ClsFileImpl.decompile(file)}"
            }
        }
    }

    abstract val language: Language
    abstract val sourceFileType: FileType

    override fun accepts(file: VirtualFile): Boolean {
        val state = QuiltflowerState.getInstance()
        if (!state.enabled || state.hadError) {
            return false
        }
        val language = getLanguage(file) ?: return false
        return acceptsLanguage(language)
    }

    abstract fun acceptsLanguage(language: String): Boolean

    private fun getLanguage(file: VirtualFile): String? {
        val quiltflowerInvoker = runCatching { QuiltflowerState.getInstance().getQuiltflowerInvoker().join() }.getOrNull() ?: return null
        val language = file.getUserData(FILE_LANGUAGE_KEY)
        if (language != null) {
            val (lastInvoker, lastLanguage) = language
            if (lastInvoker.get() === quiltflowerInvoker) {
                return lastLanguage
            }
        }
        return try {
            quiltflowerInvoker.getLanguage(file).also { lang ->
                file.putUserData(FILE_LANGUAGE_KEY, WeakReference(quiltflowerInvoker) to lang)
            }
        } catch (e: Throwable) {
            LOGGER.error("Error while getting the language of ${file.path}", e)
            null
        }
    }

    override fun createFileViewProvider(file: VirtualFile, manager: PsiManager, physical: Boolean) =
        MyFileViewProvider(manager, file, physical)

    abstract fun createDecompiledFile(viewProvider: FileViewProvider, contents: ResettableLazy<String>): PsiFile

    inner class MyFileViewProvider(
        private val manager: PsiManager,
        private val file: VirtualFile,
        physical: Boolean
    ) : SingleRootFileViewProvider(manager, file, physical, language) {
        private val textContents = ResettableLazy {
            QuiltflowerDecompilerBase.getText(file)
        }

        override fun createFile(project: Project, file: VirtualFile, fileType: FileType): PsiFile {
            return createDecompiledFile(this, textContents)
        }

        override fun createCopy(copy: VirtualFile): SingleRootFileViewProvider {
            return MyFileViewProvider(manager, file, false)
        }

        override fun getContents() = textContents.value
    }
}
