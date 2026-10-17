package dev.sterrist.openfat.data.remote

import dev.sterrist.openfat.data.db.ProductSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * Open Food Facts (ODbL). Штрихкод — API v2, поиск по названию — search.openfoodfacts.org,
 * потому что старый cgi/search.pl медленный и часто недоступен.
 */
class OpenFoodFactsSource(private val http: HttpClient) : FoodSource {
    override val source = ProductSource.OFF

    override fun isEnabled() = true

    override suspend fun findByBarcode(barcode: String): RemoteFood? {
        if (!isValidBarcode(barcode)) return null
        val url = "https://world.openfoodfacts.org/api/v2/product/$barcode?fields=$FIELDS"
        return parseProductResponse(http.getJson(url))
    }

    override suspend fun search(query: String): List<RemoteFood> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://search.openfoodfacts.org/search?q=$q&langs=ru,en&page_size=20&fields=$FIELDS"
        return parseSearchResponse(http.getJson(url))
    }

    companion object {
        private const val FIELDS = "code,product_name,product_name_ru,brands,nutriments,serving_quantity"
        private const val KJ_PER_KCAL = 4.184

        fun parseProductResponse(root: JsonElement): RemoteFood? {
            val obj = root.obj() ?: return null
            if (obj["status"].num()?.toInt() != 1) return null
            return parseProduct(obj["product"].obj())
        }

        fun parseSearchResponse(root: JsonElement): List<RemoteFood> =
            root.obj()?.get("hits").arr().orEmpty().mapNotNull { parseProduct(it.obj()) }

        fun parseProduct(product: JsonObject?): RemoteFood? {
            if (product == null) return null
            val code = product["code"].str() ?: return null
            // Несуществующий штрихкод возвращает status=1 с пустым продуктом — без названия не берём.
            val name = product["product_name_ru"].str() ?: product["product_name"].str() ?: return null
            val brands = product["brands"]
            val brand = brands.arr()?.mapNotNull { it.str() }?.joinToString(", ")?.takeIf { it.isNotEmpty() }
                ?: brands.str()
            val n = product["nutriments"].obj()
            val kcal = n?.get("energy-kcal_100g").num() ?: n?.get("energy_100g").num()?.div(KJ_PER_KCAL)
            return RemoteFood(
                source = ProductSource.OFF,
                sourceRef = "OFF:$code",
                name = name,
                brand = brand,
                barcode = code.takeIf { isValidBarcode(it) },
                kcal100 = kcal,
                protein100 = n?.get("proteins_100g").num(),
                fat100 = n?.get("fat_100g").num(),
                carbs100 = n?.get("carbohydrates_100g").num(),
                servingGrams = product["serving_quantity"].num()?.takeIf { it > 0 },
            )
        }
    }
}
