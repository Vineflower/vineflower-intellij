package net.earthcomputer.quiltflowerintellij

import com.intellij.ide.AppLifecycleListener

class QuiltflowerPreloader : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        QuiltflowerState.getInstance().initialize()
    }
}
