// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** User choices from the app screen. */
class AppSettings(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    /** Show what is underneath the network badge, e.g. "LTE + NR" after "5G NSA". Off by default. */
    val showNetworkDetail: Flow<Boolean> = store.data.map { it[SHOW_NETWORK_DETAIL] ?: false }

    suspend fun showNetworkDetail(): Boolean = showNetworkDetail.first()

    suspend fun setShowNetworkDetail(show: Boolean) {
        store.edit { it[SHOW_NETWORK_DETAIL] = show }
    }

    /**
     * True once we have shown the phone-access prompt. Android reports a permanent "don't ask again"
     * the same way as "never asked", so this tells the two apart.
     */
    suspend fun askedForPhoneAccess(): Boolean = store.data.first()[ASKED_PHONE_ACCESS] ?: false

    suspend fun setAskedForPhoneAccess() {
        store.edit { it[ASKED_PHONE_ACCESS] = true }
    }

    /** True once we have shown the location prompt (for the Wi-Fi name). See [askedForPhoneAccess]. */
    suspend fun askedForLocationAccess(): Boolean = store.data.first()[ASKED_LOCATION_ACCESS] ?: false

    suspend fun setAskedForLocationAccess() {
        store.edit { it[ASKED_LOCATION_ACCESS] = true }
    }

    /** Record connection, VPN and signal history (see data/history). On by default. */
    val historyEnabled: Flow<Boolean> = store.data.map { it[HISTORY_ENABLED] ?: true }

    suspend fun historyEnabled(): Boolean = historyEnabled.first()

    suspend fun setHistoryEnabled(enabled: Boolean) {
        store.edit { it[HISTORY_ENABLED] = enabled }
    }

    /** How many days of history to keep: one of [HISTORY_RETENTION_CHOICES]. */
    val historyRetentionDays: Flow<Int> = store.data.map { p ->
        p[HISTORY_RETENTION_DAYS]?.takeIf { it in HISTORY_RETENTION_CHOICES } ?: DEFAULT_HISTORY_RETENTION_DAYS
    }

    suspend fun historyRetentionDays(): Int = historyRetentionDays.first()

    suspend fun setHistoryRetentionDays(days: Int) {
        store.edit { it[HISTORY_RETENTION_DAYS] = days }
    }

    /** Language tags the widget was last drawn in, so a language change can redraw it. */
    suspend fun widgetLanguage(): String? = store.data.first()[WIDGET_LANGUAGE]

    suspend fun setWidgetLanguage(tags: String) {
        store.edit { it[WIDGET_LANGUAGE] = tags }
    }

    companion object {
        /** The History page's "Keep history for" choices, in days. */
        val HISTORY_RETENTION_CHOICES = listOf(31, 92, 366)
        const val DEFAULT_HISTORY_RETENTION_DAYS = 366

        private val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        private val HISTORY_RETENTION_DAYS = intPreferencesKey("history_retention_days")
        private val SHOW_NETWORK_DETAIL = booleanPreferencesKey("show_network_detail")
        private val ASKED_PHONE_ACCESS = booleanPreferencesKey("asked_phone_access")
        private val ASKED_LOCATION_ACCESS = booleanPreferencesKey("asked_location_access")
        private val WIDGET_LANGUAGE = stringPreferencesKey("widget_language")
    }
}
