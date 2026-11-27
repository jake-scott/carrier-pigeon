// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchLogTest {
    @Test
    fun `log values escape delimiters and control characters`() {
        assertEquals(
            """SIM \"one\"\\two\nforged\rline\u0009end""",
            "SIM \"one\"\\two\nforged\rline\tend".escapeLogValue(),
        )
    }

    @Test
    fun `ordinary labels stay readable`() {
        assertEquals("Personal eSIM", "Personal eSIM".escapeLogValue())
    }
}
