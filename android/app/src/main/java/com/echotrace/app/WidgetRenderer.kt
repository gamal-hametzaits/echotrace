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

    // RemoteViews travels to the launcher inside ONE binder transaction, limited to
    // ~1MB shared across everything in flight. A bitmap parcels as raw ARGB_8888
    // pixels (~4 B/px), so a full-size photo (a 720x480 shot is 1.4MB) makes
    // updateAppWidget throw TransactionTooLargeException: the update is lost
    // wholesale and the widget sits on its initial layout - this was the "empty
    // widget" regression. Every pushed bitmap is capped well under the limit.
    private const val MAX_BITMAP_PX = 110_000   // ~440KB of pixels, safe margin
    private const val FALLBACK_EDGE = 200       // long-edge px for the retry render

    private const val KEY_LAST_RENDER = "lastRenderKey"

    /**
     * Push the widget only when its visible state actually changed.
     * The visual depends on pairing state, the current image and its fade hour;
     * a 15-min poll that changes none of those skips decode + blur + the binder push.
     */
    fun updateAll(c: Context, force: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(c)
        val ids = mgr.getAppWidgetIds(ComponentName(c, TraceWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val key = renderKey(c)
        if (!force && key == readLastKey(c)) return
        var allOk = true
        for (id in ids) if (!push(c, mgr, id)) allOk = false
        // Only remember the key after a fully successful push: a failed push must
        // be retried on the next poll instead of being skipped as "already rendered".
        if (allOk) writeLastKey(c, key)
    }

    /** Push one widget; if the binder rejects the transaction (oversized bitmap),
     *  retry once with a hard-small photo so the widget is never left blank. */
    private fun push(c: Context, mgr: AppWidgetManager, id: Int): Boolean {
        return try {
            mgr.updateAppWidget(id, render(c, mgr, id))
            true
        } catch (e: RuntimeException) {
            try {
                mgr.updateAppWidget(id, render(c, mgr, id, FALLBACK_EDGE))
                true
            } catch (e2: RuntimeException) {
                false
            }
        }
    }

    /** Decode target for this widget instance: its real pixel size, clamped so the
     *  resulting bitmap (after [Imaging.capPixels]) always fits the binder budget. */
    private fun widgetTargetPx(c: Context, mgr: AppWidgetManager, id: Int): Int {
        val o = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        if (w <= 0 || h <= 0) return 360
        val d = c.resources.displayMetrics.density
        return (Math.max(w, h) * d).toInt().coerceIn(220, 480)
    }

    /**
     * Compact (photo-only) layout when the widget is 2 cells or fewer on either
     * axis; the full layout (with caption) above that. Cell math per the docs:
     * n cells = (70*n - 30) dp. Unknown options fall back to compact, matching
     * the 2x2 default.
     */
    fun isCompact(mgr: AppWidgetManager, id: Int): Boolean {
        val o = mgr.getAppWidgetOptions(id)
        val w = listOf(o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
                       o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)).filter { it > 0 }.minOrNull() ?: 0
        val h = listOf(o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
                       o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)).filter { it > 0 }.minOrNull() ?: 0
        if (w == 0 || h == 0) return true
        return minOf((w + 30) / 70, (h + 30) / 70) <= 2
    }

    private fun sizeSignature(c: Context): String {
        val mgr = AppWidgetManager.getInstance(c)
        val ids = mgr.getAppWidgetIds(ComponentName(c, TraceWidgetProvider::class.java))
        return ids.sorted().joinToString(",") { if (isCompact(mgr, it)) "c" else "f" }
    }

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
            stage,
            sizeSignature(c)
        ).joinToString("|")
    }

    fun render(c: Context, mgr: AppWidgetManager, id: Int, edgeCap: Int? = null): RemoteViews {
        val compact = isCompact(mgr, id)
        val rv = RemoteViews(c.packageName, if (compact) R.layout.widget_trace_compact else R.layout.widget_trace)
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
        rv.setViewVisibility(R.id.scrim, View.GONE)
        rv.setViewVisibility(R.id.fadeBar, View.GONE)
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
                rv.setInt(R.id.widgetRoot, "setBackgroundResource", R.drawable.widget_bg_disconnected)
                rv.setTextColor(R.id.status, 0xFFF3EDE4.toInt())
                rv.setViewVisibility(R.id.status, View.VISIBLE)
                rv.setTextViewText(R.id.status, c.getString(R.string.disconnected))
                rv.setViewVisibility(R.id.cameraBtn, View.GONE)
                rootClick(DisconnectedActivity::class.java)
            }
            partner == null -> {
                rv.setInt(R.id.widgetRoot, "setBackgroundResource", R.drawable.widget_bg)
                rv.setTextColor(R.id.status, 0xFF3B352E.toInt())
                rv.setViewVisibility(R.id.status, View.VISIBLE)
                rv.setTextViewText(R.id.status,
                    "${c.getString(R.string.your_code)}\n${Prefs.deviceId(c)}\n${c.getString(R.string.pair_now)}")
                rootClick(PairingActivity::class.java)
            }
            !hasPhoto -> {
                rv.setInt(R.id.widgetRoot, "setBackgroundResource", R.drawable.widget_bg_waiting)
                rv.setTextColor(R.id.status, 0xFF3B352E.toInt())
                rv.setViewVisibility(R.id.status, View.VISIBLE)
                rv.setTextViewText(R.id.status, c.getString(R.string.waiting_first))
                rootClick(MainActivity::class.java)
            }
            else -> {
                val m = meta!!
                val elapsed0 = System.currentTimeMillis() - m.exposedAt
                // Decode at this widget's real size (never full camera resolution),
                // then hard-cap the pixel count: the bitmap crosses to the launcher
                // in one binder transaction, and oversized bitmaps lose the whole
                // update. Blurred stages lose fine detail to the blur itself, so
                // decode them at half size: ~4x less decode + blur CPU and memory.
                val target = edgeCap ?: widgetTargetPx(c, mgr, id)
                val bmp = Imaging.decodeSampled(photoFile, if (elapsed0 < 6 * HOUR) target else target / 2)
                    ?.let { Imaging.capPixels(it, MAX_BITMAP_PX) }
                if (bmp == null) {
                    // undecodable image (e.g. a non-bitmap format slipped through):
                    // drop the corrupt state so polling can recover, and show the
                    // waiting message instead of a blank widget.
                    TraceMeta.clear(c)
                    with(Prefs) { c.lastImageId = null }
                    rv.setInt(R.id.widgetRoot, "setBackgroundResource", R.drawable.widget_bg_waiting)
                    rv.setTextColor(R.id.status, 0xFF3B352E.toInt())
                    rv.setViewVisibility(R.id.status, View.VISIBLE)
                    rv.setTextViewText(R.id.status, c.getString(R.string.waiting_first))
                    rootClick(MainActivity::class.java)
                    return rv
                }
                val mem = Imaging.memoryColor(bmp)
                val elapsed = System.currentTimeMillis() - m.exposedAt
                rv.setViewVisibility(R.id.photo, View.VISIBLE)
                rv.setInt(R.id.widgetRoot, "setBackgroundResource", R.drawable.widget_bg)
                rv.setViewVisibility(R.id.scrim, View.VISIBLE)
                when {
                    elapsed < 6 * HOUR -> {
                        rv.setImageViewBitmap(R.id.photo, Imaging.rounded(bmp))
                        if (!compact) showCaption(rv, m.caption, 1f)
                    }
                    elapsed < 12 * HOUR -> {
                        val t = (elapsed - 6 * HOUR).toFloat() / (6 * HOUR)
                        val radius = (MAX_BLUR * t).toInt()
                        rv.setImageViewBitmap(R.id.photo, Imaging.rounded(Imaging.staged(bmp, blurPx(radius, bmp.width), mem, 0)))
                        if (!compact) showCaption(rv, m.caption, 1f - t)
                    }
                    elapsed < 24 * HOUR -> {
                        val t = (elapsed - 12 * HOUR).toFloat() / (12 * HOUR)
                        rv.setImageViewBitmap(R.id.photo, Imaging.rounded(Imaging.staged(bmp, blurPx(MAX_BLUR.toInt(), bmp.width), mem, (t * 235).toInt())))
                    }
                    else -> {
                        val solid = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(mem) }
                        rv.setImageViewBitmap(R.id.photo, Imaging.rounded(solid))
                    }
                }
                // The life bar: drains across the photo's 24h. Rides the existing
                // hourly fade renders - no extra wakeups, same granularity as the blur.
                if (elapsed < 24 * HOUR) {
                    rv.setViewVisibility(R.id.fadeBar, View.VISIBLE)
                    rv.setProgressBar(R.id.fadeBar, 1440, (elapsed / 60_000L).toInt().coerceIn(0, 1440), false)
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
