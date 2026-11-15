// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    private companion object {
        val SHOW_NETWORK_DETAIL = booleanPreferencesKey("show_network_detail")
        val ASKED_PHONE_ACCESS = booleanPreferencesKey("asked_phone_access")
        val ASKED_LOCATION_ACCESS = booleanPreferencesKey("asked_location_access")
    }
}
