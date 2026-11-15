// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

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
            if (s.networkKind != null) p[NETWORK_KIND] = s.networkKind.name else p.remove(NETWORK_KIND)
            p[STATUS] = s.status.name
            p[ACCESS_KIND] = s.access.name
            // Display text stored by older versions; the enum names above replace it.
            p.remove(OLD_NETWORK_TYPE)
            p.remove(OLD_NETWORK_DETAIL)
            p.remove(OLD_ACCESS)
            p[VPN] = s.vpn
            p[PHONE_ACCESS] = s.phoneAccess
            p[UPDATED] = s.updatedAtMillis
        }
    }

    private fun Preferences.toState(): DataSimState? {
        val updated = this[UPDATED] ?: return null
        // Saved by a version that stored display text: treat as nothing stored until the next refresh.
        val access = this[ACCESS_KIND]?.let { name -> Access.entries.firstOrNull { it.name == name } } ?: return null
        return DataSimState(
            activeSubId = this[ACTIVE] ?: -1,
            defaultSubId = this[DEFAULT] ?: -1,
            networkSubId = this[NETWORK] ?: -1,
            displayName = this[NAME],
            carrierName = this[CARRIER],
            slotIndex = this[SLOT] ?: -1,
            isEmbedded = this[EMBEDDED] ?: false,
            iconTint = this[TINT] ?: 0,
            networkKind = this[NETWORK_KIND]?.let { name -> NetworkKind.entries.firstOrNull { it.name == name } },
            status = this[STATUS]?.let { name -> DataStatus.entries.firstOrNull { it.name == name } } ?: DataStatus.OK,
            access = access,
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
        val NETWORK_KIND = stringPreferencesKey("network_kind")
        val STATUS = stringPreferencesKey("status")
        val ACCESS_KIND = stringPreferencesKey("access_kind")
        val OLD_NETWORK_TYPE = stringPreferencesKey("network_type")
        val OLD_NETWORK_DETAIL = stringPreferencesKey("network_detail")
        val OLD_ACCESS = stringPreferencesKey("access")
        val VPN = booleanPreferencesKey("vpn")
        val PHONE_ACCESS = booleanPreferencesKey("phone_access")
        val UPDATED = longPreferencesKey("updated_at")
    }
}
