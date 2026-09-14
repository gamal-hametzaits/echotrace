package com.echotrace.app

import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    private val H = Logic.HOUR

    // ---- computeSampleSize (Imaging.decodeSampled bounds math) ----

    @Test fun sampleSize_belowTarget_is1() {
        assertEquals(1, Logic.computeSampleSize(560, 560))
        assertEquals(1, Logic.computeSampleSize(300, 560))
    }

    @Test fun sampleSize_exactMultiple_keepsSampling() {
        // ">=" is load-bearing: 1120/2 == 560 must trigger one more halving
        assertEquals(2, Logic.computeSampleSize(1120, 560))
        assertEquals(4, Logic.computeSampleSize(2240, 560))
        assertEquals(8, Logic.computeSampleSize(4480, 560))
    }

    @Test fun sampleSize_onePixelUnder_boundary() {
        assertEquals(1, Logic.computeSampleSize(1119, 560))
        assertEquals(2, Logic.computeSampleSize(2239, 560))
    }

    @Test fun sampleSize_typicalCamera() {
        // 4000px photo decoded for a 560px widget target
        assertEquals(4, Logic.computeSampleSize(4000, 560))
        assertEquals(2, Logic.computeSampleSize(1280, 560))
        assertEquals(1, Logic.computeSampleSize(1280, 1280))
    }

    @Test fun sampleSize_hugeWidth_terminates() {
        assertEquals(128, Logic.computeSampleSize(100_000, 560))
    }

    // ---- cappedDimensions (binder pixel cap) ----

    @Test fun capPixels_underCap_unchanged() {
        assertArrayEquals(intArrayOf(400, 200), Logic.cappedDimensions(400, 200, Logic.MAX_BITMAP_PX))
        assertArrayEquals(intArrayOf(331, 332), Logic.cappedDimensions(331, 332, Logic.MAX_BITMAP_PX))
    }

    @Test fun capPixels_overCap_fitsBudget() {
        val d = Logic.cappedDimensions(1000, 200, Logic.MAX_BITMAP_PX)
        assertTrue(d[0].toLong() * d[1] <= Logic.MAX_BITMAP_PX)
        assertTrue(d[0] < 1000 && d[1] < 200)
        // aspect roughly preserved
        assertEquals(5.0, d[0].toDouble() / d[1], 0.1)
    }

    @Test fun capPixels_extremeAspect_neverZero() {
        val d = Logic.cappedDimensions(100_000, 10, Logic.MAX_BITMAP_PX)
        assertTrue(d[0] >= 1 && d[1] >= 1)
        assertTrue(d[0].toLong() * d[1] <= Logic.MAX_BITMAP_PX)
    }

    @Test fun capPixels_exactlyAtCap_unchanged() {
        assertArrayEquals(intArrayOf(550, 200), Logic.cappedDimensions(550, 200, 110_000))
    }

    // ---- hourStage / renderKey (render-on-change) ----

    @Test fun hourStage_noPhoto_none() {
        assertEquals("none", Logic.hourStage(false, 0))
        assertEquals("none", Logic.hourStage(false, 100 * H))
    }

    @Test fun hourStage_boundaries() {
        assertEquals("h0", Logic.hourStage(true, 0))
        assertEquals("h5", Logic.hourStage(true, 6 * H - 1))
        assertEquals("h6", Logic.hourStage(true, 6 * H))
        assertEquals("h23", Logic.hourStage(true, 24 * H - 1))
        assertEquals("gone", Logic.hourStage(true, 24 * H))
        assertEquals("gone", Logic.hourStage(true, 72 * H))
    }

    @Test fun renderKey_stableForSameState() {
        val k1 = Logic.renderKey("ABCD23", false, "both", "img-1", "h3", "c")
        val k2 = Logic.renderKey("ABCD23", false, "both", "img-1", "h3", "c")
        assertEquals(k1, k2)
    }

    @Test fun renderKey_changesOnEveryVisibleField() {
        val base = Logic.renderKey("ABCD23", false, "both", "img-1", "h3", "c")
        assertNotEquals(base, Logic.renderKey("XXXX99", false, "both", "img-1", "h3", "c"))
        assertNotEquals(base, Logic.renderKey("ABCD23", true, "both", "img-1", "h3", "c"))
        assertNotEquals(base, Logic.renderKey("ABCD23", false, "sender", "img-1", "h3", "c"))
        assertNotEquals(base, Logic.renderKey("ABCD23", false, "both", "img-2", "h3", "c"))
        assertNotEquals(base, Logic.renderKey("ABCD23", false, "both", "img-1", "h4", "c"))
        assertNotEquals(base, Logic.renderKey("ABCD23", false, "both", "img-1", "gone", "c"))
        assertNotEquals(base, Logic.renderKey("ABCD23", false, "both", "img-1", "h3", "f"))
        assertNotEquals(base, Logic.renderKey(null, false, "both", "img-1", "h3", "c"))
    }

    // ---- caption encode/decode round-trip (app encode vs server decode) ----

    /** Mirror of the server: (header||'').replace(/\+/g,' ') -> decodeURIComponent
     *  -> strip control chars -> slice(0,80). */
    private fun serverDecode(header: String): String = try {
        java.net.URLDecoder.decode(header.replace("+", " "), "UTF-8")
            .replace(Regex("[\\u0000-\\u001f]"), "").take(80)
    } catch (e: Exception) { "" }

    @Test fun caption_roundTrip_hebrewWithSpaces() {
        val cap = "בוקר טוב מהצד השני"
        assertEquals(cap, serverDecode(Logic.encodeCaption(cap)))
    }

    @Test fun caption_roundTrip_literalPlusAndSpecials() {
        val cap = "א+ב & 100% = סוף? #כן"
        assertEquals(cap, serverDecode(Logic.encodeCaption(cap)))
    }

    @Test fun caption_encoded_neverContainsRawPlus() {
        assertFalse(Logic.encodeCaption("a b + c").contains("+"))
    }

    @Test fun caption_roundTrip_emoji() {
        val cap = "אוהב אותך ❤️🌅"
        assertEquals(cap, serverDecode(Logic.encodeCaption(cap)))
    }

    @Test fun caption_appTruncatesAt50() {
        val cap = "א".repeat(60)
        assertEquals("א".repeat(50), serverDecode(Logic.encodeCaption(cap)))
    }

    @Test fun caption_emptyAndBlank() {
        assertEquals("", Logic.encodeCaption(""))
        assertEquals("", serverDecode(Logic.encodeCaption("")))
    }

    // ---- fade / age calculations ----

    @Test fun fadeStage_boundaries() {
        assertEquals(0, Logic.fadeStage(0))
        assertEquals(0, Logic.fadeStage(6 * H - 1))
        assertEquals(1, Logic.fadeStage(6 * H))
        assertEquals(1, Logic.fadeStage(12 * H - 1))
        assertEquals(2, Logic.fadeStage(12 * H))
        assertEquals(2, Logic.fadeStage(24 * H - 1))
        assertEquals(3, Logic.fadeStage(24 * H))
        assertEquals(3, Logic.fadeStage(100 * H))
    }

    @Test fun fadeRadius_ramp() {
        assertEquals(0, Logic.fadeRadiusLogical(0))
        assertEquals(0, Logic.fadeRadiusLogical(6 * H - 1))
        assertEquals(0, Logic.fadeRadiusLogical(6 * H))           // t=0 at the window edge
        assertEquals(12, Logic.fadeRadiusLogical(9 * H))          // halfway
        assertEquals(24, Logic.fadeRadiusLogical(12 * H - 1))
        assertEquals(25, Logic.fadeRadiusLogical(12 * H))
        assertEquals(25, Logic.fadeRadiusLogical(23 * H))
    }

    @Test fun captionAlpha_ramp() {
        assertEquals(1f, Logic.captionAlpha(0), 0.001f)
        assertEquals(1f, Logic.captionAlpha(6 * H), 0.001f)
        assertEquals(0.5f, Logic.captionAlpha(9 * H), 0.001f)
        assertEquals(0f, Logic.captionAlpha(12 * H), 0.001f)
    }

    @Test fun memoryOverlay_ramp() {
        assertEquals(0, Logic.memoryOverlayAlpha(0))
        assertEquals(0, Logic.memoryOverlayAlpha(12 * H))
        assertEquals(117, Logic.memoryOverlayAlpha(18 * H))
        assertEquals(234, Logic.memoryOverlayAlpha(24 * H - 1))
        assertEquals(0, Logic.memoryOverlayAlpha(24 * H))         // stage 3 uses solid color instead
    }

    @Test fun blurPx_scalesWithBitmap() {
        assertEquals(15, Logic.blurPx(25, 560))
        assertEquals(7, Logic.blurPx(25, 280))
        assertEquals(0, Logic.blurPx(0, 560))
        assertEquals(0, Logic.blurPx(1, 100))   // tiny bitmaps round down to no-op blur
    }
}
