package dev.sterrist.openfat.data.remote

import android.util.Log
import dev.sterrist.openfat.data.db.ProductSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/** Тонкий клиент неофициального API YAZIO (yzapi.yazio.com/v22). */
class YazioClient(private val http: HttpClient) {
    suspend fun login(email: String, password: String): YazioTokens {
        Log.i(TAG, "login: start email=${maskEmail(email)}")
        val payload = try {
            http.postJson(
                "$BASE_URL/oauth/token",
                buildJsonObject {
                    put("grant_type", "password")
                    put("username", email)
                    put("password", password)
                    put("client_id", CLIENT_ID)
                    put("client_secret", CLIENT_SECRET)
                },
                headers = BASE_HEADERS,
            ).obj() ?: error("YAZIO: invalid token response")
        } catch (e: Exception) {
            Log.e(TAG, "login: failed type=${e::class.simpleName} message=${e.message?.safeForLog()}")
            throw e
        }
        val tokens = parseTokens(payload)
        Log.i(TAG, "login: success expiresIn=${tokens.expiresInSeconds}s refresh=${tokens.refreshToken != null}")
        return tokens
    }

    suspend fun refresh(refreshToken: String): YazioTokens {
        Log.i(TAG, "refresh: start")
        val payload = try {
            http.postJson(
                "$BASE_URL/oauth/token",
                buildJsonObject {
                    put("grant_type", "refresh_token")
                    put("refresh_token", refreshToken)
                    put("client_id", CLIENT_ID)
                    put("client_secret", CLIENT_SECRET)
                },
                headers = BASE_HEADERS,
            ).obj() ?: error("YAZIO: invalid token response")
        } catch (e: Exception) {
            Log.e(TAG, "refresh: failed type=${e::class.simpleName} message=${e.message?.safeForLog()}")
            throw e
        }
        val tokens = parseTokens(payload)
        Log.i(TAG, "refresh: success expiresIn=${tokens.expiresInSeconds}s refresh=${tokens.refreshToken != null}")
        return tokens
    }

    suspend fun search(token: String, query: String): List<RemoteFood> {
        Log.i(TAG, "search: start query=${query.take(40)}")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val root = http.getJson(
            "$BASE_URL/products/search?query=$encoded&sex=male&countries=RU,US&locales=ru_RU",
            headers = BASE_HEADERS + ("Authorization" to "Bearer $token"),
        )
        val result = root.arr().orEmpty().mapNotNull { parseSearchItem(it.obj()) }
        Log.i(TAG, "search: success count=${result.size}")
        return result
    }

    companion object {
        private const val TAG = "OpenFat/Yazio"
        private const val BASE_URL = "https://yzapi.yazio.com/v22"
        private const val CLIENT_ID = "1_4hiybetvfksgw40o0sog4s884kwc840wwso8go4k8c04goo4c"
        private const val CLIENT_SECRET = "6rok2m65xuskgkgogw40wkkk8sw0osg84s8cggsc4woos4s8o"
        private val BASE_HEADERS = mapOf(
            "Accept" to "application/json",
            "Accept-Encoding" to "gzip",
            "X-YAZIO-Platform" to "android",
            "X-YAZIO-App-Version" to "26.39.3",
            "X-YAZIO-Build-Number" to "610011141",
            "X-YAZIO-Language" to "ru-RU",
            "X-YAZIO-Country" to "RU",
            "X-YAZIO-Timezone" to "Europe/Moscow",
        )

        private fun parseTokens(obj: JsonObject): YazioTokens {
            val access = obj["access_token"].str() ?: error("YAZIO: access token missing")
            val refresh = obj["refresh_token"].str()
            val expires = obj["expires_in"].num()?.toLong() ?: 0L
            return YazioTokens(access, refresh, expires)
        }

        private fun parseSearchItem(item: JsonObject?): RemoteFood? {
            if (item == null) return null
            val id = item["product_id"].str() ?: return null
            val name = item["name"].str() ?: return null
            val nutrients = item["nutrients"].obj()
            fun per100(key: String): Double? = nutrients?.get(key).num()?.times(100.0)
            val serving = item["serving"].str()
            val amount = item["amount"].num()
            val quantity = item["serving_quantity"].num()?.takeIf { it > 0 } ?: 1.0
            val servingGrams = if (serving != null && serving != "gram" && amount != null) amount / quantity else null
            return RemoteFood(
                source = ProductSource.YAZIO,
                sourceRef = "YAZIO:$id",
                name = name,
                brand = item["producer"].str(),
                barcode = null,
                kcal100 = per100("energy.energy"),
                protein100 = per100("nutrient.protein"),
                fat100 = per100("nutrient.fat"),
                carbs100 = per100("nutrient.carb"),
                servingGrams = servingGrams?.takeIf { it > 0 },
            )
        }
    }
}

private fun maskEmail(email: String): String {
    val value = email.trim()
    val at = value.indexOf('@')
    return if (at > 1) value.first() + "***" + value.substring(at - 1) else "***"
}

private fun String.safeForLog(): String = replace(Regex("(?i)(password|token|secret)=[^&\\s]+"), "$1=[redacted]").take(160)

data class YazioTokens(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long)

class YazioSource(
    private val client: YazioClient,
    private val accessToken: () -> String?,
    private val refreshToken: () -> String?,
    private val saveTokens: (YazioTokens) -> Unit,
    private val clearTokens: () -> Unit,
) : FoodSource {
    override val source = ProductSource.YAZIO

    override fun isEnabled(): Boolean = !accessToken().isNullOrBlank()

    override suspend fun findByBarcode(barcode: String): RemoteFood? =
        if (isValidBarcode(barcode)) searchWithToken(barcode).firstOrNull() else null

    override suspend fun search(query: String): List<RemoteFood> = searchWithToken(query)

    suspend fun login(email: String, password: String) {
        client.login(email.trim(), password).also { tokens ->
            saveTokens(tokens)
            Log.i("OpenFat/Yazio", "source: tokens saved")
        }
    }

    fun logout() {
        clearTokens()
        Log.i("OpenFat/Yazio", "source: logged out")
    }

    private suspend fun searchWithToken(query: String): List<RemoteFood> {
        val token = accessToken()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            client.search(token, query)
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            val refresh = refreshToken()?.takeIf { it.isNotBlank() } ?: throw e
            val tokens = client.refresh(refresh)
            saveTokens(tokens)
            Log.i("OpenFat/Yazio", "source: refreshed tokens after 401")
            client.search(tokens.accessToken, query)
        }
    }
}
