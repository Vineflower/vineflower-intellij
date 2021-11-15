package net.earthcomputer.quiltflowerintellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.text.SemVer
import com.intellij.util.ui.AsyncProcessIcon
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.ComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class QuiltflowerSettings : SearchableConfigurable {
    companion object {
        private val LOGGER = logger<QuiltflowerSettings>()
    }

    private var panel: MyPanel? = null
    private var prevQuiltflowerVersion: SemVer? = null

    override fun createComponent(): JComponent {
        return MyPanel().also { panel = it }
    }

    override fun isModified(): Boolean {
        val panel = this.panel ?: return false
        val state = QuiltflowerState.getInstance()
        return panel.myEnabled.isSelected != state.enabled
                || panel.myAutoUpdate.isSelected != state.autoUpdate
                || panel.myEnableSnapshots.isSelected != state.enableSnapshots
                || panel.myQuiltflowerVersion.selectedItem != state.quiltflowerVersionStr
    }

    override fun reset() {
        val panel = this.panel ?: return
        val state = QuiltflowerState.getInstance()
        panel.myEnabled.isSelected = state.enabled
        panel.myAutoUpdate.isSelected = state.autoUpdate
        panel.myEnableSnapshots.isSelected = state.enableSnapshots
        panel.myQuiltflowerVersion.selectedItem = state.quiltflowerVersionStr
        panel.myQuiltflowerVersion.isEnabled = !state.autoUpdate
        prevQuiltflowerVersion = state.quiltflowerVersion
    }

    override fun apply() {
        val panel = this.panel ?: return
        val state = QuiltflowerState.getInstance()
        state.enabled = panel.myEnabled.isSelected
        state.autoUpdate = panel.myAutoUpdate.isSelected
        state.enableSnapshots = panel.myEnableSnapshots.isSelected
        state.quiltflowerVersion = panel.myQuiltflowerVersion.selectedItem as SemVer?
        if (state.quiltflowerVersion != prevQuiltflowerVersion) {
            state.downloadQuiltflower()
        }
    }

    override fun disposeUIResources() {
        this.panel = null
    }

    override fun getDisplayName() = "Quiltflower Settings"
    override fun getId() = "net.earthcomputer.quiltflowerintellij.QuiltflowerSettings"

    private class MyPanel : JPanel() {
        val myEnabled = JBCheckBox("Enable Quiltflower")
        val myAutoUpdate = JBCheckBox("Auto update")
        val myEnableSnapshots = JBCheckBox("Enable snapshots")
        private val myFetchingVersionsIcon = AsyncProcessIcon("Fetching Versions")
        private val myErrorText = JBLabel().also { it.foreground = Color.RED }
        val myQuiltflowerVersion = ComboBox(QuiltflowerVersionsModel())

        init {
            layout = FlowLayout(FlowLayout.LEFT)
            add(myEnabled)
            add(myAutoUpdate)
            add(myEnableSnapshots)
            add(myFetchingVersionsIcon)
            add(myErrorText)
            add(myQuiltflowerVersion)

            myAutoUpdate.addActionListener {
                myQuiltflowerVersion.isEnabled = !myAutoUpdate.isSelected
            }
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
