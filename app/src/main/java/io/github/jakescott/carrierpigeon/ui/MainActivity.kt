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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import io.github.jakescott.carrierpigeon.data.AppSettings
import io.github.jakescott.carrierpigeon.data.DataSimRefresher
import io.github.jakescott.carrierpigeon.data.DataSimResolver
import io.github.jakescott.carrierpigeon.data.DataSimState
import io.github.jakescott.carrierpigeon.data.DisplayInfoTracker
import io.github.jakescott.carrierpigeon.data.StateStore
import io.github.jakescott.carrierpigeon.data.SwitchLog
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
    // Ask on the first ever open, and whenever the widget sends us here to ask.
    LaunchedEffect(accessRequests) {
        if (resolver.hasPhoneStatePermission()) return@LaunchedEffect
        if (accessRequests > 0 || !settings.askedForPhoneAccess()) requestPhoneAccess()
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

            override fun allowPhoneAccess() = requestPhoneAccess()

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
        ),
        actions,
    )
}

/**
 * One tile per active SIM. Without phone access Android won't list the SIMs, so fall back to a
 * single tile for the data SIM built from what the widget knows.
 */
private fun simTiles(sims: List<SubscriptionInfo>, state: DataSimState?, resolver: DataSimResolver): List<SimTileModel> {
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
        )
    }
}
