// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.github.jakescott.carrierpigeon.data.DataSimRefresher
import io.github.jakescott.carrierpigeon.data.DataSimResolver
import io.github.jakescott.carrierpigeon.data.DataSimState
import io.github.jakescott.carrierpigeon.data.StateStore
import io.github.jakescott.carrierpigeon.data.SwitchLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Proof-of-concept screen: current data SIM, all active SIMs, and the spike log. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { MainScreen() } }
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
private fun MainScreen() {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember { StateStore(app) }
    val resolver = remember { DataSimResolver(app) }
    val log = remember { SwitchLog(app) }
    val state by store.state.collectAsState(initial = null)

    var hasPermission by remember { mutableStateOf(resolver.hasPhoneStatePermission()) }
    var logLines by remember { mutableStateOf(emptyList<String>()) }
    var logVersion by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        scope.launch { DataSimRefresher.refresh(app, "permission-result", settle = false) }
    }

    LaunchedEffect(state, logVersion) {
        logLines = withContext(Dispatchers.IO) { log.readLines().asReversed() }
    }
    val sims = remember(state, hasPermission) { resolver.activeSubscriptions() }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Carrier Pigeon", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
            }
            if (!hasPermission) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Allow phone access to show SIM names and carriers. Without it the widget can only say SIM 1 or SIM 2.")
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) }) {
                                Text("Allow")
                            }
                        }
                    }
                }
            }
            item { CurrentSimCard(state) }
            if (sims.isNotEmpty()) {
                item { Text("Active SIMs", style = MaterialTheme.typography.titleMedium) }
                items(sims, key = { it.subscriptionId }) { info ->
                    val marker = if (info.subscriptionId == state?.activeSubId) "  ← data" else ""
                    Text(
                        "#${info.subscriptionId}  slot ${info.simSlotIndex + 1}  ${info.displayName} (${info.carrierName})" +
                            (if (info.isEmbedded) "  eSIM" else "") + marker,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { DataSimRefresher.refresh(app, "manual", settle = false) } }) { Text("Refresh") }
                    OutlinedButton(onClick = {
                        val text = log.readLines().joinToString("\n")
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Carrier Pigeon switch log")
                            .putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, "Share log"))
                    }) { Text("Share log") }
                    OutlinedButton(onClick = { log.clear(); logVersion++ }) { Text("Clear") }
                }
            }
            item { Text("Switch log (newest first)", style = MaterialTheme.typography.titleMedium) }
            items(logLines) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = if (line.contains(" SWITCH ")) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun CurrentSimCard(state: DataSimState?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Mobile data is on", style = MaterialTheme.typography.labelLarge)
            Text(state?.label ?: "…", style = MaterialTheme.typography.headlineSmall)
            state?.status?.text?.let { Text("$it: no mobile data right now", color = MaterialTheme.colorScheme.error) }
            if (state != null) {
                Text("Active sub ${state.activeSubId} · default sub ${state.defaultSubId} · network sub ${state.networkSubId}")
                Text("Internet via: ${state.accessLine}")
                Text(if (state.isTemporarySwitch) "Backup SIM (not your default data SIM)" else "Your default data SIM")
                state.carrierName?.let { Text("Carrier: $it") }
                state.networkType?.let { Text("Network: $it") }
            }
        }
    }
}
