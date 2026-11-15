// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.ui

import android.Manifest
import android.content.Intent
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
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import io.github.jakescott.carrierpigeon.data.AppSettings
import io.github.jakescott.carrierpigeon.data.DataSimRefresher
import io.github.jakescott.carrierpigeon.data.DataSimResolver
import io.github.jakescott.carrierpigeon.data.DataSimState
import io.github.jakescott.carrierpigeon.data.DataStatus
import io.github.jakescott.carrierpigeon.data.DisplayInfoTracker
import io.github.jakescott.carrierpigeon.data.StateStore
import io.github.jakescott.carrierpigeon.data.SwitchLog
import io.github.jakescott.carrierpigeon.data.WifiReader
import io.github.jakescott.carrierpigeon.data.WifiStatus
import io.github.jakescott.carrierpigeon.widget.CarrierPigeonWidget
import kotlinx.coroutines.launch

/** The app screen: a tile per active SIM (see [HomeScreen]), with the switch log in the menu. */
class MainActivity : ComponentActivity() {
    /** Bumped each time the widget opens us to ask for phone access. */
    private var accessRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takeAccessRequest(intent)
        setContent { AppTheme { MainScreen(accessRequests) } }
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
        const val EXTRA_REQUEST_PHONE_ACCESS = "io.github.jakescott.carrierpigeon.REQUEST_PHONE_ACCESS"
    }

    override fun onResume() {
        super.onResume()
        val app = applicationContext
        lifecycleScope.launch { DataSimRefresher.refresh(app, "app-opened", settle = false) }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
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
        if (resolver.hasPhoneStatePermission()) return@LaunchedEffect
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
    val wifiUpdates = remember(hasLocation) { wifiReader.updates() }
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
    val tiles = remember(sims, state, displayInfoVersion) { simTiles(sims, state, resolver) }

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

            override fun readLog(): List<String> = log.readLines().asReversed()

            override fun shareLog() {
                val text = log.readLines().joinToString("\n")
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Carrier Pigeon switch log")
                    .putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share log"))
            }

            override fun clearLog() = log.clear()
        }
    }

    HomeScreen(
        HomeState(
            sims = tiles,
            showDetail = showDetail,
            needsPhoneAccess = !hasPermission,
            notice = state?.takeIf { !it.hasActiveSub }?.let { "No SIM is carrying mobile data" },
            internet = internetHeadline(state, wifi),
            wifi = wifi,
            wifiCarriesInternet = state?.access == WIFI_ACCESS,
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
        title = { Text("Why location access?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Android treats the name of your Wi-Fi network as location information, so it only tells apps with location access which network you're on.")
                Text("Carrier Pigeon uses it only to show the Wi-Fi name. It never tracks, stores or shares where you are. Choose \"Precise\": Android hides the name from apps with only approximate location.")
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** What DataSimResolver reports as the access when the default network is Wi-Fi. */
private const val WIFI_ACCESS = "Wi-Fi"

/** Access labels for other non-cellular transports; anything else is a cellular network type. */
private val NON_CELLULAR_ACCESS = setOf("Ethernet", "Bluetooth", "USB")

/** True when the default network is the data SIM, i.e. [access] is a network type like "5G NSA". */
private fun isCellularAccess(access: String): Boolean =
    access != DataSimResolver.NO_DATA && access != WIFI_ACCESS && access != "Connected" && access !in NON_CELLULAR_ACCESS

/**
 * The headline at the top: "Internet via Wi-Fi" or "Internet via Cellular" (or Ethernet etc.),
 * from the transport of the default network, or "No internet".
 */
private fun internetHeadline(state: DataSimState?, wifi: WifiStatus?): InternetHeadline? {
    state ?: return null
    val vpn = if (state.vpn) " + VPN" else ""
    val airplane = state.status == DataStatus.AIRPLANE_MODE
    return when (state.access) {
        DataSimResolver.NO_DATA -> InternetHeadline(
            "No internet",
            subtitle = if (airplane) "Airplane mode is on" else null,
            airplaneMode = airplane,
        )
        WIFI_ACCESS -> InternetHeadline("Internet via Wi-Fi$vpn", subtitle = wifi?.ssid)
        in NON_CELLULAR_ACCESS -> InternetHeadline("Internet via ${state.access}$vpn")
        "Connected" -> InternetHeadline("Internet connected$vpn")
        // Anything else is the cellular network type, e.g. "5G NSA" or "LTE".
        else -> InternetHeadline("Internet via Cellular$vpn", subtitle = "${state.label} · ${state.access}")
    }
}

/** Shown before the system prompt, which words this permission as "make and manage phone calls". */
@Composable
private fun PhoneAccessRationale(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Why phone access?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Carrier Pigeon needs it to read your SIM names and which network each SIM is on (4G, 5G and so on), so the widget can show them.")
                Text("Android groups this with calls, so its prompt will say \"make and manage phone calls\". Carrier Pigeon never makes, answers or manages calls, and it doesn't read your call log or contacts.")
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/**
 * One tile per active SIM. Without phone access Android won't list the SIMs, so fall back to a
 * single tile for the data SIM built from what the widget knows.
 */
private fun simTiles(sims: List<SubscriptionInfo>, state: DataSimState?, resolver: DataSimResolver): List<SimTileModel> {
    val airplane = state?.status == DataStatus.AIRPLANE_MODE
    // The data SIM is solid green while it carries the internet, dotted while it waits behind
    // Wi-Fi (or Ethernet), and plain when it couldn't take over (airplane mode, no service, data off).
    val dataRole = when {
        state == null || state.cellularUnavailable -> TileRole.NONE
        isCellularAccess(state.access) -> TileRole.ACTIVE
        else -> TileRole.FALLBACK
    }
    if (sims.isEmpty()) {
        if (state == null || !state.hasActiveSub) return emptyList()
        return listOf(
            SimTileModel(
                subId = state.activeSubId,
                slotIndex = state.slotIndex,
                name = state.label,
                carrier = state.carrierName,
                isEmbedded = state.isEmbedded,
                networkBadge = state.networkType,
                networkDetail = state.networkDetail,
                isData = true,
                isDefaultData = !state.isTemporarySwitch,
                problem = state.status.text,
                internetVia = state.accessLine,
                airplaneMode = airplane,
                role = dataRole,
            ),
        )
    }
    return sims.sortedBy { it.simSlotIndex }.map { info ->
        val isData = info.subscriptionId == state?.activeSubId
        val network = resolver.networkLabel(info.subscriptionId)
        SimTileModel(
            subId = info.subscriptionId,
            slotIndex = info.simSlotIndex,
            name = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                ?: info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                ?: "SIM ${info.simSlotIndex + 1}",
            carrier = info.carrierName?.toString(),
            isEmbedded = info.isEmbedded,
            networkBadge = network?.badge,
            networkDetail = network?.detail,
            isData = isData,
            isDefaultData = info.subscriptionId == state?.defaultSubId,
            problem = if (isData) state?.status?.text else null,
            internetVia = if (isData) state?.accessLine else null,
            airplaneMode = airplane,
            role = if (isData) dataRole else TileRole.NONE,
        )
    }
}
