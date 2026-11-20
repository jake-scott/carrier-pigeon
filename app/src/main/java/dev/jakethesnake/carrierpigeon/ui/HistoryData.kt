// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.ui

import dev.jakethesnake.carrierpigeon.data.Access
import dev.jakethesnake.carrierpigeon.data.NetworkKind
import dev.jakethesnake.carrierpigeon.data.history.AccessValue
import dev.jakethesnake.carrierpigeon.data.history.HistoryMath
import dev.jakethesnake.carrierpigeon.data.history.HistoryRange
import dev.jakethesnake.carrierpigeon.data.history.HistoryStore
import dev.jakethesnake.carrierpigeon.data.history.HistoryWindow
import dev.jakethesnake.carrierpigeon.data.history.SignalBucket
import dev.jakethesnake.carrierpigeon.data.history.SignalPoint
import dev.jakethesnake.carrierpigeon.data.history.SignalSource
import dev.jakethesnake.carrierpigeon.data.history.SimRecord
import dev.jakethesnake.carrierpigeon.data.history.Span

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

/** One signal source's readings for the window: raw [points] for a day, [buckets] otherwise. */
data class SignalSeries(
    val source: SignalSource,
    val points: List<SignalPoint>,
    val buckets: List<SignalBucket>,
    val bucketMs: Long,
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
        val bucketMs = HistoryMath.bucketMs(window.range)
        val signal = store.signalSources(from - HistoryMath.SIGNAL_GAP_MS, to).sortedBy { it.key }.map { source ->
            val points = store.signalPoints(source, from, to)
            if (window.range == HistoryRange.DAY) {
                SignalSeries(source, points, emptyList(), 0L)
            } else {
                val hourly = store.signalHours(source, from, to) +
                    HistoryMath.bucketize(points, from, HistoryMath.HOUR_MS)
                SignalSeries(source, points, HistoryMath.regroup(hourly, from, bucketMs), bucketMs)
            }
        }
        return HistoryData(
            window = window,
            now = now,
            access = accessValues.map { Span(it.start, it.end, AccessKey.of(it.value)) },
            switches = switches.sorted(),
            vpn = HistoryMath.knownSpans(store.vpnPeriods(from, to), from, to, now, liveSince),
            network = network,
            networkTotals = networkTotals,
            signal = signal,
            sims = store.sims(),
            firstRecord = store.firstRecord(),
        )
    }
}
