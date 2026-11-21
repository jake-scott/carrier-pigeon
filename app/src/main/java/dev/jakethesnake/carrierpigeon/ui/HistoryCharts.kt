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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jakethesnake.carrierpigeon.data.Signal
import dev.jakethesnake.carrierpigeon.data.history.SignalBucket

/** A coloured stretch of a band chart. */
data class Band(val start: Long, val end: Long, val color: Color)

/** One stacked bar: [segments] are fractions of the bar's time, in drawing order; the rest is unknown. */
data class StackedBar(val start: Long, val end: Long, val segments: List<Pair<Color, Float>>)

/** One source's bars on the signal chart: its average signal per slot, in its colour. */
data class SignalBars(val color: Color, val buckets: List<SignalBucket>)

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
 * Average signal, 0 to 4 bars, as one bar per slot ([slots] are their starts; the last runs to
 * [to]). With several sources each slot holds a bar per source, side by side. A slot with no
 * readings has no bar.
 */
@Composable
fun SignalBarChart(
    series: List<SignalBars>,
    slots: List<Long>,
    from: Long,
    to: Long,
    description: String,
    scrubAt: Long?,
    onScrub: (Long) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val scrubColor = MaterialTheme.colorScheme.onSurface
    val byStart = series.map { s -> s.buckets.associateBy { it.start } }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .scrubbable(from, to, onScrub),
    ) {
        val scale = TimeScale(from, to, size.width)
        val top = 2.dp.toPx()
        val usable = size.height - top
        for (b in 0..Signal.MAX_BARS) {
            val gy = size.height - usable * b / Signal.MAX_BARS
            drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        val minHeight = 1.5.dp.toPx()
        slots.forEachIndexed { i, start ->
            val x0 = scale.x(start)
            val slotW = scale.x(slots.getOrNull(i + 1) ?: to) - x0
            val gap = minOf(2.dp.toPx(), slotW * 0.25f)
            val w = (slotW - gap) / maxOf(series.size, 1)
            // A hairline between neighbouring sources' bars in the same slot.
            val split = if (series.size > 1) minOf(1.dp.toPx(), w * 0.3f) else 0f
            series.forEachIndexed { k, s ->
                val bucket = byStart[k][start] ?: return@forEachIndexed
                // 0 bars still gets a sliver, so "no signal" differs from "not recorded".
                val h = maxOf((bucket.barsAvg / Signal.MAX_BARS).toFloat() * usable, minHeight)
                drawRect(s.color, Offset(x0 + gap / 2 + k * w, size.height - h), Size(maxOf(w - split, 1f), h))
            }
        }
        scrubLine(scale, scrubAt, scrubColor)
    }
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
