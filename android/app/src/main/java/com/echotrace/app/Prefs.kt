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

    fun reset(c: Context) {
        p(c).edit().clear().apply()
        c.deleteFile("current_trace.jpg")
        c.deleteFile("trace_meta.json")
    }
}
