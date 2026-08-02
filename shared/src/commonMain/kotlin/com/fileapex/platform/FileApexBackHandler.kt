package com.fileapex.platform

import androidx.compose.runtime.Composable

@Composable
expect fun FileApexBackHandler(enabled: Boolean = true, onBack: () -> Unit)
