package dev.sterrist.openfat.data.remote

import android.util.Log
import dev.sterrist.openfat.data.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.net.HttpURLConnection
import java.net.URI

class HttpException(val code: Int, val responseBody: String? = null) : IOException(
    "HTTP $code${responseBody?.let { ": ${it.take(300)}" }.orEmpty()}",
)

/** Минимальный JSON GET без сторонних HTTP-библиотек. */
class HttpClient(
    private val userAgent: String,
    private val timeoutMs: Int = 10_000,
    private val maxBytes: Int = 4 * 1024 * 1024,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        requestJson("GET", url, headers, null)

    suspend fun postJson(url: String, body: JsonElement, headers: Map<String, String> = emptyMap()): JsonElement =
        requestJson("POST", url, headers, body)

    private suspend fun requestJson(method: String, url: String, headers: Map<String, String>, body: JsonElement?): JsonElement =
        runInterruptible(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            val requestText = body?.toString()?.redactSecrets()
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "request method=$method url=$url headers=${headers.redactHeaders()} body=$requestText")
            }
            try {
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.instanceFollowRedirects = true
                connection.requestMethod = method
                connection.doOutput = body != null
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                if (body != null) {
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val encoding = connection.contentEncoding.orEmpty()
                val responseStream = if (code in 200..299) connection.inputStream else connection.errorStream
                val bytes = (responseStream ?: throw HttpException(code)).let { raw ->
                    if (encoding.equals("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
                }.use { input ->
                    val buffer = input.readAtMost(maxBytes + 1)
                    if (buffer.size > maxBytes) throw IOException("Response too large")
                    buffer
                }
                val responseText = bytes.toString(Charsets.UTF_8)
                val safeResponseText = responseText.redactSecrets()
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "response code=$code encoding=${encoding.ifBlank { "identity" }} body=${safeResponseText.take(1000)}")
                }
                if (code !in 200..299) throw HttpException(code, safeResponseText)
                json.parseToJsonElement(responseText)
            } finally {
                connection.disconnect()
            }
        }
}

private const val TAG = "OpenFat/Http"

private fun Map<String, String>.redactHeaders(): String = entries.joinToString(", ") { (key, value) ->
    val safe = if (key.equals("Authorization", ignoreCase = true) || key.contains("token", ignoreCase = true)) "[redacted]" else value
    "$key=$safe"
}

private fun String.redactSecrets(): String = replace(
    Regex("(?i)(password|access_token|refresh_token|client_secret|authorization)\\\"?\\s*[:=]\\s*\\\"?[^,}\\\"&\\s]+"),
) { match ->
    match.value.substringBefore(":").substringBefore("=") + "=[redacted]"
}
