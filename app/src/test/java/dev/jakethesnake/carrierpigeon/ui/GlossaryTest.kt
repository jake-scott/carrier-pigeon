// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.ui

import dev.jakethesnake.carrierpigeon.ui.Glossary.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GlossaryTest {
    @Test
    fun splitsBadgeIntoTerms() {
        assertEquals(
            listOf(Segment("5G", TermId.FIVE_G), Segment(" "), Segment("NSA", TermId.NSA)),
            Glossary.segments("5G NSA"),
        )
    }

    @Test
    fun longestPhraseWins() {
        assertEquals(listOf(Segment("LTE-A Pro", TermId.LTE_A_PRO)), Glossary.segments("LTE-A Pro"))
        assertEquals(listOf(Segment("LTE+", TermId.LTE_PLUS)), Glossary.segments("LTE+"))
        assertEquals(
            listOf(Segment("5G+", TermId.FIVE_G_PLUS), Segment(" "), Segment("SA", TermId.SA)),
            Glossary.segments("5G+ SA"),
        )
        assertEquals(listOf(Segment("Wi-Fi 6E", TermId.WIFI_6E)), Glossary.segments("Wi-Fi 6E"))
    }

    @Test
    fun matchesWholeWordsOnly() {
        assertFalse(Glossary.hasTerms("Samsung"))
        assertFalse(Glossary.hasTerms("NSAX"))
    }

    @Test
    fun everyTermKeyIsUnique() {
        assertEquals(TermId.entries.size, TermId.entries.map { it.key }.toSet().size)
        TermId.entries.forEach { assertEquals(it, TermId.byKey(it.key)) }
    }
}
