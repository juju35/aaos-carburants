package me.jstephan.carburants.data

import me.jstephan.carburants.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpException(val code: Int, message: String) : IOException(message)

/** Blocking HTTP helpers: call them from Dispatchers.IO. */
internal object Http {
    // Wikimedia and Overpass ask for an identifying User-Agent.
    const val USER_AGENT = "Carburants-AAOS/${BuildConfig.VERSION_NAME} (+https://github.com/juju35/aaos-carburants)"

    fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }

    fun get(url: String, timeoutMs: Int = 15_000): ByteArray = request(url, timeoutMs, null)

    fun getText(url: String, timeoutMs: Int = 15_000): String = get(url, timeoutMs).decodeToString()

    fun postForm(url: String, form: Map<String, String>, timeoutMs: Int = 40_000): String =
        request(url, timeoutMs, query(form).encodeToByteArray()).decodeToString()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun request(url: String, timeoutMs: Int, body: ByteArray?): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.use { it.readBytes() }?.decodeToString()?.take(200).orEmpty()
                throw HttpException(code, "HTTP $code $detail")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
