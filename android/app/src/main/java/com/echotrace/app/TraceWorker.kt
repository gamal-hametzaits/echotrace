package com.echotrace.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Periodic heartbeat (backup to the foreground listener): one shared sync pass. */
class TraceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            TraceSync.run(applicationContext)
            Result.success()
        } catch (e: Exception) {
            // A 4xx will fail again identically - retrying just burns wakeups.
            val permanent = e is Api.ApiException && e.httpCode in 400..499
            if (!permanent && runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}
