// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon

import android.app.Application
import dev.jakethesnake.carrierpigeon.data.DataSimMonitor
import dev.jakethesnake.carrierpigeon.data.SwitchLog

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
