package net.earthcomputer.quiltflowerintellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.text.SemVer
import com.intellij.util.xmlb.XmlSerializerUtil
import java.net.URL

@State(
    name = "net.earthcomputer.quiltflowerintellij.QuiltflowerState",
    storages = [Storage("quiltflower.xml")]
)
class QuiltflowerState : PersistentStateComponent<QuiltflowerState> {
    @JvmField
    var enabled: Boolean = true
    @JvmField
    var autoUpdate: Boolean = true
    @JvmField
    var enableSnapshots: Boolean = false
    @JvmField
    var quiltflowerVersion: SemVer? = null
    @JvmField
    var releaseBaseUrl: String = "https://maven.quiltmc.org/repository/release/org/quiltmc/quiltflower/"
    @JvmField
    var snapshotsBaseUrl: String = "https://maven.quiltmc.org/repository/snapshot/org/quiltmc/quiltflower/"

    override fun getState() = this

    override fun loadState(state: QuiltflowerState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): QuiltflowerState {
            return ApplicationManager.getApplication().getService(QuiltflowerState::class.java)
        }

        fun reloadQuiltflowerVersions(callback: (QuiltflowerVersions) -> Unit, errorCallback: (Throwable) -> Unit) {
            fun download(baseUrl: String): Pair<SemVer?, List<SemVer>> {
                val latestVersion: SemVer?
                val allVersions: List<SemVer>
                URL(baseUrl + "maven-metadata.xml").openConnection().getInputStream().use { inputStream ->
                    val element = JDOMUtil.load(inputStream)
                    if (element.name != "metadata") {
                        throw IllegalStateException("Invalid metadata file")
                    }
                    val versioning = element.getChild("versioning") ?: throw IllegalStateException("Invalid metadata file")
                    latestVersion = SemVer.parseFromText(versioning.getChild("latest")?.text)
                    allVersions = versioning.getChild("versions")?.children?.mapNotNull {
                        SemVer.parseFromText(it.text)
                    } ?: emptyList()
                }
                return latestVersion to allVersions
            }
            ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Fetching Quiltflower versions", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val (latestVersion, allVersions) = download(getInstance().releaseBaseUrl)
                        val (latestSnapshot, allSnapshots) = download(getInstance().snapshotsBaseUrl)
                        callback(QuiltflowerVersions(latestVersion, latestSnapshot, allVersions, allSnapshots))
                    } catch (e: Throwable) {
                        errorCallback(e)
                    }
                }
            })
        }
    }
}

data class QuiltflowerVersions(
    val latestRelease: SemVer?,
    val latestSnapshot: SemVer?,
    val allReleases: List<SemVer>,
    val allSnapshots: List<SemVer>
)
