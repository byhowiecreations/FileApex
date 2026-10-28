package com.fileapex.ui.notice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import com.fileapex.notice.NoticeCoordinator
import com.fileapex.notice.VisibleNotice
import kotlinx.coroutines.flow.StateFlow

/** Lets a fixture draw the header without reading this machine's own stored notices. */
val LocalNoticeSource = staticCompositionLocalOf<StateFlow<List<VisibleNotice>>?> { null }

@Composable
fun rememberVisibleNotices(): State<List<VisibleNotice>> = (LocalNoticeSource.current ?: NoticeCoordinator.notices).collectAsState()
