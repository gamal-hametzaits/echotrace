package com.echotrace.app

/**
 * Pure logic, free of android.* dependencies so JVM unit tests can run it
 * directly. The Bitmap/RemoteViews call sites in Imaging, Api and
 * WidgetRenderer delegate here; behavior must stay identical to the inline
 * math these functions replace.
 */
object Logic {
    const val HOUR = 3600_000L
    /** The fade advances one visible step every 30 minutes (48 steps over the photo's 24 h). */
    const val STEP = 1800_000L
    const val STEPS_PER_DAY = 48
    const val MAX_BLUR = 25f          // logical blur 0..25 mapped onto a ~560px bitmap
    const val MAX_BITMAP_PX = 110_000 // ~440KB of pixels, safe margin under the binder limit
    const val CAPTION_MAX = 50        // app-side caption cap (server keeps 80)

    /** BitmapFactory inSampleSize: keep doubling while half the width still
     *  reaches the target. Exact multiple means one more step (">=", not ">"). */
    fun computeSampleSize(width: Int, targetW: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetW) sample *= 2
        return sample
    }

    /** Dimensions after capping width*height to maxPx; unchanged when already
     *  under the cap, never below 1px per side. */
    fun cappedDimensions(w: Int, h: Int, maxPx: Int): IntArray {
        val px = w.toLong() * h.toLong()
        if (px <= maxPx) return intArrayOf(w, h)
        val scale = Math.sqrt(maxPx.toDouble() / px.toDouble())
        return intArrayOf(
            Math.max(1, (w * scale).toInt()),
            Math.max(1, (h * scale).toInt()))
    }

    /** Age stages: 0 sharp (first 30 min), 1 blurring (30 min-12 h), 2 memory colour (12-24 h), 3 gone (24 h+). */
    fun fadeStage(elapsedMs: Long): Int = when {
        elapsedMs < STEP -> 0
        elapsedMs < 12 * HOUR -> 1
        elapsedMs < 24 * HOUR -> 2
        else -> 3
    }

    /** Completed 30-minute steps since exposure, 0..48. */
    fun fadeStep(elapsedMs: Long): Int = (Math.max(0L, elapsedMs) / STEP).toInt().coerceAtMost(STEPS_PER_DAY)

    /** Logical blur radius: grows one step every 30 min across the first 12 h (0..25), then holds at 25. */
    fun fadeRadiusLogical(elapsedMs: Long): Int {
        val i = fadeStep(elapsedMs)
        return if (i >= 24) MAX_BLUR.toInt() else (MAX_BLUR * i / 24).toInt()
    }

    /** Caption alpha: solid while sharp, fading 1->0 across 6-12h, hidden after. */
    fun captionAlpha(elapsedMs: Long): Float = when {
        elapsedMs < 6 * HOUR -> 1f
        elapsedMs < 12 * HOUR -> (1.0 - (elapsedMs - 6 * HOUR).toDouble() / (6 * HOUR)).toFloat()
        else -> 0f
    }

    /** Memory-colour overlay alpha: 0 until 12 h, then 9..235 in 24 steps of 30 min; solid colour from 24 h. */
    fun memoryOverlayAlpha(elapsedMs: Long): Int =
        if (elapsedMs < 12 * HOUR || elapsedMs >= 24 * HOUR) 0
        else {
            val i = fadeStep(elapsedMs) - 24   // 0..23
            ((i + 1) * 235 / 24)
        }

    /** Logical blur -> device pixels on a bitmap of the given width. */
    fun blurPx(logical: Int, bmpWidth: Int): Int =
        (logical * 0.6f * bmpWidth / 560f).toInt()

    /** 30-minute fade step for the render key; "gone" from 24 h. */
    fun stepStage(hasPhoto: Boolean, elapsedMs: Long): String {
        if (!hasPhoto) return "none"
        val i = fadeStep(elapsedMs)
        return if (i >= STEPS_PER_DAY) "gone" else "s$i"
    }

    /** Render-on-change key: any visible-state field change must change the key. */
    fun renderKey(partner: String?, disconnected: Boolean, role: String,
                  imageId: String?, stage: String, sizeSignature: String): String =
        listOf(partner, disconnected, role, imageId, stage, sizeSignature).joinToString("|")

    /** X-Caption header value: UTF-8 percent-encoding with spaces as %20.
     *  URLEncoder emits '+' for spaces and the server reads '+' AS a space, so
     *  a literal plus must survive as %2B and spaces must go out as %20. */
    fun encodeCaption(caption: String): String =
        java.net.URLEncoder.encode(caption.take(CAPTION_MAX), "UTF-8").replace("+", "%20")

    enum class ConnState { NONE, WAITING, CONNECTED, OFFLINE, ERROR }

    /** One clear state for the status card. A sync error wins; otherwise the state
     *  follows the real server signals the worker stored (paired / partnerConnected). */
    fun connState(hasPartner: Boolean, hasPending: Boolean, partnerOffline: Boolean, syncError: Boolean): ConnState = when {
        syncError -> ConnState.ERROR
        !hasPartner -> if (hasPending) ConnState.WAITING else ConnState.NONE
        partnerOffline -> ConnState.OFFLINE
        else -> ConnState.CONNECTED
    }

    /** Coarse age for "last seen" lines: unit 0 = minutes, 1 = hours, 2 = days; under a
     *  minute reads as 1 minute so the text is never "0 minutes ago". */
    fun agoParts(elapsedMs: Long): IntArray {
        val e = Math.max(0L, elapsedMs)
        return when {
            e < HOUR -> intArrayOf(0, Math.max(1L, e / 60_000L).toInt())
            e < 24 * HOUR -> intArrayOf(1, (e / HOUR).toInt())
            else -> intArrayOf(2, (e / (24 * HOUR)).toInt())
        }
    }

    /** Wallpaper flags for a mode: bit 1 = home (FLAG_SYSTEM), bit 2 = lock (FLAG_LOCK). */
    fun wallpaperHome(mode: Int) = mode == 1 || mode == 3
    fun wallpaperLock(mode: Int) = mode == 2 || mode == 3

    /** Polling cadence for the foreground listener: fixed 30 s, 60 s after repeated failures
     *  so an offline phone does not hammer the radio. */
    fun listenDelayMs(consecutiveFailures: Int): Long = if (consecutiveFailures >= 3) 60_000L else 30_000L

    /** Wallpaper re-render key: changes only when mode, photo or 30-minute fade step changes. */
    fun wallKey(mode: Int, imageId: String?, elapsedMs: Long): String =
        mode.toString() + "|" + (imageId ?: "-") + "|" + stepStage(imageId != null, elapsedMs)
}
