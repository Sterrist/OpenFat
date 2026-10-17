package dev.sterrist.openfat.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun offProductByBarcode() {
        val food = OpenFoodFactsSource.parseProductResponse(
            json(
                """{"code":"3017620422003","status":1,"product":{"code":"3017620422003","product_name":"Nutella",
                "brands":"Nutella, Ferrero","serving_quantity":"15",
                "nutriments":{"energy-kcal_100g":539,"proteins_100g":6.3,"fat_100g":30.9,"carbohydrates_100g":57.5}}}""",
            ),
        )!!
        assertEquals("Nutella", food.name)
        assertEquals("Nutella, Ferrero", food.brand)
        assertEquals("3017620422003", food.barcode)
        assertEquals(539.0, food.kcal100!!, 1e-9)
        assertEquals(15.0, food.servingGrams!!, 1e-9)
        assertTrue(food.isComplete)
    }

    @Test
    fun offUnknownBarcodeReturnsStatus1WithoutName() {
        val food = OpenFoodFactsSource.parseProductResponse(
            json("""{"code":"1234567890123","product":{"code":"1234567890123"},"status":1,"status_verbose":"product found"}"""),
        )
        assertNull(food)
    }

    @Test
    fun offKilojoulesFallbackAndRussianName() {
        val food = OpenFoodFactsSource.parseProduct(
            json(
                """{"code":"4600000000000","product_name":"Buckwheat","product_name_ru":"Гречка",
                "nutriments":{"energy_100g":"1422","proteins_100g":13}}""",
            ) as kotlinx.serialization.json.JsonObject,
        )!!
        assertEquals("Гречка", food.name)
        assertEquals(1422 / 4.184, food.kcal100!!, 1e-6)
        assertFalse("нет жиров и углеводов", food.isComplete)
    }

    @Test
    fun offSearchHitsWithBrandArray() {
        val list = OpenFoodFactsSource.parseSearchResponse(
            json(
                """{"hits":[{"code":"4813494063866","brands":["Любимая","Кухня"],"product_name":"Гречка",
                "nutriments":{"fat_100g":3,"proteins_100g":13,"energy-kcal_100g":340,"carbohydrates_100g":66}},
                {"code":"1","product_name":""}]}""",
            ),
        )
        assertEquals(1, list.size)
        assertEquals("Любимая, Кухня", list[0].brand)
        assertTrue(list[0].isComplete)
    }

    @Test
    fun barcodeValidation() {
        assertTrue(isValidBarcode("4600000000000"))
        assertFalse(isValidBarcode("46000/../x"))
        assertFalse(isValidBarcode("123"))
    }
}
