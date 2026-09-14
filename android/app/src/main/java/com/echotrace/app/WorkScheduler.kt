package com.echotrace.app

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object WorkScheduler {
    private const val PERIODIC = "echotrace-trace"
    private const val IMMEDIATE = "echotrace-trace-now"

    fun ensure(c: Context) {
        val req = PeriodicWorkRequestBuilder<TraceWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // UPDATE (not KEEP): an in-place app update replaces any stale request
        // left by a previous version instead of silently keeping it.
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun pollNow(c: Context) {
        val req = OneTimeWorkRequestBuilder<TraceWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // Ask WorkManager to run user-triggered/resume pulls promptly. Android may
            // exhaust the expedited quota, so always fall back instead of dropping it.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // Coalesce onCreate/onResume/widget events into one pull. Without this,
        // opening the app can enqueue the same network request several times.
        WorkManager.getInstance(c).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP, req)
    }
}
