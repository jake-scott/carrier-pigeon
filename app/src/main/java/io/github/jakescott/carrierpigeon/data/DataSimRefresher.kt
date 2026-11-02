// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon.data

import android.content.Context
import android.telephony.SubscriptionManager
import io.github.jakescott.carrierpigeon.widget.CarrierPigeonWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The single path every trigger goes through: resolve, log, store, redraw widgets. */
object DataSimRefresher {
    private val mutex = Mutex()

    suspend fun refresh(
        context: Context,
        reason: String,
        networkSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    ) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        mutex.withLock {
            val store = StateStore(app)
            val old = store.current()
            val new = DataSimResolver(app).resolve(networkSubId)
            SwitchLog(app).append(reason, old, new)
            store.save(new)
        }
        CarrierPigeonWidget().updateAll(app)
    }
}
