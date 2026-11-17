package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The linked Google account and the backup hints that go with it. Unlinking forgets all of them. */
class GoogleAccountStore(private val store: SettingsKvStore) {
    private val linkedFlow = MutableStateFlow(store.getBoolean(KEY_LINK, false))
    private val emailFlow = MutableStateFlow(store.getString(KEY_EMAIL, ""))
    private val uidFlow = MutableStateFlow(store.getString(KEY_UID, ""))
    private val restorePendingFlow = MutableStateFlow(store.getBoolean(KEY_RESTORE_PENDING, false))
    private val backupEmailHintFlow = MutableStateFlow(store.getString(KEY_BACKUP_EMAIL_HINT, ""))

    val linked: StateFlow<Boolean> = linkedFlow.asStateFlow()
    val email: StateFlow<String> = emailFlow.asStateFlow()
    val uid: StateFlow<String> = uidFlow.asStateFlow()
    val restorePending: StateFlow<Boolean> = restorePendingFlow.asStateFlow()
    val backupEmailHint: StateFlow<String> = backupEmailHintFlow.asStateFlow()

    fun setLinked(enabled: Boolean) {
        store.putBoolean(KEY_LINK, enabled)
        linkedFlow.value = enabled
        if (!enabled) {
            setEmail("")
            setUid("")
            setRestorePending(false)
            setBackupEmailHint("")
        }
    }

    fun setEmail(value: String) {
        val cleaned = value.trim()
        store.putString(KEY_EMAIL, cleaned)
        emailFlow.value = cleaned
    }

    fun setUid(value: String) {
        val cleaned = value.trim()
        store.putString(KEY_UID, cleaned)
        uidFlow.value = cleaned
    }

    fun setRestorePending(pending: Boolean) {
        store.putBoolean(KEY_RESTORE_PENDING, pending)
        restorePendingFlow.value = pending
    }

    fun setBackupEmailHint(value: String) {
        val cleaned = value.trim()
        store.putString(KEY_BACKUP_EMAIL_HINT, cleaned)
        backupEmailHintFlow.value = cleaned
    }

    /** The settings store has been cleared; bring the in-memory state back to the defaults. */
    fun forgetInMemory() {
        linkedFlow.value = false
        emailFlow.value = ""
        uidFlow.value = ""
        restorePendingFlow.value = false
        backupEmailHintFlow.value = ""
    }

    private companion object {
        const val KEY_LINK = BaseAppSettings.KEY_GOOGLE
        const val KEY_EMAIL = BaseAppSettings.KEY_GOOGLE_EMAIL
        const val KEY_UID = BaseAppSettings.KEY_GOOGLE_UID
        const val KEY_RESTORE_PENDING = BaseAppSettings.KEY_GOOGLE_RESTORE_PENDING
        const val KEY_BACKUP_EMAIL_HINT = BaseAppSettings.KEY_GOOGLE_BACKUP_EMAIL_HINT
    }
}
