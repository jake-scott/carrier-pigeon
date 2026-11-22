// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data.history

import dev.jakethesnake.carrierpigeon.data.history.HistoryMath.DAY_MS
import dev.jakethesnake.carrierpigeon.data.history.HistoryMath.GAP_TOLERANCE_MS
import dev.jakethesnake.carrierpigeon.data.history.HistoryMath.HOUR_MS
import dev.jakethesnake.carrierpigeon.data.history.HistoryMath.MINUTE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryMathTest {
    private val t0 = 1_800_000_000_000L // a whole hour, as epoch ms

    // ---- known spans ----

    @Test
    fun closedPeriodWatchedToTheEndIsKnownThroughout() {
        val p = Period(t0, t0 + HOUR_MS, t0 + HOUR_MS, "wifi")
        assertEquals(listOf(Span(t0, t0 + HOUR_MS, "wifi")), HistoryMath.knownSpans(listOf(p), 0, Long.MAX_VALUE, t0 + 5 * HOUR_MS, Long.MAX_VALUE))
    }

    @Test
    fun closedPeriodWithStaleConfirmationIsKnownOnlyToTheTolerance() {
        val p = Period(t0, t0 + 10 * MINUTE_MS, t0 + 3 * HOUR_MS, "wifi")
        val spans = HistoryMath.knownSpans(listOf(p), 0, Long.MAX_VALUE, t0 + 5 * HOUR_MS, Long.MAX_VALUE)
        assertEquals(listOf(Span(t0, t0 + 10 * MINUTE_MS + GAP_TOLERANCE_MS, "wifi")), spans)
    }

    @Test
    fun openPeriodConfirmedByThisProcessRunsToNow() {
        val now = t0 + 2 * HOUR_MS
        val p = Period(t0, t0 + MINUTE_MS, null, true)
        val spans = HistoryMath.knownSpans(listOf(p), 0, Long.MAX_VALUE, now, liveSince = t0)
        assertEquals(listOf(Span(t0, now, true)), spans)
    }

    @Test
    fun openPeriodFromAnEarlierProcessStopsAtTheTolerance() {
        val now = t0 + 2 * HOUR_MS
        val p = Period(t0, t0 + MINUTE_MS, null, true)
        val spans = HistoryMath.knownSpans(listOf(p), 0, Long.MAX_VALUE, now, liveSince = t0 + HOUR_MS)
        assertEquals(listOf(Span(t0, t0 + MINUTE_MS + GAP_TOLERANCE_MS, true)), spans)
    }

    @Test
    fun spansAreClippedToTheWindow() {
        val p = Period(t0, t0 + 4 * HOUR_MS, t0 + 4 * HOUR_MS, 1)
        val spans = HistoryMath.knownSpans(listOf(p), t0 + HOUR_MS, t0 + 2 * HOUR_MS, t0 + 9 * HOUR_MS, Long.MAX_VALUE)
        assertEquals(listOf(Span(t0 + HOUR_MS, t0 + 2 * HOUR_MS, 1)), spans)
    }

    @Test
    fun totalsAndValueAt() {
        val spans = listOf(Span(0, 10, "a"), Span(10, 15, "b"), Span(20, 30, "a"))
        assertEquals(mapOf("a" to 20L, "b" to 5L), HistoryMath.totals(spans))
        assertEquals("b", HistoryMath.valueAt(spans, 12))
        assertNull(HistoryMath.valueAt(spans, 17))
        assertEquals(listOf(Span(5, 10, "a"), Span(10, 12, "b")), HistoryMath.clip(spans, 5, 12))
    }

    // ---- signal throttling ----

    @Test
    fun firstReadingIsAlwaysStored() {
        assertTrue(HistoryMath.shouldRecordSignal(null, 2, -100, t0))
    }

    @Test
    fun readingsCloserThanTheSpacingAreDropped() {
        val last = SignalPoint(t0, 2, -100)
        assertFalse(HistoryMath.shouldRecordSignal(last, 4, -80, t0 + 10_000))
    }

    @Test
    fun aChangeOfBarsOrEnoughDbmIsStored() {
        val last = SignalPoint(t0, 2, -100)
        assertTrue(HistoryMath.shouldRecordSignal(last, 3, -100, t0 + MINUTE_MS))
        assertTrue(HistoryMath.shouldRecordSignal(last, 2, -96, t0 + MINUTE_MS))
        assertFalse(HistoryMath.shouldRecordSignal(last, 2, -98, t0 + MINUTE_MS))
    }

    @Test
    fun anUnchangedReadingIsStoredOnceThePeriodicHeartbeatIsDue() {
        val last = SignalPoint(t0, 2, -100)
        assertFalse(HistoryMath.shouldRecordSignal(last, 2, -100, t0 + 10 * MINUTE_MS))
        assertTrue(HistoryMath.shouldRecordSignal(last, 2, -100, t0 + 15 * MINUTE_MS))
    }

    @Test
    fun aClockGoingBackStoresTheReading() {
        assertTrue(HistoryMath.shouldRecordSignal(SignalPoint(t0, 2, -100), 2, -100, t0 - MINUTE_MS))
    }

    // ---- buckets and runs ----

    @Test
    fun intoSlotsWeightsBySampleCountAndKeysBySlotStart() {
        val hours = listOf(
            SignalBucket(t0, 3, 1.0, 0, 2, -110.0),
            SignalBucket(t0 + HOUR_MS, 1, 4.0, 4, 4, null),
            SignalBucket(t0 + 7 * HOUR_MS, 2, 3.0, 3, 3, -90.0),
        )
        val slots = listOf(t0, t0 + 6 * HOUR_MS)
        assertEquals(
            listOf(SignalBucket(t0, 4, 1.75, 0, 4, -110.0), SignalBucket(t0 + 6 * HOUR_MS, 2, 3.0, 3, 3, -90.0)),
            HistoryMath.intoSlots(hours, slots, t0 + 12 * HOUR_MS, HOUR_MS),
        )
    }

    @Test
    fun intoSlotsDropsReadingsOutsideTheWindow() {
        val hours = listOf(SignalBucket(t0 - HOUR_MS, 1, 2.0, 2, 2, null), SignalBucket(t0 + 2 * HOUR_MS, 1, 2.0, 2, 2, null))
        assertEquals(emptyList<SignalBucket>(), HistoryMath.intoSlots(hours, listOf(t0), t0 + HOUR_MS, HOUR_MS))
    }

    @Test
    fun anHourRunningIntoTheWindowCountsTowardsTheFirstSlot() {
        val start = t0 + 20 * MINUTE_MS
        val hours = listOf(SignalBucket(t0, 2, 3.0, 3, 3, null))
        assertEquals(listOf(SignalBucket(start, 2, 3.0, 3, 3, null)), HistoryMath.intoSlots(hours, listOf(start, t0 + HOUR_MS), t0 + 2 * HOUR_MS, HOUR_MS))
    }

    @Test
    fun singleReadingsGoIntoTheirSlot() {
        val points = listOf(SignalPoint(t0 - MINUTE_MS, 0, null), SignalPoint(t0, 1, -110), SignalPoint(t0 + 10 * MINUTE_MS, 3, null), SignalPoint(t0 + HOUR_MS + MINUTE_MS, 4, -80))
        val slots = HistoryMath.intoSlots(points.map(HistoryMath::asBucket), listOf(t0, t0 + HOUR_MS), t0 + 2 * HOUR_MS, 1)
        assertEquals(listOf(SignalBucket(t0, 2, 2.0, 1, 3, -110.0), SignalBucket(t0 + HOUR_MS, 1, 4.0, 4, 4, -80.0)), slots)
    }

    @Test
    fun secondsPerHourSplitsAcrossHours() {
        val spans = listOf(Span(t0 + 30 * MINUTE_MS, t0 + 90 * MINUTE_MS, "LTE"))
        assertEquals(mapOf((t0 to "LTE") to 1800L, ((t0 + HOUR_MS) to "LTE") to 1800L), HistoryMath.secondsPerHour(spans))
    }

    // ---- windows ----

    @Test
    fun windowsSlideBackFromNow() {
        val now = t0 + 37 * MINUTE_MS
        assertEquals(HistoryWindow(HistoryRange.DAY, now - DAY_MS, now), HistoryMath.window(HistoryRange.DAY, 0, now))
        assertEquals(HistoryWindow(HistoryRange.DAY, now - 2 * DAY_MS, now - DAY_MS), HistoryMath.window(HistoryRange.DAY, 1, now))
        assertEquals(HistoryWindow(HistoryRange.WEEK, now - 7 * DAY_MS, now), HistoryMath.window(HistoryRange.WEEK, 0, now))
        assertEquals(HistoryWindow(HistoryRange.MONTH, now - 60 * DAY_MS, now - 30 * DAY_MS), HistoryMath.window(HistoryRange.MONTH, 1, now))
        assertEquals(HistoryWindow(HistoryRange.YEAR, now - 365 * DAY_MS, now), HistoryMath.window(HistoryRange.YEAR, 0, now))
    }

    private fun at(dateTime: String, zone: ZoneId) = LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun aDaysSlotsAreClockHoursWithAPartialFirstOne() {
        val utc = ZoneOffset.UTC
        val window = HistoryMath.window(HistoryRange.DAY, 0, at("2026-11-20T15:20", utc))
        val slots = HistoryMath.slotStarts(window, utc)
        assertEquals(25, slots.size)
        assertEquals(at("2026-11-19T15:20", utc), slots.first())
        assertEquals(at("2026-11-19T16:00", utc), slots[1])
        assertEquals(at("2026-11-20T15:00", utc), slots.last())
    }

    @Test
    fun aWindowOnTheHourHasNoPartialSlot() {
        val utc = ZoneOffset.UTC
        val slots = HistoryMath.slotStarts(HistoryMath.window(HistoryRange.DAY, 0, at("2026-11-20T15:00", utc)), utc)
        assertEquals(24, slots.size)
        assertEquals(at("2026-11-19T15:00", utc), slots.first())
    }

    @Test
    fun slotsPerRange() {
        val utc = ZoneOffset.UTC
        val now = at("2026-11-20T15:20", utc)
        val week = HistoryMath.slotStarts(HistoryMath.window(HistoryRange.WEEK, 0, now), utc)
        assertEquals(29, week.size)
        assertEquals(at("2026-11-13T18:00", utc), week[1])
        val month = HistoryMath.slotStarts(HistoryMath.window(HistoryRange.MONTH, 0, now), utc)
        assertEquals(31, month.size)
        assertEquals(at("2026-10-22T00:00", utc), month[1])
        assertEquals(at("2026-11-20T00:00", utc), month.last())
        val year = HistoryMath.slotStarts(HistoryMath.window(HistoryRange.YEAR, 0, now), utc)
        assertEquals(13, year.size)
        assertEquals(at("2025-11-20T15:20", utc), year.first())
        assertEquals(at("2025-12-01T00:00", utc), year[1])
        assertEquals(at("2026-11-01T00:00", utc), year.last())
    }

    @Test
    fun aDaySpanningAClockChangeHasTwentyFourRealHours() {
        val london = ZoneId.of("Europe/London")
        val slots = HistoryMath.slotStarts(HistoryMath.window(HistoryRange.DAY, 0, at("2026-10-25T12:00", london)), london)
        assertEquals(24, slots.size)
        assertEquals(slots.zipWithNext { a, b -> b - a }.toSet(), setOf(HOUR_MS))
    }

    @Test
    fun hoursMinutesRoundsToTheNearestMinute() {
        assertEquals(3L to 20L, HistoryMath.hoursMinutes(3 * HOUR_MS + 20 * MINUTE_MS + 20_000))
        assertEquals(0L to 1L, HistoryMath.hoursMinutes(40_000))
    }

    @Test
    fun signalSourceKeysRoundTrip() {
        listOf(SignalSource.Wifi, SignalSource.Sim(3)).forEach { assertEquals(it, SignalSource.parse(it.key)) }
        assertNull(SignalSource.parse("bluetooth"))
    }
}
