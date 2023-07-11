package org.vineflower.ijplugin

import com.intellij.ide.AppLifecycleListener

class VineflowerPreloader : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        VineflowerState.getInstance().initialize()
    }
}
