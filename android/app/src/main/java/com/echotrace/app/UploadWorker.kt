package com.echotrace.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Sends the just-captured photo to the partner. No send button: this fires right after capture. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val path = inputData.getString("path") ?: return@withContext Result.failure()
        val f = File(path)
        try {
            val me = Prefs.deviceId(c)
            val bmp = Imaging.decodeSampled(f, 1280) ?: return@withContext Result.failure()
            val jpeg = Imaging.compressJpeg(bmp, 1280, 85)
            Api.upload(me, "", jpeg)
            f.delete()
            WidgetRenderer.updateAll(c)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 4) Result.retry() else { f.delete(); Result.failure() }
        }
    }
}
