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

    private companion object {
        val SHOW_NETWORK_DETAIL = booleanPreferencesKey("show_network_detail")
    }
}
