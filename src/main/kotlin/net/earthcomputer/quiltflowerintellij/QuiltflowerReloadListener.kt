package net.earthcomputer.quiltflowerintellij

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor

class QuiltflowerReloadListener : DynamicPluginListener {
    override fun beforePluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        QuiltflowerState.getInstance().initialize()
    }
}
