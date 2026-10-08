package com.echotrace.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Periodic heartbeat: register, adopt server-side pairing state, poll for a new Trace, re-render fade. */
class TraceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        try {
            val me = Prefs.deviceId(c)
            if (!with(Prefs) { c.registered }) {
                Api.register(me)
                with(Prefs) { c.registered = true }
            }
            // Always ask the server: pairing may have been completed outside this
            // install (support fix, reinstall, data clear), in which case local
            // prefs know nothing and the photo would never arrive.
            val pending = with(Prefs) { c.pendingPartner }
            if (pending != null) {
                Api.connect(me, pending, with(Prefs) { c.role })
            }
            val poll = Api.poll(me)
            with(Prefs) { c.lastSyncAt = System.currentTimeMillis(); c.syncError = null }
            val unconfirmed = with(Prefs) { c.pendingConfirmation }
            if (unconfirmed != null) {
                try {
                    Api.confirmDownload(me, unconfirmed)
                    with(Prefs) { c.pendingConfirmation = null }
                } catch (_: Exception) { /* Retry deletion confirmation on next poll. */ }
            }
            if (poll.optBoolean("paired", false)) {
                val serverPartner = poll.optString("partner", "")
                if (serverPartner.length == 6) {
                    if (serverPartner != with(Prefs) { c.partner }) {
                        with(Prefs) { c.partner = serverPartner; c.disconnected = false }
                    }
                    with(Prefs) { c.pendingPartner = null }
                    val serverRole = poll.optString("role", "")
                    if (serverRole.isNotBlank()) with(Prefs) { c.role = serverRole }
                    // Server state is authoritative on every successful poll. The old
                    // one-way assignment latched `disconnected=true` forever, even
                    // after the partner came back and the server reported connected.
                    val partnerConnected = poll.optBoolean("partnerConnected", true)
                    with(Prefs) { c.disconnected = !partnerConnected }
                    if (partnerConnected) {
                        val img = poll.optJSONObject("image")
                        if (img != null) {
                            val imageId = img.getString("imageId")
                            if (imageId != with(Prefs) { c.lastImageId }) {
                                val bytes = Api.downloadImage(me, imageId)
                                if (bytes != null && bytes.isNotEmpty() && Imaging.decodeSampled(bytes, 560) != null) {
                                    File(c.filesDir, "current_trace.jpg").writeBytes(bytes)
                                    TraceMeta.save(c, TraceMeta(
                                        imageId = imageId,
                                        caption = img.optString("caption", ""),
                                        sentAt = img.optLong("sentAt", System.currentTimeMillis()),
                                        exposedAt = System.currentTimeMillis(),
                                        viewed = false))
                                    with(Prefs) { c.lastImageId = imageId }
                                    with(Prefs) { c.pendingConfirmation = imageId }
                                    try {
                                        Api.confirmDownload(me, imageId)
                                        with(Prefs) { c.pendingConfirmation = null }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                }
            }
            WidgetRenderer.updateAll(c)
            Result.success()
        } catch (e: Exception) {
            with(Prefs) { c.syncError = e.javaClass.simpleName }
            try { WidgetRenderer.updateAll(c) } catch (_: Exception) {}
            // A 4xx will fail again identically - retrying just burns wakeups.
            val permanent = e is Api.ApiException && e.httpCode in 400..499
            if (!permanent && runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}
