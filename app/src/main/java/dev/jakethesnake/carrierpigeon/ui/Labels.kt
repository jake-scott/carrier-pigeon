// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import dev.jakethesnake.carrierpigeon.R
import dev.jakethesnake.carrierpigeon.data.Access
import dev.jakethesnake.carrierpigeon.data.DataSimState
import dev.jakethesnake.carrierpigeon.data.DataStatus
import dev.jakethesnake.carrierpigeon.data.NetworkKind
import dev.jakethesnake.carrierpigeon.data.SimLabel
import dev.jakethesnake.carrierpigeon.data.WifiBand
import dev.jakethesnake.carrierpigeon.data.WifiSecurity
import java.text.NumberFormat

/**
 * Turns stored state into words in the current language. Shared by the app screen and the widget,
 * so both say the same thing.
 */
object Labels {
    /** What is underneath the badge, e.g. "LTE + NR" for 5G NSA; null when the badge says it all. */
    fun networkDetail(res: Resources, kind: NetworkKind?): RichText? = detailRes(kind)?.let { res.richString(it) }

    @StringRes
    private fun detailRes(kind: NetworkKind?): Int? = when (kind) {
        NetworkKind.NR_SA_HIGH_BAND -> R.string.net_detail_nr_sa_high_band
        NetworkKind.NR_SA -> R.string.net_detail_nr_sa
        NetworkKind.NR_NSA_HIGH_BAND -> R.string.net_detail_nr_nsa_high_band
        NetworkKind.NR_NSA -> R.string.net_detail_nr_nsa
        NetworkKind.LTE_A_PRO -> R.string.net_detail_lte_a_pro
        NetworkKind.LTE_CA -> R.string.net_detail_lte_ca
        NetworkKind.LTE, NetworkKind.GEN_3G, NetworkKind.GEN_2G, null -> null
    }

    /** How the phone reaches the internet: "Wi-Fi", "5G NSA", "Mobile data", "No data" … */
    fun access(res: Resources, access: Access, kind: NetworkKind?): RichText = when (access) {
        Access.WIFI -> res.richString(R.string.access_wifi).linkTechnical()
        Access.CELLULAR -> kind?.let { Glossary.segments(it.badge) } ?: res.richString(R.string.access_mobile_data)
        Access.ETHERNET -> res.richString(R.string.access_ethernet)
        Access.BLUETOOTH -> res.richString(R.string.access_bluetooth)
        Access.USB -> res.richString(R.string.access_usb)
        Access.OTHER -> res.richString(R.string.access_connected)
        Access.NONE -> res.richString(R.string.access_none)
    }

    /** [access], plus " + VPN" when the default route goes over a VPN, e.g. "Wi-Fi + VPN". */
    fun accessLine(res: Resources, state: DataSimState): RichText =
        withVpn(res, access(res, state.access, state.networkKind), state.vpn)

    private fun withVpn(res: Resources, text: RichText, vpn: Boolean): RichText =
        if (vpn) res.richString(R.string.with_vpn, RichArg.Rich(text)) else text

    /** The data SIM's short name, e.g. its name from Settings, "SIM 2" or "No data SIM". */
    fun simLabel(res: Resources, label: SimLabel): String = when (label) {
        is SimLabel.Named -> label.name
        is SimLabel.Slot -> res.getString(R.string.sim_slot, label.number)
        is SimLabel.SubId -> res.getString(R.string.sim_sub_id, label.id)
        SimLabel.NoDataSim -> res.getString(R.string.sim_no_data_sim)
    }

    /** Why the data SIM can't carry data, e.g. "Airplane mode"; null when it can. */
    fun problem(res: Resources, status: DataStatus): String? = when (status) {
        DataStatus.OK -> null
        DataStatus.AIRPLANE_MODE -> res.getString(R.string.status_airplane_mode)
        DataStatus.DATA_OFF -> res.getString(R.string.status_data_off)
        DataStatus.NO_SERVICE -> res.getString(R.string.status_no_service)
    }

    /**
     * Top line of the widget: the carrier carrying data, or why there is none ("No cellular" in
     * airplane mode or without service, "Mobile data off").
     */
    fun widgetHeadline(res: Resources, state: DataSimState): String = if (!state.hasTelephony) {
        res.getString(R.string.widget_no_telephony)
    } else when (state.status) {
        DataStatus.AIRPLANE_MODE, DataStatus.NO_SERVICE -> res.getString(R.string.widget_no_cellular)
        DataStatus.DATA_OFF -> res.getString(R.string.status_data_off)
        DataStatus.OK -> simLabel(res, state.simLabel)
    }

    /**
     * The headline at the top of the app: "Internet via Wi-Fi" or "Internet via Cellular" (or
     * Ethernet etc.), from the transport of the default network, or "No internet".
     */
    fun internetHeadline(res: Resources, state: DataSimState, wifiName: String?): InternetHeadline {
        fun title(@StringRes id: Int) = withVpn(res, res.richString(id), state.vpn)
        val airplane = state.status == DataStatus.AIRPLANE_MODE
        return when (state.access) {
            Access.NONE -> InternetHeadline(
                res.richString(R.string.headline_no_internet),
                subtitle = if (airplane) res.richString(R.string.airplane_mode_on) else null,
                airplaneMode = airplane,
            )
            Access.WIFI -> InternetHeadline(title(R.string.headline_via_wifi), subtitle = wifiName?.let { listOf(Glossary.Segment(it)) })
            Access.ETHERNET -> InternetHeadline(title(R.string.headline_via_ethernet))
            Access.BLUETOOTH -> InternetHeadline(title(R.string.headline_via_bluetooth))
            Access.USB -> InternetHeadline(title(R.string.headline_via_usb))
            Access.OTHER -> InternetHeadline(title(R.string.headline_connected))
            Access.CELLULAR -> InternetHeadline(
                title(R.string.headline_via_cellular),
                subtitle = res.richString(
                    R.string.headline_cellular_subtitle,
                    RichArg.Plain(simLabel(res, state.simLabel)),
                    RichArg.Rich(access(res, Access.CELLULAR, state.networkKind)),
                ),
            )
        }
    }

    /** e.g. "WPA2", "Open", "WPA3 Enterprise", with the known terms tappable. */
    fun security(res: Resources, security: WifiSecurity): RichText = res.richString(
        when (security) {
            WifiSecurity.OPEN -> R.string.wifi_security_open
            WifiSecurity.ENHANCED_OPEN -> R.string.wifi_security_enhanced_open
            WifiSecurity.WEP -> R.string.wifi_security_wep
            WifiSecurity.WPA2 -> R.string.wifi_security_wpa2
            WifiSecurity.WPA3 -> R.string.wifi_security_wpa3
            WifiSecurity.WPA2_ENTERPRISE -> R.string.wifi_security_wpa2_enterprise
            WifiSecurity.WPA3_ENTERPRISE -> R.string.wifi_security_wpa3_enterprise
            WifiSecurity.WPA3_ENTERPRISE_192 -> R.string.wifi_security_wpa3_enterprise_192
            WifiSecurity.PASSPOINT -> R.string.wifi_security_passpoint
            WifiSecurity.WAPI -> R.string.wifi_security_wapi
            WifiSecurity.OSEN -> R.string.wifi_security_osen
            WifiSecurity.DPP -> R.string.wifi_security_dpp
        },
    )

    /** e.g. "2.4 GHz", or "2,4 GHz" where the decimal separator is a comma. */
    fun band(res: Resources, band: WifiBand): String {
        val number = NumberFormat.getNumberInstance(res.configuration.locales[0]).apply { maximumFractionDigits = 1 }
        return res.getString(R.string.wifi_band, number.format(band.ghz))
    }

    /** A technical name loaded from a resource ("Wi-Fi"): link the terms in it. */
    private fun RichText.linkTechnical(): RichText = Glossary.segments(plain())
}
