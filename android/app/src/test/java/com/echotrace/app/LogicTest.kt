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

    // ---- stepStage / renderKey (render-on-change, 30-minute steps) ----

    @Test fun stepStage_noPhoto_none() {
        assertEquals("none", Logic.stepStage(false, 0))
        assertEquals("none", Logic.stepStage(false, 100 * H))
    }

    @Test fun stepStage_boundaries() {
        val M = 60_000L
        assertEquals("s0", Logic.stepStage(true, 0))
        assertEquals("s0", Logic.stepStage(true, 30 * M - 1))
        assertEquals("s1", Logic.stepStage(true, 30 * M))
        assertEquals("s2", Logic.stepStage(true, H))
        assertEquals("s47", Logic.stepStage(true, 24 * H - 1))
        assertEquals("gone", Logic.stepStage(true, 24 * H))
        assertEquals("gone", Logic.stepStage(true, 72 * H))
    }

    @Test fun stepStage_exactly48StepsPerDay() {
        val keys = (0 until 48 * 30).map { Logic.stepStage(true, it * 60_000L) }.distinct()
        assertEquals(48, keys.size)
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
        val M = 60_000L
        assertEquals(0, Logic.fadeStage(0))
        assertEquals(0, Logic.fadeStage(30 * M - 1))
        assertEquals(1, Logic.fadeStage(30 * M))
        assertEquals(1, Logic.fadeStage(12 * H - 1))
        assertEquals(2, Logic.fadeStage(12 * H))
        assertEquals(2, Logic.fadeStage(24 * H - 1))
        assertEquals(3, Logic.fadeStage(24 * H))
        assertEquals(3, Logic.fadeStage(100 * H))
    }

    @Test fun fadeRadius_changesEveryHalfHourAndNeverJumps() {
        val M = 60_000L
        assertEquals(0, Logic.fadeRadiusLogical(0))
        assertEquals(0, Logic.fadeRadiusLogical(30 * M - 1))
        assertEquals(1, Logic.fadeRadiusLogical(30 * M))
        assertEquals(12, Logic.fadeRadiusLogical(6 * H))
        assertEquals(23, Logic.fadeRadiusLogical(12 * H - 1))
        assertEquals(25, Logic.fadeRadiusLogical(12 * H))
        assertEquals(25, Logic.fadeRadiusLogical(23 * H))
        var prev = 0
        for (i in 0 until 48) {
            val r = Logic.fadeRadiusLogical(i * 30 * M)
            assertTrue("radius never decreases", r >= prev)
            assertTrue("no jump bigger than 2 logical px per step", r - prev <= 2)
            prev = r
        }
        // every step in the first 12 h really changes the picture
        assertEquals(24, (1..23).count { Logic.fadeRadiusLogical(it * 30 * M) != Logic.fadeRadiusLogical((it - 1) * 30 * M) } + 1)
    }

    @Test fun captionAlpha_ramp() {
        assertEquals(1f, Logic.captionAlpha(0), 0.001f)
        assertEquals(1f, Logic.captionAlpha(6 * H), 0.001f)
        assertEquals(0.5f, Logic.captionAlpha(9 * H), 0.001f)
        assertEquals(0f, Logic.captionAlpha(12 * H), 0.001f)
    }

    @Test fun memoryOverlay_ramp() {
        assertEquals(0, Logic.memoryOverlayAlpha(0))
        assertEquals(0, Logic.memoryOverlayAlpha(12 * H - 1))
        assertEquals(9, Logic.memoryOverlayAlpha(12 * H))
        assertEquals(235, Logic.memoryOverlayAlpha(24 * H - 1))
        assertEquals(0, Logic.memoryOverlayAlpha(24 * H))         // stage 3 uses solid color instead
        var prev = 0
        for (i in 24 until 48) {
            val a = Logic.memoryOverlayAlpha(i * 30 * 60_000L)
            assertTrue(a > prev); prev = a
        }
    }

    @Test fun blurPx_scalesWithBitmap() {
        assertEquals(15, Logic.blurPx(25, 560))
        assertEquals(7, Logic.blurPx(25, 280))
        assertEquals(0, Logic.blurPx(0, 560))
        assertEquals(0, Logic.blurPx(1, 100))   // tiny bitmaps round down to no-op blur
    }

    @Test fun connState_priority() {
        assertEquals(Logic.ConnState.NONE, Logic.connState(false, false, false, false))
        assertEquals(Logic.ConnState.WAITING, Logic.connState(false, true, false, false))
        assertEquals(Logic.ConnState.CONNECTED, Logic.connState(true, false, false, false))
        assertEquals(Logic.ConnState.OFFLINE, Logic.connState(true, false, true, false))
        assertEquals(Logic.ConnState.ERROR, Logic.connState(true, false, false, true))
        assertEquals(Logic.ConnState.ERROR, Logic.connState(false, true, false, true))
        assertEquals(Logic.ConnState.OFFLINE, Logic.connState(true, true, true, false))
    }

    @Test fun agoParts_units() {
        assertArrayEquals(intArrayOf(0, 1), Logic.agoParts(5_000))
        assertArrayEquals(intArrayOf(0, 1), Logic.agoParts(-10))
        assertArrayEquals(intArrayOf(0, 59), Logic.agoParts(59 * 60_000L))
        assertArrayEquals(intArrayOf(1, 1), Logic.agoParts(H))
        assertArrayEquals(intArrayOf(1, 23), Logic.agoParts(23 * H + 59 * 60_000L))
        assertArrayEquals(intArrayOf(2, 6), Logic.agoParts(6 * 24 * H + 3 * H))
    }

    @Test fun wallpaperModes() {
        assertEquals(listOf(false, true, false, true), (0..3).map { Logic.wallpaperHome(it) })
        assertEquals(listOf(false, false, true, true), (0..3).map { Logic.wallpaperLock(it) })
    }

    @Test fun listenDelay_backsOff() {
        assertEquals(30_000L, Logic.listenDelayMs(0))
        assertEquals(30_000L, Logic.listenDelayMs(2))
        assertEquals(60_000L, Logic.listenDelayMs(3))
    }

    @Test fun wallKey_changesOnlyWhenNeeded() {
        val k = Logic.wallKey(1, "a", 2 * H + 5)
        assertEquals(k, Logic.wallKey(1, "a", 2 * H + 20 * 60_000L))   // same 30-min step
        assertNotEquals(k, Logic.wallKey(1, "a", 2 * H + 30 * 60_000L)) // next step
        assertNotEquals(k, Logic.wallKey(3, "a", 2 * H))               // mode
        assertNotEquals(k, Logic.wallKey(1, "b", 2 * H))               // new photo
        assertEquals(Logic.wallKey(1, "a", 24 * H), Logic.wallKey(1, "a", 90 * H)) // gone stays gone
    }
}
