// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.jakethesnake.carrierpigeon.data.Access
import dev.jakethesnake.carrierpigeon.data.DataStatus
import dev.jakethesnake.carrierpigeon.data.NetworkKind

/** How the internet was reached, as one access period stores it. */
data class AccessValue(
    val access: Access,
    /** The SIM carrying data when [access] is cellular; -1 otherwise or when unknown. */
    val subId: Int,
    /** The data SIM chosen in Settings. */
    val defaultSubId: Int,
    val status: DataStatus,
) {
    /** Data had moved off the chosen data SIM (automatic switch, data during calls). */
    val temporarySwitch: Boolean
        get() = access == Access.CELLULAR && subId >= 0 && defaultSubId >= 0 && subId != defaultSubId
}

/** A SIM as history last saw it. */
data class SimRecord(
    val subId: Int,
    /** Display name or carrier name; null when unknown. */
    val label: String?,
    /** 0-based, or -1. */
    val slotIndex: Int,
    val isEmbedded: Boolean,
    /** ARGB Android assigns to the SIM, or 0. */
    val iconTint: Int,
)

/**
 * Reads and writes `history.db`. All calls block, so call them off the main thread. One instance
 * per process ([get]), so every caller shares the same connection.
 */
class HistoryStore private constructor(context: Context) {
    private val helper = HistoryDb(context)
    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** Last stored signal reading per source, so throttling doesn't need a query per reading. */
    private val lastSignal = HashMap<String, SignalPoint?>()

    // ---- writing ----

    /**
     * Records the state seen at [now]: extends the open periods that still hold, closes those that
     * changed and opens new ones. [networkKinds] holds each active SIM's network type.
     *
     * @param liveSince when this process started watching; see [HistoryMath.knownSpans].
     */
    @Synchronized
    fun recordState(access: AccessValue, vpn: Boolean, networkKinds: Map<Int, NetworkKind?>, now: Long, liveSince: Long) {
        db.inTransaction {
            advance(
                table = "access_period",
                subId = null,
                values = linkedMapOf(
                    "access" to access.access.name,
                    "sub_id" to access.subId,
                    "default_sub_id" to access.defaultSubId,
                    "data_status" to access.status.name,
                ),
                now = now,
                liveSince = liveSince,
            )
            advance("vpn_period", null, linkedMapOf("vpn" to if (vpn) 1 else 0), now, liveSince)
            networkKinds.forEach { (subId, kind) ->
                advance("network_period", subId, linkedMapOf("sub_id" to subId, "network_kind" to kind?.name), now, liveSince)
            }
            // SIMs no longer active: their network periods end here.
            val open = openNetworkSubIds() - networkKinds.keys
            open.forEach { closeOpen("network_period", "sub_id = ?", arrayOf(it.toString()), now, liveSince) }
        }
    }

    /** Remembers [sims]' names and colours for history's labels. */
    @Synchronized
    fun recordSims(sims: List<SimRecord>, now: Long) {
        if (sims.isEmpty()) return
        db.inTransaction {
            sims.forEach { s ->
                val cv = ContentValues().apply {
                    put("sub_id", s.subId)
                    put("slot_index", s.slotIndex)
                    put("is_embedded", if (s.isEmbedded) 1 else 0)
                    put("icon_tint", s.iconTint)
                    put("last_seen", now)
                    if (s.label != null) put("label", s.label)
                }
                // Keep the last known name when this reading has none (no phone access mid-switch).
                val updated = if (s.label == null) {
                    update("sim", cv, "sub_id = ?", arrayOf(s.subId.toString()))
                } else {
                    0
                }
                if (updated == 0) insertWithOnConflict("sim", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    /**
     * Stores a signal reading if [HistoryMath.shouldRecordSignal] says it is worth keeping.
     * @return true if it was stored.
     */
    @Synchronized
    fun recordSignal(source: SignalSource, bars: Int, dbm: Int?, now: Long): Boolean {
        val key = source.key
        val last = if (lastSignal.containsKey(key)) lastSignal[key] else latestSignal(key).also { lastSignal[key] = it }
        if (!HistoryMath.shouldRecordSignal(last, bars, dbm, now)) return false
        val cv = ContentValues().apply {
            put("source", key)
            put("at_ms", now)
            put("bars", bars)
            if (dbm != null) put("dbm", dbm) else putNull("dbm")
        }
        db.insertWithOnConflict("signal_sample", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        lastSignal[key] = SignalPoint(now, bars, dbm)
        return true
    }

    /** Ends every open period at [now], for when recording is switched off. */
    @Synchronized
    fun closeAll(now: Long, liveSince: Long) {
        db.inTransaction {
            listOf("access_period", "vpn_period", "network_period").forEach { closeOpen(it, null, null, now, liveSince) }
        }
    }

    /**
     * Rolls raw signal samples and network-type periods older than [HistoryMath.RAW_RETENTION_MS]
     * up into hourly totals, then deletes anything older than [retentionMs]. Safe to repeat: each
     * step deletes what it rolled up in the same transaction.
     */
    @Synchronized
    fun maintain(now: Long, retentionMs: Long) {
        val keepFrom = now - retentionMs
        val rawCutoff = (now - minOf(HistoryMath.RAW_RETENTION_MS, retentionMs)) / HistoryMath.HOUR_MS * HistoryMath.HOUR_MS
        db.inTransaction {
            execSQL(
                """INSERT OR REPLACE INTO signal_hour (source, hour_ms, samples, bars_avg, bars_min, bars_max, dbm_avg)
                   SELECT source, (at_ms / ${HistoryMath.HOUR_MS}) * ${HistoryMath.HOUR_MS} AS h,
                          COUNT(*), AVG(bars), MIN(bars), MAX(bars), AVG(dbm)
                   FROM signal_sample WHERE at_ms < ? GROUP BY source, h""",
                arrayOf<Any>(rawCutoff),
            )
            delete("signal_sample", "at_ms < ?", arrayOf(rawCutoff.toString()))

            val old = networkPeriodsWhere("end_ms IS NOT NULL AND end_ms < ?", arrayOf(rawCutoff.toString()))
            old.forEach { (subId, periods) ->
                val spans = HistoryMath.knownSpans(periods, Long.MIN_VALUE, Long.MAX_VALUE, now, Long.MAX_VALUE)
                HistoryMath.secondsPerHour(spans).forEach { (key, seconds) ->
                    execSQL(
                        """INSERT INTO network_hour (sub_id, hour_ms, network_kind, seconds) VALUES (?, ?, ?, ?)
                           ON CONFLICT(sub_id, hour_ms, network_kind) DO UPDATE SET seconds = seconds + excluded.seconds""",
                        arrayOf<Any>(subId, key.first, key.second?.name ?: "", seconds),
                    )
                }
            }
            delete("network_period", "end_ms IS NOT NULL AND end_ms < ?", arrayOf(rawCutoff.toString()))

            val keep = arrayOf(keepFrom.toString())
            listOf("access_period", "vpn_period").forEach { delete(it, "end_ms IS NOT NULL AND end_ms < ?", keep) }
            delete("signal_hour", "hour_ms < ?", keep)
            delete("network_hour", "hour_ms < ?", keep)
            delete(
                "sim",
                "last_seen < ? AND sub_id NOT IN (SELECT sub_id FROM access_period UNION SELECT sub_id FROM network_period " +
                    "UNION SELECT sub_id FROM network_hour)",
                keep,
            )
        }
    }

    /** Deletes all history. */
    @Synchronized
    fun clear() {
        db.inTransaction {
            listOf("access_period", "vpn_period", "network_period", "signal_sample", "signal_hour", "network_hour", "sim")
                .forEach { delete(it, null, null) }
        }
        lastSignal.clear()
    }

    // ---- reading ----

    /** True once anything has been recorded. */
    fun hasAny(): Boolean = db.rawQuery(
        "SELECT EXISTS(SELECT 1 FROM access_period) OR EXISTS(SELECT 1 FROM signal_sample) OR EXISTS(SELECT 1 FROM signal_hour)",
        null,
    ).use { it.moveToFirst() && it.getInt(0) == 1 }

    /** When history begins, or null when there is none. */
    fun firstRecord(): Long? = db.rawQuery(
        """SELECT MIN(t) FROM (
             SELECT MIN(start_ms) AS t FROM access_period
             UNION ALL SELECT MIN(at_ms) FROM signal_sample
             UNION ALL SELECT MIN(hour_ms) FROM signal_hour)""",
        null,
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    fun accessPeriods(from: Long, to: Long): List<Period<AccessValue>> = db.rawQuery(
        "SELECT start_ms, confirmed_until, end_ms, access, sub_id, default_sub_id, data_status FROM access_period " +
            "WHERE start_ms < ? AND (end_ms IS NULL OR end_ms > ?) ORDER BY start_ms",
        arrayOf(to.toString(), from.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val access = Access.entries.firstOrNull { it.name == c.getString(3) } ?: continue
                val status = DataStatus.entries.firstOrNull { it.name == c.getString(6) } ?: DataStatus.OK
                add(Period(c.getLong(0), c.getLong(1), c.longOrNull(2), AccessValue(access, c.getInt(4), c.getInt(5), status)))
            }
        }
    }

    fun vpnPeriods(from: Long, to: Long): List<Period<Boolean>> = db.rawQuery(
        "SELECT start_ms, confirmed_until, end_ms, vpn FROM vpn_period WHERE start_ms < ? AND (end_ms IS NULL OR end_ms > ?) ORDER BY start_ms",
        arrayOf(to.toString(), from.toString()),
    ).use { c ->
        buildList { while (c.moveToNext()) add(Period(c.getLong(0), c.getLong(1), c.longOrNull(2), c.getInt(3) == 1)) }
    }

    /** Each SIM's network-type periods overlapping [from]..[to]. */
    fun networkPeriods(from: Long, to: Long): Map<Int, List<Period<NetworkKind?>>> =
        db.networkPeriodsWhere("start_ms < ? AND (end_ms IS NULL OR end_ms > ?)", arrayOf(to.toString(), from.toString()))

    /** Seconds per network type per SIM from the hourly rollups in [from]..[to]. */
    fun networkHourTotals(from: Long, to: Long): Map<Int, Map<NetworkKind?, Long>> = db.rawQuery(
        "SELECT sub_id, network_kind, SUM(seconds) FROM network_hour WHERE hour_ms >= ? AND hour_ms < ? GROUP BY sub_id, network_kind",
        arrayOf(from.toString(), to.toString()),
    ).use { c ->
        val out = LinkedHashMap<Int, MutableMap<NetworkKind?, Long>>()
        while (c.moveToNext()) {
            val kind = NetworkKind.entries.firstOrNull { it.name == c.getString(1) }
            out.getOrPut(c.getInt(0)) { LinkedHashMap() }.merge(kind, c.getLong(2) * 1000) { a, b -> a + b }
        }
        out
    }

    /** Signal sources with readings in [from]..[to]. */
    fun signalSources(from: Long, to: Long): List<SignalSource> = db.rawQuery(
        "SELECT source FROM signal_sample WHERE at_ms >= ? AND at_ms < ? " +
            "UNION SELECT source FROM signal_hour WHERE hour_ms >= ? AND hour_ms < ?",
        arrayOf(from.toString(), to.toString(), from.toString(), to.toString()),
    ).use { c -> buildList { while (c.moveToNext()) SignalSource.parse(c.getString(0))?.let { add(it) } } }

    /** Raw readings in [from]..[to], plus the one before [from] so the line starts at the edge. */
    fun signalPoints(source: SignalSource, from: Long, to: Long): List<SignalPoint> {
        val key = source.key
        val before = db.rawQuery(
            "SELECT at_ms, bars, dbm FROM signal_sample WHERE source = ? AND at_ms < ? ORDER BY at_ms DESC LIMIT 1",
            arrayOf(key, from.toString()),
        ).use { c -> if (c.moveToFirst()) c.signalPoint() else null }
        val inside = db.rawQuery(
            "SELECT at_ms, bars, dbm FROM signal_sample WHERE source = ? AND at_ms >= ? AND at_ms < ? ORDER BY at_ms",
            arrayOf(key, from.toString(), to.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.signalPoint()) } }
        return listOfNotNull(before) + inside
    }

    /** Hourly rollups in [from]..[to]. */
    fun signalHours(source: SignalSource, from: Long, to: Long): List<SignalBucket> = db.rawQuery(
        "SELECT hour_ms, samples, bars_avg, bars_min, bars_max, dbm_avg FROM signal_hour " +
            "WHERE source = ? AND hour_ms >= ? AND hour_ms < ? ORDER BY hour_ms",
        arrayOf(source.key, from.toString(), to.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(SignalBucket(c.getLong(0), c.getInt(1), c.getDouble(2), c.getInt(3), c.getInt(4), if (c.isNull(5)) null else c.getDouble(5)))
            }
        }
    }

    fun sims(): Map<Int, SimRecord> = db.rawQuery("SELECT sub_id, label, slot_index, is_embedded, icon_tint FROM sim", null).use { c ->
        buildMap {
            while (c.moveToNext()) {
                val id = c.getInt(0)
                put(id, SimRecord(id, if (c.isNull(1)) null else c.getString(1), c.getInt(2), c.getInt(3) == 1, c.getInt(4)))
            }
        }
    }

    // ---- helpers ----

    /**
     * Extends the open row in [table] (for [subId] when given) if it holds [values], otherwise
     * closes it and opens a new one.
     */
    private fun SQLiteDatabase.advance(
        table: String,
        subId: Int?,
        values: LinkedHashMap<String, Any?>,
        now: Long,
        liveSince: Long,
    ) {
        val where = if (subId != null) "end_ms IS NULL AND sub_id = ?" else "end_ms IS NULL"
        val args = if (subId != null) arrayOf(subId.toString()) else null
        val cols = values.keys.joinToString(", ")
        val open = rawQuery("SELECT id, confirmed_until, $cols FROM $table WHERE $where ORDER BY start_ms DESC LIMIT 1", args).use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                val same = values.values.withIndex().all { (i, v) ->
                    val stored = if (c.isNull(i + 2)) null else c.getString(i + 2)
                    stored == v?.toString()
                }
                Triple(c.getLong(0), c.getLong(1), same)
            }
        }
        if (open != null) {
            val (id, confirmed, same) = open
            if (same) {
                if (now - confirmed >= CONFIRM_SPACING_MS || confirmed < liveSince || now < confirmed) {
                    update(table, ContentValues().apply { put("confirmed_until", now) }, "id = ?", arrayOf(id.toString()))
                }
                return
            }
            update(table, closing(confirmed, now, liveSince), "id = ?", arrayOf(id.toString()))
        }
        val cv = ContentValues().apply {
            put("start_ms", now)
            put("confirmed_until", now)
            values.forEach { (k, v) ->
                when (v) {
                    null -> putNull(k)
                    is Int -> put(k, v)
                    is Long -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }
        insert(table, null, cv)
    }

    private fun SQLiteDatabase.closeOpen(table: String, where: String?, args: Array<String>?, now: Long, liveSince: Long) {
        val clause = if (where == null) "end_ms IS NULL" else "end_ms IS NULL AND $where"
        val rows = rawQuery("SELECT id, confirmed_until FROM $table WHERE $clause", args).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) }
        }
        rows.forEach { (id, confirmed) -> update(table, closing(confirmed, now, liveSince), "id = ?", arrayOf(id.toString())) }
    }

    /**
     * Ends a period at [now]. If this process confirmed it, the live listeners were watching all
     * along, so it held right up to [now]; otherwise the time since its last confirmation stays unknown.
     */
    private fun closing(confirmed: Long, now: Long, liveSince: Long) = ContentValues().apply {
        put("end_ms", now)
        put("confirmed_until", if (confirmed >= liveSince) now else minOf(confirmed, now))
    }

    private fun SQLiteDatabase.openNetworkSubIds(): Set<Int> =
        rawQuery("SELECT sub_id FROM network_period WHERE end_ms IS NULL", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getInt(0)) }
        }

    private fun SQLiteDatabase.networkPeriodsWhere(where: String, args: Array<String>): Map<Int, List<Period<NetworkKind?>>> =
        rawQuery("SELECT sub_id, start_ms, confirmed_until, end_ms, network_kind FROM network_period WHERE $where ORDER BY start_ms", args)
            .use { c ->
                val out = LinkedHashMap<Int, MutableList<Period<NetworkKind?>>>()
                while (c.moveToNext()) {
                    val kind = if (c.isNull(4)) null else NetworkKind.entries.firstOrNull { it.name == c.getString(4) }
                    out.getOrPut(c.getInt(0)) { mutableListOf() } += Period(c.getLong(1), c.getLong(2), c.longOrNull(3), kind)
                }
                out
            }

    private fun latestSignal(key: String): SignalPoint? = db.rawQuery(
        "SELECT at_ms, bars, dbm FROM signal_sample WHERE source = ? ORDER BY at_ms DESC LIMIT 1",
        arrayOf(key),
    ).use { c -> if (c.moveToFirst()) c.signalPoint() else null }

    private fun Cursor.signalPoint() = SignalPoint(getLong(0), getInt(1), if (isNull(2)) null else getInt(2))

    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    companion object {
        /** An unchanged state moves its period's confirmation forward at most this often. */
        private const val CONFIRM_SPACING_MS = 60_000L

        @Volatile
        private var instance: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) { instance ?: HistoryStore(context.applicationContext).also { instance = it } }
    }
}
