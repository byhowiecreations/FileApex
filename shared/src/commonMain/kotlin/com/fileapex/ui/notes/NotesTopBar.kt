package com.fileapex.ui.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.BulletinFilter
import com.fileapex.ui.theme.FileApexTeal

@Composable
internal fun NotesScope.NotesTopBar() {
    with(frame) { with(live) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringRes("back"),
                    tint = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringRes("bulletin_board"),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = textColor
                )
                Text(
                    text = stringRes("notes_subtitle"),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = subTextColor
                )
            }
            Box {
                IconButton(onClick = { filterMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = stringRes("filter_notes"),
                        tint = if (activeFilter != BulletinFilter.All) {
                            if (isCustomGlass) Color(0xFF00E5FF) else FileApexTeal
                        } else {
                            subTextColor
                        }
                    )
                }
                val checkTint = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
                DropdownMenu(
                    expanded = filterMenuExpanded,
                    onDismissRequest = { filterMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringRes("filter_all"),
                                fontWeight = if (activeFilter == BulletinFilter.All) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        trailingIcon = if (activeFilter == BulletinFilter.All) {
                            { Icon(Icons.Filled.Check, contentDescription = null, tint = checkTint) }
                        } else null,
                        onClick = {
                            viewModel.setFilter(BulletinFilter.All)
                            filterMenuExpanded = false
                        }
                    )
                    if (hasLinks) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringRes("filter_links"),
                                    fontWeight = if (activeFilter == BulletinFilter.Links) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            trailingIcon = if (activeFilter == BulletinFilter.Links) {
                                { Icon(Icons.Filled.Check, contentDescription = null, tint = checkTint) }
                            } else null,
                            onClick = {
                                viewModel.setFilter(BulletinFilter.Links)
                                filterMenuExpanded = false
                            }
                        )
                    }
                    if (hasImages) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringRes("filter_images"),
                                    fontWeight = if (activeFilter == BulletinFilter.Images) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            trailingIcon = if (activeFilter == BulletinFilter.Images) {
                                { Icon(Icons.Filled.Check, contentDescription = null, tint = checkTint) }
                            } else null,
                            onClick = {
                                viewModel.setFilter(BulletinFilter.Images)
                                filterMenuExpanded = false
                            }
                        )
                    }
                    if (hasDocs) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringRes("filter_documents"),
                                    fontWeight = if (activeFilter == BulletinFilter.Documents) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            trailingIcon = if (activeFilter == BulletinFilter.Documents) {
                                { Icon(Icons.Filled.Check, contentDescription = null, tint = checkTint) }
                            } else null,
                            onClick = {
                                viewModel.setFilter(BulletinFilter.Documents)
                                filterMenuExpanded = false
                            }
                        )
                    }
                    if (hasSnippets) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringRes("filter_snippets"),
                                    fontWeight = if (activeFilter == BulletinFilter.Snippets) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            trailingIcon = if (activeFilter == BulletinFilter.Snippets) {
                                { Icon(Icons.Filled.Check, contentDescription = null, tint = checkTint) }
                            } else null,
                            onClick = {
                                viewModel.setFilter(BulletinFilter.Snippets)
                                filterMenuExpanded = false
                            }
                        )
                    }
                    if (hasOtherFiles) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringRes("filter_files"),
                                    fontWeight = if (activeFilter == BulletinFilter.Files) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            trailingIcon = if (activeFilter == BulletinFilter.Files) {
                                { Icon(Icons.Filled.Check, contentDescription = null, tint = checkTint) }
                            } else null,
                            onClick = {
                                viewModel.setFilter(BulletinFilter.Files)
                                filterMenuExpanded = false
                            }
                        )
                    }
                    if (hasPinned) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringRes("filter_pinned"),
                                    fontWeight = if (activeFilter == BulletinFilter.Pinned) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            trailingIcon = if (activeFilter == BulletinFilter.Pinned) {
                                { Icon(Icons.Filled.Check, contentDescription = null, tint = checkTint) }
                            } else null,
                            onClick = {
                                viewModel.setFilter(BulletinFilter.Pinned)
                                filterMenuExpanded = false
                            }
                        )
                    }
                    if (availableTags.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        availableTags.forEach { tag ->
                            val isSelected = activeFilter is BulletinFilter.Tag && (activeFilter as BulletinFilter.Tag).tag == tag
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = tag,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCustomGlass) Color(0xFF00E5FF) else FileApexTeal
                                    )
                                },
                                trailingIcon = if (isSelected) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, tint = checkTint) }
                                } else null,
                                onClick = {
                                    viewModel.setFilter(BulletinFilter.Tag(tag))
                                    filterMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    } }
}
