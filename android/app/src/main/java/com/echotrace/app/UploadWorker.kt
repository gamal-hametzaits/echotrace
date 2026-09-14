package com.echotrace.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sends the just-captured photo (plus its optional caption) to the partner.
 *  Every outcome is recorded in Prefs so the main screen can show the last
 *  upload attempt without a server roundtrip. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val path = inputData.getString("path")
        if (path == null) {
            record(c, false, "no path in input data")
            return@withContext Result.failure()
        }
        val caption = inputData.getString("caption") ?: ""
        val f = File(path)
        try {
            val me = Prefs.deviceId(c)
            val bmp = Imaging.decodeSampledRotated(f, 1280)
            if (bmp == null) {
                // An undecodable capture will never become decodable by retrying.
                f.delete()
                record(c, false, "decode failed")
                return@withContext Result.failure()
            }
            val jpeg = Imaging.compressJpeg(bmp, 1280, 85)
            Api.upload(me, caption, jpeg)
            f.delete()
            record(c, true, null)
            WidgetRenderer.updateAll(c)
            Result.success()
        } catch (t: Throwable) {
            // Throwable, not Exception: an OutOfMemoryError here must also leave a trace.
            if (runAttemptCount < 4) {
                Result.retry()
            } else {
                f.delete()
                record(c, false, t.javaClass.simpleName + ": " + (t.message ?: "").take(80))
                Result.failure()
            }
        }
    }

    private fun record(c: Context, ok: Boolean, detail: String?) {
        val time = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date())
        val msg = if (ok) "נשלחה בהצלחה · $time"
                  else "נכשלה · $time · ${detail ?: "unknown"}"
        with(Prefs) { c.lastUploadDiag = msg }
    }
}
