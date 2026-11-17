// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalTest {
    @Test
    fun fourLevelsMapStraightToBars() {
        (0..4).forEach { level -> assertEquals(level, Signal.fromLevel(level, 4, -80)!!.bars) }
    }

    @Test
    fun otherScalesRoundToNearestBar() {
        // Some phones report Wi-Fi on a 0..5 scale.
        assertEquals(listOf(0, 1, 2, 2, 3, 4), (0..5).map { Signal.fromLevel(it, 5, -60)!!.bars })
        assertEquals(4, Signal.fromLevel(9, 5, -40)!!.bars)
    }

    @Test
    fun unusableLevelsAreUnknown() {
        assertNull(Signal.fromLevel(-1, 4, -80))
        assertNull(Signal.fromLevel(2, 0, -80))
    }

    @Test
    fun invalidDbmIsDropped() {
        assertEquals(-95, Signal.fromLevel(2, 4, -95)!!.dbm)
        assertNull(Signal.fromLevel(2, 4, Int.MAX_VALUE)!!.dbm)
        assertNull(Signal.fromLevel(2, 4, 0)!!.dbm)
        assertNull(Signal.fromLevel(2, 4, -200)!!.dbm)
        assertNull(Signal.fromLevel(2, 4, null)!!.dbm)
    }

    @Test
    fun noReadingIsUnknown() {
        assertNull(Signal.of(null))
    }
}
