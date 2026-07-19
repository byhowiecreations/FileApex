package com.omninode.platform

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberGoogleAccountPicker(onPicked: (email: String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            onPicked(null)
            return@rememberLauncherForActivityResult
        }
        val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        onPicked(email?.takeIf { it.isNotBlank() })
    }
    return remember(launcher, context) {
        {
            val intent = AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null
            )
            launcher.launch(intent)
        }
    }
}
