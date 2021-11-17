package net.earthcomputer.quiltflowerintellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.io.exists
import com.intellij.util.io.readText
import com.intellij.util.text.SemVer
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Transient
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture

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
    var quiltflowerVersionStr: String? = null
    @JvmField
    var releaseBaseUrl: String = "https://maven.quiltmc.org/repository/release/org/quiltmc/quiltflower/"
    @JvmField
    var snapshotsBaseUrl: String = "https://maven.quiltmc.org/repository/snapshot/org/quiltmc/quiltflower/"
    @JvmField
    var quiltflowerSettings: MutableMap<String, String> = mutableMapOf()

    @Transient
    @JvmField
    var quiltflowerVersionsFuture: CompletableFuture<QuiltflowerVersions> = downloadQuiltflowerVersions()
    @Transient
    @JvmField
    var hadError = false
    @Transient
    private var downloadedQuiltflowerFuture: CompletableFuture<Path>? = null
    @Transient
    private var quiltflowerClassLoaderFuture: CompletableFuture<URLClassLoader>? = null

    @get:Transient
    var quiltflowerVersion: SemVer?
        get() = SemVer.parseFromText(quiltflowerVersionStr)
        set(value) {
            quiltflowerVersionStr = value?.toString()
        }

    fun initialize() {
        downloadQuiltflower().whenComplete { path, error ->
            if (error != null) {
                LOGGER.error("Failed to download Quiltflower", error)
            } else {
                LOGGER.info("Successfully downloaded Quiltflower to $path")
            }
        }
    }

    override fun getState() = this

    override fun loadState(state: QuiltflowerState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun getQuiltflowerClassLoader(): CompletableFuture<URLClassLoader>? {
        val downloadedFuture = this.downloadedQuiltflowerFuture ?: return null
        synchronized(downloadedFuture) {
            if (quiltflowerClassLoaderFuture != null) {
                return quiltflowerClassLoaderFuture
            }
            return downloadedFuture.thenApply { path ->
                var pluginJar = javaClass.protectionDomain.codeSource.location.toString()
                if (pluginJar.startsWith("jar:") && pluginJar.endsWith(".class")) {
                    pluginJar = pluginJar.substring(4).substringBeforeLast("!/")
                }
                URLClassLoader(
                    arrayOf(path.toUri().toURL(), URL(pluginJar)),
                    ClassLoader.getPlatformClassLoader()
                )
            }.also { quiltflowerClassLoaderFuture = it }
        }
    }

    fun downloadQuiltflower(): CompletableFuture<Path> {
        val oldClassLoader = this.quiltflowerClassLoaderFuture
        this.quiltflowerClassLoaderFuture = null
        oldClassLoader?.whenComplete() { loader, _ -> loader.close() }
        synchronized(quiltflowerVersionsFuture) {
            if (downloadedQuiltflowerFuture != null) {
                return downloadedQuiltflowerFuture!!
            }
            this.hadError = false
            val future = quiltflowerVersionsFuture.thenCompose { quiltflowerVersions ->
                val future = CompletableFuture<Path>()
                runOnBackgroundThreadWithProgress("Downloading Quiltflower") download@{
                    val version = quiltflowerVersion
                    if (version == null) {
                        hadError = true
                        future.completeExceptionally(Throwable())
                        return@download
                    }
                    val jarsDir = PathManager.getConfigDir().resolve("quiltflower").resolve("jars")
                    val jarFile = jarsDir.resolve("quiltflower-$version.jar")
                    val etagFile = jarsDir.resolve("quiltflower-$version.etag")
                    val etag = if (jarFile.exists() && etagFile.exists()) etagFile.readText() else null
                    val urlStr = if (version in quiltflowerVersions.allReleases) {
                        "$releaseBaseUrl$version/quiltflower-$version.jar"
                    } else {
                        val snapshotVersion = version.toString().substringBefore("-") + "-SNAPSHOT"
                        "$snapshotsBaseUrl$snapshotVersion/quiltflower-$version.jar"
                    }
                    val connection = URL(urlStr).openConnection() as HttpURLConnection
                    if (etag != null) {
                        connection.setRequestProperty("If-None-Match", etag)
                    }
                    connection.setRequestProperty("User-Agent", "Quiltflower IntelliJ Plugin")
                    try {
                        connection.connect()
                    } catch (e: UnknownHostException) {
                        if (jarFile.exists()) {
                            LOGGER.info("Unknown host, we're probably offline, assume jar is ok")
                            future.complete(jarFile)
                            return@download
                        }
                        throw e
                    }
                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                        LOGGER.info("Quiltflower $version already downloaded")
                        future.complete(jarFile)
                        return@download
                    }
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        LOGGER.error("Failed to download quiltflower $version from $urlStr: $responseCode")
                        hadError = true
                        future.completeExceptionally(Throwable())
                        return@download
                    }
                    if (!Files.exists(jarFile.parent)) {
                        Files.createDirectories(jarFile.parent)
                    }
                    connection.inputStream.use {
                        Files.copy(it, jarFile, StandardCopyOption.REPLACE_EXISTING)
                    }
                    val newEtag = connection.getHeaderField("ETag")
                    if (newEtag != null) {
                        Files.writeString(etagFile, newEtag, StandardOpenOption.CREATE)
                    }
                    future.complete(jarFile)
                }
                future
            }

            this.downloadedQuiltflowerFuture = future

            return future
        }
    }

    fun downloadQuiltflowerVersions(): CompletableFuture<QuiltflowerVersions> {
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

        val future = CompletableFuture<QuiltflowerVersions>()

        runOnBackgroundThreadWithProgress("Fetching Quiltflower versions") {
            try {
                val (latestVersion, allVersions) = download(releaseBaseUrl)
                val (latestSnapshot, allSnapshots) = download(snapshotsBaseUrl)
                val snapshotSubversions = allSnapshots.associateWith { snapshot ->
                    val latest: String
                    val all: List<String>
                    URL("${snapshotsBaseUrl}$snapshot/maven-metadata.xml").openConnection()
                        .getInputStream().use { inputStream ->
                            val element = JDOMUtil.load(inputStream)
                            if (element.name != "metadata") {
                                throw IllegalStateException("Invalid metadata file")
                            }
                            val versioning = element.getChild("versioning")
                                ?: throw IllegalStateException("Invalid metadata file")
                            val latestSnapshotVer = versioning.getChild("snapshot")
                                ?: throw IllegalStateException("Invalid metadata file")
                            val timestamp = latestSnapshotVer.getChild("timestamp")?.text
                                ?: throw IllegalStateException("Invalid metadata file")
                            val buildNumber = latestSnapshotVer.getChild("buildNumber")?.text
                                ?: throw IllegalStateException("Invalid metadata file")
                            latest = "${snapshot.toString().replace("-SNAPSHOT", "")}-$timestamp-$buildNumber"
                            all = versioning.getChild("snapshotVersions")?.children?.mapNotNull {
                                if (it.getChild("extension")?.text == "jar" && it.getChild("classifier") == null) {
                                    it.getChild("value")?.text
                                } else {
                                    null
                                }
                            } ?: emptyList()
                        }
                    (latest to all)
                }
                val result = QuiltflowerVersions(
                    latestVersion,
                    SemVer.parseFromText(snapshotSubversions[latestSnapshot]?.first),
                    allVersions,
                    allSnapshots.flatMap {
                        snapshotSubversions[it]?.second?.mapNotNull(SemVer::parseFromText) ?: emptyList()
                    }
                )
                if (quiltflowerVersion == null || autoUpdate) {
                    quiltflowerVersion = if (enableSnapshots) {
                        result.latestSnapshot
                    } else {
                        result.latestRelease
                    }
                }
                future.complete(result)
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }

        return future
    }

    companion object {
        private val LOGGER = logger<QuiltflowerState>()

        fun getInstance(): QuiltflowerState {
            return ApplicationManager.getApplication().getService(QuiltflowerState::class.java)
        }
    }
}

data class QuiltflowerVersions(
    val latestRelease: SemVer?,
    val latestSnapshot: SemVer?,
    val allReleases: List<SemVer>,
    val allSnapshots: List<SemVer>
)
