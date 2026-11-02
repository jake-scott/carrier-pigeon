// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon

import android.app.Application
import io.github.jakescott.carrierpigeon.data.DataSimMonitor

class CarrierPigeonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Any time our process starts, make sure the system will wake us on cellular network changes,
        // and listen directly for active-data-sub changes while the process is alive.
        DataSimMonitor.registerNetworkCallback(this)
        DataSimMonitor.registerInProcessListener(this)
    }
}
