// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The history database, `history.db` in the app's private storage. Times are UTC epoch
 * milliseconds; enums are stored by name, like StateStore, so nothing in it is translated text.
 * See [HistoryStore] for what is written when.
 */
internal class HistoryDb(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        // A SIM as it was last seen, so history still has a name after the SIM is removed.
        db.execSQL(
            """CREATE TABLE sim (
                sub_id INTEGER PRIMARY KEY,
                label TEXT,
                slot_index INTEGER NOT NULL,
                is_embedded INTEGER NOT NULL,
                icon_tint INTEGER NOT NULL,
                last_seen INTEGER NOT NULL
            )""",
        )
        // How the internet was reached. At most one open row (end_ms NULL).
        db.execSQL(
            """CREATE TABLE access_period (
                id INTEGER PRIMARY KEY,
                start_ms INTEGER NOT NULL,
                confirmed_until INTEGER NOT NULL,
                end_ms INTEGER,
                access TEXT NOT NULL,
                sub_id INTEGER NOT NULL,
                default_sub_id INTEGER NOT NULL,
                data_status TEXT NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE vpn_period (
                id INTEGER PRIMARY KEY,
                start_ms INTEGER NOT NULL,
                confirmed_until INTEGER NOT NULL,
                end_ms INTEGER,
                vpn INTEGER NOT NULL
            )""",
        )
        // Each SIM's network type; network_kind NULL means no service. At most one open row per SIM.
        db.execSQL(
            """CREATE TABLE network_period (
                id INTEGER PRIMARY KEY,
                sub_id INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                confirmed_until INTEGER NOT NULL,
                end_ms INTEGER,
                network_kind TEXT
            )""",
        )
        // Raw signal readings, throttled (HistoryMath.shouldRecordSignal). source: "sim:<subId>" or "wifi".
        db.execSQL(
            """CREATE TABLE signal_sample (
                source TEXT NOT NULL,
                at_ms INTEGER NOT NULL,
                bars INTEGER NOT NULL,
                dbm INTEGER,
                PRIMARY KEY (source, at_ms)
            ) WITHOUT ROWID""",
        )
        // Hourly rollups of signal_sample, written before the raw rows are deleted.
        db.execSQL(
            """CREATE TABLE signal_hour (
                source TEXT NOT NULL,
                hour_ms INTEGER NOT NULL,
                samples INTEGER NOT NULL,
                bars_avg REAL NOT NULL,
                bars_min INTEGER NOT NULL,
                bars_max INTEGER NOT NULL,
                dbm_avg REAL,
                PRIMARY KEY (source, hour_ms)
            ) WITHOUT ROWID""",
        )
        // Seconds on each network type per SIM per hour, rolled up from network_period. '' = no service.
        db.execSQL(
            """CREATE TABLE network_hour (
                sub_id INTEGER NOT NULL,
                hour_ms INTEGER NOT NULL,
                network_kind TEXT NOT NULL,
                seconds INTEGER NOT NULL,
                PRIMARY KEY (sub_id, hour_ms, network_kind)
            ) WITHOUT ROWID""",
        )
        db.execSQL("CREATE INDEX access_period_start ON access_period(start_ms)")
        db.execSQL("CREATE INDEX vpn_period_start ON vpn_period(start_ms)")
        db.execSQL("CREATE INDEX network_period_start ON network_period(start_ms)")
        db.execSQL("CREATE INDEX signal_sample_at ON signal_sample(at_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first; later versions migrate here.
    }

    companion object {
        const val NAME = "history.db"
        const val VERSION = 1
    }
}
