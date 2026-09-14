package com.echotrace.app

/**
 * Pure logic, free of android.* dependencies so JVM unit tests can run it
 * directly. The Bitmap/RemoteViews call sites in Imaging, Api and
 * WidgetRenderer delegate here; behavior must stay identical to the inline
 * math these functions replace.
 */
object Logic {
    const val HOUR = 3600_000L
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

    /** Age stages: 0 sharp (0-6h), 1 fading (6-12h), 2 memory (12-24h), 3 memory-color (24h+). */
    fun fadeStage(elapsedMs: Long): Int = when {
        elapsedMs < 6 * HOUR -> 0
        elapsedMs < 12 * HOUR -> 1
        elapsedMs < 24 * HOUR -> 2
        else -> 3
    }

    /** Logical blur radius: 0 while sharp, ramping 0..25 across 6-12h, 25 after. */
    fun fadeRadiusLogical(elapsedMs: Long): Int = when {
        elapsedMs < 6 * HOUR -> 0
        elapsedMs < 12 * HOUR -> (MAX_BLUR * (elapsedMs - 6 * HOUR).toDouble() / (6 * HOUR)).toInt()
        else -> MAX_BLUR.toInt()
    }

    /** Caption alpha: solid while sharp, fading 1->0 across 6-12h, hidden after. */
    fun captionAlpha(elapsedMs: Long): Float = when {
        elapsedMs < 6 * HOUR -> 1f
        elapsedMs < 12 * HOUR -> (1.0 - (elapsedMs - 6 * HOUR).toDouble() / (6 * HOUR)).toFloat()
        else -> 0f
    }

    /** Memory-color overlay alpha: 0 at 12h ramping to 235 at 24h. */
    fun memoryOverlayAlpha(elapsedMs: Long): Int =
        if (elapsedMs < 12 * HOUR || elapsedMs >= 24 * HOUR) 0
        else (((elapsedMs - 12 * HOUR).toDouble() / (12 * HOUR)) * 235).toInt()

    /** Logical blur -> device pixels on a bitmap of the given width. */
    fun blurPx(logical: Int, bmpWidth: Int): Int =
        (logical * 0.6f * bmpWidth / 560f).toInt()

    /** Hour-granular fade stage for the render key; "gone" past 24h. */
    fun hourStage(hasPhoto: Boolean, elapsedMs: Long): String {
        if (!hasPhoto) return "none"
        val h = elapsedMs / HOUR
        return if (h >= 24) "gone" else "h$h"
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
}
