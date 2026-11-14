// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

import android.telephony.SubscriptionManager

/** Whether mobile data can actually flow on the data SIM right now. */
enum class DataStatus(val text: String?) {
    OK(null),
    AIRPLANE_MODE("Airplane mode"),
    DATA_OFF("Mobile data off"),
    NO_SERVICE("No service"),
}

/** Snapshot of which SIM is carrying mobile data. */
data class DataSimState(
    /** Sub currently providing cellular internet (follows automatic switching). */
    val activeSubId: Int,
    /** Data SIM chosen in Settings. Differs from [activeSubId] during a temporary switch. */
    val defaultSubId: Int,
    /** Sub behind the most recent cellular network the system told us about, if known. */
    val networkSubId: Int,
    /** User-visible SIM name, or null if READ_PHONE_STATE is not granted. */
    val displayName: String?,
    val carrierName: String?,
    /** 0-based SIM slot, or -1 if unknown. */
    val slotIndex: Int,
    val isEmbedded: Boolean,
    /** ARGB colour Android assigns to this SIM, or 0 if unknown. */
    val iconTint: Int,
    /** e.g. "5G NSA", "LTE+", "LTE"; null if unknown. See [NetworkLabel]. */
    val networkType: String?,
    /** What is underneath [networkType], e.g. "LTE + NR" for 5G NSA; null when there is nothing to add. */
    val networkDetail: String?,
    val status: DataStatus,
    /** How the device is reaching the internet right now: "Wi-Fi", "5G NSA", "LTE", "No data", ... */
    val access: String,
    /** True when the default route goes over a VPN. */
    val vpn: Boolean,
    val updatedAtMillis: Long,
) {
    val hasActiveSub: Boolean get() = activeSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID

    /** True when data has moved away from the default data SIM (auto switch, data during calls). */
    val isTemporarySwitch: Boolean
        get() = hasActiveSub &&
            defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
            activeSubId != defaultSubId

    /** Bottom line of the widget, e.g. "Wi-Fi + VPN". */
    val accessLine: String get() = if (vpn) "$access + VPN" else access

    /** True when data is going over the data SIM, so [networkDetail] describes the [access] in use. */
    val accessIsCellular: Boolean get() = networkType != null && access == networkType

    /** Best short label for the widget. */
    val label: String
        get() {
            val name = displayName
            return when {
                !hasActiveSub -> "No data SIM"
                !name.isNullOrBlank() -> name
                !carrierName.isNullOrBlank() -> carrierName
                slotIndex >= 0 -> "SIM ${slotIndex + 1}"
                else -> "SIM #$activeSubId"
            }
        }
}
