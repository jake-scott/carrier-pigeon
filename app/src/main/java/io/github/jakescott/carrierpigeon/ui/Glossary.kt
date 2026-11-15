// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.ui

/** A network term the app can explain in a small popup, e.g. "NSA". */
data class Term(val title: String, val explanation: String)

/**
 * Plain-language explanations of the labels the app shows. Kept free of Android APIs so the
 * matching can be unit tested and the screen previewed off-device.
 */
object Glossary {
    private val LTE = Term(
        "LTE",
        "Long Term Evolution, the 4G mobile network. Most mobile data still travels over LTE, " +
            "including the LTE \"anchor\" that 5G NSA is built on.",
    )
    private val LTE_PLUS = Term(
        "LTE+",
        "LTE with carrier aggregation: the phone is using several LTE channels at once, " +
            "which gives more speed than plain LTE. It is still 4G.",
    )
    private val CARRIER_AGGREGATION = Term(
        "Carrier aggregation",
        "Combining two or more radio channels into one connection, so data can flow over all " +
            "of them at once. Shown as LTE+ in the status bar.",
    )
    private val LTE_A_PRO = Term(
        "LTE-A Pro",
        "LTE Advanced Pro, the last upgrade to 4G: more aggregated channels and smarter antennas. " +
            "Some US carriers call it \"5G E\", but it is LTE, not 5G.",
    )
    private val FIVE_G = Term(
        "5G",
        "The fifth generation of mobile networks. Its radio is called NR. The label after it " +
            "says how the phone is using it: NSA (alongside LTE) or SA (5G on its own).",
    )
    private val FIVE_G_PLUS = Term(
        "5G+",
        "5G on spectrum the carrier treats as its fastest, usually high band (mmWave) or " +
            "very wide channels. Speeds can be much higher, but range is shorter.",
    )
    private val NR = Term(
        "NR",
        "New Radio, the radio technology 5G uses. \"LTE + NR\" means the phone has an LTE " +
            "connection with 5G NR added on top.",
    )
    private val NSA = Term(
        "NSA",
        "Non-standalone 5G. The phone stays connected to an LTE cell, which handles signalling, " +
            "and 5G NR is added for extra speed. Android may show 5G when the LTE cell only " +
            "offers 5G nearby, even if no data is going over NR at that moment.",
    )
    private val SA = Term(
        "SA",
        "Standalone 5G. The phone talks to a 5G core network over NR alone, with no LTE " +
            "underneath. It can mean lower latency and newer 5G features.",
    )
    private val HIGH_BAND = Term(
        "High band",
        "High-frequency 5G spectrum, such as mmWave. Very fast but short range, and easily " +
            "blocked by walls. Carriers often label it 5G+ or 5G UW.",
    )
    private val THREE_G = Term(
        "3G",
        "The third generation of mobile networks (UMTS, HSPA). Usable data but slow by today's " +
            "standards. Many carriers have switched it off.",
    )
    private val TWO_G = Term(
        "2G",
        "The second generation of mobile networks (GSM, GPRS, EDGE). Calls and texts, but very " +
            "slow data.",
    )
    private val ESIM = Term(
        "eSIM",
        "An embedded SIM: a chip built into the phone that is set up by downloading a profile " +
            "from the carrier, instead of inserting a plastic card.",
    )
    private val WIFI = Term(
        "Wi-Fi",
        "The phone is reaching the internet over Wi-Fi right now, so mobile data is not being " +
            "used, even though a SIM is set for mobile data.",
    )
    private val VPN = Term(
        "VPN",
        "A virtual private network. Your traffic is tunnelled through a VPN app, on top of " +
            "Wi-Fi or mobile data.",
    )

    /**
     * Phrases matched in labels, longest first so "LTE-A Pro" wins over "LTE" and "5G+" over "5G".
     * Matching ignores case.
     */
    private val PHRASES: List<Pair<String, Term>> = listOf(
        "LTE Advanced Pro" to LTE_A_PRO,
        "carrier aggregation" to CARRIER_AGGREGATION,
        "non-standalone" to NSA,
        "standalone" to SA,
        "high band" to HIGH_BAND,
        "LTE-A Pro" to LTE_A_PRO,
        "LTE+" to LTE_PLUS,
        "LTE" to LTE,
        "5G+" to FIVE_G_PLUS,
        "5G" to FIVE_G,
        "NSA" to NSA,
        "SA" to SA,
        "NR" to NR,
        "3G" to THREE_G,
        "2G" to TWO_G,
        "eSIM" to ESIM,
        "Wi-Fi" to WIFI,
        "VPN" to VPN,
    ).sortedByDescending { it.first.length }

    /** A run of label text: either plain, or a term that can be tapped for its explanation. */
    data class Segment(val text: String, val term: Term? = null)

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
