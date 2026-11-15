// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jakescott.carrierpigeon.data.WifiStatus

/** The Material "airplanemode_active" glyph, drawn here so we don't need the extended icon set. */
private val AirplaneVector: ImageVector = ImageVector.Builder(
    name = "AirplaneMode",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = addPathNodes(
        "M21,16v-2l-8,-5V3.5C13,2.67 12.33,2 11.5,2S10,2.67 10,3.5V9l-8,5v2l8,-2.5V19l-2,1.5V22l3.5,-1 3.5,1v-1.5L13,19v-5.5l8,2.5z",
    ),
    fill = SolidColor(Color.Black),
).build()

/** Marks a tile whose connection is down because airplane mode is on. */
@Composable
fun AirplaneModeIcon(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Icon(
        AirplaneVector,
        contentDescription = "Airplane mode is on",
        tint = MaterialTheme.colorScheme.error,
        modifier = modifier.size(size),
    )
}

/** How the phone reaches the internet right now, e.g. "Internet via Wi-Fi". */
data class InternetHeadline(
    /** e.g. "Internet via Cellular", or "No internet". */
    val title: String,
    /** e.g. the Wi-Fi name, or "Vodafone · 5G NSA". */
    val subtitle: String? = null,
    /** Show the airplane icon: there's no internet because airplane mode is on. */
    val airplaneMode: Boolean = false,
)

@Composable
fun InternetHeader(headline: InternetHeadline, onTerm: (Term) -> Unit) {
    Column(Modifier.padding(top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TermText(headline.title, onTerm, MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold))
            if (headline.airplaneMode) AirplaneModeIcon(size = 26.dp)
        }
        headline.subtitle?.let {
            TermText(it, onTerm, MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}

/**
 * The Wi-Fi connection: network name, security and Wi-Fi generation, plus link speeds when
 * [showDetail] is on. Says so when there is no Wi-Fi, with an airplane icon if that's why.
 */
@Composable
fun WifiTile(
    wifi: WifiStatus,
    carriesInternet: Boolean,
    showDetail: Boolean,
    ssidHint: SsidHint?,
    onAllowLocation: () -> Unit,
    onTerm: (Term) -> Unit,
    modifier: Modifier = Modifier,
) {
    val green = dataGreen()
    val role = if (carriesInternet && wifi.connected) TileRole.ACTIVE else TileRole.NONE
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = roleBorder(role),
        colors = CardDefaults.outlinedCardColors(containerColor = roleContainer(role)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Wi-Fi", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (wifi.offForAirplane) AirplaneModeIcon()
                if (carriesInternet && wifi.connected) Tag("Internet", green, Color.White)
            }

            if (!wifi.connected) {
                Text(
                    when {
                        wifi.offForAirplane -> "No Wi-Fi: off in airplane mode"
                        !wifi.enabled -> "No Wi-Fi: Wi-Fi is off"
                        else -> "No Wi-Fi connection"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                wifi.ssid ?: "Network name hidden",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (wifi.ssid == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
            if (wifi.ssid == null) {
                when (ssidHint) {
                    SsidHint.NEEDS_PERMISSION -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Android only shows the name to apps with location access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onAllowLocation) { Text("Allow") }
                    }
                    SsidHint.LOCATION_OFF -> Text(
                        "Turn on Location in quick settings to see the network name.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    null -> Unit
                }
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                wifi.generation?.let {
                    TermText(it, onTerm, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                }
                wifi.band?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            wifi.security?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Security:", style = MaterialTheme.typography.bodyMedium)
                    TermText(it, onTerm, MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                }
            }

            if (showDetail) {
                val speeds = listOfNotNull(
                    wifi.rxMbps?.let { "$it Mbps down" },
                    wifi.txMbps?.let { "$it Mbps up" },
                )
                if (speeds.isNotEmpty()) {
                    Text("Link speed: ${speeds.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                }
                val extra = listOfNotNull(
                    wifi.maxMbps?.let { "Max $it Mbps" },
                    wifi.rssi?.let { "Signal $it dBm" },
                )
                if (extra.isNotEmpty()) {
                    Text(
                        extra.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Why the Wi-Fi name is hidden, so the tile can say how to show it. */
enum class SsidHint { NEEDS_PERMISSION, LOCATION_OFF }
