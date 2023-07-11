package org.vineflower.ijplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.io.readText
import com.intellij.util.text.SemVer
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.Transient
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.stream.Collectors
import kotlin.io.path.exists

@State(
    name = "net.earthcomputer.quiltflowerintellij.QuiltflowerState",
    storages = [Storage("quiltflower.xml")]
)
class VineflowerState : PersistentStateComponent<VineflowerState> {
    @JvmField
    var enabled: Boolean = true
    @JvmField
    var autoUpdate: Boolean = true
    @JvmField
    var enableSnapshots: Boolean = false
    @JvmField
    @Tag("quiltflowerVersionStr")
    var vineflowerVersionStr: String? = null
    @JvmField
    var releaseBaseUrl: String = "https://maven.quiltmc.org/repository/release/org/quiltmc/quiltflower/"
    @JvmField
    var snapshotsBaseUrl: String = "https://maven.quiltmc.org/repository/snapshot/org/quiltmc/quiltflower/"
    @JvmField
    @Tag("quiltflowerSettings")
    var vineflowerSettings: MutableMap<String, String> = mutableMapOf()

    @Transient
    private var hasInitialized = false

    @Transient
    @JvmField
    var vineflowerVersionsFuture: CompletableFuture<VineflowerVersions> = CompletableFuture.completedFuture(VineflowerVersions(null, null, listOf(), listOf()))
    @Transient
    @JvmField
    var hadError = false
    @Transient
    private var downloadedVineflowerFuture: CompletableFuture<Path>? = null
    @Transient
    private var vineflowerClassLoaderFuture: CompletableFuture<URLClassLoader>? = null
    @Transient
    private var vineflowerInvokerFuture: CompletableFuture<VineflowerInvoker>? = null

    @get:Transient
    var vineflowerVersion: SemVer?
        get() = SemVer.parseFromText(vineflowerVersionStr)
        set(value) {
            vineflowerVersionStr = value?.toString()
        }

    fun initialize() {
        if (hasInitialized) {
            return
        }
        hasInitialized = true
        vineflowerVersionsFuture = downloadVineflowerVersions()
        downloadVineflower().whenComplete { path, error ->
            if (error != null) {
                LOGGER.error("Failed to download Vineflower", error)
            } else {
                LOGGER.info("Successfully downloaded Vineflower to $path")
            }
        }
    }

    override fun getState() = this

    override fun loadState(state: VineflowerState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun getVineflowerInvoker(): CompletableFuture<VineflowerInvoker> {
        synchronized(this) {
            if (vineflowerInvokerFuture != null) {
                return vineflowerInvokerFuture!!
            }
            return getVineflowerClassLoader().thenApply { classLoader ->
                try {
                    VineflowerInvoker(classLoader)
                } catch (e: Throwable) {
                    hadError = true
                    throw e
                }
            }.also { vineflowerInvokerFuture = it }
        }
    }

    fun getVineflowerClassLoader(): CompletableFuture<URLClassLoader> {
        synchronized(this) {
            if (vineflowerClassLoaderFuture != null) {
                return vineflowerClassLoaderFuture!!
            }
            return downloadVineflower().thenApply { path ->
                try {
                    var pluginJar = javaClass.getResource("/${javaClass.name.replace('.', '/')}.class")!!.toString()
                    if (pluginJar.startsWith("jar:") && pluginJar.endsWith(".class")) {
                        pluginJar = pluginJar.substring(4).substringBeforeLast("!/")
                    }
                    URLClassLoader(
                        arrayOf(path.toUri().toURL(), URL(pluginJar)),
                        ClassLoader.getPlatformClassLoader()
                    )
                } catch (e: Throwable) {
                    hadError = true
                    throw e
                }
            }.also { vineflowerClassLoaderFuture = it }
        }
    }

    fun downloadVineflower(): CompletableFuture<Path> {
        synchronized(this) {
            if (!hadError && downloadedVineflowerFuture != null) {
                return downloadedVineflowerFuture!!
            }
            val oldClassLoader = this.vineflowerClassLoaderFuture
            this.vineflowerClassLoaderFuture = null
            this.vineflowerInvokerFuture = null
            oldClassLoader?.whenComplete { loader, _ -> loader.close() }
            this.hadError = false
            val future = vineflowerVersionsFuture.thenCompose { vineflowerVersions ->
                val future = CompletableFuture<Path>()
                runOnBackgroundThreadWithProgress("Downloading Vineflower") download@{
                    try {
                        val jarFile = doVineflowerDownload(vineflowerVersions) ?: return@download
                        future.complete(jarFile)
                    } catch (e: Throwable) {
                        hadError = true
                        future.completeExceptionally(e)
                    }
                }
                future
            }

            this.downloadedVineflowerFuture = future

            return future
        }
    }

    private fun doVineflowerDownload(vineflowerVersions: VineflowerVersions): Path? {
        val jarsDir = PathManager.getConfigDir().resolve("vineflower").resolve("jars")

        val version = vineflowerVersion ?: run {
            // we may be offline, try to find latest version from file system
            val versions = Files.list(jarsDir).use { subFiles ->
                subFiles
                        .map { it.fileName.toString() }
                        .filter { it.startsWith("vineflower-") && it.endsWith(".jar") }
                        .map { it.substring(12, it.length - 4) }
                        .filter { enableSnapshots || !it.contains('-') }
                        .map(SemVer::parseFromText)
                        .filter { it != null }
                        .map { it!! }
                        .collect(Collectors.toList())
            }
            versions.maxOrNull() ?: throw Throwable()
        }

        val jarFile = jarsDir.resolve("vineflower-$version.jar")
        val etagFile = jarsDir.resolve("vineflower-$version.etag")

        // read the etag
        val etag = if (jarFile.exists() && etagFile.exists()) etagFile.readText() else null

        // decide which repo to use based on whether we're a snapshot or release
        val urlStr = if (version in vineflowerVersions.allReleases) {
            "$releaseBaseUrl$version/quiltflower-$version.jar"
        } else {
            val snapshotVersion = version.toString().substringBefore("-") + "-SNAPSHOT"
            "$snapshotsBaseUrl$snapshotVersion/quiltflower-$version.jar"
        }

        // setup the connection and connect
        val connection = URL(urlStr).openVineflowerConnection()
        if (etag != null) {
            connection.setRequestProperty("If-None-Match", etag)
        }
        try {
            connection.connect()
        } catch (e: UnknownHostException) {
            if (jarFile.exists()) {
                LOGGER.info("Unknown host, we're probably offline, assume jar is ok")
                return jarFile
            }
            throw e
        }

        // read the response
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            LOGGER.info("Vineflower $version already downloaded")
            return jarFile
        }
        if (responseCode != HttpURLConnection.HTTP_OK) {
            LOGGER.error("Failed to download Vineflower $version from $urlStr: $responseCode")
            hadError = true
            throw Throwable()
        }

        // write the file
        if (!Files.exists(jarFile.parent)) {
            Files.createDirectories(jarFile.parent)
        }
        connection.inputStream.use {
            Files.copy(it, jarFile, StandardCopyOption.REPLACE_EXISTING)
        }

        // save the etag for future use
        val newEtag = connection.getHeaderField("ETag")
        if (newEtag != null) {
            Files.writeString(etagFile, newEtag, StandardOpenOption.CREATE)
        }

        return jarFile
    }

    private fun downloadMavenMetadata(baseUrl: String): MavenMetadataInfo {
        val latestVersion: SemVer?
        val allVersions: List<SemVer>
        try {
            URL(baseUrl + "maven-metadata.xml").openVineflowerConnection().inputStream.use { inputStream ->
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
        } catch (e: FileNotFoundException) {
            LOGGER.warn("${baseUrl}maven-metadata.xml could not be found.")
            return MavenMetadataInfo(null, emptyList())
        }
        return MavenMetadataInfo(latestVersion, allVersions)
    }

    fun downloadVineflowerVersions(): CompletableFuture<VineflowerVersions> {
        val future = CompletableFuture<VineflowerVersions>()

        runOnBackgroundThreadWithProgress("Fetching Vineflower versions") {
            try {
                val (latestVersion, allVersions) = downloadMavenMetadata(releaseBaseUrl)
                val (latestSnapshot, allSnapshots) = downloadMavenMetadata(snapshotsBaseUrl)
                val snapshotSubversions = downloadSnapshotSubversions(allSnapshots)
                val result = VineflowerVersions(
                    latestVersion,
                    SemVer.parseFromText(snapshotSubversions[latestSnapshot]?.first),
                    allVersions,
                    allSnapshots.flatMap {
                        snapshotSubversions[it]?.second?.mapNotNull(SemVer::parseFromText) ?: emptyList()
                    }
                )
                if (vineflowerVersion == null || autoUpdate) {
                    vineflowerVersion = if (enableSnapshots) {
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

    private fun downloadSnapshotSubversions(allSnapshots: List<SemVer>): Map<SemVer, Pair<String, List<String>>> {
        val snapshotSubversionFutures = allSnapshots.associateWith { snapshot ->
            CompletableFuture.supplyAsync({
                val latest: String
                val all: List<String>
                URL("${snapshotsBaseUrl}$snapshot/maven-metadata.xml").openVineflowerConnection()
                        .inputStream.use { inputStream ->
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
            }, snapshotDownloadPool)
        }
        CompletableFuture.allOf(*snapshotSubversionFutures.values.toTypedArray()).join()
        return snapshotSubversionFutures.map { (key, value) -> key to value.join() }.toMap()
    }

    private fun URL.openVineflowerConnection() =
        this.openConnection().apply { setRequestProperty("User-Agent", "Vineflower IntelliJ Plugin") } as HttpURLConnection

    companion object {
        private val LOGGER = logger<VineflowerState>()

        private val snapshotDownloadPool = Executors.newFixedThreadPool(4)

        fun getInstance(): VineflowerState {
            return ApplicationManager.getApplication().getService(VineflowerState::class.java)
        }
    }
}

data class MavenMetadataInfo(val latestVersion: SemVer?, val allVersions: List<SemVer>)

data class VineflowerVersions(
    val latestRelease: SemVer?,
    val latestSnapshot: SemVer?,
    val allReleases: List<SemVer>,
    val allSnapshots: List<SemVer>
)
