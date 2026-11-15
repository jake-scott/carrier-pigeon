// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the Wi-Fi tile shows. Plain data, so the screen can be previewed without a phone. */
data class WifiStatus(
    /** Wi-Fi radio switched on. */
    val enabled: Boolean,
    val airplaneMode: Boolean,
    val connected: Boolean,
    /** Network name; null when not connected or when Android hides it (no location access). */
    val ssid: String? = null,
    /** e.g. "WPA2", "WPA3"; null when unknown (Android 11 doesn't report it). */
    val security: String? = null,
    /** e.g. "Wi-Fi 6", "Wi-Fi 6E", "Wi-Fi 7". */
    val generation: String? = null,
    /** e.g. "5 GHz". */
    val band: String? = null,
    /** Current receive (download) link speed in Mbps. */
    val rxMbps: Int? = null,
    /** Current transmit (upload) link speed in Mbps. */
    val txMbps: Int? = null,
    /** Fastest the link could go with this access point. */
    val maxMbps: Int? = null,
    /** Signal strength in dBm. */
    val rssi: Int? = null,
) {
    /** Wi-Fi is off only because airplane mode switched it off. */
    val offForAirplane: Boolean get() = airplaneMode && !enabled
}

/**
 * Reads the Wi-Fi connection: name, security, generation and link speeds. The name (SSID) is
 * location-sensitive, so Android only reveals it with location access and Location switched on.
 */
class WifiReader(context: Context) {
    private val app = context.applicationContext
    private val wifiManager = app.getSystemService(WifiManager::class.java)

    fun hasLocationPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The system-wide Location switch. Even with permission, the SSID is hidden while it is off. */
    fun locationEnabled(): Boolean = app.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: false

    private fun airplaneMode(): Boolean =
        Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    /** A snapshot from the older WifiManager API, used before the first callback arrives. */
    @Suppress("DEPRECATION") // connectionInfo is deprecated but still works, and is all Android 11 has.
    fun current(): WifiStatus {
        val info = wifiManager?.connectionInfo?.takeIf { it.networkId != -1 }
        return snapshot(info)
    }

    /**
     * Live Wi-Fi status. Collect it again after location access is granted: the network callback
     * only gets the SSID if the permission was held when it was registered.
     */
    fun updates(): Flow<WifiStatus> = callbackFlow {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        var wifiNetwork: Network? = null
        var lastInfo: WifiInfo? = null
        fun push() {
            trySend(snapshot(if (wifiNetwork != null) lastInfo ?: legacyInfo() else null))
        }

        val handleCaps: (Network, NetworkCapabilities) -> Unit = { network, caps ->
            wifiNetwork = network
            lastInfo = caps.transportInfo as? WifiInfo
            push()
        }
        val handleLost: (Network) -> Unit = { network ->
            if (network == wifiNetwork) {
                wifiNetwork = null
                lastInfo = null
            }
            push()
        }
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = handleCaps(network, caps)
                override fun onLost(network: Network) = handleLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = handleCaps(network, caps)
                override fun onLost(network: Network) = handleLost(network)
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(request, callback)

        // Wi-Fi switched on or off, or airplane mode toggled, with no network change to tell us.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = push()
        }
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        trySend(current())
        awaitClose {
            cm.unregisterNetworkCallback(callback)
            app.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged()

    @Suppress("DEPRECATION")
    private fun legacyInfo(): WifiInfo? = wifiManager?.connectionInfo?.takeIf { it.networkId != -1 }

    private fun snapshot(info: WifiInfo?): WifiStatus {
        val enabled = wifiManager?.isWifiEnabled ?: false
        val airplane = airplaneMode()
        if (info == null || !enabled) return WifiStatus(enabled = enabled, airplaneMode = airplane, connected = false)
        return WifiStatus(
            enabled = true,
            airplaneMode = airplane,
            connected = true,
            ssid = ssidOf(info),
            security = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) securityName(info.currentSecurityType) else null,
            generation = generationName(
                info.wifiStandard,
                info.frequency,
                maxOf(info.maxSupportedRxLinkSpeedMbps, info.maxSupportedTxLinkSpeedMbps, info.linkSpeed),
            ),
            band = bandName(info.frequency),
            rxMbps = info.rxLinkSpeedMbps.takeIf { it > 0 },
            txMbps = info.txLinkSpeedMbps.takeIf { it > 0 },
            maxMbps = maxOf(info.maxSupportedRxLinkSpeedMbps, info.maxSupportedTxLinkSpeedMbps).takeIf { it > 0 },
            rssi = info.rssi.takeIf { it > -127 && it < 0 },
        )
    }

    companion object {
        /** Android wraps a readable SSID in quotes and uses "<unknown ssid>" when it hides it. */
        fun ssidOf(info: WifiInfo): String? {
            val raw = info.ssid ?: return null
            if (raw == WifiManager.UNKNOWN_SSID || raw.isBlank()) return null
            return raw.removeSurrounding("\"").takeIf { it.isNotBlank() }
        }

        fun securityName(type: Int): String? = when (type) {
            WifiInfo.SECURITY_TYPE_OPEN -> "Open"
            WifiInfo.SECURITY_TYPE_OWE -> "Enhanced Open"
            WifiInfo.SECURITY_TYPE_WEP -> "WEP"
            WifiInfo.SECURITY_TYPE_PSK -> "WPA2"
            WifiInfo.SECURITY_TYPE_SAE -> "WPA3"
            WifiInfo.SECURITY_TYPE_EAP -> "WPA2 Enterprise"
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3 Enterprise"
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "WPA3 Enterprise 192-bit"
            WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2, WifiInfo.SECURITY_TYPE_PASSPOINT_R3 -> "Passpoint"
            WifiInfo.SECURITY_TYPE_WAPI_PSK, WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI"
            WifiInfo.SECURITY_TYPE_OSEN -> "OSEN"
            WifiInfo.SECURITY_TYPE_DPP -> "DPP"
            else -> null
        }

        /**
         * Wi-Fi Alliance generation. Android lumps 802.11a/b/g together as "legacy", so tell them
         * apart by band and speed: 11a is the only one on 5 GHz, and 11b tops out at 11 Mbps.
         */
        fun generationName(standard: Int, frequencyMhz: Int, maxMbps: Int): String? = when (standard) {
            ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7"
            ScanResult.WIFI_STANDARD_11AX -> if (frequencyMhz >= 5925) "Wi-Fi 6E" else "Wi-Fi 6"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4"
            ScanResult.WIFI_STANDARD_11AD -> "WiGig"
            ScanResult.WIFI_STANDARD_LEGACY -> when {
                frequencyMhz >= 4900 -> "Wi-Fi 2"
                maxMbps in 1..11 -> "Wi-Fi 1"
                else -> "Wi-Fi 3"
            }
            else -> null
        }

        fun bandName(frequencyMhz: Int): String? = when (frequencyMhz) {
            in 2400..2500 -> "2.4 GHz"
            in 4900..5899 -> "5 GHz"
            in 5925..7125 -> "6 GHz"
            in 57000..71000 -> "60 GHz"
            else -> null
        }
    }
}
