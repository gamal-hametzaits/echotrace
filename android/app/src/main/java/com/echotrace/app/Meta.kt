package com.echotrace.app

import android.content.Context
import org.json.JSONObject
import java.io.File

data class TraceMeta(
    val imageId: String,
    val caption: String,
    val sentAt: Long,
    val exposedAt: Long,
    val viewed: Boolean
) {
    fun toJson() = JSONObject().apply {
        put("imageId", imageId); put("caption", caption)
        put("sentAt", sentAt); put("exposedAt", exposedAt); put("viewed", viewed)
    }.toString()
    companion object {
        fun fromJson(s: String) = JSONObject(s).let {
            TraceMeta(it.getString("imageId"), it.optString("caption",""),
                it.getLong("sentAt"), it.getLong("exposedAt"), it.optBoolean("viewed", false))
        }
        fun load(c: Context): TraceMeta? = try {
            val f = File(c.filesDir, "trace_meta.json")
            if (f.exists()) fromJson(f.readText()) else null
        } catch (e: Exception) { null }
        fun save(c: Context, m: TraceMeta) {
            File(c.filesDir, "trace_meta.json").writeText(m.toJson())
        }
        fun clear(c: Context) {
            c.deleteFile("trace_meta.json"); c.deleteFile("current_trace.jpg")
        }
    }
}
