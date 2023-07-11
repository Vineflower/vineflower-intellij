package org.vineflower.ijplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.util.text.SemVer
import javax.swing.JComponent

class VineflowerSettings : SearchableConfigurable {
    private var panel: VineflowerSettingsPanel? = null

    override fun createComponent(): JComponent {
        return VineflowerSettingsPanel(VineflowerState.getInstance().vineflowerVersion).also { panel = it }.mainPanel
    }

    override fun isModified(): Boolean {
        val panel = this.panel ?: return false
        val state = VineflowerState.getInstance()
        return panel.enableVineflowerCheckbox.isSelected != state.enabled
                || panel.autoUpdateCheckbox.isSelected != state.autoUpdate
                || panel.enableSnapshotsCheckbox.isSelected != state.enableSnapshots
                || panel.vineflowerVersionComboBox.selectedItem != state.vineflowerVersion
                || panel.vineflowerSettings != state.vineflowerSettings
    }

    override fun reset() {
        val panel = this.panel ?: return
        val state = VineflowerState.getInstance()
        panel.enableVineflowerCheckbox.isSelected = state.enabled
        panel.autoUpdateCheckbox.isSelected = state.autoUpdate
        panel.enableSnapshotsCheckbox.isSelected = state.enableSnapshots
        panel.vineflowerVersionComboBox.selectedItem = state.vineflowerVersion
        panel.vineflowerVersionComboBox.isEnabled = !state.autoUpdate
        panel.vineflowerSettings.clear()
        panel.vineflowerSettings.putAll(state.vineflowerSettings)
    }

    override fun apply() {
        val panel = this.panel ?: return
        val state = VineflowerState.getInstance()
        state.enabled = panel.enableVineflowerCheckbox.isSelected
        state.autoUpdate = panel.autoUpdateCheckbox.isSelected
        state.enableSnapshots = panel.enableSnapshotsCheckbox.isSelected
        state.vineflowerVersion = panel.vineflowerVersionComboBox.selectedItem as SemVer?
        state.vineflowerSettings.clear()
        state.vineflowerSettings.putAll(panel.vineflowerSettings)
        if (state.vineflowerVersion != panel.prevVineflowerVersion) {
            panel.prevVineflowerVersion = state.vineflowerVersion
            state.downloadVineflower().whenComplete { _, _ ->
                ApplicationManager.getApplication().invokeLater {
                    panel.refreshVineflowerSettings()
                }
            }
        }
    }

    override fun disposeUIResources() {
        this.panel = null
    }

    override fun getDisplayName() = "Vineflower"
    override fun getId() = "org.vineflower.ijplugin.VineflowerSettings"
}
