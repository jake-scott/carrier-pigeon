// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.ui

import androidx.annotation.StringRes
import dev.jakethesnake.carrierpigeon.R

/**
 * A network term the app can explain in a small popup, e.g. NSA. [key] names it in string
 * resources: `<annotation term="nsa">` marks tappable words in a translated sentence.
 */
enum class TermId(val key: String, @param:StringRes val title: Int, @param:StringRes val body: Int) {
    LTE("lte", R.string.glossary_lte_title, R.string.glossary_lte_body),
    LTE_PLUS("lte_plus", R.string.glossary_lte_plus_title, R.string.glossary_lte_plus_body),
    CARRIER_AGGREGATION("carrier_aggregation", R.string.glossary_carrier_aggregation_title, R.string.glossary_carrier_aggregation_body),
    LTE_A_PRO("lte_a_pro", R.string.glossary_lte_a_pro_title, R.string.glossary_lte_a_pro_body),
    FIVE_G("five_g", R.string.glossary_five_g_title, R.string.glossary_five_g_body),
    FIVE_G_PLUS("five_g_plus", R.string.glossary_five_g_plus_title, R.string.glossary_five_g_plus_body),
    NR("nr", R.string.glossary_nr_title, R.string.glossary_nr_body),
    NSA("nsa", R.string.glossary_nsa_title, R.string.glossary_nsa_body),
    SA("sa", R.string.glossary_sa_title, R.string.glossary_sa_body),
    HIGH_BAND("high_band", R.string.glossary_high_band_title, R.string.glossary_high_band_body),
    THREE_G("three_g", R.string.glossary_three_g_title, R.string.glossary_three_g_body),
    TWO_G("two_g", R.string.glossary_two_g_title, R.string.glossary_two_g_body),
    ESIM("esim", R.string.glossary_esim_title, R.string.glossary_esim_body),
    WIFI("wifi", R.string.glossary_wifi_title, R.string.glossary_wifi_body),
    VPN("vpn", R.string.glossary_vpn_title, R.string.glossary_vpn_body),
    CELLULAR("cellular", R.string.glossary_cellular_title, R.string.glossary_cellular_body),
    WIFI_7("wifi_7", R.string.glossary_wifi_7_title, R.string.glossary_wifi_7_body),
    WIFI_6E("wifi_6e", R.string.glossary_wifi_6e_title, R.string.glossary_wifi_6e_body),
    WIFI_6("wifi_6", R.string.glossary_wifi_6_title, R.string.glossary_wifi_6_body),
    WIFI_5("wifi_5", R.string.glossary_wifi_5_title, R.string.glossary_wifi_5_body),
    WIFI_4("wifi_4", R.string.glossary_wifi_4_title, R.string.glossary_wifi_4_body),
    WIFI_OLD("wifi_old", R.string.glossary_wifi_old_title, R.string.glossary_wifi_old_body),
    WPA3("wpa3", R.string.glossary_wpa3_title, R.string.glossary_wpa3_body),
    WPA2("wpa2", R.string.glossary_wpa2_title, R.string.glossary_wpa2_body),
    WEP("wep", R.string.glossary_wep_title, R.string.glossary_wep_body),
    OPEN("open", R.string.glossary_open_title, R.string.glossary_open_body),
    ENHANCED_OPEN("enhanced_open", R.string.glossary_enhanced_open_title, R.string.glossary_enhanced_open_body),
    ;

    companion object {
        /** The term an `<annotation term="…">` names, or null if the key is unknown. */
        fun byKey(key: String): TermId? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Finds explainable terms in technical names that are never translated: network badges ("5G NSA"),
 * Wi-Fi generations ("Wi-Fi 6E") and the like. Translated sentences mark their terms with
 * `<annotation>` instead (see [richString]), so nothing here scans translated text.
 *
 * Kept free of Android APIs so the matching can be unit tested.
 */
object Glossary {
    /**
     * Technical names matched in labels, longest first so "LTE-A Pro" wins over "LTE" and "5G+"
     * over "5G". Matching ignores case.
     */
    private val PHRASES: List<Pair<String, TermId>> = listOf(
        "LTE-A Pro" to TermId.LTE_A_PRO,
        "LTE+" to TermId.LTE_PLUS,
        "LTE" to TermId.LTE,
        "5G+" to TermId.FIVE_G_PLUS,
        "5G" to TermId.FIVE_G,
        "NSA" to TermId.NSA,
        "SA" to TermId.SA,
        "NR" to TermId.NR,
        "3G" to TermId.THREE_G,
        "2G" to TermId.TWO_G,
        "eSIM" to TermId.ESIM,
        "Wi-Fi 7" to TermId.WIFI_7,
        "Wi-Fi 6E" to TermId.WIFI_6E,
        "Wi-Fi 6" to TermId.WIFI_6,
        "Wi-Fi 5" to TermId.WIFI_5,
        "Wi-Fi 4" to TermId.WIFI_4,
        "Wi-Fi 3" to TermId.WIFI_OLD,
        "Wi-Fi 2" to TermId.WIFI_OLD,
        "Wi-Fi 1" to TermId.WIFI_OLD,
        "Wi-Fi" to TermId.WIFI,
        "WPA3" to TermId.WPA3,
        "WPA2" to TermId.WPA2,
        "WEP" to TermId.WEP,
        "VPN" to TermId.VPN,
    ).sortedByDescending { it.first.length }

    /**
     * A run of label text: either plain, or a term that can be tapped for its explanation.
     * [emphasis] marks a value filled into a sentence, e.g. "WPA3" in "Security: WPA3".
     */
    data class Segment(val text: String, val term: TermId? = null, val emphasis: Boolean = false)

    /** Splits [label] into plain text and explainable terms, e.g. "5G NSA" into "5G", " ", "NSA". */
    fun segments(label: String): List<Segment> {
        val out = mutableListOf<Segment>()
        val plain = StringBuilder()
        var i = 0
        while (i < label.length) {
            val hit = if (startsWord(label, i)) {
                PHRASES.firstOrNull { (phrase, _) ->
                    label.regionMatches(i, phrase, 0, phrase.length, ignoreCase = true) &&
                        endsWord(label, i + phrase.length)
                }
            } else {
                null
            }
            if (hit == null) {
                plain.append(label[i])
                i++
                continue
            }
            if (plain.isNotEmpty()) {
                out += Segment(plain.toString())
                plain.clear()
            }
            val end = i + hit.first.length
            out += Segment(label.substring(i, end), hit.second)
            i = end
        }
        if (plain.isNotEmpty()) out += Segment(plain.toString())
        return out
    }

    /** True if [label] contains anything [segments] would make tappable. */
    fun hasTerms(label: String): Boolean = segments(label).any { it.term != null }

    private fun startsWord(s: String, i: Int) = i == 0 || !s[i - 1].isLetterOrDigit()

    // "+" ends a phrase like "LTE+", so after "LTE" a following "+" is part of the word.
    private fun endsWord(s: String, i: Int) = i >= s.length || !(s[i].isLetterOrDigit() || s[i] == '+' || s[i] == '-')
}
