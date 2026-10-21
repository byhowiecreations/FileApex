package com.fileapex.ui.kinetic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned

internal class KineticLive {
    var addMenuOpen by mutableStateOf(false)
    var activeRadialNodeId by mutableStateOf<String?>(null)
    // Do NOT store LayoutCoordinates in Compose state: onGloballyPositioned fires every layout pass with a new instance.
    val sphereCoordsRef = arrayOfNulls<LayoutCoordinates>(1)
}
