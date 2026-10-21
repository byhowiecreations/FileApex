package com.fileapex.update

/** What an APK has to be before it may replace the installed app. Sending a newer build to a device in the cluster stays allowed. */
internal enum class ApkUpdateVerdict(val messageKey: String?) {
    ACCEPT(null),
    WRONG_PACKAGE("update_wrong_package"),
    SIGNATURE_MISMATCH("update_signature_mismatch"),
    OLDER_VERSION("update_older_version");

    companion object {
        /** [signaturesMatch] is null when the platform cannot read signatures; the system installer then makes that check. */
        fun judge(
            archivePackage: String?,
            ownPackage: String,
            archiveVersionCode: Long,
            installedVersionCode: Long,
            signaturesMatch: Boolean?
        ): ApkUpdateVerdict = when {
            archivePackage != ownPackage -> WRONG_PACKAGE
            signaturesMatch == false -> SIGNATURE_MISMATCH
            archiveVersionCode < installedVersionCode -> OLDER_VERSION
            else -> ACCEPT
        }
    }
}
