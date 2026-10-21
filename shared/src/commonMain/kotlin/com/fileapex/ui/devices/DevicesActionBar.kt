package com.fileapex.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DevicesUiState
import com.fileapex.ui.devices.SendClipboardActionChip
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.FluxGlassPalette
import com.fileapex.ui.theme.LocalFileApexUiStyle
import com.fileapex.ui.theme.isFileApexCleanCurved

@Composable
internal fun DevicesActionBar(
    state: DevicesUiState,
    addMenuOpen: Boolean,
    onAddMenuOpenChange: (Boolean) -> Unit,
    onSendClipboard: () -> Unit,
    onGenerateQr: () -> Unit,
    onJoinDevice: () -> Unit
) {
    val isFlux = LocalAppTheme.current.traits.fluxSurfaces
    val isCleanPills = isFileApexCleanCurved()
    if (isFlux || isCleanPills) {
        val pillColor = if (isFlux) FluxGlassPalette.actionPill else MaterialTheme.colorScheme.surface
        val clipTint = if (isFlux) FluxGlassPalette.cyan else FileApexTeal
        val clipText = if (isFlux) Color.White else MaterialTheme.colorScheme.onSurface
        val addColor = if (isFlux) FluxGlassPalette.actionPill else FileApexTeal
        val addTint = if (isFlux) FluxGlassPalette.accent else Color.White
        val addText = Color.White
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (isCleanPills) 44.dp else 0.dp)
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onSendClipboard,
                    enabled = !state.isSendingClipboard,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(percent = 50),
                    color = pillColor,
                    border = BorderStroke(
                        1.dp,
                        clipTint.copy(alpha = if (state.isSendingClipboard) 0.25f else if (isFlux) 0.55f else 0.35f)
                    ),
                    shadowElevation = if (state.isSendingClipboard) 2.dp else 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (state.isSendingClipboard) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = clipTint
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.ContentPaste,
                                contentDescription = null,
                                tint = clipTint,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringRes("send_clipboard"),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            ),
                            color = if (state.isSendingClipboard) clipText.copy(alpha = 0.6f) else clipText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        onClick = { onAddMenuOpenChange(true) },
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(percent = 50),
                        color = addColor,
                        border = if (isFlux) BorderStroke(1.dp, FluxGlassPalette.accent.copy(alpha = 0.65f)) else null,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                tint = addTint,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringRes("add_new_device"),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                ),
                                color = addText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = addMenuOpen,
                        onDismissRequest = { onAddMenuOpenChange(false) }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringRes("generate_qr")) },
                            onClick = {
                                onAddMenuOpenChange(false)
                                onGenerateQr()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringRes("join_device")) },
                            onClick = {
                                onAddMenuOpenChange(false)
                                onJoinDevice()
                            }
                        )
                    }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SendClipboardActionChip(
                onClick = onSendClipboard,
                enabled = !state.isSendingClipboard,
                isLoading = state.isSendingClipboard
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = { onAddMenuOpenChange(true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    shape = if (LocalFileApexUiStyle.current == DesktopUiStyle.WindowsFluent) {
                        MaterialTheme.shapes.medium
                    } else {
                        RoundedCornerShape(20.dp)
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FileApexTeal,
                        contentColor = Color.White
                    ),
                    elevation = if (LocalFileApexUiStyle.current == DesktopUiStyle.WindowsFluent) {
                        ButtonDefaults.buttonElevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp,
                            hoveredElevation = 0.dp
                        )
                    } else {
                        ButtonDefaults.buttonElevation()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringRes("add_new_device"),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        softWrap = true,
                        maxLines = 2
                    )
                }
                DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { onAddMenuOpenChange(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringRes("generate_qr")) },
                        onClick = {
                            onAddMenuOpenChange(false)
                            onGenerateQr()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringRes("join_device")) },
                        onClick = {
                            onAddMenuOpenChange(false)
                            onJoinDevice()
                        }
                    )
                }
            }
        }
    }
}
