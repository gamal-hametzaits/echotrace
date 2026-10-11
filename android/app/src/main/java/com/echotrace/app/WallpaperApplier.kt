package com.echotrace.app

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import java.io.File

/** Sets the incoming photo as home and/or lock screen wallpaper when the user chose that.
 *  Android 13+ does not let an app read the current wallpaper, so "restore" re-applies a
 *  picture the user picked once (restore_wallpaper.jpg), not the previous wallpaper itself. */
object WallpaperApplier {
    const val RESTORE_FILE = "restore_wallpaper.jpg"

    private fun flags(mode: Int): Int {
        var f = 0
        if (Logic.wallpaperHome(mode)) f = f or WallpaperManager.FLAG_SYSTEM
        if (Logic.wallpaperLock(mode)) f = f or WallpaperManager.FLAG_LOCK
        return f
    }

    /** Keeps the wallpaper in step with the widget: same sharp -> blur -> memory -> gone
     *  stages, re-rendered at most once per hour per photo (and only if the mode, photo
     *  or hour changed). Never throws. [force] re-applies even if nothing changed. */
    fun refresh(c: Context, force: Boolean = false) {
        try {
            val mode = with(Prefs) { c.wallpaperMode }
            if (mode == 0) return
            val meta = TraceMeta.load(c)
            val file = File(c.filesDir, "current_trace.jpg")
            if (meta == null || !file.exists()) return
            val elapsed = System.currentTimeMillis() - meta.exposedAt
            val key = Logic.wallKey(mode, meta.imageId, elapsed)
            if (!force && key == with(Prefs) { c.wallKey }) return
            val dm = c.resources.displayMetrics
            val target = Math.min(1080, Math.max(dm.widthPixels, 480))
            val stage = Logic.fadeStage(elapsed)
            val src = Imaging.decodeSampled(file.readBytes(), if (elapsed < 6 * Logic.HOUR) target else target / 2) ?: return
            val bmp = Imaging.capPixels(src, 4_000_000)
            val mem = Imaging.memoryColor(bmp)
            val out: android.graphics.Bitmap = when (stage) {
                0 -> bmp
                1 -> Imaging.staged(bmp, Logic.blurPx(Logic.fadeRadiusLogical(elapsed), bmp.width), mem, 0)
                2 -> Imaging.staged(bmp, Logic.blurPx(Logic.MAX_BLUR.toInt(), bmp.width), mem, Logic.memoryOverlayAlpha(elapsed))
                else -> {
                    val rf = File(c.filesDir, RESTORE_FILE)
                    // After 24 h the photo is gone: back to the user's own picture if chosen, else a quiet solid colour.
                    (if (rf.exists()) BitmapFactory.decodeFile(rf.absolutePath) else null)
                        ?: android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(mem) }
                }
            }
            val wm = WallpaperManager.getInstance(c)
            if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) return
            wm.setBitmap(out, null, true, flags(mode))
            with(Prefs) { c.wallKey = key }
        } catch (_: Throwable) { /* wallpaper trouble must never break receiving */ }
    }

    /** Returns true on success; never throws (a wallpaper problem must not break receiving). */
    fun set(c: Context, jpeg: ByteArray, mode: Int): Boolean = try {
        val wm = WallpaperManager.getInstance(c)
        if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) false else {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            if (bmp == null) false else {
                wm.setBitmap(bmp, null, true, flags(mode))
                bmp.recycle()
                true
            }
        }
    } catch (_: Throwable) { false }

    fun hasRestorePicture(c: Context) = File(c.filesDir, RESTORE_FILE).exists()

    /** Re-applies the picture the user chose as "my wallpaper" to the screens that were used. */
    fun restore(c: Context, mode: Int): Boolean {
        val f = File(c.filesDir, RESTORE_FILE)
        if (!f.exists() || mode == 0) return false
        return set(c, f.readBytes(), mode)
    }

    fun saveRestorePicture(c: Context, bytes: ByteArray) {
        File(c.filesDir, RESTORE_FILE).writeBytes(bytes)
    }
}
