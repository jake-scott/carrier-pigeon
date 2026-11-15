// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the home screen shows. Plain data, so the screen can be previewed without a phone. */
data class HomeState(
    val sims: List<SimTileModel>,
    val showDetail: Boolean,
    val needsPhoneAccess: Boolean,
    /** Shown above the tiles when there's something to say about data overall, e.g. "No data SIM". */
    val notice: String? = null,
)

/** What the home screen can ask for. */
interface HomeActions {
    fun setShowDetail(show: Boolean)
    fun allowPhoneAccess()
    fun refresh()
    /** Newest first. */
    fun readLog(): List<String>
    fun shareLog()
    fun clearLog()
}

/**
 * The app's one screen: the "Show network detail" switch, then a tile per active SIM. Refresh and
 * the switch log live in the menu at the top right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    var menuOpen by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf<List<String>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var term by remember { mutableStateOf<Term?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Carrier Pigeon") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Refresh") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = { menuOpen = false; actions.refresh() },
                        )
                        DropdownMenuItem(
                            text = { Text("Switch log") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                            onClick = { menuOpen = false; logLines = actions.readLog() },
                        )
                        DropdownMenuItem(
                            text = { Text("Share log") },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = { menuOpen = false; actions.shareLog() },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear log") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; confirmClear = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DetailSwitch(state.showDetail, actions::setShowDetail) }
            if (state.needsPhoneAccess) {
                item { PhoneAccessCard(actions::allowPhoneAccess) }
            }
            state.notice?.let { notice ->
                item { Text(notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            }
            if (state.sims.isEmpty()) {
                item {
                    Text(
                        "No active SIMs",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                item {
                    Column(Modifier.padding(top = 4.dp)) {
                        Text("Active SIMs", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tap a term like LTE or 5G to see what it means",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.sims, key = { it.subId }) { sim ->
                    SimTile(sim, state.showDetail, onTerm = { term = it })
                }
            }
        }
    }

    term?.let { TermDialog(it) { term = null } }
    logLines?.let { lines -> LogDialog(lines, onShare = actions::shareLog, onDismiss = { logLines = null }) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the switch log?") },
            text = { Text("This deletes the record of SIM switches kept on this phone.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; actions.clearLog() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Show network detail", style = MaterialTheme.typography.titleSmall)
                Text(
                    "What's under the network type, e.g. \"LTE + NR\" under 5G NSA. Also shown on the widget.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun PhoneAccessCard(onAllow: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Allow phone access to see all your SIMs, their names and network type. Without it the app " +
                    "and widget can only show the carrier name stored on the data SIM.",
            )
            Text(
                "Carrier Pigeon only reads SIM and network info. It never makes or manages calls.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAllow) { Text("Allow") }
        }
    }
}

@Composable
private fun LogDialog(lines: List<String>, onShare: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch log") },
        text = {
            if (lines.isEmpty()) {
                Text("Nothing logged yet.")
            } else {
                LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(lines) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = if (line.contains(" SWITCH ")) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (lines.isNotEmpty()) TextButton(onClick = onShare) { Text("Share") } },
    )
}
