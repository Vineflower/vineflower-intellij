package org.vineflower.ijplugin

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.compiled.ClassFileDecompilers

class VineflowerDecompilerLight : ClassFileDecompilers.Light() {
    override fun accepts(file: VirtualFile): Boolean {
        val state = VineflowerState.getInstance()
        return state.enabled && !state.hadError
    }

    override fun getText(file: VirtualFile) = VineflowerDecompilerBase.getText(file)
}
