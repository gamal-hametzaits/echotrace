package com.echotrace.app

import android.content.Context
import java.util.UUID

object Prefs {
    private const val FILE = "echotrace"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun deviceId(c: Context): String {
        var id = p(c).getString("deviceId", null)
        if (id == null) {
            id = (1..6).map { ALPHABET[UUID.randomUUID().leastSignificantBits.let { if (it < 0) -it else it }.toInt().let { i -> Math.floorMod(i, ALPHABET.length) }] }.joinToString("")
            p(c).edit().putString("deviceId", id).apply()
        }
        return id
    }
    var Context.partner: String? get() = p(this).getString("partner", null)
        set(v) = p(this).edit().putString("partner", v).apply()
    var Context.role: String get() = p(this).getString("role", "both") ?: "both"
        set(v) = p(this).edit().putString("role", v).apply()
    var Context.lastImageId: String? get() = p(this).getString("lastImageId", null)
        set(v) = p(this).edit().putString("lastImageId", v).apply()
    var Context.disconnected: Boolean get() = p(this).getBoolean("disconnected", false)
        set(v) = p(this).edit().putBoolean("disconnected", v).apply()
    var Context.registered: Boolean get() = p(this).getBoolean("registered", false)
        set(v) = p(this).edit().putBoolean("registered", v).apply()
    var Context.pendingCapturePath: String? get() = p(this).getString("pendingCapturePath", null)
        set(v) = p(this).edit().putString("pendingCapturePath", v).apply()
    var Context.lastUploadDiag: String? get() = p(this).getString("lastUploadDiag", null)
        set(v) = p(this).edit().putString("lastUploadDiag", v).apply()

    var Context.pendingPartner: String? get() = p(this).getString("pendingPartner", null)
        set(v) = p(this).edit().putString("pendingPartner", v).apply()
    var Context.lastSyncAt: Long get() = p(this).getLong("lastSyncAt", 0)
        set(v) = p(this).edit().putLong("lastSyncAt", v).apply()
    var Context.syncError: String? get() = p(this).getString("syncError", null)
        set(v) = p(this).edit().putString("syncError", v).apply()
    var Context.pendingConfirmation: String? get() = p(this).getString("pendingConfirmation", null)
        set(v) = p(this).edit().putString("pendingConfirmation", v).apply()

    var Context.foregroundSeconds: Int get() = p(this).getInt("foregroundSeconds", 30).coerceIn(30, 120)
        set(v) = p(this).edit().putInt("foregroundSeconds", v.coerceIn(30, 120)).apply()
    var Context.backgroundMinutes: Int get() = p(this).getInt("backgroundMinutes", 15).coerceIn(15, 60)
        set(v) = p(this).edit().putInt("backgroundMinutes", v.coerceIn(15, 60)).apply()
    var Context.jpegQuality: Int get() = p(this).getInt("jpegQuality", 85).coerceIn(65, 90)
        set(v) = p(this).edit().putInt("jpegQuality", v.coerceIn(65, 90)).apply()
    var Context.photoWidth: Int get() = p(this).getInt("photoWidth", 1280).coerceIn(800, 1600)
        set(v) = p(this).edit().putInt("photoWidth", v.coerceIn(800, 1600)).apply()
    var Context.wifiOnly: Boolean get() = p(this).getBoolean("wifiOnly", false)
        set(v) = p(this).edit().putBoolean("wifiOnly", v).apply()
    var Context.lang: String get() = p(this).getString("lang", "he").let { if (it == "en") "en" else "he" }
        set(v) = p(this).edit().putString("lang", if (v == "en") "en" else "he").apply()
    var Context.darkTheme: Boolean get() = p(this).getBoolean("darkTheme", true)
        set(v) = p(this).edit().putBoolean("darkTheme", v).apply()
    var Context.animations: Boolean get() = p(this).getBoolean("animations", true)
        set(v) = p(this).edit().putBoolean("animations", v).apply()

    /** Server-reported time the partner was last seen (ms, 0 = unknown). */
    var Context.partnerLastSeenAt: Long get() = p(this).getLong("partnerLastSeenAt", 0)
        set(v) = p(this).edit().putLong("partnerLastSeenAt", v).apply()
    /** Keep a foreground listener running so photos arrive within about 30 s. */
    var Context.instantMode: Boolean get() = p(this).getBoolean("instantMode", true)
        set(v) = p(this).edit().putBoolean("instantMode", v).apply()
    /** Where a new photo shows: 0 widget only, 1 home screen, 2 lock screen, 3 both. */
    var Context.wallpaperMode: Int get() = p(this).getInt("wallpaperMode", 0).coerceIn(0, 3)
        set(v) = p(this).edit().putInt("wallpaperMode", v.coerceIn(0, 3)).apply()

    /** What the wallpaper currently shows (mode + image + fade hour), so it re-renders only on change. */
    var Context.wallKey: String? get() = p(this).getString("wallKey", null)
        set(v) = p(this).edit().putString("wallKey", v).apply()

    fun reset(c: Context) {
        val keepLang = p(c).getString("lang", "he"); val keepDark = p(c).getBoolean("darkTheme", true)
        val keepInstant = p(c).getBoolean("instantMode", true); val keepWall = p(c).getInt("wallpaperMode", 0)
        p(c).edit().clear().putString("lang", keepLang).putBoolean("darkTheme", keepDark)
            .putBoolean("instantMode", keepInstant).putInt("wallpaperMode", keepWall).apply()
        c.deleteFile("current_trace.jpg")
        c.deleteFile("trace_meta.json")
    }
}
