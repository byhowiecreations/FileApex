package com.fileapex.cloud.drive

/**
 * Drive API v3 over HTTPS — single I/O surface for relay uploads, downloads, and [log.md].
 */
expect object GoogleDriveClient {
    suspend fun uploadResumable(
        localAbsolutePath: String,
        fileName: String,
        mimeType: String = "application/octet-stream"
    ): DriveUploadedFile

    suspend fun downloadToPath(
        driveFileId: String,
        destAbsolutePath: String,
        expectedSizeBytes: Long = -1L
    )

    suspend fun deleteFile(driveFileId: String)

    /**
     * Loads [log.md]. When [ifNoneMatch] matches the current metadata ETag, [DriveLedgerSnapshot.notModified]
     * is true and the body is not downloaded.
     */
    suspend fun loadLedger(ifNoneMatch: String? = null): DriveLedgerSnapshot

    suspend fun saveLedger(ledger: DriveLedger, ifMatchEtag: String?): DriveLedgerSnapshot

    /** Creates or finds the FileApex Relay folder. Throws if the token cannot use Drive. */
    suspend fun verifyRelayAccess()

    /**
     * Deletes every file in FileApex Relay (including [log.md]) and seeds an empty ledger.
     * @return number of files removed
     */
    suspend fun purgeRelayFolder(): Int
}

class DriveUnauthorizedException(message: String) : Exception(message)

class DriveHttpException(val status: Int, message: String) : Exception(message)

/** Short Settings copy — never dump Google JSON bodies. */
fun driveGrantUserMessage(error: Throwable): String {
    val raw = generateSequence(error) { it.cause }
        .mapNotNull { it.message }
        .joinToString("\n")
    return when {
        "SERVICE_DISABLED" in raw ||
            "accessNotConfigured" in raw ||
            "has not been used in project" in raw ->
            "Google Drive API is disabled on this Cloud project. Enable it in Google Cloud Console " +
                "(APIs & Services → Google Drive API), wait a few minutes, then tap Grant Access again."
        error is DriveUnauthorizedException || "401" in raw ->
            "Google Drive sign-in expired. Tap Grant Access again."
        error is DriveHttpException && error.status == 403 ->
            "Google Drive denied access. Allow FileApex on the Google permission page and try again."
        "Drive authorization cancelled" in raw ->
            "Drive authorization cancelled"
        else ->
            "Drive authorization failed. Tap Grant Access and allow FileApex on the Google page."
    }
}
