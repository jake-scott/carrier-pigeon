// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.TelephonyNetworkSpecifier
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
            updatedAtMillis = System.currentTimeMillis(),
        )
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
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> null
                else -> "2G"
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
