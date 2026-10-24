package dev.sterrist.openfat.data.backup

import dev.sterrist.openfat.data.db.MealEntity
import dev.sterrist.openfat.data.db.MedicationEntity
import dev.sterrist.openfat.data.db.MedicationIntakeEntity
import dev.sterrist.openfat.data.db.ProductEntity
import dev.sterrist.openfat.data.remote.isValidBarcode
import dev.sterrist.openfat.data.repo.SettingsRepository
import dev.sterrist.openfat.domain.Norm
import dev.sterrist.openfat.domain.Repeat
import java.util.UUID

/**
 * Проверка и слияние файла импорта. Файл — недоверенные данные: всё невалидное
 * отбрасывается и учитывается в сводке, ссылки между сущностями проверяются.
 */
object ImportPlanner {
    private const val MAX_TEXT = 200
    private const val MAX_DAY = 200_000L // ~2517 год
    private const val MAX_PER100_KCAL = 1000.0
    private const val MAX_PER100_GRAMS = 100.0
    private const val MAX_ENTRY_KCAL = 100_000.0

    data class Sanitized(val file: BackupFile, val skipped: Int)

    fun sanitize(file: BackupFile): Sanitized {
        var skipped = 0
        fun <T> List<T>.keep(valid: (T) -> Boolean): List<T> = filter { item ->
            valid(item).also { if (!it) skipped++ }
        }

        val seenBarcodes = HashSet<String>()
        val products = file.products.distinctBy { it.id }.keep { p ->
            validId(p.id) && validText(p.name) && optText(p.brand) &&
                validPer100(p.kcal100, MAX_PER100_KCAL) && validPer100(p.protein100) &&
                validPer100(p.fat100) && validPer100(p.carbs100) &&
                optPositive(p.servingGrams) && optPositive(p.pieceGrams)
        }.map { p ->
            val barcode = p.barcode?.takeIf { isValidBarcode(it) && seenBarcodes.add(it) }
            p.copy(barcode = barcode, searchKey = ProductEntity.searchKeyOf(p.name, p.brand))
        }
        val meals = file.meals.distinctBy { it.id }.keep { m ->
            validId(m.id) && validText(m.name) && (m.oneTimeDate == null || validDay(m.oneTimeDate))
        }
        val snacks = file.snacks.distinctBy { it.id }.keep { s ->
            validId(s.id) && validDay(s.date) && s.timeMinutes in 0 until 24 * 60
        }
        val mealIds = meals.mapTo(HashSet()) { it.id }
        val snackIds = snacks.mapTo(HashSet()) { it.id }
        val productIds = products.mapTo(HashSet()) { it.id }
        val entries = file.entries.distinctBy { it.id }.keep { e ->
            validId(e.id) && validDay(e.date) && validText(e.name) && optText(e.brand) &&
                ((e.mealId != null && e.mealId in mealIds) xor (e.snackId != null && e.snackId in snackIds)) &&
                e.kcal.isFinite() && e.kcal in 0.0..MAX_ENTRY_KCAL &&
                listOf(e.protein, e.fat, e.carbs, e.amount, e.grams).all { it == null || (it.isFinite() && it >= 0) } &&
                listOf(e.kcal100, e.protein100, e.fat100, e.carbs100).all { it == null || validPer100(it, MAX_PER100_KCAL) }
        }.map { e -> if (e.productId != null && e.productId !in productIds) e.copy(productId = null) else e }
        val weights = file.weights.distinctBy { it.date }.distinctBy { it.id }.keep { w ->
            validId(w.id) && validDay(w.date) && w.kg.isFinite() && w.kg in 1.0..700.0 && optText(w.note, 1000)
        }
        val norms = file.norms.distinctBy { it.effectiveFrom }.keep { n ->
            n.effectiveFrom in 0..MAX_DAY && Norm.isValid(n.kcal, n.proteinPct, n.fatPct, n.carbsPct)
        }
        val settings = file.settings.filter { it.key in SettingsRepository.IMPORTABLE_KEYS }
            .keep { SettingsRepository.isValidImportedSetting(it.key, it.value) }
        val medications = file.medications.distinctBy { it.id }.keep { m ->
            validId(m.id) && validText(m.name) && optText(m.dose) && m.times.length <= 1000 &&
                MedicationEntity.parseTimes(m.times).isNotEmpty() && m.startAt >= 0 && m.createdAt >= 0 &&
                m.repeatUnit in Repeat.UNIT_DAY..Repeat.UNIT_MONTH && m.repeatEvery in 1..Repeat.MAX_EVERY &&
                validDay(m.repeatAnchor)
        }.map { m -> m.copy(times = MedicationEntity.formatTimes(m.timeList)) }
        val medicationIds = medications.mapTo(HashSet()) { it.id }
        val intakes = file.intakes.distinctBy { it.id }.distinctBy { intakeKey(it) }.keep { i ->
            validId(i.id) && i.medicationId in medicationIds && validDay(i.date) && i.time in 0 until 24 * 60 && i.at >= 0
        }

        return Sanitized(
            file.copy(
                products = products, meals = meals, snacks = snacks, entries = entries,
                weights = weights, norms = norms, settings = settings,
                medications = medications, intakes = intakes,
            ),
            skipped,
        )
    }

    fun planReplace(sanitized: Sanitized): ImportPlan {
        val f = sanitized.file
        return ImportPlan(
            mode = ImportMode.REPLACE,
            products = f.products, meals = f.meals, snacks = f.snacks, entries = f.entries,
            weights = f.weights, norms = f.norms, settings = f.settings,
            medications = f.medications, intakes = f.intakes,
            summary = ImportSummary(
                products = f.products.size, meals = f.meals.size, snacks = f.snacks.size,
                entries = f.entries.size, weights = f.weights.size, weightConflicts = 0,
                skippedInvalid = sanitized.skipped, medications = f.medications.size,
            ),
        )
    }

    /**
     * Слияние: записи с тем же ID пропускаются, продукты объединяются по штрихкоду,
     * приёмы — по названию, при конфликте веса на одну дату побеждает файл.
     */
    fun planMerge(sanitized: Sanitized, local: LocalSnapshot, newId: () -> String = { UUID.randomUUID().toString() }): ImportPlan {
        val f = sanitized.file

        // Продукты
        val localProductIds = local.products.mapTo(HashSet()) { it.id }
        val localByBarcode = local.products.filter { it.barcode != null }.associateBy { it.barcode!! }
        val productIdMap = HashMap<String, String>()
        val newProducts = ArrayList<ProductEntity>()
        for (p in f.products) {
            val match = when {
                p.id in localProductIds -> p.id
                p.barcode != null -> localByBarcode[p.barcode]?.id
                else -> null
            }
            if (match != null) productIdMap[p.id] = match else newProducts += p.also { productIdMap[p.id] = p.id }
        }

        // Приёмы пищи
        val localMealIds = local.meals.mapTo(HashSet()) { it.id }
        fun mealKey(name: String, oneTimeDate: Long?) = name.trim().lowercase() to oneTimeDate
        // Активные приёмы имеют приоритет над архивными с тем же названием.
        val localMealsByKey = local.meals.sortedByDescending { it.archived }.associateBy { mealKey(it.name, it.oneTimeDate) }
        val mealIdMap = HashMap<String, String>()
        val newMeals = ArrayList<MealEntity>()
        var nextPosition = (local.meals.maxOfOrNull { it.position } ?: -1) + 1
        for (m in f.meals.sortedBy { it.position }) {
            val match = if (m.id in localMealIds) m.id else localMealsByKey[mealKey(m.name, m.oneTimeDate)]?.id
            if (match != null) {
                mealIdMap[m.id] = match
            } else {
                mealIdMap[m.id] = m.id
                newMeals += m.copy(position = nextPosition++)
            }
        }

        // Перекусы
        val newSnacks = f.snacks.filter { it.id !in local.snackIds }

        // Записи еды
        val newEntries = f.entries.filter { it.id !in local.entryIds }.map { e ->
            e.copy(
                mealId = e.mealId?.let { mealIdMap.getValue(it) },
                productId = e.productId?.let { productIdMap[it] },
            )
        }

        // Вес
        val localByDate = local.weights.associateBy { it.date }
        val localWeightIds = local.weights.mapTo(HashSet()) { it.id }
        var conflicts = 0
        val weights = f.weights.mapNotNull { w ->
            val existing = localByDate[w.date]
            when {
                existing == null -> if (w.id in localWeightIds) w.copy(id = newId()) else w
                existing.kg == w.kg && existing.note == w.note -> null
                else -> {
                    conflicts++
                    w.copy(id = existing.id)
                }
            }
        }

        // Препараты и отметки: совпадающие по ID и уже отмеченные приёмы не трогаем.
        val newMedications = f.medications.filter { it.id !in local.medicationIds }
        val newIntakes = f.intakes.filter { it.id !in local.intakeIds && intakeKey(it) !in local.intakeKeys }

        return ImportPlan(
            mode = ImportMode.MERGE,
            products = newProducts,
            meals = newMeals,
            snacks = newSnacks,
            entries = newEntries,
            weights = weights,
            norms = f.norms,
            settings = f.settings,
            medications = newMedications,
            intakes = newIntakes,
            summary = ImportSummary(
                products = newProducts.size,
                meals = newMeals.size,
                snacks = newSnacks.size,
                entries = newEntries.size,
                weights = weights.size - conflicts,
                weightConflicts = conflicts,
                skippedInvalid = sanitized.skipped,
                medications = newMedications.size,
            ),
        )
    }

    fun intakeKey(i: MedicationIntakeEntity) = "${i.medicationId}|${i.date}|${i.time}"

    private fun validId(id: String) = id.isNotBlank() && id.length <= 64
    private fun validText(s: String) = s.isNotBlank() && s.length <= MAX_TEXT
    private fun optText(s: String?, max: Int = MAX_TEXT) = s == null || s.length <= max
    private fun validDay(day: Long) = day in 0..MAX_DAY
    private fun validPer100(v: Double, max: Double = MAX_PER100_GRAMS) = v.isFinite() && v in 0.0..max
    private fun optPositive(v: Double?) = v == null || (v.isFinite() && v > 0 && v <= 10_000)
}
