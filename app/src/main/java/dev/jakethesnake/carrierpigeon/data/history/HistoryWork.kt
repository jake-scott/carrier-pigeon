// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.data.history

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.jakethesnake.carrierpigeon.data.AppSettings
import dev.jakethesnake.carrierpigeon.data.DataSimMonitor
import dev.jakethesnake.carrierpigeon.data.DataSimRefresher
import java.util.concurrent.TimeUnit

/**
 * Every 15 minutes (WorkManager's shortest period; Doze stretches it): refresh, which confirms
 * the open connection periods and re-registers listeners, then take one signal reading per source.
 * No network, no wake lock of its own, well under a second.
 */
class HistorySampleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!AppSettings(app).historyEnabled()) return Result.success()
        DataSimRefresher.refresh(app, DataSimMonitor.HISTORY_SAMPLE_REASON, settle = false)
        HistoryRecorder.sampleSignalNow(app)
        return Result.success()
    }
}

/** Once a day while the phone is idle: roll up old detail and delete what is past the chosen retention. */
class HistoryMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val days = AppSettings(app).historyRetentionDays()
        HistoryStore.get(app).maintain(System.currentTimeMillis(), days * HistoryMath.DAY_MS)
        return Result.success()
    }
}

/** Starts or stops the two periodic jobs to match the "Record history" setting. */
object HistoryScheduler {
    private const val SAMPLE = "history-sample"
    private const val MAINTAIN = "history-maintain"

    fun sync(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            wm.cancelUniqueWork(SAMPLE)
            wm.cancelUniqueWork(MAINTAIN)
            return
        }
        wm.enqueueUniquePeriodicWork(
            SAMPLE,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HistorySampleWorker>(15, TimeUnit.MINUTES).build(),
        )
        wm.enqueueUniquePeriodicWork(
            MAINTAIN,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HistoryMaintenanceWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresDeviceIdle(true).build())
                .build(),
        )
    }
}
