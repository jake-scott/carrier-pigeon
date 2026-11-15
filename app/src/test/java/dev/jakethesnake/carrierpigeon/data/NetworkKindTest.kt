// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkKindTest {
    @Test
    fun lteWithOverrides() {
        val lte = TelephonyManager.NETWORK_TYPE_LTE
        assertEquals(NetworkKind.LTE, NetworkKind.of(lte, null))
        assertEquals(NetworkKind.LTE, NetworkKind.of(lte, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
        assertEquals(NetworkKind.LTE_CA, NetworkKind.of(lte, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA))
        assertEquals(NetworkKind.LTE_A_PRO, NetworkKind.of(lte, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO))
        assertEquals(NetworkKind.NR_NSA, NetworkKind.of(lte, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA))
        assertEquals(NetworkKind.NR_NSA_HIGH_BAND, NetworkKind.of(lte, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED))
    }

    @Test
    fun standalone() {
        val nr = TelephonyManager.NETWORK_TYPE_NR
        assertEquals(NetworkKind.NR_SA, NetworkKind.of(nr, null))
        assertEquals(NetworkKind.NR_SA_HIGH_BAND, NetworkKind.of(nr, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED))
    }

    @Test
    fun olderGenerationsAndUnknown() {
        assertEquals(NetworkKind.GEN_3G, NetworkKind.of(TelephonyManager.NETWORK_TYPE_HSPAP, null))
        assertEquals(NetworkKind.GEN_2G, NetworkKind.of(TelephonyManager.NETWORK_TYPE_EDGE, null))
        assertNull(NetworkKind.of(TelephonyManager.NETWORK_TYPE_UNKNOWN, null))
    }

    @Test
    fun badgesMatchTheStatusBar() {
        assertEquals("5G NSA", NetworkKind.NR_NSA.badge)
        assertEquals("LTE+", NetworkKind.LTE_CA.badge)
    }
}
