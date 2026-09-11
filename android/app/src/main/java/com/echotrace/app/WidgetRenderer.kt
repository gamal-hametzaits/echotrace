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

    fun updateAll(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        val ids = mgr.getAppWidgetIds(ComponentName(c, TraceWidgetProvider::class.java))
        for (id in ids) mgr.updateAppWidget(id, render(c))
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
        rv.setViewVisibility(R.id.cameraBtn, if (canSend && !disconnected) View.VISIBLE else View.GONE)
        rv.setImageViewResource(R.id.photo, android.R.color.transparent)

        fun rootClick(cls: Class<*>) {
            val i = Intent(c, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            rv.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getActivity(
                c, cls.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (canSend && !disconnected) {
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
                val bmp = Imaging.decodeSampled(photoFile, 560) ?: return rv
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
                        rv.setImageViewBitmap(R.id.photo, Imaging.staged(bmp, blurPx(radius), mem, 0))
                        showCaption(rv, m.caption, 1f - t)
                    }
                    elapsed < 24 * HOUR -> {
                        val t = (elapsed - 12 * HOUR).toFloat() / (12 * HOUR)
                        rv.setImageViewBitmap(R.id.photo, Imaging.staged(bmp, blurPx(MAX_BLUR.toInt()), mem, (t * 235).toInt()))
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

    private fun blurPx(logical: Int): Int = (logical * 0.6f).toInt() // 0..15 px on the sampled bitmap

    private fun showCaption(rv: RemoteViews, caption: String, alpha: Float) {
        if (caption.isBlank()) return
        rv.setViewVisibility(R.id.caption, View.VISIBLE)
        rv.setTextViewText(R.id.caption, caption.take(50))
        rv.setFloat(R.id.caption, "setAlpha", alpha)
    }
}
