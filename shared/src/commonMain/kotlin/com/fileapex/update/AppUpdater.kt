package com.fileapex.update

import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readAtMostTo
import kotlinx.io.write

/**
 * Checks GitHub Releases for a newer FileApex build, downloads the platform asset,
 * and hands off to [PlatformUpdateInstaller].
 */
object AppUpdater {
    const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/byhowiecreations/FileApex/releases/latest"

    private val client get() = FileApexServices.httpClient

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
                ?: error(AppI18n.t("update_no_platform_asset"))
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
            val safeVersion = offer.remoteVersion.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetPath = Path("$cacheDir/$safeVersion-${offer.assetName}")
            val partPath = Path("$targetPath.part")
            downloadToFile(offer.assetDownloadUrl, partPath)
            validateDownloadedAsset(partPath, offer.assetSizeBytes)
            replaceDownloadedFile(partPath, targetPath)
            validateDownloadedAsset(targetPath, offer.assetSizeBytes)
            println(
                "AppUpdater: download complete → $targetPath " +
                    "(${SystemFileSystem.metadataOrNull(targetPath)?.size ?: -1} bytes); installing…"
            )
            PlatformUpdateInstaller.installAndRelaunch(
                localFilePath = targetPath.toString(),
                remoteVersion = offer.remoteVersion
            )
            delay(INSTALL_GRACE_MS)
            return UpdateCheckOutcome.Installing(
                remoteVersion = offer.remoteVersion,
                releaseTitle = offer.offerTitleOrNull(),
                releaseNotes = offer.releaseNotes
            )
        }
    }

    private suspend fun fetchLatestRelease(): GitHubRelease {
        val response = client.get(LATEST_RELEASE_URL) {
            header(HttpHeaders.UserAgent, "FileApex/${currentAppVersionName()}")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        if (!response.status.isSuccess()) {
            error(AppI18n.t("update_github_failed"))
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
            header(HttpHeaders.UserAgent, "FileApex/${currentAppVersionName()}")
            // browser_download_url redirects to CDN; avoid negotiating JSON/HTML bodies.
            header(HttpHeaders.Accept, "*/*")
        }.execute { response ->
            if (!response.status.isSuccess()) {
                error(AppI18n.t("update_download_failed"))
            }
            val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
            if (contentType.contains("text/html", ignoreCase = true)) {
                error(AppI18n.t("update_html_download"))
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

    private fun replaceDownloadedFile(source: Path, destination: Path) {
        if (SystemFileSystem.exists(destination)) {
            SystemFileSystem.delete(destination)
        }
        SystemFileSystem.source(source).buffered().use { input ->
            SystemFileSystem.sink(destination).buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (!input.exhausted()) {
                    val read = input.readAtMostTo(buffer)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        SystemFileSystem.delete(source)
    }

    private fun validateDownloadedAsset(target: Path, expectedSizeBytes: Long) {
        val metadata = SystemFileSystem.metadataOrNull(target)
            ?: error(AppI18n.t("update_file_missing"))
        val size = metadata.size
        check(size > 1_024L) {
            AppI18n.t("update_too_small")
        }
        if (expectedSizeBytes > 0L && size != expectedSizeBytes) {
            error(AppI18n.t("update_size_mismatch"))
        }
    }

    private fun PendingUpdateOffer.offerTitleOrNull(): String? =
        releaseTitle?.trim()?.takeIf { it.isNotEmpty() }

    private const val INSTALL_GRACE_MS = 15_000L
}
