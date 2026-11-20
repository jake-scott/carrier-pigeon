// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.ui

import android.Manifest
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.jakethesnake.carrierpigeon.R
import dev.jakethesnake.carrierpigeon.data.Access
import dev.jakethesnake.carrierpigeon.data.AppSettings
import dev.jakethesnake.carrierpigeon.data.CellSignalReader
import dev.jakethesnake.carrierpigeon.data.DataSimRefresher
import dev.jakethesnake.carrierpigeon.data.DataSimResolver
import dev.jakethesnake.carrierpigeon.data.DataSimState
import dev.jakethesnake.carrierpigeon.data.DataStatus
import dev.jakethesnake.carrierpigeon.data.DeviceFeatures
import dev.jakethesnake.carrierpigeon.data.SimLabel
import dev.jakethesnake.carrierpigeon.data.DisplayInfoTracker
import dev.jakethesnake.carrierpigeon.data.StateStore
import dev.jakethesnake.carrierpigeon.data.SwitchLog
import dev.jakethesnake.carrierpigeon.data.WifiReader
import dev.jakethesnake.carrierpigeon.data.WifiStatus
import dev.jakethesnake.carrierpigeon.widget.CarrierPigeonWidget
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** The app screen: a tile per active SIM (see [HomeScreen]), with history and the switch log in the menu. */
class MainActivity : ComponentActivity() {
    /** Bumped each time the widget opens us to ask for phone access. */
    private var accessRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takeAccessRequest(intent)
        redrawWidgetIfLanguageChanged()
        setContent { AppTheme { MainScreen(accessRequests) } }
    }

    /**
     * The widget words its text when it draws. A change of the system language also reaches
     * LocaleChangedReceiver, but a per-app language change (Android 13+) only recreates this
     * activity, so check here too.
     */
    private fun redrawWidgetIfLanguageChanged() {
        val app = applicationContext
        val tags = resources.configuration.locales.toLanguageTags()
        lifecycleScope.launch {
            val settings = AppSettings(app)
            if (settings.widgetLanguage() != tags) {
                settings.setWidgetLanguage(tags)
                CarrierPigeonWidget().updateAll(app)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeAccessRequest(intent)
    }

    private fun takeAccessRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_PHONE_ACCESS, false) == true) {
            intent.removeExtra(EXTRA_REQUEST_PHONE_ACCESS)
            accessRequests++
        }
    }

    companion object {
        /** Set by the widget when phone access is missing: ask for it as soon as the screen opens. */
        const val EXTRA_REQUEST_PHONE_ACCESS = "dev.jakethesnake.carrierpigeon.REQUEST_PHONE_ACCESS"
    }

    override fun onResume() {
        super.onResume()
        val app = applicationContext
        lifecycleScope.launch { DataSimRefresher.refresh(app, "app-opened", settle = false) }
    }
}

/** The app's Material theme, shared with the History page. */
@Composable
internal fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun MainScreen(accessRequests: Int) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember { StateStore(app) }
    val resolver = remember { DataSimResolver(app) }
    val log = remember { SwitchLog(app) }
    val state by store.state.collectAsState(initial = null)

    val settings = remember { AppSettings(app) }
    // A Wi-Fi-only tablet or a Chromebook can install the app too: no SIMs to show, nothing to ask for.
    val hasTelephony = resolver.hasTelephony
    val hasWifi = remember { DeviceFeatures.hasWifi(app) }
    var hasPermission by remember { mutableStateOf(resolver.hasPhoneStatePermission()) }
    // Access can also be granted or revoked in Settings while we're in the background.
    LaunchedEffect(state) { hasPermission = resolver.hasPhoneStatePermission() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        scope.launch { DataSimRefresher.refresh(app, "permission-result", settle = false) }
    }
    val activity = context as ComponentActivity
    /**
     * Shows the system prompt. After "don't ask again" Android silently refuses, so once we have
     * asked before and it won't show a rationale either, open the app's settings page instead.
     */
    val requestPhoneAccess: () -> Unit = {
        scope.launch {
            val blocked = settings.askedForPhoneAccess() &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE)
            if (blocked) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            } else {
                settings.setAskedForPhoneAccess()
                permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            }
        }
    }
    // Android labels this permission "make and manage phone calls", so explain what it is really
    // for before every prompt. The dialog's Continue button is what shows the system prompt.
    var showRationale by rememberSaveable { mutableStateOf(false) }
    // Ask on the first ever open, and whenever the widget sends us here to ask.
    LaunchedEffect(accessRequests) {
        if (!hasTelephony || resolver.hasPhoneStatePermission()) return@LaunchedEffect
        if (accessRequests > 0 || !settings.askedForPhoneAccess()) showRationale = true
    }

    // Location access, only so Android will tell us the Wi-Fi network's name. Asked for only when
    // the user taps Allow on the Wi-Fi tile, never on open.
    val wifiReader = remember { WifiReader(app) }
    var hasLocation by remember { mutableStateOf(wifiReader.hasLocationPermission()) }
    LaunchedEffect(state) { hasLocation = wifiReader.hasLocationPermission() }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }
    val requestLocationAccess: () -> Unit = {
        scope.launch {
            val blocked = settings.askedForLocationAccess() &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            if (blocked) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            } else {
                settings.setAskedForLocationAccess()
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }
    var showLocationRationale by rememberSaveable { mutableStateOf(false) }
    // Registered again once location is granted, so the callback can include the network name.
    // No Wi-Fi radio: no tile, so don't listen.
    val wifiUpdates = remember(hasLocation) { if (hasWifi) wifiReader.updates() else emptyFlow<WifiStatus>() }
    val wifi by wifiUpdates.collectAsState(initial = null)
    val ssidHint = wifi?.takeIf { it.connected && it.ssid == null }?.let {
        when {
            !hasLocation -> SsidHint.NEEDS_PERMISSION
            !wifiReader.locationEnabled() -> SsidHint.LOCATION_OFF
            else -> null
        }
    }

    val sims = remember(state, hasPermission) { resolver.activeSubscriptions() }
    // Redraw the tiles when any SIM's 5G NSA / LTE+ override changes, not only the data SIM's.
    val displayInfoVersion by DisplayInfoTracker.version.collectAsState()
    val showDetail by settings.showNetworkDetail.collectAsState(initial = false)
    val baseTiles = remember(sims, state, displayInfoVersion) { simTiles(sims, state, resolver, context.resources) }
    // Live signal bars for each tile's SIM. Listens only while the screen is showing, and again
    // whenever the set of SIMs changes.
    val signalReader = remember { CellSignalReader(app) }
    val subIds = remember(baseTiles) { baseTiles.map { it.subId }.toSet() }
    val signalUpdates = remember(subIds) { signalReader.updates(subIds) }
    val signals by signalUpdates.collectAsStateWithLifecycle(initialValue = emptyMap())
    val tiles = remember(baseTiles, signals) { baseTiles.map { it.copy(signal = signals[it.subId]) } }

    val actions = remember {
        object : HomeActions {
            override fun setShowDetail(show: Boolean) {
                scope.launch {
                    settings.setShowNetworkDetail(show)
                    CarrierPigeonWidget().updateAll(app)
                }
            }

            override fun allowPhoneAccess() {
                showRationale = true
            }

            override fun allowLocationAccess() {
                showLocationRationale = true
            }

            override fun refresh() {
                scope.launch { DataSimRefresher.refresh(app, "manual", settle = false) }
            }

            override fun openLanguageSettings() {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
                context.startActivity(
                    Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }

            override fun openHistory() {
                context.startActivity(Intent(context, HistoryActivity::class.java))
            }

            override fun readLog(): List<String> = log.readLines().asReversed()

            override fun shareLog() {
                val text = log.readLines().joinToString("\n")
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_share_subject))
                    .putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, context.getString(R.string.log_share_chooser)))
            }

            override fun clearLog() = log.clear()
        }
    }

    HomeScreen(
        HomeState(
            sims = tiles,
            showDetail = showDetail,
            needsPhoneAccess = hasTelephony && !hasPermission,
            notice = state?.takeIf { hasTelephony && !it.hasActiveSub }?.let { stringResource(R.string.home_no_data_sim) },
            hasTelephony = hasTelephony,
            canPickLanguage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            internet = state?.let { Labels.internetHeadline(context.resources, it, wifi?.ssid) },
            wifi = wifi,
            wifiCarriesInternet = state?.access == Access.WIFI,
            ssidHint = ssidHint,
        ),
        actions,
    )
    if (showRationale) {
        PhoneAccessRationale(
            onContinue = { showRationale = false; requestPhoneAccess() },
            onDismiss = { showRationale = false },
        )
    }
    if (showLocationRationale) {
        LocationAccessRationale(
            onContinue = { showLocationRationale = false; requestLocationAccess() },
            onDismiss = { showLocationRationale = false },
        )
    }
}

/** Shown before the system location prompt, which doesn't say why a SIM app wants location. */
@Composable
private fun LocationAccessRationale(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_rationale_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.location_rationale_body))
                Text(stringResource(R.string.location_rationale_precise))
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.action_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) } },
    )
}

/** Shown before the system prompt, which words this permission as "make and manage phone calls". */
@Composable
private fun PhoneAccessRationale(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.phone_rationale_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.phone_rationale_body))
                Text(stringResource(R.string.phone_rationale_calls))
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.action_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) } },
    )
}

/**
 * One tile per active SIM. Without phone access Android won't list the SIMs, so fall back to a
 * single tile for the data SIM built from what the widget knows.
 */
private fun simTiles(
    sims: List<SubscriptionInfo>,
    state: DataSimState?,
    resolver: DataSimResolver,
    res: Resources,
): List<SimTileModel> {
    val airplane = state?.status == DataStatus.AIRPLANE_MODE
    // The data SIM is solid green while it carries the internet, dotted while it waits behind
    // Wi-Fi (or Ethernet), and plain when it couldn't take over (airplane mode, no service, data off).
    val dataRole = when {
        state == null || state.cellularUnavailable -> TileRole.NONE
        state.access == Access.CELLULAR -> TileRole.ACTIVE
        else -> TileRole.FALLBACK
    }
    if (sims.isEmpty()) {
        if (state == null || !state.hasActiveSub) return emptyList()
        return listOf(
            SimTileModel(
                subId = state.activeSubId,
                slotIndex = state.slotIndex,
                name = Labels.simLabel(res, state.simLabel),
                carrier = state.carrierName,
                isEmbedded = state.isEmbedded,
                networkBadge = state.networkKind?.badge,
                networkDetail = Labels.networkDetail(res, state.networkKind),
                isData = true,
                isDefaultData = !state.isTemporarySwitch,
                problem = Labels.problem(res, state.status),
                internetVia = Labels.accessLine(res, state),
                airplaneMode = airplane,
                role = dataRole,
            ),
        )
    }
    return sims.sortedBy { it.simSlotIndex }.map { info ->
        val isData = info.subscriptionId == state?.activeSubId
        val network = resolver.networkKind(info.subscriptionId)
        SimTileModel(
            subId = info.subscriptionId,
            slotIndex = info.simSlotIndex,
            name = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                ?: info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                ?: Labels.simLabel(res, SimLabel.Slot(info.simSlotIndex + 1)),
            carrier = info.carrierName?.toString(),
            isEmbedded = info.isEmbedded,
            networkBadge = network?.badge,
            networkDetail = Labels.networkDetail(res, network),
            isData = isData,
            isDefaultData = info.subscriptionId == state?.defaultSubId,
            problem = if (isData) Labels.problem(res, state.status) else null,
            internetVia = if (isData) Labels.accessLine(res, state) else null,
            airplaneMode = airplane,
            role = if (isData) dataRole else TileRole.NONE,
        )
    }
}
