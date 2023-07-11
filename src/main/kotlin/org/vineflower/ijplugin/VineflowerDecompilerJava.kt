package org.vineflower.ijplugin

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.compiled.ClsStubBuilder
import com.intellij.psi.impl.compiled.ClsFileImpl

// TODO: full decompilers
class VineflowerDecompilerJava : VineflowerDecompilerBase() {
    private val myStubBuilder = Class.forName("com.intellij.psi.impl.compiled.ClsDecompilerImpl\$MyClsStubBuilder")
        .getDeclaredConstructor()
        .apply { isAccessible = true }
        .newInstance() as ClsStubBuilder

    override val language = JavaLanguage.INSTANCE
    override val sourceFileType: FileType = JavaFileType.INSTANCE

    override fun acceptsLanguage(language: String) = language == "java"

    override fun getStubBuilder() = myStubBuilder

    override fun createDecompiledFile(viewProvider: FileViewProvider, contents: ResettableLazy<String>) =
        DecompiledFile(viewProvider, contents)

    class DecompiledFile(viewProvider: FileViewProvider, private val contents: ResettableLazy<String>) : ClsFileImpl(viewProvider) {
        override fun getText(): String = contents.value

        override fun onContentReload() {
            super.onContentReload()
            contents.reset()
        }
    }
}