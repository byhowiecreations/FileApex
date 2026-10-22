package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.friends.FriendsHub
import com.fileapex.friends.ui.FriendsUi
import com.fileapex.i18n.stringRes

/** First-run entry on the Simple Overview: one Setup button that drops a menu with the three ways to connect. */
@Composable
internal fun SimpleSetupButton(onGenerate: () -> Unit, onJoin: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { open = true }) {
            Text(stringRes("simple_setup"))
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringRes("add_new_device")) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { open = false; onGenerate() }
            )
            DropdownMenuItem(
                text = { Text(stringRes("join_device")) },
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                onClick = { open = false; onJoin() }
            )
            if (FriendsHub.available) DropdownMenuItem(
                text = { Text(stringRes("friends_entry")) },
                leadingIcon = { Icon(Icons.Filled.People, contentDescription = null) },
                onClick = { open = false; FriendsUi.open() }
            )
        }
    }
}
