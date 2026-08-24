package com.anant.splitbill.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anant.splitbill.SplitBillApp
import com.anant.splitbill.ui.widget.BalanceWidgetReceiver
import java.util.concurrent.TimeUnit

class CloudSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SplitBillApp ?: return Result.success()
        val settings = app.settingsRepository.current()
        if (!settings.onboardingComplete || settings.supportId.isBlank()) {
            return Result.success()
        }
        if (!settings.cloudAutoUploadEnabled) {
            return Result.success()
        }
        val sync = app.backupManager.syncCloud()
        return sync.fold(
            onSuccess = { result ->
                val currency = app.repository.exportSnapshot().first
                    .firstOrNull()?.currencySymbol ?: "Rs."
                if (result.newEntries.isNotEmpty()) {
                    SyncNotifier.notifyNewEntries(app, result.newEntries, currency)
                }
                if (result.newlyDeletedEntries.isNotEmpty()) {
                    SyncNotifier.notifyDeletedEntries(app, result.newlyDeletedEntries, currency)
                    DeletionAlertCenter.post(result.newlyDeletedEntries)
                    app.backupManager.markDeletionsNotified(
                        result.newlyDeletedEntries.map { it.id }
                    )
                }
                BalanceWidgetReceiver.requestUpdate(app)
                Result.success()
            },
            onFailure = {
                // Wait for the next hourly window rather than thrashing retries.
                Result.success()
            }
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "splitbill_cloud_sync_hourly"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
