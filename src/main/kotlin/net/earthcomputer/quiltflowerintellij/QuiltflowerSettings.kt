package net.earthcomputer.quiltflowerintellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.util.text.SemVer
import javax.swing.JComponent

class QuiltflowerSettings : SearchableConfigurable {
    private var panel: QuiltflowerSettingsPanel? = null

    override fun createComponent(): JComponent {
        return QuiltflowerSettingsPanel(QuiltflowerState.getInstance().quiltflowerVersion).also { panel = it }.mainPanel
    }

    override fun isModified(): Boolean {
        val panel = this.panel ?: return false
        val state = QuiltflowerState.getInstance()
        return panel.enableQuiltflowerCheckbox.isSelected != state.enabled
                || panel.autoUpdateCheckbox.isSelected != state.autoUpdate
                || panel.enableSnapshotsCheckbox.isSelected != state.enableSnapshots
                || panel.quiltflowerVersionComboBox.selectedItem != state.quiltflowerVersion
                || panel.quiltflowerSettings != state.quiltflowerSettings
    }

    override fun reset() {
        val panel = this.panel ?: return
        val state = QuiltflowerState.getInstance()
        panel.enableQuiltflowerCheckbox.isSelected = state.enabled
        panel.autoUpdateCheckbox.isSelected = state.autoUpdate
        panel.enableSnapshotsCheckbox.isSelected = state.enableSnapshots
        panel.quiltflowerVersionComboBox.selectedItem = state.quiltflowerVersion
        panel.quiltflowerVersionComboBox.isEnabled = !state.autoUpdate
        panel.quiltflowerSettings.clear()
        panel.quiltflowerSettings.putAll(state.quiltflowerSettings)
    }

    override fun apply() {
        val panel = this.panel ?: return
        val state = QuiltflowerState.getInstance()
        state.enabled = panel.enableQuiltflowerCheckbox.isSelected
        state.autoUpdate = panel.autoUpdateCheckbox.isSelected
        state.enableSnapshots = panel.enableSnapshotsCheckbox.isSelected
        state.quiltflowerVersion = panel.quiltflowerVersionComboBox.selectedItem as SemVer?
        state.quiltflowerSettings.clear()
        state.quiltflowerSettings.putAll(panel.quiltflowerSettings)
        if (state.quiltflowerVersion != panel.prevQuiltflowerVersion) {
            panel.prevQuiltflowerVersion = state.quiltflowerVersion
            state.downloadQuiltflower().whenComplete { _, _ ->
                ApplicationManager.getApplication().invokeLater {
                    panel.refreshQuiltflowerSettings()
                }
            }
        }
    }

    override fun disposeUIResources() {
        this.panel = null
    }

    override fun getDisplayName() = "Quiltflower"
    override fun getId() = "net.earthcomputer.quiltflowerintellij.QuiltflowerSettings"
}
