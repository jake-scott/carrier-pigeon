// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jakethesnake.carrierpigeon.R
import dev.jakethesnake.carrierpigeon.data.Signal

/**
 * Four rising bars, filled up to [signal]'s strength like the status bar. When the signal is
 * unknown every bar is drawn empty, and screen readers say "Signal strength unknown".
 */
@Composable
fun SignalBars(signal: Signal?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val empty = MaterialTheme.colorScheme.outlineVariant
    val description = if (signal == null) {
        stringResource(R.string.signal_unknown)
    } else {
        stringResource(R.string.signal_bars, signal.bars, Signal.MAX_BARS)
    }
    Canvas(modifier.size(width = 22.dp, height = 18.dp).semantics { contentDescription = description }) {
        val count = Signal.MAX_BARS
        val gap = size.width * 0.1f
        val barWidth = (size.width - gap * (count - 1)) / count
        repeat(count) { i ->
            val height = size.height * (i + 1) / count
            val filled = signal != null && i < signal.bars
            drawRoundRect(
                color = if (filled) color else empty,
                topLeft = Offset(i * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 4),
            )
        }
    }
}
