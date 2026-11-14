// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager

/**
 * What to show for a SIM's mobile network.
 *
 * @property badge short label, e.g. "LTE", "5G NSA". Matches the status bar's idea of the network.
 * @property detail what is underneath the badge, e.g. "LTE + NR"; null when the badge says it all.
 */
data class NetworkLabel(val badge: String, val detail: String? = null) {

    companion object {
        /**
         * Combines the network the phone is registered on with the carrier's display override.
         *
         * In 5G NSA (non-standalone) the phone is registered on an LTE cell and NR is added on top,
         * so the raw data network type stays LTE while the status bar shows 5G. The status bar uses
         * [TelephonyDisplayInfo.getOverrideNetworkType]; we show both, so the label explains itself.
         *
         * NR_NSA can mean the LTE anchor only advertises 5G, not that NR is carrying data right now.
         * Android has no public, reliable way to tell those apart, so we don't try.
         *
         * @param rawType a TelephonyManager.NETWORK_TYPE_* value.
         * @param overrideType a TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_* value, or null if unknown.
         * @return null when the network type is unknown (e.g. mid-switch or no service).
         */
        @Suppress("DEPRECATION") // OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE: Android 11 sends it instead of NR_ADVANCED.
        fun of(rawType: Int, overrideType: Int?): NetworkLabel? {
            if (rawType == TelephonyManager.NETWORK_TYPE_NR) {
                return if (overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) {
                    NetworkLabel("5G+ SA", "NR standalone, high band")
                } else {
                    NetworkLabel("5G SA", "NR standalone")
                }
            }
            if (rawType == TelephonyManager.NETWORK_TYPE_LTE) {
                return when (overrideType) {
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA ->
                        NetworkLabel("LTE+", "LTE carrier aggregation")
                    // US carriers brand this "5G E"; it is LTE.
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO ->
                        NetworkLabel("LTE-A Pro", "LTE Advanced Pro, not 5G")
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ->
                        NetworkLabel("5G NSA", "LTE + NR")
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE,
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED ->
                        NetworkLabel("5G+ NSA", "LTE + NR, high band")
                    else -> NetworkLabel("LTE")
                }
            }
            return generation(rawType)?.let { NetworkLabel(it) }
        }

        private fun generation(rawType: Int): String? = when (rawType) {
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
    }
}
