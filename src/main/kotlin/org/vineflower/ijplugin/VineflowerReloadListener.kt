package org.vineflower.ijplugin

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor

class VineflowerReloadListener : DynamicPluginListener {
    override fun beforePluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        VineflowerState.getInstance().initialize()
    }
}
