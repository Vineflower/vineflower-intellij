package net.earthcomputer.quiltflowerintellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.compiled.ClassFileDecompilers

class QuiltflowerDecompilerLight : ClassFileDecompilers.Light() {
    override fun accepts(file: VirtualFile): Boolean {
        val state = QuiltflowerState.getInstance()
        return state.enabled && !state.hadError
    }

    override fun getText(file: VirtualFile) = QuiltflowerDecompilerBase.getText(file)
}
