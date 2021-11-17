package net.earthcomputer.quiltflowerintellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
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
import java.awt.FlowLayout
import java.lang.reflect.Modifier
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.ComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class QuiltflowerSettingsPanel(var prevQuiltflowerVersion: SemVer?) {
    companion object {
        private val LOGGER = logger<QuiltflowerSettingsPanel>()
    }

    lateinit var mainPanel: JPanel
    lateinit var pluginSettingsPanel: JPanel
    lateinit var quiltflowerSettingsPanel: JPanel
    lateinit var enableQuiltflowerCheckbox: JBCheckBox
    lateinit var autoUpdateCheckbox: JBCheckBox
    lateinit var enableSnapshotsCheckbox: JBCheckBox
    lateinit var quiltflowerVersionComboBox: ComboBox<SemVer>
    lateinit var fetchingVersionsIcon: AsyncProcessIcon
    lateinit var errorLabel: JLabel
    val quiltflowerSettings = mutableMapOf<String, String>()

    init {
        pluginSettingsPanel.border = IdeBorderFactory.createTitledBorder("Plugin Settings")
        quiltflowerSettingsPanel.border = IdeBorderFactory.createTitledBorder("Quiltflower Settings")
        quiltflowerSettingsPanel.layout = BoxLayout(quiltflowerSettingsPanel, BoxLayout.Y_AXIS)

        refreshQuiltflowerSettings()

        autoUpdateCheckbox.addActionListener {
            quiltflowerVersionComboBox.isEnabled = !autoUpdateCheckbox.isSelected
        }

        quiltflowerVersionComboBox.addActionListener {
            refreshQuiltflowerSettings()
        }
        (quiltflowerVersionComboBox.model as QuiltflowerVersionsModel).initialize()
    }

    fun createUIComponents() {
        quiltflowerVersionComboBox = ComboBox(QuiltflowerVersionsModel())
        fetchingVersionsIcon = AsyncProcessIcon("Fetching Versions")
    }

    fun refreshQuiltflowerSettings() {
        quiltflowerSettingsPanel.removeAll()
        if (quiltflowerVersionComboBox.selectedItem != prevQuiltflowerVersion) {
            quiltflowerSettingsPanel.add(JBLabel("Apply settings for Quiltflower to be downloaded and settings to be displayed"))
            quiltflowerSettingsPanel.revalidate()
            quiltflowerSettingsPanel.repaint()
            return
        }
        val classLoader = QuiltflowerState.getInstance().getQuiltflowerClassLoader().getNow(null) ?: return
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
            val currentValue = quiltflowerSettings[key] ?: defaults[key]!!.toString()
            val component = when (type) {
                QuiltflowerPreferences.Type.BOOLEAN -> JBCheckBox().also { checkBox ->
                    checkBox.isSelected = currentValue == "1"
                    checkBox.addActionListener {
                        val newValue = if (checkBox.isSelected) "1" else "0"
                        if (newValue != defaults[key]) {
                            quiltflowerSettings[key] = newValue
                        } else {
                            quiltflowerSettings.remove(key)
                        }
                    }
                }
                QuiltflowerPreferences.Type.STRING -> JBTextField(currentValue).also { textField ->
                    textField.columns = 20
                    textField.document.addDocumentListener(object : DocumentAdapter() {
                        override fun textChanged(e: DocumentEvent) {
                            val newValue = textField.text
                            if (newValue != defaults[key]) {
                                quiltflowerSettings[key] = newValue
                            } else {
                                quiltflowerSettings.remove(key)
                            }
                        }
                    })
                }
                QuiltflowerPreferences.Type.INTEGER -> JBIntSpinner(currentValue.toInt(), 0, Int.MAX_VALUE).also { spinner ->
                    spinner.addChangeListener {
                        val newValue = spinner.value.toString()
                        if (newValue != defaults[key]) {
                            quiltflowerSettings[key] = newValue
                        } else {
                            quiltflowerSettings.remove(key)
                        }
                    }
                }
            }
            allSettings += name to component
        }
        allSettings.sortBy { it.first }
        for ((name, component) in allSettings) {
            val panel = JPanel(FlowLayout(FlowLayout.LEFT))
            if (component is JCheckBox) {
                panel.add(component)
            }
            panel.add(JBLabel(name))
            if (component !is JCheckBox) {
                panel.add(component)
            }
            quiltflowerSettingsPanel.add(panel)
        }
        quiltflowerSettingsPanel.revalidate()
        quiltflowerSettingsPanel.repaint()
    }

    inner class QuiltflowerVersionsModel : ComboBoxModel<SemVer> {
        private val versions = mutableListOf<SemVer>()
        private val listeners = mutableListOf<ListDataListener>()
        private var selectedItem: SemVer? = null

        private fun refreshVersions() {
            val (latestRelease, latestSnapshot, allRelease, allSnapshots) =
                QuiltflowerState.getInstance().quiltflowerVersionsFuture.getNow(null) ?: return
            val prevSize = versions.size
            versions.clear()
            versions.addAll(allRelease)
            if (enableSnapshotsCheckbox.isSelected) {
                versions.addAll(allSnapshots)
            }
            val prevSelectedItem = selectedItem
            if (prevSelectedItem != null && prevSelectedItem !in versions) {
                versions += prevSelectedItem
            }
            versions.sortDescending()
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
                prevSelectedItem != null -> {
                    quiltflowerVersionComboBox.selectedItem = prevSelectedItem
                }
                autoUpdateCheckbox.isSelected -> {
                    quiltflowerVersionComboBox.selectedItem = if (enableSnapshotsCheckbox.isSelected) latestSnapshot else latestRelease
                }
                else -> {
                    quiltflowerVersionComboBox.selectedItem = QuiltflowerState.getInstance().quiltflowerVersion
                }
            }
        }

        fun initialize() {
            if (!fetchingVersionsIcon.isRunning) {
                fetchingVersionsIcon.resume()
            }
            QuiltflowerState.getInstance().quiltflowerVersionsFuture = QuiltflowerState.getInstance().downloadQuiltflowerVersions().whenComplete { _, error ->
                ApplicationManager.getApplication().invokeLater {
                    if (error != null) {
                        errorLabel.text = "Error fetching versions"
                        LOGGER.error("Error fetching versions", error)
                    } else {
                        refreshVersions()
                        if (!fetchingVersionsIcon.isDisposed) {
                            fetchingVersionsIcon.suspend()
                            fetchingVersionsIcon.isVisible = false
                        }
                    }
                }
            }

            enableSnapshotsCheckbox.addActionListener {
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
