// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

    /** Signal readings further apart than this are drawn with a break between them. */
    const val SIGNAL_GAP_MS = 30 * MINUTE_MS

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

    /** Summarises [points] into buckets of [bucketMs] starting at [from]. Empty buckets are left out. */
    fun bucketize(points: List<SignalPoint>, from: Long, bucketMs: Long): List<SignalBucket> =
        points.filter { it.at >= from }
            .groupBy { from + (it.at - from) / bucketMs * bucketMs }
            .map { (start, ps) ->
                val dbms = ps.mapNotNull { it.dbm }
                SignalBucket(
                    start = start,
                    samples = ps.size,
                    barsAvg = ps.sumOf { it.bars }.toDouble() / ps.size,
                    barsMin = ps.minOf { it.bars },
                    barsMax = ps.maxOf { it.bars },
                    dbmAvg = if (dbms.isEmpty()) null else dbms.sum().toDouble() / dbms.size,
                )
            }
            .sortedBy { it.start }

    /**
     * Regroups buckets (for example stored hourly rollups) into larger buckets of [bucketMs] from
     * [from], weighting each by its sample count.
     */
    fun regroup(buckets: List<SignalBucket>, from: Long, bucketMs: Long): List<SignalBucket> =
        buckets.filter { it.start >= from && it.samples > 0 }
            .groupBy { from + (it.start - from) / bucketMs * bucketMs }
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

    /** Splits readings where two neighbours are more than [gapMs] apart, so the line breaks there. */
    fun runs(points: List<SignalPoint>, gapMs: Long = SIGNAL_GAP_MS): List<List<SignalPoint>> {
        val out = mutableListOf<MutableList<SignalPoint>>()
        points.sortedBy { it.at }.forEach { p ->
            val run = out.lastOrNull()
            if (run == null || p.at - run.last().at > gapMs) out += mutableListOf(p) else run += p
        }
        return out
    }

    /** The reading in force at [at]: the latest at or before it, if no older than [gapMs]. */
    fun signalAt(points: List<SignalPoint>, at: Long, gapMs: Long = SIGNAL_GAP_MS): SignalPoint? =
        points.lastOrNull { it.at <= at }?.takeIf { at - it.at <= gapMs }

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

    /**
     * The window for [range], [offset] steps back from the one containing [now] (0 = current).
     * Day and month are calendar ones; week is the 7 days ending today; year is the 12 calendar
     * months ending this month.
     */
    fun window(range: HistoryRange, offset: Int, now: Long, zone: ZoneId): HistoryWindow {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val (start, end) = when (range) {
            HistoryRange.DAY -> today.minusDays(offset.toLong()).let { it to it.plusDays(1) }
            HistoryRange.WEEK -> today.minusDays(7L * offset).let { it.minusDays(6) to it.plusDays(1) }
            HistoryRange.MONTH -> today.withDayOfMonth(1).minusMonths(offset.toLong()).let { it to it.plusMonths(1) }
            HistoryRange.YEAR -> today.withDayOfMonth(1).minusMonths(12L * offset).let { it.minusMonths(11) to it.plusMonths(1) }
        }
        return HistoryWindow(range, start.startMillis(zone), end.startMillis(zone))
    }

    /** Bar boundaries for the stacked bar chart: days for a month, months for a year. */
    fun barStarts(window: HistoryWindow, zone: ZoneId): List<Long> {
        val first = Instant.ofEpochMilli(window.from).atZone(zone).toLocalDate()
        val out = mutableListOf<Long>()
        var d = first
        while (true) {
            val ms = d.startMillis(zone)
            if (ms >= window.to) break
            out += ms
            d = if (window.range == HistoryRange.YEAR) d.plusMonths(1) else d.plusDays(1)
        }
        return out
    }

    /** Signal bucket length for the averaged views. */
    fun bucketMs(range: HistoryRange): Long = when (range) {
        HistoryRange.DAY -> 0L
        HistoryRange.WEEK -> HOUR_MS
        HistoryRange.MONTH -> 6 * HOUR_MS
        HistoryRange.YEAR -> DAY_MS
    }

    /** Whole hours and minutes in [ms], for "3 h 20 m" style totals. */
    fun hoursMinutes(ms: Long): Pair<Long, Long> {
        val minutes = (ms + MINUTE_MS / 2) / MINUTE_MS
        return minutes / 60 to minutes % 60
    }

    /** Midnight, or the first moment of the day where a time change skips midnight. */
    private fun LocalDate.startMillis(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli()
}
