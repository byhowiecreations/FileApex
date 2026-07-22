package com.omninode.update

import com.omninode.di.OmniNodeServices
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write

/**
 * Checks GitHub Releases for a newer OmniNode build, downloads the platform asset,
 * and hands off to [PlatformUpdateInstaller].
 */
object AppUpdater {
    const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/byhowiecreations/OmniNode/releases/latest"

    private val client get() = OmniNodeServices.httpClient

    private val checkMutex = Mutex()
    private val installMutex = Mutex()

    /**
     * Fetches the latest release and returns [UpdateCheckOutcome.Available] when newer than local.
     */
    suspend fun probeForUpdates(): UpdateCheckOutcome {
        checkMutex.withLock {
            val localVersion = currentAppVersionName()
            println("AppUpdater: checking for updates (local=$localVersion)")
            val release = fetchLatestRelease()
            val remoteTag = release.tagName.trim()
            if (!isRemoteVersionNewer(localVersion, release.tagName)) {
                println(
                    "AppUpdater: already current " +
                        "(local $localVersion, latest $remoteTag)"
                )
                return UpdateCheckOutcome.AlreadyCurrent(
                    localVersion = localVersion,
                    latestTag = remoteTag
                )
            }
            val asset = PlatformUpdateInstaller.selectAsset(release.assets)
                ?: error(
                    "No platform asset found in release ${release.tagName} " +
                        "(assets=${release.assets.map { it.name }})"
                )
            return UpdateCheckOutcome.Available(
                offer = PendingUpdateOffer(
                    remoteVersion = remoteTag,
                    releaseTitle = release.name?.trim()?.takeIf { it.isNotEmpty() },
                    releaseNotes = release.body?.trim()?.takeIf { it.isNotEmpty() },
                    assetName = asset.name,
                    assetDownloadUrl = asset.browserDownloadUrl,
                    assetSizeBytes = asset.size
                )
            )
        }
    }

    /**
     * Fetches the latest release; if newer than the running app, downloads and installs it.
     * [onNewerRelease] runs before download so the UI can surface update info immediately.
     */
    suspend fun checkForUpdatesAndInstall(
        onNewerRelease: (UpdateCheckOutcome.Installing) -> Unit = {}
    ): UpdateCheckOutcome {
        return when (val outcome = probeForUpdates()) {
            is UpdateCheckOutcome.AlreadyCurrent -> outcome
            is UpdateCheckOutcome.Available -> {
                onNewerRelease(
                    UpdateCheckOutcome.Installing(
                        remoteVersion = outcome.offer.remoteVersion,
                        releaseTitle = outcome.offer.releaseTitle,
                        releaseNotes = outcome.offer.releaseNotes
                    )
                )
                downloadAndInstall(outcome.offer)
            }
            is UpdateCheckOutcome.Installing -> outcome
        }
    }

    suspend fun downloadAndInstall(offer: PendingUpdateOffer): UpdateCheckOutcome.Installing {
        installMutex.withLock {
            println(
                "AppUpdater: downloading ${offer.assetName} " +
                    "(${offer.assetSizeBytes} bytes) for ${offer.remoteVersion}"
            )
            val cacheDir = PlatformUpdateInstaller.updateCacheDirectory()
            SystemFileSystem.createDirectories(Path(cacheDir))
            val targetPath = Path("$cacheDir/${offer.assetName}")
            downloadToFile(offer.assetDownloadUrl, targetPath)
            println("AppUpdater: download complete → $targetPath; installing…")
            PlatformUpdateInstaller.installAndRelaunch(
                localFilePath = targetPath.toString(),
                remoteVersion = offer.remoteVersion
            )
            return UpdateCheckOutcome.Installing(
                remoteVersion = offer.remoteVersion,
                releaseTitle = offer.offerTitleOrNull(),
                releaseNotes = offer.releaseNotes
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

    private fun PendingUpdateOffer.offerTitleOrNull(): String? =
        releaseTitle?.trim()?.takeIf { it.isNotEmpty() }
}
