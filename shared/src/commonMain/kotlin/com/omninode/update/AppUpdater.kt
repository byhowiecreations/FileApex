package com.omninode.update

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import kotlinx.serialization.json.Json

/**
 * Checks GitHub Releases for a newer OmniNode build, downloads the platform asset,
 * and hands off to [PlatformUpdateInstaller].
 */
object AppUpdater {
    const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/byhowiecreations/OmniNode/releases/latest"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10 * 60 * 1000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 10 * 60 * 1000
        }
    }

    private val checkMutex = Mutex()

    /**
     * Fetches the latest release; if newer than the running app, downloads and installs it.
     * No-ops when already up to date or when no matching asset exists.
     */
    suspend fun checkForUpdatesAndInstall() {
        checkMutex.withLock {
            val localVersion = currentAppVersionName()
            println("AppUpdater: checking for updates (local=$localVersion)")
            val release = fetchLatestRelease()
            if (!isRemoteVersionNewer(localVersion, release.tagName)) {
                println(
                    "AppUpdater: up to date " +
                        "(local=$localVersion, remote=${release.tagName})"
                )
                return
            }
            val asset = PlatformUpdateInstaller.selectAsset(release.assets)
                ?: error(
                    "No platform asset found in release ${release.tagName} " +
                        "(assets=${release.assets.map { it.name }})"
                )
            println(
                "AppUpdater: downloading ${asset.name} " +
                    "(${asset.size} bytes) for ${release.tagName}"
            )
            val cacheDir = PlatformUpdateInstaller.updateCacheDirectory()
            SystemFileSystem.createDirectories(Path(cacheDir))
            val targetPath = Path("$cacheDir/${asset.name}")
            downloadToFile(asset.browserDownloadUrl, targetPath)
            println("AppUpdater: download complete → $targetPath; installing…")
            PlatformUpdateInstaller.installAndRelaunch(
                localFilePath = targetPath.toString(),
                remoteVersion = release.tagName
            )
        }
    }

    private suspend fun fetchLatestRelease(): GitHubRelease {
        val response = client.get(LATEST_RELEASE_URL) {
            header(HttpHeaders.UserAgent, "OmniNode/${currentAppVersionName()}")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        if (!response.status.isSuccess()) {
            error("GitHub latest release failed (${response.status})")
        }
        return response.body()
    }

    private suspend fun downloadToFile(url: String, target: Path) {
        target.parent?.let { parent ->
            if (!SystemFileSystem.exists(parent)) {
                SystemFileSystem.createDirectories(parent)
            }
        }
        if (SystemFileSystem.exists(target)) {
            SystemFileSystem.delete(target)
        }
        client.prepareGet(url) {
            header(HttpHeaders.UserAgent, "OmniNode/${currentAppVersionName()}")
            header(HttpHeaders.Accept, "application/octet-stream")
        }.execute { response ->
            if (!response.status.isSuccess()) {
                error("Download failed (${response.status}) for $url")
            }
            val channel = response.bodyAsChannel()
            SystemFileSystem.sink(target).buffered().use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read > 0) {
                        sink.write(buffer, 0, read)
                    }
                }
            }
        }
    }
}
