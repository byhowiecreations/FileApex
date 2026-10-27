package com.fileapex.presentation.settings

import androidx.lifecycle.viewModelScope
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.fileapex.presentation.SettingsUiState
import kotlinx.coroutines.CoroutineScope

internal class SettingsGoogleActions(
    private val _uiState: MutableStateFlow<SettingsUiState>,
    private val viewModelScope: CoroutineScope
) {
    private val settings = FileApexServices.settings

    /** Credential Manager / desktop OAuth returned a Google ID token. */
    fun onGoogleIdToken(idToken: String?, emailHint: String?, errorMessage: String?) {
        if (idToken.isNullOrBlank()) {
            _uiState.update {
                it.copy(googleAccountError = errorMessage ?: AppI18n.t("google_signin_cancelled"))
            }
            return
        }
        viewModelScope.launch {
            cancellableCatching {
                GoogleLinkCoordinator.linkWithGoogleIdToken(idToken, emailHint)
            }.onSuccess { session ->
                _uiState.update {
                    it.copy(
                        googleAccountLinkEnabled = true,
                        googleAccountEmail = session.email,
                        googleAccountError = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(googleAccountError = UserFacingErrors.message(error, "google_link_failed"))
                }
            }
        }
    }

    fun disableGoogleAccountLink() {
        viewModelScope.launch {
            cancellableCatching {
                GoogleLinkCoordinator.unlinkAndSignOut()
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        googleAccountLinkEnabled = false,
                        googleAccountEmail = "",
                        googleAccountError = null
                    )
                }
            }.onFailure { error ->
                settings.setGoogleAccountLinkEnabled(false)
                _uiState.update {
                    it.copy(
                        googleAccountLinkEnabled = false,
                        googleAccountEmail = "",
                        googleAccountError = error.message
                    )
                }
            }
        }
    }
}
