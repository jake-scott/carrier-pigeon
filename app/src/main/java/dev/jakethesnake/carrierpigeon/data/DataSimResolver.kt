// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TelephonyNetworkSpecifier
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

/** Reads the current data SIM from the platform. Never throws. */
// Every call that needs READ_PHONE_STATE is guarded by hasPhoneStatePermission() and a SecurityException catch.
@SuppressLint("MissingPermission")
class DataSimResolver(private val context: Context) {

    private val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)

    /** False on a device that can't hold a SIM; every telephony call below is skipped then. */
    val hasTelephony: Boolean = DeviceFeatures.hasTelephony(context)

    fun hasPhoneStatePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun resolve(networkSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID): DataSimState {
        if (!hasTelephony) return resolveWithoutTelephony()
        val active = SubscriptionManager.getActiveDataSubscriptionId()
        val defaultSub = SubscriptionManager.getDefaultDataSubscriptionId()
        val info = subscriptionInfo(active)
        val network = networkKind(active)
        val (access, vpn) = access()
        return DataSimState(
            activeSubId = active,
            defaultSubId = defaultSub,
            networkSubId = networkSubId,
            displayName = info?.displayName?.toString(),
            carrierName = info?.carrierName?.toString() ?: simOperatorName(active),
            slotIndex = info?.simSlotIndex ?: slotIndex(active),
            isEmbedded = info?.isEmbedded ?: false,
            iconTint = info?.iconTint ?: 0,
            networkKind = network,
            status = status(active),
            access = access,
            vpn = vpn,
            phoneAccess = hasPhoneStatePermission(),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    /** A device with no SIM support: only how it reaches the internet, and airplane mode. */
    private fun resolveWithoutTelephony(): DataSimState {
        val none = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val (access, vpn) = access()
        return DataSimState(
            activeSubId = none,
            defaultSubId = none,
            networkSubId = none,
            displayName = null,
            carrierName = null,
            slotIndex = -1,
            isEmbedded = false,
            iconTint = 0,
            networkKind = null,
            status = if (airplaneMode()) DataStatus.AIRPLANE_MODE else DataStatus.OK,
            access = access,
            vpn = vpn,
            phoneAccess = true,
            updatedAtMillis = System.currentTimeMillis(),
            hasTelephony = false,
        )
    }

    private fun airplaneMode(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    /**
     * How the device is reaching the internet: the transport of the default network, looking through
     * a VPN to the network it runs over. Needs only ACCESS_NETWORK_STATE.
     */
    private fun access(): Pair<Access, Boolean> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return Access.NONE to false
        val caps = cm.getNetworkCapabilities(network) ?: return Access.NONE to false
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        // A VPN's capabilities normally carry its underlying transports too; if not, find the
        // physical network it is likely running over.
        // A VPN with no physical network under it (e.g. airplane mode with Wi-Fi off) is not connectivity.
        val physical = (if (vpn && !caps.hasPhysicalTransport()) underlyingCapabilities(cm) else caps)
            ?: return Access.NONE to false
        val label = when {
            physical.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Access.WIFI
            physical.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Access.ETHERNET
            physical.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Access.CELLULAR
            physical.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> Access.BLUETOOTH
            physical.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> Access.USB
            else -> Access.OTHER
        }
        return label to vpn
    }

    private fun NetworkCapabilities.hasPhysicalTransport() =
        PHYSICAL_TRANSPORTS.any { hasTransport(it) }

    @Suppress("DEPRECATION") // allNetworks is the only public way to list networks without a callback.
    private fun underlyingCapabilities(cm: ConnectivityManager): NetworkCapabilities? {
        val candidates = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.filter {
            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        return candidates.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?: candidates.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
            ?: candidates.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
            ?: candidates.firstOrNull()
    }

    /**
     * Android keeps reporting a data SIM in airplane mode or with mobile data off, so check separately
     * whether data can actually flow. Checks that need READ_PHONE_STATE are skipped without it.
     */
    private fun status(subId: Int): DataStatus {
        if (airplaneMode()) return DataStatus.AIRPLANE_MODE
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID || !hasPhoneStatePermission()) return DataStatus.OK
        val tm = telephonyManager.createForSubscriptionId(subId)
        try {
            if (!tm.isDataEnabled) return DataStatus.DATA_OFF
        } catch (e: RuntimeException) {
            // SecurityException, or UnsupportedOperationException on a device without the feature.
            // Fall through: unknown is treated as on.
        }
        try {
            val state = tm.serviceState?.state
            if (state == ServiceState.STATE_OUT_OF_SERVICE || state == ServiceState.STATE_POWER_OFF) {
                return DataStatus.NO_SERVICE
            }
        } catch (e: RuntimeException) {
            // Some builds also want location for getServiceState(); skip the check.
        }
        return DataStatus.OK
    }

    /**
     * Carrier name from the SIM itself. Needs no permission, so the widget still names the carrier
     * when READ_PHONE_STATE hasn't been granted (e.g. a freshly installed debug build).
     */
    private fun simOperatorName(subId: Int): String? {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null
        return try {
            telephonyManager.createForSubscriptionId(subId).simOperatorName?.takeIf { it.isNotBlank() }
        } catch (e: RuntimeException) {
            null
        }
    }

    /** SIM slot for [subId]; needs no permission. -1 if unknown. */
    private fun slotIndex(subId: Int): Int {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return -1
        return try {
            SubscriptionManager.getSlotIndex(subId)
        } catch (e: RuntimeException) {
            -1
        }
    }

    /** All active SIMs, for the app screen. Empty without READ_PHONE_STATE or without telephony. */
    fun activeSubscriptions(): List<SubscriptionInfo> {
        if (!hasTelephony || !hasPhoneStatePermission()) return emptyList()
        return try {
            subscriptionManager?.activeSubscriptionInfoList.orEmpty()
        } catch (e: RuntimeException) {
            Log.w(TAG, "activeSubscriptionInfoList denied", e)
            emptyList()
        }
    }

    private fun subscriptionInfo(subId: Int): SubscriptionInfo? {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID || !hasPhoneStatePermission()) return null
        return try {
            subscriptionManager?.getActiveSubscriptionInfo(subId)
        } catch (e: RuntimeException) {
            Log.w(TAG, "getActiveSubscriptionInfo denied", e)
            null
        }
    }

    /**
     * The SIM's network as the status bar would show it, e.g. 5G NSA. Combines the raw data network
     * type with the carrier's display override (see [NetworkKind.of]).
     */
    fun networkKind(subId: Int): NetworkKind? {
        if (!hasTelephony || subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null
        val display = DisplayInfoTracker.displayInfo(subId)
        val raw = display?.networkType?.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
            ?: rawNetworkType(subId)
        return NetworkKind.of(raw, display?.overrideNetworkType)
    }

    private fun rawNetworkType(subId: Int): Int {
        if (!hasPhoneStatePermission()) return TelephonyManager.NETWORK_TYPE_UNKNOWN
        return try {
            telephonyManager.createForSubscriptionId(subId).dataNetworkType
        } catch (e: RuntimeException) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
    }

    companion object {
        private const val TAG = "DataSimResolver"
        private val PHYSICAL_TRANSPORTS = intArrayOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_BLUETOOTH,
            NetworkCapabilities.TRANSPORT_USB,
        )

        /** Sub id behind a cellular [Network], used to cross-check the active-sub API during the spike. */
        fun subIdForNetwork(context: Context, network: Network?): Int {
            if (network == null) return SubscriptionManager.INVALID_SUBSCRIPTION_ID
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val specifier = cm.getNetworkCapabilities(network)?.networkSpecifier
            return (specifier as? TelephonyNetworkSpecifier)?.subscriptionId
                ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
    }
}
