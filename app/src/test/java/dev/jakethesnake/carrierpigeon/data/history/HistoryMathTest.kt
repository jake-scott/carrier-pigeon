// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data.history

import dev.jakethesnake.carrierpigeon.data.history.HistoryMath.GAP_TOLERANCE_MS
import dev.jakethesnake.carrierpigeon.data.history.HistoryMath.HOUR_MS
import dev.jakethesnake.carrierpigeon.data.history.HistoryMath.MINUTE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
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
    fun bucketizeSummarisesEachBucket() {
        val points = listOf(
            SignalPoint(t0, 1, -110),
            SignalPoint(t0 + 10 * MINUTE_MS, 3, null),
            SignalPoint(t0 + HOUR_MS + MINUTE_MS, 4, -80),
        )
        val buckets = HistoryMath.bucketize(points, t0, HOUR_MS)
        assertEquals(2, buckets.size)
        assertEquals(SignalBucket(t0, 2, 2.0, 1, 3, -110.0), buckets[0])
        assertEquals(SignalBucket(t0 + HOUR_MS, 1, 4.0, 4, 4, -80.0), buckets[1])
    }

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
            HistoryMath.intoSlots(hours, slots, t0 + 12 * HOUR_MS),
        )
    }

    @Test
    fun intoSlotsDropsReadingsOutsideTheWindow() {
        val hours = listOf(SignalBucket(t0 - HOUR_MS, 1, 2.0, 2, 2, null), SignalBucket(t0 + 2 * HOUR_MS, 1, 2.0, 2, 2, null))
        assertEquals(emptyList<SignalBucket>(), HistoryMath.intoSlots(hours, listOf(t0), t0 + HOUR_MS))
    }

    @Test
    fun secondsPerHourSplitsAcrossHours() {
        val spans = listOf(Span(t0 + 30 * MINUTE_MS, t0 + 90 * MINUTE_MS, "LTE"))
        assertEquals(mapOf((t0 to "LTE") to 1800L, ((t0 + HOUR_MS) to "LTE") to 1800L), HistoryMath.secondsPerHour(spans))
    }

    // ---- windows ----

    private fun ms(date: String, zone: ZoneId) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun windowsForEachRange() {
        val utc = ZoneOffset.UTC
        val now = ms("2026-11-20", utc) + 15 * HOUR_MS
        assertEquals(HistoryWindow(HistoryRange.DAY, ms("2026-11-20", utc), ms("2026-11-21", utc)), HistoryMath.window(HistoryRange.DAY, 0, now, utc))
        assertEquals(HistoryWindow(HistoryRange.DAY, ms("2026-11-19", utc), ms("2026-11-20", utc)), HistoryMath.window(HistoryRange.DAY, 1, now, utc))
        assertEquals(HistoryWindow(HistoryRange.WEEK, ms("2026-11-14", utc), ms("2026-11-21", utc)), HistoryMath.window(HistoryRange.WEEK, 0, now, utc))
        assertEquals(HistoryWindow(HistoryRange.MONTH, ms("2026-10-01", utc), ms("2026-11-01", utc)), HistoryMath.window(HistoryRange.MONTH, 1, now, utc))
        assertEquals(HistoryWindow(HistoryRange.YEAR, ms("2025-12-01", utc), ms("2026-12-01", utc)), HistoryMath.window(HistoryRange.YEAR, 0, now, utc))
    }

    @Test
    fun aDayWithAClockChangeIsTwentyFiveHours() {
        val london = ZoneId.of("Europe/London")
        val now = ms("2026-10-25", london) + 12 * HOUR_MS
        assertEquals(25 * HOUR_MS, HistoryMath.window(HistoryRange.DAY, 0, now, london).length)
    }

    @Test
    fun barsAreDaysForAMonthAndMonthsForAYear() {
        val utc = ZoneOffset.UTC
        val now = ms("2026-11-20", utc)
        val month = HistoryMath.window(HistoryRange.MONTH, 0, now, utc)
        assertEquals(30, HistoryMath.barStarts(month, utc).size)
        val year = HistoryMath.window(HistoryRange.YEAR, 0, now, utc)
        val bars = HistoryMath.barStarts(year, utc)
        assertEquals(12, bars.size)
        assertEquals(ms("2026-11-01", utc), bars.last())
    }

    @Test
    fun signalSlotsPerRange() {
        val utc = ZoneOffset.UTC
        val now = ms("2026-11-20", utc)
        assertEquals(24, HistoryMath.slotStarts(HistoryMath.window(HistoryRange.DAY, 0, now, utc), utc).size)
        assertEquals(28, HistoryMath.slotStarts(HistoryMath.window(HistoryRange.WEEK, 0, now, utc), utc).size)
        assertEquals(30, HistoryMath.slotStarts(HistoryMath.window(HistoryRange.MONTH, 0, now, utc), utc).size)
        assertEquals(12, HistoryMath.slotStarts(HistoryMath.window(HistoryRange.YEAR, 0, now, utc), utc).size)
        val london = ZoneId.of("Europe/London")
        val clockChange = ms("2026-10-25", london) + 12 * HOUR_MS
        assertEquals(25, HistoryMath.slotStarts(HistoryMath.window(HistoryRange.DAY, 0, clockChange, london), london).size)
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
