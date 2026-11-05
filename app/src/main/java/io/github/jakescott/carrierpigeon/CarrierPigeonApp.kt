// SPDX-License-Identifier: Apache-2.0
package io.github.jakescott.carrierpigeon

import android.app.Application
import io.github.jakescott.carrierpigeon.data.DataSimMonitor
import io.github.jakescott.carrierpigeon.data.SwitchLog

class CarrierPigeonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SwitchLog(this).note("process started")
        // Any time our process starts, make sure the system will wake us on cellular network changes,
        // and listen directly for data SIM changes while the process is alive.
        DataSimMonitor.registerNetworkCallback(this)
        DataSimMonitor.registerInProcessListeners(this)
    }
}
