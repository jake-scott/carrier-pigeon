// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.ui

import dev.jakethesnake.carrierpigeon.ui.Glossary.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class RichTextTest {
    @Test
    fun fillsTechnicalArgWithLinksAndEmphasis() {
        val filled = fillPlaceholders(
            listOf(Segment("Security: %1\$s")),
            listOf(RichArg.Technical("WPA3")),
            emphasizeArgs = true,
        )
        assertEquals(listOf(Segment("Security: "), Segment("WPA3", TermId.WPA3, emphasis = true)), filled)
    }

    @Test
    fun plainArgIsNeverLinked() {
        // A Wi-Fi network named after a term must not become a link.
        val filled = fillPlaceholders(listOf(Segment("%1\$s · %2\$s")), listOf(RichArg.Plain("LTE Home"), RichArg.Technical("LTE")))
        assertEquals(listOf(Segment("LTE Home"), Segment(" · "), Segment("LTE", TermId.LTE)), filled)
    }

    @Test
    fun translatorsCanReorderPlaceholders() {
        val filled = fillPlaceholders(listOf(Segment("%2\$s, %1\$s")), listOf(RichArg.Plain("a"), RichArg.Plain("b")))
        assertEquals("b, a", filled.plain())
    }

    @Test
    fun keepsAnnotatedTermsAndUnescapesPercent() {
        val template = listOf(Segment("%1\$s + "), Segment("VPN", TermId.VPN), Segment(" 100%%"))
        val filled = fillPlaceholders(template, listOf(RichArg.Rich(listOf(Segment("Wi-Fi", TermId.WIFI)))))
        assertEquals(
            listOf(Segment("Wi-Fi", TermId.WIFI), Segment(" + "), Segment("VPN", TermId.VPN), Segment(" 100"), Segment("%")),
            filled,
        )
    }
}
