package com.omninode.platform

import androidx.compose.runtime.Composable

@Composable
expect fun OmniBackHandler(enabled: Boolean = true, onBack: () -> Unit)
