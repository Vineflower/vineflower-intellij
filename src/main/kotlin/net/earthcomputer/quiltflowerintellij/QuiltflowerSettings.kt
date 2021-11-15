package net.earthcomputer.quiltflowerintellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.text.SemVer
import com.intellij.util.ui.AsyncProcessIcon
import java.awt.Color
import java.awt.FlowLayout
import java.lang.reflect.Modifier
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.ComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class QuiltflowerSettings : SearchableConfigurable {
    companion object {
        private val LOGGER = logger<QuiltflowerSettings>()
    }

    private var panel: MyPanel? = null

    override fun createComponent(): JComponent {
        return MyPanel(QuiltflowerState.getInstance().quiltflowerVersion).also { panel = it }
    }

    override fun isModified(): Boolean {
        val panel = this.panel ?: return false
        val state = QuiltflowerState.getInstance()
        return panel.myEnabled.isSelected != state.enabled
                || panel.myAutoUpdate.isSelected != state.autoUpdate
                || panel.myEnableSnapshots.isSelected != state.enableSnapshots
                || panel.myQuiltflowerVersion.selectedItem != state.quiltflowerVersionStr
                || panel.myQuiltflowerSettings != state.quiltflowerSettings
    }

    override fun reset() {
        val panel = this.panel ?: return
        val state = QuiltflowerState.getInstance()
        panel.myEnabled.isSelected = state.enabled
        panel.myAutoUpdate.isSelected = state.autoUpdate
        panel.myEnableSnapshots.isSelected = state.enableSnapshots
        panel.myQuiltflowerVersion.selectedItem = state.quiltflowerVersionStr
        panel.myQuiltflowerVersion.isEnabled = !state.autoUpdate
        panel.myQuiltflowerSettings.clear()
        panel.myQuiltflowerSettings.putAll(state.quiltflowerSettings)
    }

    override fun apply() {
        val panel = this.panel ?: return
        val state = QuiltflowerState.getInstance()
        state.enabled = panel.myEnabled.isSelected
        state.autoUpdate = panel.myAutoUpdate.isSelected
        state.enableSnapshots = panel.myEnableSnapshots.isSelected
        state.quiltflowerVersion = panel.myQuiltflowerVersion.selectedItem as SemVer?
        state.quiltflowerSettings.clear()
        state.quiltflowerSettings.putAll(panel.myQuiltflowerSettings)
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

    override fun getDisplayName() = "Quiltflower Settings"
    override fun getId() = "net.earthcomputer.quiltflowerintellij.QuiltflowerSettings"

    private class MyPanel(var prevQuiltflowerVersion: SemVer?) : JPanel() {
        val myEnabled = JBCheckBox("Enable Quiltflower")
        val myAutoUpdate = JBCheckBox("Auto update")
        val myEnableSnapshots = JBCheckBox("Enable snapshots")
        private val myFetchingVersionsIcon = AsyncProcessIcon("Fetching Versions")
        private val myErrorText = JBLabel().also { it.foreground = Color.RED }
        val myQuiltflowerVersion = ComboBox(QuiltflowerVersionsModel())
        val myQuiltflowerSettingsPanel = JPanel()
        val myQuiltflowerSettings = mutableMapOf<String, String>()

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            val pluginSettingsPanel = JPanel()
            pluginSettingsPanel.layout = BoxLayout(pluginSettingsPanel, BoxLayout.Y_AXIS)
            pluginSettingsPanel.border = IdeBorderFactory.createTitledBorder("Plugin Settings")
            pluginSettingsPanel.add(myEnabled)
            pluginSettingsPanel.add(myAutoUpdate)
            pluginSettingsPanel.add(myEnableSnapshots)
            val quiltflowerVersionPanel = JPanel(FlowLayout(FlowLayout.LEFT))
            quiltflowerVersionPanel.add(JBLabel("Quiltflower version:"))
            quiltflowerVersionPanel.add(myQuiltflowerVersion)
            quiltflowerVersionPanel.add(myErrorText)
            quiltflowerVersionPanel.add(myFetchingVersionsIcon)
            pluginSettingsPanel.add(quiltflowerVersionPanel)
            add(pluginSettingsPanel)

            myQuiltflowerSettingsPanel.layout = BoxLayout(myQuiltflowerSettingsPanel, BoxLayout.Y_AXIS)
            myQuiltflowerSettingsPanel.border = IdeBorderFactory.createTitledBorder("Quiltflower Settings")
            add(myQuiltflowerSettingsPanel)


            refreshQuiltflowerSettings()

            myAutoUpdate.addActionListener {
                myQuiltflowerVersion.isEnabled = !myAutoUpdate.isSelected
            }

            myQuiltflowerVersion.addActionListener {
                refreshQuiltflowerSettings()
            }
        }

        fun refreshQuiltflowerSettings() {
            myQuiltflowerSettingsPanel.removeAll()
            if (myQuiltflowerVersion.selectedItem != prevQuiltflowerVersion) {
                myQuiltflowerSettingsPanel.add(JBLabel("Apply settings for Quiltflower to be downloaded and settings to be displayed"))
                myQuiltflowerSettingsPanel.revalidate()
                myQuiltflowerSettingsPanel.repaint()
                return
            }
            val classLoader = QuiltflowerState.getInstance().getQuiltflowerClassLoader()?.getNow(null) ?: return
            val preferencesClass = classLoader.loadClass("org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences")
            @Suppress("UNCHECKED_CAST")
            val defaults = (preferencesClass.getField("DEFAULTS").get(null) as Map<String, *>).toMutableMap()
            defaults.putAll(QuiltflowerPreferences.defaultOverrides)
            val allSettings = mutableListOf<Pair<String, JComponent>>()
            for (field in preferencesClass.fields) {
                if (!Modifier.isStatic(field.modifiers) || field.type != String::class.java) {
                    continue
                }
                val key = field.get(null) as String
                if (key in QuiltflowerPreferences.ignoredPreferences) {
                    continue
                }
                val type = QuiltflowerPreferences.inferType(key, defaults) ?: continue
                val name = QuiltflowerPreferences.nameOverrides[key]
                    ?: StringUtil.toTitleCase(field.name.replace("_", " ").toLowerCase(Locale.ROOT))
                val currentValue = myQuiltflowerSettings[key] ?: defaults[key]!!.toString()
                val component = when (type) {
                    QuiltflowerPreferences.Type.BOOLEAN -> JBCheckBox().also { checkBox ->
                        checkBox.isSelected = currentValue == "1"
                        checkBox.addActionListener {
                            val newValue = if (checkBox.isSelected) "1" else "0"
                            if (newValue != defaults[key]) {
                                myQuiltflowerSettings[key] = newValue
                            } else {
                                myQuiltflowerSettings.remove(key)
                            }
                        }
                    }
                    QuiltflowerPreferences.Type.STRING -> JBTextField(currentValue).also { textField ->
                        textField.columns = 20
                        textField.document.addDocumentListener(object : DocumentAdapter() {
                            override fun textChanged(e: DocumentEvent) {
                                val newValue = textField.text
                                if (newValue != defaults[key]) {
                                    myQuiltflowerSettings[key] = newValue
                                } else {
                                    myQuiltflowerSettings.remove(key)
                                }
                            }
                        })
                    }
                    QuiltflowerPreferences.Type.INTEGER -> JBIntSpinner(currentValue.toInt(), 0, Int.MAX_VALUE).also { spinner ->
                        spinner.addChangeListener {
                            val newValue = spinner.value.toString()
                            if (newValue != defaults[key]) {
                                myQuiltflowerSettings[key] = newValue
                            } else {
                                myQuiltflowerSettings.remove(key)
                            }
                        }
                    }
                }
                allSettings += name to component
            }
            allSettings.sortBy { it.first }
            for ((name, component) in allSettings) {
                val panel = JPanel(FlowLayout(FlowLayout.LEFT))
                panel.add(JBLabel(name))
                panel.add(component)
                myQuiltflowerSettingsPanel.add(panel)
            }
            myQuiltflowerSettingsPanel.revalidate()
            myQuiltflowerSettingsPanel.repaint()
        }

        inner class QuiltflowerVersionsModel : ComboBoxModel<SemVer> {
            private var myQuiltflowerVersions: QuiltflowerVersions? = null
            private val versions = mutableListOf<SemVer>()
            private val listeners = mutableListOf<ListDataListener>()
            private var selectedItem: SemVer? = null

            private fun refreshVersions() {
                val (latestRelease, latestSnapshot, allRelease, allSnapshots) = myQuiltflowerVersions ?: return
                val prevSize = versions.size
                versions.clear()
                versions.addAll(allRelease)
                if (myEnableSnapshots.isSelected) {
                    versions.addAll(allSnapshots)
                }
                versions.sortDescending()
                val prevSelectedItem = selectedItem
                myQuiltflowerVersion.selectedItem = null
                for (listener in listeners) {
                    if (prevSize > versions.size) {
                        listener.intervalRemoved(ListDataEvent(this, ListDataEvent.INTERVAL_REMOVED, versions.size, prevSize - 1))
                    }
                    listener.contentsChanged(ListDataEvent(this, ListDataEvent.CONTENTS_CHANGED, 0, versions.size - 1))
                    if (prevSize < versions.size) {
                        listener.intervalAdded(ListDataEvent(this, ListDataEvent.INTERVAL_ADDED, prevSize, versions.size - 1))
                    }
                }
                when {
                    prevSelectedItem != null && prevSelectedItem in versions -> {
                        myQuiltflowerVersion.selectedItem = prevSelectedItem
                    }
                    myAutoUpdate.isSelected -> {
                        myQuiltflowerVersion.selectedItem = if (myEnableSnapshots.isSelected) latestSnapshot else latestRelease
                    }
                    else -> {
                        myQuiltflowerVersion.selectedItem = QuiltflowerState.getInstance().quiltflowerVersionStr
                    }
                }
            }

            init {
                if (!myFetchingVersionsIcon.isRunning) {
                    myFetchingVersionsIcon.resume()
                }
                QuiltflowerState.reloadQuiltflowerVersions(
                    { versions ->
                        ApplicationManager.getApplication().invokeLater {
                            myQuiltflowerVersions = versions
                            refreshVersions()
                            if (!myFetchingVersionsIcon.isDisposed) {
                                myFetchingVersionsIcon.suspend()
                                myFetchingVersionsIcon.isVisible = false
                            }
                        }
                    }
                ) {
                    myErrorText.text = "Error fetching versions"
                    LOGGER.error("Error fetching versions", it)
                }

                myEnableSnapshots.addActionListener {
                    refreshVersions()
                }
            }

            override fun getSize() = versions.size

            override fun getElementAt(index: Int) = versions[index]

            override fun addListDataListener(l: ListDataListener) {
                listeners.add(l)
            }

            override fun removeListDataListener(l: ListDataListener) {
                listeners.remove(l)
            }

            override fun setSelectedItem(anItem: Any?) {
                selectedItem = anItem as? SemVer
            }

            override fun getSelectedItem(): Any? {
                return selectedItem
            }

        }
    }
}
