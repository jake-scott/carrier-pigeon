// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import android.telephony.SubscriptionManager

/** Whether mobile data can actually flow on the data SIM right now. Shown via ui/Labels.kt. */
enum class DataStatus {
    OK,
    AIRPLANE_MODE,
    DATA_OFF,
    NO_SERVICE,
}

/** How the device is reaching the internet: the transport of the default network. */
enum class Access {
    WIFI,

    /** The data SIM; [DataSimState.networkKind] says which network, when known. */
    CELLULAR,
    ETHERNET,
    BLUETOOTH,
    USB,

    /** A network with none of the transports above. */
    OTHER,

    /** No internet. */
    NONE,
}

/**
 * The best short name for the data SIM. SIM and carrier names come from the phone and are shown as
 * they are; the other cases are worded by the UI (see ui/Labels.kt).
 */
sealed interface SimLabel {
    data class Named(val name: String) : SimLabel

    /** 1-based slot number, e.g. "SIM 2". */
    data class Slot(val number: Int) : SimLabel

    /** Only the subscription id is known, e.g. "SIM #3". */
    data class SubId(val id: Int) : SimLabel

    data object NoDataSim : SimLabel
}

/** Snapshot of which SIM is carrying mobile data. Holds no display text, so it suits every language. */
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
    /** The data SIM's network, e.g. 5G NSA; null if unknown. */
    val networkKind: NetworkKind?,
    val status: DataStatus,
    /** How the device is reaching the internet right now. */
    val access: Access,
    /** True when the default route goes over a VPN. */
    val vpn: Boolean,
    /**
     * False when READ_PHONE_STATE isn't granted, so SIM names may be missing. Always true on a device
     * without telephony, since there is nothing to ask for.
     */
    val phoneAccess: Boolean,
    val updatedAtMillis: Long,
    /** False on a device that can't hold a SIM (e.g. a Wi-Fi-only tablet): there is no data SIM to show. */
    val hasTelephony: Boolean = true,
) {
    val hasActiveSub: Boolean get() = activeSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID

    /** True when data has moved away from the default data SIM (auto switch, data during calls). */
    val isTemporarySwitch: Boolean
        get() = hasActiveSub &&
            defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
            activeSubId != defaultSubId

    /** True when data is going over the data SIM and its network is known, so [networkKind] describes the access in use. */
    val accessIsCellular: Boolean get() = access == Access.CELLULAR && networkKind != null

    /** True when the data SIM can't carry data, so the carrier name would mislead. */
    val cellularUnavailable: Boolean get() = status != DataStatus.OK

    /** Best short label for the data SIM, regardless of whether it can carry data. */
    val simLabel: SimLabel
        get() {
            val name = displayName
            return when {
                !hasActiveSub -> SimLabel.NoDataSim
                !name.isNullOrBlank() -> SimLabel.Named(name)
                !carrierName.isNullOrBlank() -> SimLabel.Named(carrierName)
                slotIndex >= 0 -> SimLabel.Slot(slotIndex + 1)
                else -> SimLabel.SubId(activeSubId)
            }
        }
}
