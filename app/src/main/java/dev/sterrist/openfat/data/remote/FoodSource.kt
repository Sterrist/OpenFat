package dev.sterrist.openfat.data.remote

import dev.sterrist.openfat.data.db.ProductSource

/** Продукт из внешнего источника. КБЖУ на 100 г могут отсутствовать. */
data class RemoteFood(
    val source: ProductSource,
    val sourceRef: String,
    val name: String,
    val brand: String?,
    val barcode: String?,
    val kcal100: Double?,
    val protein100: Double?,
    val fat100: Double?,
    val carbs100: Double?,
    val servingGrams: Double?,
) {
    val isComplete: Boolean
        get() = kcal100 != null && protein100 != null && fat100 != null && carbs100 != null
}

/** Открытый источник данных о продуктах. Новый источник подключается одной реализацией. */
interface FoodSource {
    val source: ProductSource

    /** Источник может быть выключен, например без API-ключа. */
    fun isEnabled(): Boolean

    suspend fun findByBarcode(barcode: String): RemoteFood?

    suspend fun search(query: String): List<RemoteFood>
}

/** Штрихкоды EAN-8/EAN-13/UPC-A/UPC-E/GTIN-14: только цифры. Проверка защищает URL от подстановки. */
fun isValidBarcode(code: String): Boolean = code.length in 6..14 && code.all { it in '0'..'9' }
