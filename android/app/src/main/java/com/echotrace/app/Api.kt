package com.echotrace.app

import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Api {
    private val BASE = BuildConfig.SERVER_URL.trimEnd('/')

    private fun conn(path: String, method: String): HttpURLConnection =
        (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json")
            // Old Android/Samsung HTTP stacks can reuse a half-closed socket and
            // fail with "unexpected end of stream". Prefer a fresh connection.
            setRequestProperty("Connection", "close")
        }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val c = conn(path, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        DataOutputStream(c.outputStream).use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: "{}"
        c.disconnect()
        if (code !in 200..299) throw ApiException(code, text)
        return JSONObject(text.ifBlank { "{}" })
    }

    fun register(deviceId: String) = postJson("/register", JSONObject().put("deviceId", deviceId))

    fun connect(myId: String, partnerCode: String, role: String) = postJson("/connect",
        JSONObject().put("myDeviceId", myId).put("partnerCode", partnerCode).put("role", role))

    fun upload(deviceId: String, caption: String, jpeg: ByteArray): JSONObject {
        val c = conn("/upload", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "image/jpeg")
        c.setRequestProperty("X-Device-Id", deviceId)
        c.setFixedLengthStreamingMode(jpeg.size)
        if (caption.isNotBlank())
            c.setRequestProperty("X-Caption", Logic.encodeCaption(caption))
        c.outputStream.use { it.write(jpeg) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: "{}"
        c.disconnect()
        if (code !in 200..299) throw ApiException(code, text)
        return JSONObject(text.ifBlank { "{}" })
    }

    fun poll(deviceId: String): JSONObject {
        val c = conn("/poll/$deviceId", "GET")
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: "{}"
        c.disconnect()
        if (code !in 200..299) throw ApiException(code, text)
        return JSONObject(text)
    }

    fun downloadImage(deviceId: String, imageId: String): ByteArray? {
        val c = conn("/image/$imageId?deviceId=$deviceId", "GET")
        val code = c.responseCode
        val bytes = if (code == 200) c.inputStream.readBytes() else null
        c.disconnect()
        return bytes
    }

    fun confirmDownload(deviceId: String, imageId: String) = postJson("/confirm-download",
        JSONObject().put("deviceId", deviceId).put("imageId", imageId))

    class ApiException(val httpCode: Int, body: String) : Exception("HTTP $httpCode: ${body.take(120)}")
}
