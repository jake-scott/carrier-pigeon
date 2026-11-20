// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jakethesnake.carrierpigeon.data.Signal
import dev.jakethesnake.carrierpigeon.data.history.HistoryMath
import dev.jakethesnake.carrierpigeon.data.history.SignalBucket
import dev.jakethesnake.carrierpigeon.data.history.SignalPoint

/** A coloured stretch of a band chart. */
data class Band(val start: Long, val end: Long, val color: Color)

/** One stacked bar: [segments] are fractions of the bar's time, in drawing order; the rest is unknown. */
data class StackedBar(val start: Long, val end: Long, val segments: List<Pair<Color, Float>>)

/** One signal line, from raw readings (a step line) or buckets (an average with a min–max band). */
data class SignalLine(
    val color: Color,
    val points: List<SignalPoint> = emptyList(),
    val buckets: List<SignalBucket> = emptyList(),
    val bucketMs: Long = 0L,
)

/** A tick on the time axis: where, and the words under it. */
data class Tick(val at: Long, val label: String)

/** Time to x and back for a chart [from]..[to] drawn [width] wide. */
private class TimeScale(val from: Long, val to: Long, val width: Float) {
    fun x(t: Long): Float = ((t - from).toFloat() / (to - from).toFloat()).coerceIn(0f, 1f) * width
    fun t(x: Float): Long = from + ((x / width).coerceIn(0f, 1f) * (to - from)).toLong()
}

/**
 * Tap or drag sideways to pick a moment; vertical drags still scroll the page. Reports the time
 * under the finger.
 */
private fun Modifier.scrubbable(from: Long, to: Long, onScrub: (Long) -> Unit): Modifier = this
    .pointerInput(from, to) {
        detectTapGestures { pos -> onScrub(TimeScale(from, to, size.width.toFloat()).t(pos.x)) }
    }
    .pointerInput(from, to) {
        detectHorizontalDragGestures(
            onDragStart = { pos -> onScrub(TimeScale(from, to, size.width.toFloat()).t(pos.x)) },
        ) { change, _ -> onScrub(TimeScale(from, to, size.width.toFloat()).t(change.position.x)) }
    }

private fun DrawScope.scrubLine(scale: TimeScale, at: Long?, color: Color) {
    if (at == null) return
    val x = scale.x(at)
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
}

/**
 * A horizontal band coloured by what held when; [unknown] where nothing was recorded. [markers]
 * get a small triangle above the band (automatic switches).
 */
@Composable
fun BandChart(
    bands: List<Band>,
    from: Long,
    to: Long,
    /** The unknown colour stops here: time after now hasn't happened yet. */
    until: Long,
    unknown: Color,
    description: String,
    scrubAt: Long?,
    onScrub: (Long) -> Unit,
    modifier: Modifier = Modifier,
    markers: List<Long> = emptyList(),
    height: Dp = 28.dp,
) {
    val markerColor = MaterialTheme.colorScheme.onSurfaceVariant
    val scrubColor = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height + if (markers.isEmpty()) 0.dp else 8.dp)
            .semantics { contentDescription = description }
            .scrubbable(from, to, onScrub),
    ) {
        val scale = TimeScale(from, to, size.width)
        val top = if (markers.isEmpty()) 0f else 8.dp.toPx()
        val h = size.height - top
        drawRect(unknown, Offset(0f, top), Size(scale.x(until), h))
        bands.forEach { b ->
            val x0 = scale.x(b.start)
            val x1 = scale.x(b.end)
            // At least a hairline, so a short stretch over a long range still shows.
            drawRect(b.color, Offset(x0, top), Size(maxOf(x1 - x0, 1f), h))
        }
        val m = 4.dp.toPx()
        markers.forEach { t ->
            val x = scale.x(t)
            val p = Path().apply {
                moveTo(x - m, 0f)
                lineTo(x + m, 0f)
                lineTo(x, top - 1.dp.toPx())
                close()
            }
            drawPath(p, markerColor)
        }
        scrubLine(scale, scrubAt, scrubColor)
    }
}

/** One bar per day (month view) or month (year view), stacked by share of time. */
@Composable
fun StackedBarChart(
    bars: List<StackedBar>,
    from: Long,
    to: Long,
    unknown: Color,
    description: String,
    scrubAt: Long?,
    onScrub: (Long) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
) {
    val scrubColor = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .scrubbable(from, to, onScrub),
    ) {
        val scale = TimeScale(from, to, size.width)
        val gap = 1.dp.toPx()
        bars.forEach { bar ->
            val x0 = scale.x(bar.start) + gap / 2
            val w = maxOf(scale.x(bar.end) - scale.x(bar.start) - gap, 1f)
            drawRect(unknown, Offset(x0, 0f), Size(w, size.height))
            var y = size.height
            bar.segments.forEach { (color, fraction) ->
                val h = fraction.coerceIn(0f, 1f) * size.height
                drawRect(color, Offset(x0, y - h), Size(w, h))
                y -= h
            }
        }
        scrubLine(scale, scrubAt, scrubColor)
    }
}

/**
 * Signal in bars (0 to 4), one line per source. Raw readings draw as steps; buckets draw their
 * average with a shaded min–max band. Stretches with no readings are left as breaks.
 */
@Composable
fun SignalChart(
    lines: List<SignalLine>,
    from: Long,
    to: Long,
    now: Long,
    description: String,
    scrubAt: Long?,
    onScrub: (Long) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val scrubColor = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .scrubbable(from, to, onScrub),
    ) {
        val scale = TimeScale(from, to, size.width)
        val pad = 3.dp.toPx()
        val usable = size.height - 2 * pad
        fun y(bars: Double): Float = pad + usable * (1f - (bars / Signal.MAX_BARS).toFloat())
        for (b in 0..Signal.MAX_BARS) {
            val gy = y(b.toDouble())
            drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        val stroke = Stroke(width = 2.dp.toPx())
        lines.forEach { line ->
            if (line.points.isNotEmpty()) {
                HistoryMath.runs(line.points).forEach { run ->
                    val path = Path()
                    run.forEachIndexed { i, p ->
                        val x = scale.x(maxOf(p.at, from))
                        if (i == 0) {
                            path.moveTo(x, y(p.bars.toDouble()))
                        } else {
                            path.lineTo(x, y(run[i - 1].bars.toDouble()))
                            path.lineTo(x, y(p.bars.toDouble()))
                        }
                    }
                    // The last reading holds until the next would have been due, or now.
                    val last = run.last()
                    val until = minOf(last.at + HistoryMath.SIGNAL_GAP_MS, now, to)
                    path.lineTo(scale.x(maxOf(until, last.at)), y(last.bars.toDouble()))
                    drawPath(path, line.color, style = stroke)
                }
            } else if (line.buckets.isNotEmpty()) {
                bucketRuns(line.buckets, line.bucketMs).forEach { run ->
                    val half = line.bucketMs / 2
                    val band = Path()
                    run.forEachIndexed { i, b ->
                        val x = scale.x(b.start + half)
                        if (i == 0) band.moveTo(x, y(b.barsMax.toDouble())) else band.lineTo(x, y(b.barsMax.toDouble()))
                    }
                    run.asReversed().forEach { b -> band.lineTo(scale.x(b.start + half), y(b.barsMin.toDouble())) }
                    band.close()
                    drawPath(band, line.color.copy(alpha = 0.18f))
                    if (run.size == 1) {
                        val b = run.first()
                        drawCircle(line.color, radius = 2.5.dp.toPx(), center = Offset(scale.x(b.start + half), y(b.barsAvg)))
                    } else {
                        val avg = Path()
                        run.forEachIndexed { i, b ->
                            val x = scale.x(b.start + half)
                            if (i == 0) avg.moveTo(x, y(b.barsAvg)) else avg.lineTo(x, y(b.barsAvg))
                        }
                        drawPath(avg, line.color, style = stroke)
                    }
                }
            }
        }
        scrubLine(scale, scrubAt, scrubColor)
    }
}

/** Buckets in runs of neighbours; a missing bucket starts a new run. */
private fun bucketRuns(buckets: List<SignalBucket>, bucketMs: Long): List<List<SignalBucket>> {
    val out = mutableListOf<MutableList<SignalBucket>>()
    buckets.sortedBy { it.start }.forEach { b ->
        val run = out.lastOrNull()
        if (run == null || b.start - run.last().start > bucketMs) out += mutableListOf(b) else run += b
    }
    return out
}

/** Labels under a chart, each centred on its tick (kept inside the edges). */
@Composable
fun TimeAxis(ticks: List<Tick>, from: Long, to: Long, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.merge(TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant))
    Canvas(modifier.fillMaxWidth().height(16.dp)) {
        val scale = TimeScale(from, to, size.width)
        ticks.forEach { tick ->
            val layout = measurer.measure(tick.label, style)
            val w = layout.size.width.toFloat()
            val x = (scale.x(tick.at) - w / 2).coerceIn(0f, maxOf(size.width - w, 0f))
            drawText(layout, topLeft = Offset(x, 0f))
        }
    }
}
