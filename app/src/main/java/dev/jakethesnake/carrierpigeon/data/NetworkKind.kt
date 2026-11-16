// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager

/**
 * A SIM's mobile network, as the status bar would show it. Stored by name, so it holds no
 * translated text: the UI turns it into words (see ui/Labels.kt).
 *
 * @property badge short label, e.g. "LTE", "5G NSA". Technical names, the same in every language,
 *   because they match what the status bar shows.
 */
enum class NetworkKind(val badge: String) {
    /** NR standalone on high band. */
    NR_SA_HIGH_BAND("5G+ SA"),

    /** NR standalone. */
    NR_SA("5G SA"),

    /** LTE + NR on high band. */
    NR_NSA_HIGH_BAND("5G+ NSA"),

    /** LTE + NR. */
    NR_NSA("5G NSA"),

    /** LTE Advanced Pro. US carriers brand it "5G E"; it is LTE. */
    LTE_A_PRO("LTE-A Pro"),

    /** LTE carrier aggregation. */
    LTE_CA("LTE+"),
    LTE("LTE"),
    GEN_3G("3G"),
    GEN_2G("2G"),
    ;

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
        fun of(rawType: Int, overrideType: Int?): NetworkKind? {
            if (rawType == TelephonyManager.NETWORK_TYPE_NR) {
                return if (overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) NR_SA_HIGH_BAND else NR_SA
            }
            if (rawType == TelephonyManager.NETWORK_TYPE_LTE) {
                return when (overrideType) {
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> LTE_CA
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> LTE_A_PRO
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> NR_NSA
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE,
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> NR_NSA_HIGH_BAND
                    else -> LTE
                }
            }
            return generation(rawType)
        }

        // The CDMA, EVDO, eHRPD, 1xRTT and iDEN constants are deprecated, but phones can still report them.
        @Suppress("DEPRECATION")
        private fun generation(rawType: Int): NetworkKind? = when (rawType) {
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD -> GEN_3G
            TelephonyManager.NETWORK_TYPE_GSM,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> GEN_2G
            // Unknown, or mid-switch: show nothing rather than a misleading generation.
            else -> null
        }
    }
}
