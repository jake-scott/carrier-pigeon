// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.stateDataStore: DataStore<Preferences> by preferencesDataStore(name = "data_sim_state")

/** Last resolved [DataSimState]. The widget and the app screen only ever read from here. */
class StateStore(context: Context) {
    private val store = context.applicationContext.stateDataStore

    val state: Flow<DataSimState?> = store.data.map { it.toState() }

    suspend fun current(): DataSimState? = state.first()

    suspend fun save(s: DataSimState) {
        store.edit { p ->
            p[ACTIVE] = s.activeSubId
            p[DEFAULT] = s.defaultSubId
            p[NETWORK] = s.networkSubId
            if (s.displayName != null) p[NAME] = s.displayName else p.remove(NAME)
            if (s.carrierName != null) p[CARRIER] = s.carrierName else p.remove(CARRIER)
            p[SLOT] = s.slotIndex
            p[EMBEDDED] = s.isEmbedded
            p[TINT] = s.iconTint
            if (s.networkType != null) p[NETWORK_TYPE] = s.networkType else p.remove(NETWORK_TYPE)
            if (s.networkDetail != null) p[NETWORK_DETAIL] = s.networkDetail else p.remove(NETWORK_DETAIL)
            p[STATUS] = s.status.name
            p[ACCESS] = s.access
            p[VPN] = s.vpn
            p[PHONE_ACCESS] = s.phoneAccess
            p[UPDATED] = s.updatedAtMillis
        }
    }

    private fun Preferences.toState(): DataSimState? {
        val updated = this[UPDATED] ?: return null
        return DataSimState(
            activeSubId = this[ACTIVE] ?: -1,
            defaultSubId = this[DEFAULT] ?: -1,
            networkSubId = this[NETWORK] ?: -1,
            displayName = this[NAME],
            carrierName = this[CARRIER],
            slotIndex = this[SLOT] ?: -1,
            isEmbedded = this[EMBEDDED] ?: false,
            iconTint = this[TINT] ?: 0,
            networkType = this[NETWORK_TYPE],
            networkDetail = this[NETWORK_DETAIL],
            status = this[STATUS]?.let { name -> DataStatus.entries.firstOrNull { it.name == name } } ?: DataStatus.OK,
            access = this[ACCESS] ?: DataSimResolver.NO_DATA,
            vpn = this[VPN] ?: false,
            phoneAccess = this[PHONE_ACCESS] ?: true,
            updatedAtMillis = updated,
        )
    }

    private companion object {
        val ACTIVE = intPreferencesKey("active_sub")
        val DEFAULT = intPreferencesKey("default_sub")
        val NETWORK = intPreferencesKey("network_sub")
        val NAME = stringPreferencesKey("display_name")
        val CARRIER = stringPreferencesKey("carrier_name")
        val SLOT = intPreferencesKey("slot")
        val EMBEDDED = booleanPreferencesKey("embedded")
        val TINT = intPreferencesKey("tint")
        val NETWORK_TYPE = stringPreferencesKey("network_type")
        val NETWORK_DETAIL = stringPreferencesKey("network_detail")
        val STATUS = stringPreferencesKey("status")
        val ACCESS = stringPreferencesKey("access")
        val VPN = booleanPreferencesKey("vpn")
        val PHONE_ACCESS = booleanPreferencesKey("phone_access")
        val UPDATED = longPreferencesKey("updated_at")
    }
}
