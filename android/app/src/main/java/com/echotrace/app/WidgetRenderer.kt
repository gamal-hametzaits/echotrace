package com.echotrace.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import java.io.File

object WidgetRenderer {
    private const val HOUR = 3600_000L
    private const val MAX_BLUR = 25f   // logical blur 0..25 mapped onto a ~560px bitmap

    private const val KEY_LAST_RENDER = "lastRenderKey"

    /**
     * Push the widget only when its visible state actually changed.
     * The visual depends on pairing state, the current image and its fade hour;
     * a 15-min poll that changes none of those skips decode + blur + the binder push.
     */
    fun updateAll(c: Context, force: Boolean = false) {
        val key = renderKey(c)
        if (!force && key == readLastKey(c)) return
        val mgr = AppWidgetManager.getInstance(c)
        val ids = mgr.getAppWidgetIds(ComponentName(c, TraceWidgetProvider::class.java))
        val rv = render(c)
        for (id in ids) mgr.updateAppWidget(id, rv)
        writeLastKey(c, key)
    }

    /** The provider pushed [render] itself (new widget instance): remember the key. */
    fun markRendered(c: Context) = writeLastKey(c, renderKey(c))

    private fun readLastKey(c: Context): String? =
        c.getSharedPreferences("echotrace", Context.MODE_PRIVATE).getString(KEY_LAST_RENDER, null)

    private fun writeLastKey(c: Context, key: String) =
        c.getSharedPreferences("echotrace", Context.MODE_PRIVATE).edit().putString(KEY_LAST_RENDER, key).apply()

    /** Hour-granular fade stage: the blur/alpha fade is far too slow to see finer steps. */
    private fun renderKey(c: Context): String {
        val meta = TraceMeta.load(c)
        val hasPhoto = meta != null && File(c.filesDir, "current_trace.jpg").exists()
        val stage = if (!hasPhoto) "none" else {
            val h = (System.currentTimeMillis() - meta!!.exposedAt) / HOUR
            if (h >= 24) "gone" else "h$h"
        }
        return listOf(
            with(Prefs) { c.partner },
            with(Prefs) { c.disconnected },
            with(Prefs) { c.role },
            meta?.imageId,
            stage
        ).joinToString("|")
    }

    fun render(c: Context): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.widget_trace)
        val partner = with(Prefs) { c.partner }
        val disconnected = with(Prefs) { c.disconnected }
        val role = with(Prefs) { c.role }
        val canSend = role == "both" || role == "sender"
        val meta = TraceMeta.load(c)
        val photoFile = File(c.filesDir, "current_trace.jpg")
        val hasPhoto = meta != null && photoFile.exists()

        // defaults
        rv.setViewVisibility(R.id.status, View.GONE)
        rv.setViewVisibility(R.id.caption, View.GONE)
        rv.setViewVisibility(R.id.photo, View.GONE)
        rv.setViewVisibility(R.id.cameraBtn, if (canSend && !disconnected && partner != null) View.VISIBLE else View.GONE)
        rv.setImageViewResource(R.id.photo, android.R.color.transparent)

        fun rootClick(cls: Class<*>) {
            val i = Intent(c, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            rv.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getActivity(
                c, cls.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (canSend && !disconnected && partner != null) {
            val ci = Intent(c, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            rv.setOnClickPendingIntent(R.id.cameraBtn, PendingIntent.getActivity(
                c, 7, ci, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }

        when {
            disconnected -> {
                rv.setInt(R.id.widgetRoot, "setBackgroundColor", 0xFF8C8377.toInt())
                rv.setViewVisibility(R.id.status, View.VISIBLE)
                rv.setTextViewText(R.id.status, c.getString(R.string.disconnected))
                rv.setViewVisibility(R.id.cameraBtn, View.GONE)
                rootClick(DisconnectedActivity::class.java)
            }
            partner == null -> {
                rv.setInt(R.id.widgetRoot, "setBackgroundColor", 0xFFF3EDE4.toInt())
                rv.setViewVisibility(R.id.status, View.VISIBLE)
                rv.setTextViewText(R.id.status,
                    "${c.getString(R.string.your_code)}\n${Prefs.deviceId(c)}\n${c.getString(R.string.pair_now)}")
                rootClick(PairingActivity::class.java)
            }
            !hasPhoto -> {
                rv.setInt(R.id.widgetRoot, "setBackgroundColor", 0xFFE8D9CB.toInt())
                rv.setViewVisibility(R.id.status, View.VISIBLE)
                rv.setTextViewText(R.id.status, c.getString(R.string.waiting_first))
                rootClick(MainActivity::class.java)
            }
            else -> {
                val m = meta!!
                val elapsed0 = System.currentTimeMillis() - m.exposedAt
                // Blurred stages lose the fine detail to the blur itself, so decode
                // them at half size: ~4x less decode + blur CPU and memory.
                val bmp = Imaging.decodeSampled(photoFile, if (elapsed0 < 6 * HOUR) 560 else 280)
                if (bmp == null) {
                    // undecodable image (e.g. a non-bitmap format slipped through):
                    // drop the corrupt state so polling can recover, and show the
                    // waiting message instead of a blank widget.
                    TraceMeta.clear(c)
                    with(Prefs) { c.lastImageId = null }
                    rv.setInt(R.id.widgetRoot, "setBackgroundColor", 0xFFE8D9CB.toInt())
                    rv.setViewVisibility(R.id.status, View.VISIBLE)
                    rv.setTextViewText(R.id.status, c.getString(R.string.waiting_first))
                    rootClick(MainActivity::class.java)
                    return rv
                }
                val mem = Imaging.memoryColor(bmp)
                val elapsed = System.currentTimeMillis() - m.exposedAt
                rv.setViewVisibility(R.id.photo, View.VISIBLE)
                rv.setInt(R.id.widgetRoot, "setBackgroundColor", mem)
                when {
                    elapsed < 6 * HOUR -> {
                        rv.setImageViewBitmap(R.id.photo, bmp)
                        showCaption(rv, m.caption, 1f)
                    }
                    elapsed < 12 * HOUR -> {
                        val t = (elapsed - 6 * HOUR).toFloat() / (6 * HOUR)
                        val radius = (MAX_BLUR * t).toInt()
                        rv.setImageViewBitmap(R.id.photo, Imaging.staged(bmp, blurPx(radius, bmp.width), mem, 0))
                        showCaption(rv, m.caption, 1f - t)
                    }
                    elapsed < 24 * HOUR -> {
                        val t = (elapsed - 12 * HOUR).toFloat() / (12 * HOUR)
                        rv.setImageViewBitmap(R.id.photo, Imaging.staged(bmp, blurPx(MAX_BLUR.toInt(), bmp.width), mem, (t * 235).toInt()))
                    }
                    else -> {
                        val solid = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(mem) }
                        rv.setImageViewBitmap(R.id.photo, solid)
                    }
                }
                rootClick(PhotoTapActivity::class.java)
            }
        }
        return rv
    }

    private fun blurPx(logical: Int, bmpWidth: Int): Int =
        (logical * 0.6f * bmpWidth / 560f).toInt() // 0..15 px on a full-size sample, scaled down

    private fun showCaption(rv: RemoteViews, caption: String, alpha: Float) {
        if (caption.isBlank()) return
        rv.setViewVisibility(R.id.caption, View.VISIBLE)
        rv.setTextViewText(R.id.caption, caption.take(50))
        rv.setFloat(R.id.caption, "setAlpha", alpha)
    }
}
