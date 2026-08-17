package com.fileapex.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun PairingQrScanner(
    onQrText: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
)
