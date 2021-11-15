package net.earthcomputer.quiltflowerintellij

import com.intellij.openapi.application.PreloadingActivity
import com.intellij.openapi.progress.ProgressIndicator

class QuiltflowerPreloadingActivity : PreloadingActivity() {
    override fun preload(indicator: ProgressIndicator) {
        QuiltflowerState.getInstance().initialize()
    }
}
