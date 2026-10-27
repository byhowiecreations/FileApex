package com.fileapex.ui.devices

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.fileapex.friends.ui.FriendSendTarget
import com.fileapex.presentation.BrowseTarget

internal class FreestyleLive {
    var activeDeviceId by mutableStateOf<String?>(null)
    var optionsMenuExpanded by mutableStateOf(false)
    var showFilesWindow by mutableStateOf(false)
    var browsingTarget by mutableStateOf<BrowseTarget?>(null)
    var isResolvingBrowse by mutableStateOf(false)
    var resolvingDeviceName by mutableStateOf<String?>(null)
    var sendingToFriend by mutableStateOf<FriendSendTarget?>(null)
    var showTutorialDialog by mutableStateOf(false)
    var windowWidthDp by mutableStateOf(400.dp)
    var windowHeightDp by mutableStateOf(480.dp)
    var draggingOptIndex by mutableStateOf<Int?>(null)
    var draggingOptOffsetX by mutableStateOf(0f)
    var draggingOptOffsetY by mutableStateOf(0f)
    var dragCanvasPos by mutableStateOf<Offset?>(null)
    var isDraggingOut by mutableStateOf(false)
}
