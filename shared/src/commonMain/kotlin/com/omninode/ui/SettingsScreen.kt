package com.omninode.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omninode.platform.OmniBackHandler
import com.omninode.presentation.SettingsViewModel
import com.omninode.ui.theme.OmniTeal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appVersionName: String,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() }
) {
    val state by viewModel.uiState.collectAsState()

    OmniBackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OmniTeal,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Auto-Update") },
                    supportingContent = {
                        Text(
                            "When on, OmniNode checks GitHub Releases on launch and installs " +
                                "newer builds for this platform. Default is off."
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.autoUpdateEnabled,
                            onCheckedChange = viewModel::setAutoUpdate
                        )
                    }
                )
                ListItem(
                    headlineContent = { Text("PIN required") },
                    supportingContent = {
                        Text(
                            "When on, other devices must enter this device's PIN to pair and to " +
                                "browse or transfer files. Default is off. Set a 4–8 digit PIN " +
                                "below — only this device stores it."
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.pinRequiredEnabled,
                            onCheckedChange = viewModel::setPinRequired
                        )
                    }
                )
                OutlinedTextField(
                    value = state.devicePin,
                    onValueChange = viewModel::setDevicePin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = true,
                    label = { Text("Device PIN (4–8 digits)") },
                    supportingText = state.pinError?.let { err ->
                        {
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    isError = state.pinError != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                ListItem(
                    headlineContent = { Text("File Transfer notifications") },
                    supportingContent = {
                        Text(
                            "When on, this device shows a notification after files are received " +
                                "successfully (includes filenames). Off keeps transfers silent. " +
                                "Applies only when receiving, not when sending."
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.fileTransferNotificationsEnabled,
                            onCheckedChange = viewModel::setFileTransferNotifications
                        )
                    }
                )
                ListItem(
                    headlineContent = { Text("Google Account") },
                    supportingContent = {
                        Text("Link a Google Account to discover devices signed into the same account. Coming soon.")
                    },
                    trailingContent = {
                        Switch(
                            checked = state.googleAccountLinkEnabled,
                            onCheckedChange = viewModel::setGoogleAccountLink
                        )
                    }
                )
            }
            Text(
                text = "OmniNode v$appVersionName",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    fontSize = 12.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            )
        }
    }
}
