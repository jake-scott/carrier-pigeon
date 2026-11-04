// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
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

    fun hasPhoneStatePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun resolve(networkSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID): DataSimState {
        val active = SubscriptionManager.getActiveDataSubscriptionId()
        val defaultSub = SubscriptionManager.getDefaultDataSubscriptionId()
        val info = subscriptionInfo(active)
        return DataSimState(
            activeSubId = active,
            defaultSubId = defaultSub,
            networkSubId = networkSubId,
            displayName = info?.displayName?.toString(),
            carrierName = info?.carrierName?.toString(),
            slotIndex = info?.simSlotIndex ?: -1,
            isEmbedded = info?.isEmbedded ?: false,
            iconTint = info?.iconTint ?: 0,
            networkType = networkType(active),
            status = status(active),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    /**
     * Android keeps reporting a data SIM in airplane mode or with mobile data off, so check separately
     * whether data can actually flow. Checks that need READ_PHONE_STATE are skipped without it.
     */
    private fun status(subId: Int): DataStatus {
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) {
            return DataStatus.AIRPLANE_MODE
        }
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID || !hasPhoneStatePermission()) return DataStatus.OK
        val tm = telephonyManager.createForSubscriptionId(subId)
        try {
            if (!tm.isDataEnabled) return DataStatus.DATA_OFF
        } catch (e: SecurityException) {
            // Fall through: unknown is treated as on.
        }
        try {
            val state = tm.serviceState?.state
            if (state == ServiceState.STATE_OUT_OF_SERVICE || state == ServiceState.STATE_POWER_OFF) {
                return DataStatus.NO_SERVICE
            }
        } catch (e: SecurityException) {
            // Some builds also want location for getServiceState(); skip the check.
        }
        return DataStatus.OK
    }

    /** All active SIMs, for the app screen. Empty without READ_PHONE_STATE. */
    fun activeSubscriptions(): List<SubscriptionInfo> {
        if (!hasPhoneStatePermission()) return emptyList()
        return try {
            subscriptionManager.activeSubscriptionInfoList.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "activeSubscriptionInfoList denied", e)
            emptyList()
        }
    }

    private fun subscriptionInfo(subId: Int): SubscriptionInfo? {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID || !hasPhoneStatePermission()) return null
        return try {
            subscriptionManager.getActiveSubscriptionInfo(subId)
        } catch (e: SecurityException) {
            Log.w(TAG, "getActiveSubscriptionInfo denied", e)
            null
        }
    }

    private fun networkType(subId: Int): String? {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID || !hasPhoneStatePermission()) return null
        return try {
            when (telephonyManager.createForSubscriptionId(subId).dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi"
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_TD_SCDMA,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD -> "3G"
                TelephonyManager.NETWORK_TYPE_GSM,
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
                // Unknown, or mid-switch: show nothing rather than a misleading generation.
                else -> null
            }
        } catch (e: SecurityException) {
            null
        }
    }

    companion object {
        private const val TAG = "DataSimResolver"

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
