// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Hardware the manifest marks optional. A tablet, Chromebook or XR headset can install the app
 * without a SIM or Wi-Fi radio, so check before touching those APIs. When targeting Android 15+,
 * many TelephonyManager and SubscriptionManager calls throw UnsupportedOperationException on a
 * device without the feature instead of returning empty values.
 */
object DeviceFeatures {
    /** Can hold a SIM. Android 13+ splits out the subscription part, which is what SIM lists need. */
    fun hasTelephony(context: Context): Boolean {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)
    }

    fun hasWifi(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
}
