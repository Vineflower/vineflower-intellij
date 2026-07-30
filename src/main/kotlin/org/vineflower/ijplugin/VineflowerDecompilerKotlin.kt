@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package org.vineflower.ijplugin

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiManager
import com.intellij.psi.compiled.ClsStubBuilder
import com.intellij.psi.stubs.PsiFileStub
import com.intellij.psi.stubs.PsiFileStubImpl
import com.intellij.psi.stubs.StubElement
import com.intellij.util.indexing.FileContent
import org.jetbrains.kotlin.analysis.decompiler.psi.KotlinDecompiledFileViewProvider
import org.jetbrains.kotlin.analysis.decompiler.psi.file.KtDecompiledFile
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImplementationDetail
import org.jetbrains.kotlin.psi.stubs.KotlinFileStub
import org.jetbrains.kotlin.psi.stubs.impl.KotlinFileStubImpl
import org.jetbrains.kotlin.psi.stubs.KotlinFileStubKind
import org.jetbrains.kotlin.psi.stubs.KotlinImportDirectiveStub
import org.jetbrains.kotlin.psi.stubs.KotlinStubElement
import org.jetbrains.kotlin.psi.stubs.elements.KtFileStubBuilder

class VineflowerDecompilerKotlin : VineflowerDecompilerBase() {
    private val myStubBuilder = StubBuilder()

    override val language = KotlinLanguage.INSTANCE
    override val sourceFileType = KotlinFileType.INSTANCE

    override fun acceptsLanguage(language: String) = language == "kotlin"

    override fun getStubBuilder(): ClsStubBuilder = myStubBuilder

    override fun createFileViewProvider(file: VirtualFile, manager: PsiManager, physical: Boolean) =
        KotlinDecompiledFileViewProvider(manager, file, physical) {
            MyDecompiledFile(it)
        }

    override fun createDecompiledFile(viewProvider: FileViewProvider, contents: ResettableLazy<String>) =
        error("Should not be called")

    private inner class StubBuilder : ClsStubBuilder() {
        private val logger = logger<StubBuilder>()

        override fun getStubVersion() = -1

        @OptIn(KtImplementationDetail::class)
        override fun buildFileStub(content: FileContent): PsiFileStub<*>? {
            try {
                val text = getText(content.file)
                val viewProvider = KotlinDecompiledFileViewProvider(
                    PsiManager.getInstance(content.project),
                    content.file,
                    true
                ) { null }
                val file = MyDecompiledFile(viewProvider)
                val builder = KtFileStubBuilder()
                return builder.createStubForFile(file) as KotlinFileStubImpl
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to decompile ${content.file.url}", e)
                return null
            }
        }
    }

    private class MyDecompiledFile(viewProvider: KotlinDecompiledFileViewProvider) : KtDecompiledFile(viewProvider) {
        override fun getStub() = stubTree?.root as KotlinFileStub?

        override fun toString(): String = super.toString().replace("KtFile", "VfDecompiledFile")
    }
}
