// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.ui

import dev.jakethesnake.carrierpigeon.data.Access
import dev.jakethesnake.carrierpigeon.data.NetworkKind
import dev.jakethesnake.carrierpigeon.data.history.AccessValue
import dev.jakethesnake.carrierpigeon.data.history.HistoryMath
import dev.jakethesnake.carrierpigeon.data.history.HistoryStore
import dev.jakethesnake.carrierpigeon.data.history.HistoryWindow
import dev.jakethesnake.carrierpigeon.data.history.SignalBucket
import dev.jakethesnake.carrierpigeon.data.history.SignalSource
import dev.jakethesnake.carrierpigeon.data.history.SimRecord
import dev.jakethesnake.carrierpigeon.data.history.Span
import java.time.ZoneId

/** What carried the internet, as the History page colours it. */
sealed interface AccessKey {
    data object Wifi : AccessKey

    /** A SIM by subscription id; -1 when the SIM isn't known. */
    data class Sim(val subId: Int) : AccessKey
    data object Ethernet : AccessKey

    /** Bluetooth, USB or another transport. */
    data object Other : AccessKey
    data object None : AccessKey

    companion object {
        fun of(v: AccessValue): AccessKey = when (v.access) {
            Access.WIFI -> Wifi
            Access.CELLULAR -> Sim(v.subId)
            Access.ETHERNET -> Ethernet
            Access.BLUETOOTH, Access.USB, Access.OTHER -> Other
            Access.NONE -> None
        }
    }
}

/** One signal source's average per bar of the signal chart; a bar with no readings is missing. */
data class SignalSeries(
    val source: SignalSource,
    /** Each bucket's start is the start of its bar in [HistoryData.slots]. */
    val buckets: List<SignalBucket>,
)

/** Everything the History page draws for one window. */
data class HistoryData(
    val window: HistoryWindow,
    val now: Long,
    val access: List<Span<AccessKey>>,
    /** When an automatic data switch began (data moved off the chosen data SIM). */
    val switches: List<Long>,
    val vpn: List<Span<Boolean>>,
    val network: Map<Int, List<Span<NetworkKind?>>>,
    /** Time per network type per SIM, from detail and hourly rollups together. */
    val networkTotals: Map<Int, Map<NetworkKind?, Long>>,
    /** Where each bar of the charts starts; the first is the window's start, the last runs to its end. */
    val slots: List<Long>,
    val signal: List<SignalSeries>,
    val sims: Map<Int, SimRecord>,
    val firstRecord: Long?,
)

/** Reads one window of history from the database. Blocking: call it off the main thread. */
object HistoryLoader {
    fun load(store: HistoryStore, window: HistoryWindow, now: Long, liveSince: Long): HistoryData {
        val from = window.from
        val to = window.to
        val accessValues = HistoryMath.knownSpans(store.accessPeriods(from, to), from, to, now, liveSince)
        val switches = accessValues.zipWithNext()
            .filter { (a, b) -> b.value.temporarySwitch && !(a.value.temporarySwitch && a.value.subId == b.value.subId) }
            .map { it.second.start } +
            listOfNotNull(accessValues.firstOrNull()?.takeIf { it.value.temporarySwitch && it.start > from }?.start)
        val network = store.networkPeriods(from, to).mapValues { (_, ps) -> HistoryMath.knownSpans(ps, from, to, now, liveSince) }
        val networkTotals = LinkedHashMap<Int, MutableMap<NetworkKind?, Long>>()
        network.forEach { (subId, spans) ->
            HistoryMath.totals(spans).forEach { (k, ms) -> networkTotals.getOrPut(subId) { LinkedHashMap() }.merge(k, ms) { a, b -> a + b } }
        }
        store.networkHourTotals(from, to).forEach { (subId, m) ->
            m.forEach { (k, ms) -> networkTotals.getOrPut(subId) { LinkedHashMap() }.merge(k, ms) { a, b -> a + b } }
        }
        val slots = HistoryMath.slotStarts(window, ZoneId.systemDefault())
        val signal = store.signalSources(from, to).sortedBy { it.key }.map { source ->
            // Older readings are already hourly (the hour the window starts in included); newer
            // ones go into the chart's bars one by one.
            val hourly = HistoryMath.intoSlots(store.signalHours(source, from - HistoryMath.HOUR_MS + 1, to), slots, to, HistoryMath.HOUR_MS)
            val raw = HistoryMath.intoSlots(store.signalPoints(source, from, to).map(HistoryMath::asBucket), slots, to, 1)
            SignalSeries(source, HistoryMath.intoSlots(hourly + raw, slots, to, 1))
        }
        return HistoryData(
            window = window,
            now = now,
            access = accessValues.map { Span(it.start, it.end, AccessKey.of(it.value)) },
            switches = switches.sorted(),
            vpn = HistoryMath.knownSpans(store.vpnPeriods(from, to), from, to, now, liveSince),
            network = network,
            networkTotals = networkTotals,
            slots = slots,
            signal = signal,
            sims = store.sims(),
            firstRecord = store.firstRecord(),
        )
    }
}
