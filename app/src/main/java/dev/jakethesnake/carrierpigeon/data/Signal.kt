// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import android.telephony.CellInfo
import android.telephony.SignalStrength

/**
 * Signal strength as bars, the way the status bar shows it. A null [Signal] means unknown: the
 * radio hasn't reported anything yet, or reports nothing usable.
 */
data class Signal(
    /** 0 (no signal) to [MAX_BARS]. */
    val bars: Int,
    /** Strength in dBm (negative); null when the radio doesn't report it. */
    val dbm: Int? = null,
) {
    companion object {
        const val MAX_BARS = 4

        /**
         * Scales a platform signal level from 0..[max] to 0..[MAX_BARS], rounding to the nearest
         * bar. Wi-Fi's top level varies by device, cellular's is always 4. Null when unusable.
         */
        fun fromLevel(level: Int, max: Int, dbm: Int?): Signal? {
            if (max <= 0 || level < 0) return null
            val bars = (level.coerceAtMost(max) * MAX_BARS + max / 2) / max
            return Signal(bars, dbm?.takeIf { validDbm(it) })
        }

        /** Android reports unknown dBm as [CellInfo.UNAVAILABLE] or out-of-range values such as -127. */
        fun validDbm(dbm: Int): Boolean = dbm != CellInfo.UNAVAILABLE && dbm in -140..-1

        /**
         * A SIM's signal from [ss]. The bars are [SignalStrength.getLevel], which is what the status
         * bar shows; the dBm comes from the radio at that level (on 5G NSA, LTE or NR, whichever
         * is stronger). Null when no radio reports a reading, e.g. with no service.
         */
        fun of(ss: SignalStrength?): Signal? {
            val cells = ss?.cellSignalStrengths.orEmpty()
            if (cells.isEmpty()) return null
            val level = ss!!.level
            val cell = cells.firstOrNull { it.level == level } ?: cells.maxBy { it.level }
            return fromLevel(level, MAX_BARS, cell.dbm)
        }
    }
}
