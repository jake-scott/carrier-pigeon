// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data.history

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * A stretch of time during which one value held, as stored. [confirmedUntil] is the last time the
 * app saw the value still true; [end] is when it saw the next value (null while the period is
 * open). Between the two the app wasn't watching, so the value there is unknown.
 */
data class Period<T>(val start: Long, val confirmedUntil: Long, val end: Long?, val value: T)

/** A known stretch, ready to draw: [start] inclusive, [end] exclusive, in epoch milliseconds. */
data class Span<T>(val start: Long, val end: Long, val value: T) {
    val duration: Long get() = end - start
}

/** One signal reading: bars 0..4, dBm when the radio reports it. */
data class SignalPoint(val at: Long, val bars: Int, val dbm: Int?)

/** Signal readings summarised over [start] to [start] + the bucket's length. */
data class SignalBucket(
    val start: Long,
    val samples: Int,
    val barsAvg: Double,
    val barsMin: Int,
    val barsMax: Int,
    val dbmAvg: Double?,
)

/** Where a signal reading came from. Stored as [key]: "sim:3" or "wifi". */
sealed interface SignalSource {
    val key: String

    data class Sim(val subId: Int) : SignalSource {
        override val key: String get() = "sim:$subId"
    }

    data object Wifi : SignalSource {
        override val key: String get() = "wifi"
    }

    companion object {
        fun parse(key: String): SignalSource? = when {
            key == "wifi" -> Wifi
            key.startsWith("sim:") -> key.removePrefix("sim:").toIntOrNull()?.let { Sim(it) }
            else -> null
        }
    }
}

/** The time ranges the History page offers. */
enum class HistoryRange {
    DAY,
    WEEK,
    MONTH,
    YEAR,
}

/** One window on the History page, [from] inclusive to [to] exclusive. */
data class HistoryWindow(val range: HistoryRange, val from: Long, val to: Long) {
    val length: Long get() = to - from
}

/**
 * The arithmetic behind history: which stretches are known, totals, throttling of signal samples
 * and rollups. No Android classes, so it is unit tested on the JVM.
 */
object HistoryMath {
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS

    /**
     * A period confirmed this long ago still counts as known up to this much later: the 15-minute
     * sampler can't confirm more often, and Android delays it further in Doze.
     */
    const val GAP_TOLERANCE_MS = 20 * MINUTE_MS

    /** Never store more than one signal sample per source in this time. */
    const val SIGNAL_MIN_SPACING_MS = 30_000L

    /** Store a sample at least this often while readings keep arriving, even if unchanged. */
    const val SIGNAL_HEARTBEAT_MS = 15 * MINUTE_MS

    /** A dBm change this large is worth a sample even when the bars stay the same. */
    const val SIGNAL_DBM_STEP = 4

    /** Raw signal samples and network-type periods are kept this long, then rolled up hourly. */
    const val RAW_RETENTION_MS = 31 * DAY_MS

    /**
     * How far each period is known, clipped to [from]..[to].
     *
     * @param now the current time: an open period runs to here.
     * @param liveSince when this process started watching. An open period confirmed since then has
     *   been watched by the live listeners all along, so it is known up to [now].
     */
    fun <T> knownSpans(periods: List<Period<T>>, from: Long, to: Long, now: Long, liveSince: Long): List<Span<T>> =
        periods.mapNotNull { p ->
            val stop = p.end ?: now
            val knownEnd = if (p.end == null && p.confirmedUntil >= liveSince) {
                now
            } else {
                minOf(stop, maxOf(p.confirmedUntil, p.start) + GAP_TOLERANCE_MS)
            }
            val s = maxOf(p.start, from)
            val e = minOf(knownEnd, to, now)
            if (e > s) Span(s, e, p.value) else null
        }

    /** Total time per value. */
    fun <T> totals(spans: List<Span<T>>): Map<T, Long> {
        val out = LinkedHashMap<T, Long>()
        spans.forEach { out[it.value] = (out[it.value] ?: 0L) + it.duration }
        return out
    }

    /** The value at [at], or null when unknown. */
    fun <T> valueAt(spans: List<Span<T>>, at: Long): T? = spans.firstOrNull { at >= it.start && at < it.end }?.value

    /** [spans] cut to [from]..[to]. */
    fun <T> clip(spans: List<Span<T>>, from: Long, to: Long): List<Span<T>> = spans.mapNotNull { s ->
        val a = maxOf(s.start, from)
        val b = minOf(s.end, to)
        if (b > a) Span(a, b, s.value) else null
    }

    /**
     * Whether a new reading should be stored, given the last one stored for the same source:
     * a change of bars or of at least [SIGNAL_DBM_STEP] dBm, at most once per
     * [SIGNAL_MIN_SPACING_MS], and at least once per [SIGNAL_HEARTBEAT_MS].
     */
    fun shouldRecordSignal(last: SignalPoint?, bars: Int, dbm: Int?, now: Long): Boolean {
        if (last == null) return true
        val since = now - last.at
        if (since < 0) return true // the clock went back
        if (since < SIGNAL_MIN_SPACING_MS) return false
        if (since >= SIGNAL_HEARTBEAT_MS) return true
        if (bars != last.bars) return true
        if (dbm != null && last.dbm != null && abs(dbm - last.dbm) >= SIGNAL_DBM_STEP) return true
        return (dbm == null) != (last.dbm == null)
    }

    /** A single reading as a one-sample bucket, so raw readings and hourly rollups go into slots alike. */
    fun asBucket(p: SignalPoint): SignalBucket =
        SignalBucket(p.at, 1, p.bars.toDouble(), p.bars, p.bars, p.dbm?.toDouble())

    /**
     * Summarises [buckets] (hourly rollups, or single readings via [asBucket]) into one bucket per
     * slot, weighting each by its sample count. [slots] are the slot starts in order; the last slot
     * runs to [end]. Each bucket covers [bucketMs] from its start: one that began before the first
     * slot but runs into it counts towards the first slot. Each result's start is its slot's start;
     * slots with no readings are left out.
     */
    fun intoSlots(buckets: List<SignalBucket>, slots: List<Long>, end: Long, bucketMs: Long): List<SignalBucket> {
        if (slots.isEmpty()) return emptyList()
        val first = slots.first()
        return buckets.filter { it.samples > 0 && it.start < end && (it.start >= first || it.start + bucketMs > first) }
            .groupBy { b -> slots.lastOrNull { it <= b.start } ?: first }
            .map { (start, bs) ->
                val n = bs.sumOf { it.samples }
                val withDbm = bs.filter { it.dbmAvg != null }
                val dbmN = withDbm.sumOf { it.samples }
                SignalBucket(
                    start = start,
                    samples = n,
                    barsAvg = bs.sumOf { it.barsAvg * it.samples } / n,
                    barsMin = bs.minOf { it.barsMin },
                    barsMax = bs.maxOf { it.barsMax },
                    dbmAvg = if (dbmN == 0) null else withDbm.sumOf { it.dbmAvg!! * it.samples } / dbmN,
                )
            }
            .sortedBy { it.start }
    }

    /**
     * Seconds of each period inside each whole hour, for rolling network-type periods up into
     * hourly totals. Keys are (hour start, value).
     */
    fun <T> secondsPerHour(spans: List<Span<T>>): Map<Pair<Long, T>, Long> {
        val out = LinkedHashMap<Pair<Long, T>, Long>()
        spans.forEach { s ->
            var hour = s.start / HOUR_MS * HOUR_MS
            while (hour < s.end) {
                val a = maxOf(s.start, hour)
                val b = minOf(s.end, hour + HOUR_MS)
                if (b > a) {
                    val key = hour to s.value
                    out[key] = (out[key] ?: 0L) + (b - a) / 1000
                }
                hour += HOUR_MS
            }
        }
        return out
    }

    /** How long each range's window is: the last 24 hours, 7 days, 30 days or 365 days. */
    fun length(range: HistoryRange): Long = when (range) {
        HistoryRange.DAY -> DAY_MS
        HistoryRange.WEEK -> 7 * DAY_MS
        HistoryRange.MONTH -> 30 * DAY_MS
        HistoryRange.YEAR -> 365 * DAY_MS
    }

    /**
     * The window for [range], sliding: offset 0 ends at [now] and starts one range length before
     * it (so a day is the last 24 hours, not since midnight). Each step of [offset] moves it back
     * one whole length.
     */
    fun window(range: HistoryRange, offset: Int, now: Long): HistoryWindow {
        val len = length(range)
        val to = now - offset * len
        return HistoryWindow(range, to - len, to)
    }

    /**
     * Where each bar starts, for the signal chart and the stacked bars: every clock hour for a day,
     * every 6 hours (midnight, 6, noon, 18) for a week, each day for a month and each month for a
     * year. Bars sit on the clock and calendar so they don't shift as the window slides, which
     * makes the first and last bars partial: the first starts at the window's start. Hours are real
     * hours, so a day spanning a clock change still has 24 of them.
     */
    fun slotStarts(window: HistoryWindow, zone: ZoneId): List<Long> {
        val from = Instant.ofEpochMilli(window.from).atZone(zone)
        val boundaries: Sequence<ZonedDateTime> = when (window.range) {
            HistoryRange.DAY -> from.truncatedTo(ChronoUnit.HOURS).let { h -> generateSequence(h) { it.plusHours(1) } }
            HistoryRange.WEEK -> from.toLocalDate().atStartOfDay().let { d ->
                generateSequence(d) { it.plusHours(6) }.map { it.atZone(zone) }
            }
            HistoryRange.MONTH -> from.toLocalDate().let { d -> generateSequence(d) { it.plusDays(1) }.map { it.atStartOfDay(zone) } }
            HistoryRange.YEAR -> from.toLocalDate().withDayOfMonth(1).let { d ->
                generateSequence(d) { it.plusMonths(1) }.map { it.atStartOfDay(zone) }
            }
        }
        return listOf(window.from) + boundaries.map { it.toInstant().toEpochMilli() }
            .dropWhile { it <= window.from }
            .takeWhile { it < window.to }
            .distinct()
    }

    /** Whole hours and minutes in [ms], for "3 h 20 m" style totals. */
    fun hoursMinutes(ms: Long): Pair<Long, Long> {
        val minutes = (ms + MINUTE_MS / 2) / MINUTE_MS
        return minutes / 60 to minutes % 60
    }
}
