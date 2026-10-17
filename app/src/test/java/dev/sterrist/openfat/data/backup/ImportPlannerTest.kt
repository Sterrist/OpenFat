package dev.sterrist.openfat.data.backup

import dev.sterrist.openfat.data.db.FoodEntryEntity
import dev.sterrist.openfat.data.db.MealEntity
import dev.sterrist.openfat.data.db.NormEntity
import dev.sterrist.openfat.data.db.ProductEntity
import dev.sterrist.openfat.data.db.ProductSource
import dev.sterrist.openfat.data.db.SnackEntity
import dev.sterrist.openfat.data.db.WeightEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportPlannerTest {
    private fun product(id: String, barcode: String?) = ProductEntity(
        id, "Гречка", null, barcode, 340.0, 13.0, 3.0, 66.0, null, null, ProductSource.USER, null, "", 0, 0,
    )

    private fun entry(id: String, mealId: String? = "m1", snackId: String? = null, productId: String? = null, kcal: Double = 100.0) =
        FoodEntryEntity(
            id, 20_000, mealId, snackId, productId, "Еда", null, null, null, null,
            null, null, null, null, null, null, kcal, null, null, null, 0,
        )

    private val fileMeal = MealEntity("m1", "Завтрак", 0, null, false)

    @Test
    fun sanitizeDropsInvalidAndDanglingRecords() {
        val file = BackupFile(
            meals = listOf(fileMeal),
            snacks = listOf(SnackEntity("s1", 20_000, 9999)), // время вне суток
            entries = listOf(
                entry("ok"),
                entry("dangling", mealId = "nope"),
                entry("bad-kcal", kcal = Double.NaN),
                entry("to-missing-snack", mealId = null, snackId = "s1"),
                entry("unknown-product", productId = "p-missing"),
            ),
            weights = listOf(WeightEntity("w1", 20_000, 80.0, null), WeightEntity("w2", 20_001, -5.0, null)),
            norms = listOf(NormEntity(0, 2000, 30, 30, 40), NormEntity(1, 2000, 50, 50, 50)),
        )
        val s = ImportPlanner.sanitize(file)
        assertEquals(listOf("ok", "unknown-product"), s.file.entries.map { it.id })
        assertNull("ссылка на отсутствующий продукт обнуляется", s.file.entries[1].productId)
        assertEquals(1, s.file.weights.size)
        assertEquals(1, s.file.norms.size)
        assertEquals(6, s.skipped) // перекус, 3 записи, вес, норма
    }

    @Test
    fun mergeMapsProductsByBarcodeAndMealsByName() {
        val file = BackupFile(
            products = listOf(product("p-file", "4600000000000"), product("p-new", null)),
            meals = listOf(fileMeal, MealEntity("m2", "Полдник", 1, null, false)),
            entries = listOf(entry("e1", productId = "p-file"), entry("e2", mealId = "m2", productId = "p-new")),
        )
        val local = LocalSnapshot(
            products = listOf(product("p-local", "4600000000000")),
            meals = listOf(MealEntity("local-breakfast", "завтрак ", 0, null, false)),
            snackIds = emptySet(),
            entryIds = emptySet(),
            weights = emptyList(),
        )
        val plan = ImportPlanner.planMerge(ImportPlanner.sanitize(file), local)
        assertEquals(listOf("p-new"), plan.products.map { it.id })
        assertEquals(listOf("m2"), plan.meals.map { it.id })
        assertEquals(1, plan.meals[0].position)
        val e1 = plan.entries.first { it.id == "e1" }
        assertEquals("local-breakfast", e1.mealId)
        assertEquals("p-local", e1.productId)
        assertEquals(2, plan.summary.entries)
    }

    @Test
    fun mergeSkipsKnownEntriesAndFileWinsWeightConflicts() {
        val file = BackupFile(
            meals = listOf(fileMeal),
            entries = listOf(entry("known"), entry("fresh")),
            weights = listOf(
                WeightEntity("wf1", 100, 81.0, null), // конфликт
                WeightEntity("wf2", 101, 80.0, null), // совпадает
                WeightEntity("wf3", 102, 79.5, null), // новая дата
            ),
        )
        val local = LocalSnapshot(
            products = emptyList(),
            meals = listOf(MealEntity("m1", "Завтрак", 0, null, false)),
            snackIds = emptySet(),
            entryIds = setOf("known"),
            weights = listOf(WeightEntity("wl1", 100, 80.0, null), WeightEntity("wl2", 101, 80.0, null)),
        )
        val plan = ImportPlanner.planMerge(ImportPlanner.sanitize(file), local)
        assertEquals(listOf("fresh"), plan.entries.map { it.id })
        assertEquals(1, plan.summary.weightConflicts)
        assertEquals(1, plan.summary.weights)
        val conflict = plan.weights.first { it.date == 100L }
        assertEquals("локальный id сохраняется", "wl1", conflict.id)
        assertEquals(81.0, conflict.kg, 1e-9)
    }

    @Test
    fun backupRoundTripsThroughJson() {
        val file = BackupFile(exportedAt = 1, meals = listOf(fileMeal), entries = listOf(entry("e1")))
        val json = Json { encodeDefaults = true }
        val text = json.encodeToString(BackupFile.serializer(), file)
        assertEquals(file, Json { ignoreUnknownKeys = true }.decodeFromString(BackupFile.serializer(), text))
    }
}
