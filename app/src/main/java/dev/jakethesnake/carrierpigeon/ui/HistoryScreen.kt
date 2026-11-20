// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.jakethesnake.carrierpigeon.ui

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.jakethesnake.carrierpigeon.R
import dev.jakethesnake.carrierpigeon.data.AppSettings
import dev.jakethesnake.carrierpigeon.data.DataSimRefresher
import dev.jakethesnake.carrierpigeon.data.NetworkKind
import dev.jakethesnake.carrierpigeon.data.SimLabel
import dev.jakethesnake.carrierpigeon.data.history.HistoryMath
import dev.jakethesnake.carrierpigeon.data.history.HistoryRange
import dev.jakethesnake.carrierpigeon.data.history.HistoryRecorder
import dev.jakethesnake.carrierpigeon.data.history.HistoryStore
import dev.jakethesnake.carrierpigeon.data.history.HistoryWindow
import dev.jakethesnake.carrierpigeon.data.history.SignalSource
import dev.jakethesnake.carrierpigeon.data.history.SimRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** The History page, opened from the home screen's menu. */
class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { HistoryRoute(onBack = ::finish) } }
    }
}

/** Loads history for the chosen window and keeps it current while the page is open. */
@Composable
private fun HistoryRoute(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(app) }
    val store = remember { HistoryStore.get(app) }
    val enabled by settings.historyEnabled.collectAsState(initial = true)
    val retentionDays by settings.historyRetentionDays.collectAsState(initial = AppSettings.DEFAULT_HISTORY_RETENTION_DAYS)
    var range by rememberSaveable { mutableStateOf(HistoryRange.DAY) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<HistoryData?>(null) }

    // On open: confirm the current state, and tidy up in case the daily job hasn't run lately.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            store.maintain(System.currentTimeMillis(), settings.historyRetentionDays() * HistoryMath.DAY_MS)
        }
        DataSimRefresher.refresh(app, "history-opened", settle = false)
        reload++
    }
    LaunchedEffect(range, offset, reload) {
        data = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            HistoryLoader.load(store, HistoryMath.window(range, offset, now, ZoneId.systemDefault()), now, HistoryRecorder.liveSince)
        }
    }
    // The current window grows while you watch.
    LaunchedEffect(range, offset) {
        if (offset != 0) return@LaunchedEffect
        while (true) {
            delay(60_000)
            reload++
        }
    }

    HistoryScreen(
        HistoryUiState(range, offset, data, enabled, retentionDays),
        object : HistoryActions {
            override fun back() = onBack()
            override fun setRange(r: HistoryRange) {
                range = r
                offset = 0
            }
            override fun earlier() {
                offset++
            }
            override fun later() {
                if (offset > 0) offset--
            }
            override fun setEnabled(on: Boolean) {
                scope.launch {
                    settings.setHistoryEnabled(on)
                    if (on) DataSimRefresher.refresh(app, "history-on", settle = false)
                    reload++
                }
            }
            override fun setRetentionDays(days: Int) {
                scope.launch {
                    settings.setHistoryRetentionDays(days)
                    withContext(Dispatchers.IO) { store.maintain(System.currentTimeMillis(), days * HistoryMath.DAY_MS) }
                    reload++
                }
            }
            override fun clear() {
                scope.launch {
                    withContext(Dispatchers.IO) { store.clear() }
                    if (enabled) DataSimRefresher.refresh(app, "history-cleared", settle = false)
                    reload++
                }
            }
        },
    )
}

/** What the History page shows. */
data class HistoryUiState(
    val range: HistoryRange,
    /** 0 = the window containing now; 1 = the one before, and so on. */
    val offset: Int,
    /** Null while the first load runs. */
    val data: HistoryData?,
    val enabled: Boolean,
    val retentionDays: Int,
)

/** What the History page can ask for. */
interface HistoryActions {
    fun back()
    fun setRange(r: HistoryRange)
    fun earlier()
    fun later()
    fun setEnabled(on: Boolean)
    fun setRetentionDays(days: Int)
    fun clear()
}

/**
 * Range buttons, then cards that share one time axis: what carried the internet, VPN, signal and
 * network type. Touching any chart picks a moment, shown in every chart and in the readout.
 */
@Composable
fun HistoryScreen(state: HistoryUiState, actions: HistoryActions) {
    var menuOpen by remember { mutableStateOf(false) }
    var pickRetention by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var scrubAt by remember(state.range, state.offset) { mutableStateOf<Long?>(null) }
    val res = LocalContext.current.resources

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = actions::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.history_back))
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.history_menu_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_menu_record)) },
                            trailingIcon = { Switch(checked = state.enabled, onCheckedChange = null) },
                            onClick = { menuOpen = false; actions.setEnabled(!state.enabled) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_menu_keep)) },
                            onClick = { menuOpen = false; pickRetention = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_menu_clear)) },
                            onClick = { menuOpen = false; confirmClear = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RangeBar(state, actions)
            if (!state.enabled) Note(stringResource(R.string.history_off))
            val data = state.data
            if (data != null) {
                if (data.firstRecord == null) {
                    Note(stringResource(R.string.history_empty))
                } else if (data.firstRecord > data.window.from) {
                    Note(stringResource(R.string.history_started, formatMoment(LocalContext.current, data.firstRecord, HistoryRange.MONTH)))
                }
                val colors = historyColors()
                val ticks = remember(data.window) { ticks(data.window, ZoneId.systemDefault(), res.configuration.locales[0]) }
                InternetCard(data, colors, ticks, scrubAt) { scrubAt = it }
                VpnCard(data, colors, ticks, scrubAt) { scrubAt = it }
                SignalCard(data, colors, ticks, scrubAt) { scrubAt = it }
                NetworkCard(data)
                Readout(data, scrubAt)
                Text(
                    stringResource(R.string.history_gaps_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (pickRetention) {
        RetentionDialog(state.retentionDays, onPick = { pickRetention = false; actions.setRetentionDays(it) }, onDismiss = { pickRetention = false })
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_body)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; actions.clear() }) { Text(stringResource(R.string.action_clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun RangeBar(state: HistoryUiState, actions: HistoryActions) {
    val labels = listOf(
        HistoryRange.DAY to R.string.history_range_day,
        HistoryRange.WEEK to R.string.history_range_week,
        HistoryRange.MONTH to R.string.history_range_month,
        HistoryRange.YEAR to R.string.history_range_year,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, (r, label) ->
                SegmentedButton(
                    selected = state.range == r,
                    onClick = { actions.setRange(r) },
                    shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                ) { Text(stringResource(label)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions::earlier) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.history_previous))
            }
            Text(
                state.data?.window?.let { rangeLabel(LocalContext.current, it, state.offset) }.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = actions::later, enabled = state.offset > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.history_next))
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, trailing: String? = null, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (trailing != null) {
                    Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InternetCard(data: HistoryData, colors: HistoryColors, ticks: List<Tick>, scrubAt: Long?, onScrub: (Long) -> Unit) {
    val res = LocalContext.current.resources
    val window = data.window
    val totals = HistoryMath.totals(data.access).entries.sortedByDescending { it.value }
    val order = totals.map { it.key }
    val summary = totals.joinToString(", ") { (k, ms) -> res.getString(R.string.history_total, keyLabel(res, k, data.sims), duration(res, ms)) }
    val description = res.getString(R.string.history_desc, res.getString(R.string.history_internet_via), summary)
    ChartCard(stringResource(R.string.history_internet_via)) {
        if (window.range == HistoryRange.DAY || window.range == HistoryRange.WEEK) {
            BandChart(
                bands = data.access.map { Band(it.start, it.end, colors.of(it.value, data.sims)) },
                from = window.from,
                to = window.to,
                until = minOf(data.now, window.to),
                unknown = colors.unknown,
                description = description,
                scrubAt = scrubAt,
                onScrub = onScrub,
                markers = data.switches,
            )
        } else {
            val starts = HistoryMath.barStarts(window, ZoneId.systemDefault())
            val bars = starts.mapIndexedNotNull { i, start ->
                if (start >= data.now) return@mapIndexedNotNull null
                val end = starts.getOrNull(i + 1) ?: window.to
                val within = HistoryMath.totals(HistoryMath.clip(data.access, start, end))
                StackedBar(start, end, order.mapNotNull { k -> within[k]?.let { colors.of(k, data.sims) to it.toFloat() / (end - start) } })
            }
            StackedBarChart(bars, window.from, window.to, colors.unknown, description, scrubAt, onScrub)
        }
        TimeAxis(ticks, window.from, window.to)
        Legend(totals.map { (k, ms) -> colors.of(k, data.sims) to res.getString(R.string.history_total, keyLabel(res, k, data.sims), duration(res, ms)) })
    }
}

@Composable
private fun VpnCard(data: HistoryData, colors: HistoryColors, ticks: List<Tick>, scrubAt: Long?, onScrub: (Long) -> Unit) {
    val res = LocalContext.current.resources
    val window = data.window
    val totals = HistoryMath.totals(data.vpn)
    val known = totals.values.sum()
    val share = if (known > 0) (totals[true] ?: 0L).toDouble() / known else null
    val shareText = share?.let { res.getString(R.string.history_vpn_share, percent(res, it)) }
    ChartCard(stringResource(R.string.history_vpn), trailing = shareText) {
        BandChart(
            bands = data.vpn.map { Band(it.start, it.end, if (it.value) colors.vpn else colors.vpnOff) },
            from = window.from,
            to = window.to,
            until = minOf(data.now, window.to),
            unknown = colors.unknown,
            description = res.getString(R.string.history_desc, res.getString(R.string.history_vpn), shareText.orEmpty()),
            scrubAt = scrubAt,
            onScrub = onScrub,
            height = 14.dp,
        )
        TimeAxis(ticks, window.from, window.to)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalCard(data: HistoryData, colors: HistoryColors, ticks: List<Tick>, scrubAt: Long?, onScrub: (Long) -> Unit) {
    val res = LocalContext.current.resources
    val window = data.window
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    ChartCard(stringResource(R.string.history_signal), trailing = stringResource(R.string.history_signal_hint)) {
        if (data.signal.isEmpty()) {
            Note(stringResource(R.string.history_nothing_recorded))
            return@ChartCard
        }
        val shown = data.signal.filter { it.source.key !in hidden }
        val lines = shown.map { s ->
            val color = colors.of(s.source, data.sims)
            if (window.range == HistoryRange.DAY) SignalLine(color, points = s.points) else SignalLine(color, buckets = s.buckets, bucketMs = s.bucketMs)
        }
        SignalChart(
            lines = lines,
            from = window.from,
            to = window.to,
            now = data.now,
            description = res.getString(
                R.string.history_desc,
                res.getString(R.string.history_signal),
                shown.joinToString(", ") { sourceLabel(res, it.source, data.sims) },
            ),
            scrubAt = scrubAt,
            onScrub = onScrub,
        )
        TimeAxis(ticks, window.from, window.to)
        if (data.signal.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                data.signal.forEach { s ->
                    val key = s.source.key
                    FilterChip(
                        selected = key !in hidden,
                        onClick = { hidden = if (key in hidden) hidden - key else hidden + key },
                        label = { Text(dot(colors.of(s.source, data.sims), sourceLabel(res, s.source, data.sims))) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkCard(data: HistoryData) {
    val res = LocalContext.current.resources
    val rows = data.networkTotals.filterValues { m -> m.values.sum() > 0 }
    if (rows.isEmpty()) return
    ChartCard(stringResource(R.string.history_network)) {
        rows.forEach { (subId, totals) ->
            val all = totals.values.sum().toDouble()
            val parts = totals.entries.sortedByDescending { it.value }
                .joinToString(" · ") { (kind, ms) -> res.getString(R.string.history_total, kindLabel(res, kind), percent(res, ms / all)) }
            Column {
                Text(simName(res, subId, data.sims), style = MaterialTheme.typography.bodyMedium)
                Text(parts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What held at the picked moment, across every chart. */
@Composable
private fun Readout(data: HistoryData, at: Long?) {
    val context = LocalContext.current
    val res = context.resources
    val text = if (at == null) {
        stringResource(R.string.history_readout_hint)
    } else {
        val parts = mutableListOf(formatMoment(context, at, data.window.range))
        val access = HistoryMath.valueAt(data.access, at)
        parts += if (access == null) res.getString(R.string.history_unknown) else keyLabel(res, access, data.sims)
        if (access is AccessKey.Sim) {
            data.network[access.subId]?.let { HistoryMath.valueAt(it, at) }?.let { parts += it.badge }
        }
        HistoryMath.valueAt(data.vpn, at)?.let { vpn ->
            parts += res.getString(if (vpn) R.string.history_readout_vpn_on else R.string.history_readout_vpn_off)
        }
        val number = NumberFormat.getNumberInstance(res.configuration.locales[0]).apply { maximumFractionDigits = 1 }
        data.signal.forEach { s ->
            val bars: Double? = if (s.bucketMs == 0L) {
                HistoryMath.signalAt(s.points, at)?.bars?.toDouble()
            } else {
                s.buckets.firstOrNull { at >= it.start && at < it.start + s.bucketMs }?.barsAvg
            }
            if (bars != null) parts += res.getString(R.string.history_signal_value, sourceLabel(res, s.source, data.sims), number.format(bars))
        }
        parts.joinToString(" · ")
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(items: List<Pair<Color, String>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (color, label) ->
            Text(dot(color, label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A coloured dot before [label], matching the chart's colour. */
private fun dot(color: Color, label: String) = buildAnnotatedString {
    withStyle(SpanStyle(color = color)) { append("● ") }
    append(label)
}

@Composable
private fun RetentionDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val choices = AppSettings.HISTORY_RETENTION_CHOICES.zip(
        listOf(R.string.history_keep_1_month, R.string.history_keep_3_months, R.string.history_keep_1_year),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_keep_title)) },
        text = {
            Column {
                choices.forEach { (days, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = days == current, onClick = { onPick(days) }, role = Role.RadioButton)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = days == current, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ---- colours and words ----

/** The History page's colours. SIMs use the colour Android gives them, like the SIM tiles. */
data class HistoryColors(
    val wifi: Color,
    val ethernet: Color,
    val other: Color,
    val none: Color,
    val unknown: Color,
    val vpn: Color,
    val vpnOff: Color,
) {
    fun of(key: AccessKey, sims: Map<Int, SimRecord>): Color = when (key) {
        AccessKey.Wifi -> wifi
        AccessKey.Ethernet -> ethernet
        AccessKey.Other -> other
        AccessKey.None -> none
        is AccessKey.Sim -> simColor(key.subId, sims)
    }

    fun of(source: SignalSource, sims: Map<Int, SimRecord>): Color = when (source) {
        SignalSource.Wifi -> wifi
        is SignalSource.Sim -> simColor(source.subId, sims)
    }

    private fun simColor(subId: Int, sims: Map<Int, SimRecord>): Color =
        sims[subId]?.iconTint?.takeIf { it != 0 }?.let { Color(it) } ?: SIM_FALLBACK[abs(subId) % SIM_FALLBACK.size]

    private companion object {
        val SIM_FALLBACK = listOf(Color(0xFF1A73E8), Color(0xFF12A4AF), Color(0xFF188038), Color(0xFF9334E6))
    }
}

@Composable
private fun historyColors(): HistoryColors {
    val scheme = MaterialTheme.colorScheme
    return HistoryColors(
        wifi = Color(0xFFE37400),
        ethernet = Color(0xFF8E24AA),
        other = Color(0xFF757575),
        none = scheme.error,
        unknown = scheme.surfaceVariant,
        vpn = scheme.tertiary,
        vpnOff = scheme.outlineVariant,
    )
}

private fun simName(res: Resources, subId: Int, sims: Map<Int, SimRecord>): String {
    val sim = sims[subId]
    return when {
        subId < 0 -> res.getString(R.string.access_mobile_data)
        !sim?.label.isNullOrBlank() -> sim!!.label!!
        sim != null && sim.slotIndex >= 0 -> Labels.simLabel(res, SimLabel.Slot(sim.slotIndex + 1))
        else -> Labels.simLabel(res, SimLabel.SubId(subId))
    }
}

private fun keyLabel(res: Resources, key: AccessKey, sims: Map<Int, SimRecord>): String = when (key) {
    AccessKey.Wifi -> res.getString(R.string.access_wifi)
    AccessKey.Ethernet -> res.getString(R.string.access_ethernet)
    AccessKey.Other -> res.getString(R.string.history_access_other)
    AccessKey.None -> res.getString(R.string.history_no_internet)
    is AccessKey.Sim -> simName(res, key.subId, sims)
}

private fun sourceLabel(res: Resources, source: SignalSource, sims: Map<Int, SimRecord>): String = when (source) {
    SignalSource.Wifi -> res.getString(R.string.access_wifi)
    is SignalSource.Sim -> simName(res, source.subId, sims)
}

private fun kindLabel(res: Resources, kind: NetworkKind?): String = kind?.badge ?: res.getString(R.string.status_no_service)

private fun percent(res: Resources, fraction: Double): String =
    NumberFormat.getPercentInstance(res.configuration.locales[0]).format(fraction)

/** "40 min", "3 h 20 min", "2 d 5 h". */
private fun duration(res: Resources, ms: Long): String {
    val (hours, minutes) = HistoryMath.hoursMinutes(ms)
    return when {
        hours == 0L -> res.getString(R.string.history_duration_minutes, minutes.toInt())
        hours < 24 -> res.getString(R.string.history_duration_hours, hours.toInt(), minutes.toInt())
        else -> res.getString(R.string.history_duration_days, (hours / 24).toInt(), (hours % 24).toInt())
    }
}

/** "Today", "Yesterday", "Tue, 18 Nov", "12 – 18 Nov", "November 2026", "Dec 2025 – Nov 2026". */
private fun rangeLabel(context: Context, window: HistoryWindow, offset: Int): String {
    val last = window.to - 1
    return when (window.range) {
        HistoryRange.DAY -> when (offset) {
            0 -> context.getString(R.string.history_today)
            1 -> context.getString(R.string.history_yesterday)
            else -> DateUtils.formatDateTime(context, window.from, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
        }
        HistoryRange.WEEK -> DateUtils.formatDateRange(context, window.from, last, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        HistoryRange.MONTH -> DateUtils.formatDateTime(
            context,
            window.from,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_SHOW_YEAR,
        )
        HistoryRange.YEAR -> DateUtils.formatDateRange(
            context,
            window.from,
            last,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
}

/** A moment, as precise as the range calls for. */
private fun formatMoment(context: Context, at: Long, range: HistoryRange): String = DateUtils.formatDateTime(
    context,
    at,
    when (range) {
        HistoryRange.DAY -> DateUtils.FORMAT_SHOW_TIME
        HistoryRange.WEEK -> DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
        HistoryRange.MONTH -> DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        HistoryRange.YEAR -> DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    },
)

/** Axis labels: every 6 hours for a day, each day for a week, weekly for a month, each month for a year. */
private fun ticks(window: HistoryWindow, zone: ZoneId, locale: Locale): List<Tick> {
    fun fmt(skeleton: String) = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    val start = Instant.ofEpochMilli(window.from).atZone(zone)
    return when (window.range) {
        HistoryRange.DAY -> fmt("j").let { f -> (0..3).map { k -> start.plusHours(6L * k).let { Tick(it.toInstant().toEpochMilli(), f.format(it)) } } }
        HistoryRange.WEEK -> fmt("EEE").let { f ->
            (0..6).map { k -> start.plusDays(k.toLong()).let { Tick(it.toInstant().toEpochMilli() + HistoryMath.DAY_MS / 2, f.format(it)) } }
        }
        HistoryRange.MONTH -> fmt("d").let { f ->
            listOf(0L, 7L, 14L, 21L, 28L).map { start.plusDays(it) }.filter { it.toInstant().toEpochMilli() < window.to }
                .map { Tick(it.toInstant().toEpochMilli(), f.format(it)) }
        }
        HistoryRange.YEAR -> fmt("MMMMM").let { f ->
            (0..11).map { k -> start.plusMonths(k.toLong()).let { Tick(it.toInstant().toEpochMilli() + 15 * HistoryMath.DAY_MS, f.format(it)) } }
        }
    }
}
