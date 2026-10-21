package com.fileapex.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.fileapex.di.FileApexServices
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun FreestyleScope.FreestyleTutorialDialog() {
    with(inputs) { with(persisted) { with(geometry) { with(live) {
        // One-time Edit Mode Tutorial Dialog
        if (showTutorialDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(300f)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 380.dp)
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFA0B1B2B),
                    border = BorderStroke(1.5.dp, Color(0xFF64B5F6)),
                    shadowElevation = 24.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Customize Your Layout",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "You can drag and rearrange the Options button and device buttons across the canvas. In Edit mode, you can also reorder action menus inside device cards and Options.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = {
                                showTutorialDialog = false
                                FileApexServices.settings.setFreestyleEditTutorialShown(true)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1976D2),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Got it", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    } } } }
}
