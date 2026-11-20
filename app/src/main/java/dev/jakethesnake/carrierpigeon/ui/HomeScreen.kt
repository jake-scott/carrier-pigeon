// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import dev.jakethesnake.carrierpigeon.R
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jakethesnake.carrierpigeon.data.WifiStatus

/** What the home screen shows. Plain data, so the screen can be previewed without a phone. */
data class HomeState(
    val sims: List<SimTileModel>,
    val showDetail: Boolean,
    val needsPhoneAccess: Boolean,
    /** Shown above the tiles when there's something to say about data overall, e.g. "No data SIM". */
    val notice: String? = null,
    /** Android 13+: the menu can open the system screen that sets this app's language. */
    val canPickLanguage: Boolean = false,
    /** "Internet via Wi-Fi" etc., at the top of the screen. */
    val internet: InternetHeadline? = null,
    /** Null until the first reading. */
    val wifi: WifiStatus? = null,
    /** The phone is reaching the internet over Wi-Fi right now. */
    val wifiCarriesInternet: Boolean = false,
    /** Why the Wi-Fi name is hidden, if it is. */
    val ssidHint: SsidHint? = null,
    /** False on a device that can't hold a SIM: say so instead of showing SIM tiles. */
    val hasTelephony: Boolean = true,
)

/** What the home screen can ask for. */
interface HomeActions {
    fun setShowDetail(show: Boolean)
    fun allowPhoneAccess()
    fun allowLocationAccess()
    fun refresh()
    /** Opens the system screen that sets this app's language (Android 13+). */
    fun openLanguageSettings()
    /** Opens the History page. */
    fun openHistory()
    /** Newest first. */
    fun readLog(): List<String>
    fun shareLog()
    fun clearLog()
}

/**
 * The app's one screen: how the phone reaches the internet, a Wi-Fi tile, a tile per active SIM,
 * then the "Show network detail" switch. Refresh, History and
 * the switch log live in the menu at the top right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    var menuOpen by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf<List<String>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var term by remember { mutableStateOf<TermId?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(36.dp)
                        Text(stringResource(R.string.home_title))
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu_open))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_refresh)) },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = { menuOpen = false; actions.refresh() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_history)) },
                            leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                            onClick = { menuOpen = false; actions.openHistory() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_switch_log)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                            onClick = { menuOpen = false; logLines = actions.readLog() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_share_log)) },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = { menuOpen = false; actions.shareLog() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_clear_log)) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; confirmClear = true },
                        )
                        if (state.canPickLanguage) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_language)) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = { menuOpen = false; actions.openLanguageSettings() },
                            )
                        }
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
            state.internet?.let { headline ->
                item { InternetHeader(headline, onTerm = { term = it }) }
            }
            if (state.needsPhoneAccess) {
                item { PhoneAccessCard(actions::allowPhoneAccess) }
            }
            state.notice?.let { notice ->
                item { Text(notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            }
            state.wifi?.let { wifi ->
                item {
                    WifiTile(
                        wifi,
                        carriesInternet = state.wifiCarriesInternet,
                        showDetail = state.showDetail,
                        ssidHint = state.ssidHint,
                        onAllowLocation = actions::allowLocationAccess,
                        onTerm = { term = it },
                    )
                }
            }
            if (state.sims.isEmpty()) {
                item {
                    Text(
                        stringResource(if (state.hasTelephony) R.string.home_no_sims else R.string.home_no_telephony),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                item {
                    Column(Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.home_active_sims), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.home_terms_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.sims, key = { it.subId }) { sim ->
                    SimTile(sim, state.showDetail, onTerm = { term = it })
                }
            }
            item { DetailSwitch(state.showDetail, actions::setShowDetail) }
        }
    }

    term?.let { TermDialog(it) { term = null } }
    logLines?.let { lines -> LogDialog(lines, onShare = actions::shareLog, onDismiss = { logLines = null }) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_log_title)) },
            text = { Text(stringResource(R.string.clear_log_body)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; actions.clearLog() }) { Text(stringResource(R.string.action_clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * The launcher icon, drawn from its two layers. Compose can't load the adaptive-icon mipmap, so
 * stack the 108dp background and foreground and crop to the middle 72dp a launcher shows.
 */
@Composable
private fun AppIcon(size: Dp) {
    Box(Modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        val layer = Modifier.requiredSize(size * 108f / 72f)
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = layer)
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = layer)
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
                Text(stringResource(R.string.home_show_detail), style = MaterialTheme.typography.titleSmall)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun PhoneAccessCard(onAllow: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.phone_card_body))
            Text(
                stringResource(R.string.phone_card_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAllow) { Text(stringResource(R.string.action_allow)) }
        }
    }
}

@Composable
private fun LogDialog(lines: List<String>, onShare: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_title)) },
        text = {
            if (lines.isEmpty()) {
                Text(stringResource(R.string.log_empty))
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = { if (lines.isNotEmpty()) TextButton(onClick = onShare) { Text(stringResource(R.string.action_share)) } },
    )
}
