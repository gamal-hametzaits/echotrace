package com.echotrace.app

import android.content.Context
import java.io.File

/** One sync pass: register, adopt server pairing state, poll, download a new photo, confirm.
 *  Shared by the WorkManager job and the foreground listener so both behave identically. */
object TraceSync {
    /** Returns true when the server answered (even with "nothing new"). */
    fun run(c: Context): Boolean {
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
                    val seen = poll.optLong("partnerLastSeen", 0L)
                    if (seen > 0L) with(Prefs) { c.partnerLastSeenAt = seen }
                    if (partnerConnected) {
                        val img = poll.optJSONObject("image")
                        if (img != null) receive(c, me, img)
                    }
                }
            }
            WallpaperApplier.refresh(c)
            WidgetRenderer.updateAll(c)
            return true
        } catch (e: Exception) {
            with(Prefs) { c.syncError = e.javaClass.simpleName }
            try { WidgetRenderer.updateAll(c) } catch (_: Exception) {}
            throw e
        }
    }

    private fun receive(c: Context, me: String, img: org.json.JSONObject) {
        val imageId = img.getString("imageId")
        if (imageId == with(Prefs) { c.lastImageId }) return
        val bytes = Api.downloadImage(me, imageId)
        if (bytes == null || bytes.isEmpty() || Imaging.decodeSampled(bytes, 560) == null) return
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
