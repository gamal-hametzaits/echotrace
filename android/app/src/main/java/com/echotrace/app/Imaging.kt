package com.echotrace.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import java.io.File

object Imaging {
    fun decodeSampled(file: File, targetW: Int): Bitmap? {
        // inJustDecodeBounds only fills outWidth/outHeight and always returns null -
        // check the bounds, not the (always-null) return value.
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val o2 = BitmapFactory.Options().apply { inSampleSize = Logic.computeSampleSize(o.outWidth, targetW) }
        return BitmapFactory.decodeFile(file.absolutePath, o2)
    }

    /** Sampled decode with EXIF orientation applied - Samsung captures carry their
     *  rotation in EXIF, and BitmapFactory.decodeFile ignores it, which uploads the
     *  photo sideways. */
    fun decodeSampledRotated(file: File, targetW: Int): Bitmap? {
        val bmp = decodeSampled(file, targetW) ?: return null
        val rotation = try {
            val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (t: Throwable) { 0 }
        if (rotation == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun decodeSampled(bytes: ByteArray, targetW: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val o2 = BitmapFactory.Options().apply { inSampleSize = Logic.computeSampleSize(o.outWidth, targetW) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }

    fun compressJpeg(src: Bitmap, maxDim: Int, quality: Int): ByteArray {
        val scale = Math.min(1f, maxDim.toFloat() / Math.max(src.width, src.height))
        val bmp = if (scale < 1f)
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Average of warm pixels (red/orange/pink hues). Falls back to overall average. */
    fun memoryColor(bmp: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bmp, 48, 48, true)
        val hsv = FloatArray(3)
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var n = 0L
        var aR = 0L; var aG = 0L; var aB = 0L; var aN = 0L
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            aR += r; aG += g; aB += b; aN++
            Color.RGBToHSV(r, g, b, hsv)
            val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
            if ((h <= 55f || h >= 300f) && s > 0.18f && v > 0.15f) {
                rSum += r; gSum += g; bSum += b; n++
            }
        }
        val base = if (n > 12)
            Color.rgb((rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt())
        else if (aN > 0)
            Color.rgb((aR / aN).toInt(), (aG / aN).toInt(), (aB / aN).toInt())
        else Color.rgb(0xC9, 0xA6, 0x9A)
        return soften(base)
    }

    /** Soften toward warm sand so the memory tone stays gentle. */
    private fun soften(color: Int): Int {
        val mix = { ch: Int, sand: Int -> (ch * 0.72f + sand * 0.28f).toInt().coerceIn(0, 255) }
        return Color.rgb(mix(Color.red(color), 0xE8), mix(Color.green(color), 0xD9), mix(Color.blue(color), 0xCB))
    }

    /** Scale down so width*height <= maxPx. Widget bitmaps travel to the launcher
     *  inside one binder transaction parceled as raw pixels (~4 B/px); oversized
     *  bitmaps make updateAppWidget throw TransactionTooLargeException and the
     *  whole update is lost. */
    fun capPixels(src: Bitmap, maxPx: Int): Bitmap {
        val dims = Logic.cappedDimensions(src.width, src.height, maxPx)
        if (dims[0] == src.width && dims[1] == src.height) return src
        return Bitmap.createScaledBitmap(src, dims[0], dims[1], true)
    }

    /** Rounded-corner copy for the widget photo: consistent corners below API 31
     *  (where the launcher does not clip widgets) and a deliberate, slightly
     *  stronger round above it. Radius scales with the bitmap so every fade
     *  stage matches. */
    fun rounded(src: Bitmap): Bitmap {
        val r = (src.width * 0.07f).coerceIn(10f, 64f)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), r, r, p)
        return out
    }

    /** Blur `radius` then overlay `color` at `overlayAlpha` (0..255). */
    fun staged(bmp: Bitmap, radius: Int, overlayColor: Int, overlayAlpha: Int): Bitmap {
        val blurred = if (radius >= 1) Blur.apply(bmp, radius) else bmp.copy(Bitmap.Config.ARGB_8888, true)
        if (overlayAlpha > 0) {
            val cv = Canvas(blurred)
            cv.drawColor(Color.argb(overlayAlpha, Color.red(overlayColor), Color.green(overlayColor), Color.blue(overlayColor)))
        }
        return blurred
    }
}
