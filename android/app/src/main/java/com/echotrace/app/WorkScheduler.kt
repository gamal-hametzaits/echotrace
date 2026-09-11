package com.echotrace.app

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object WorkScheduler {
    private const val PERIODIC = "echotrace-trace"

    fun ensure(c: Context) {
        val req = PeriodicWorkRequestBuilder<TraceWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun pollNow(c: Context) {
        WorkManager.getInstance(c).enqueue(OneTimeWorkRequestBuilder<TraceWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build())
    }
}
