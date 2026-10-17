package dev.sterrist.openfat.data.repo

import androidx.room.withTransaction
import dev.sterrist.openfat.data.db.DayKcal
import dev.sterrist.openfat.data.db.FoodEntryEntity
import dev.sterrist.openfat.data.db.MealEntity
import dev.sterrist.openfat.data.db.NormEntity
import dev.sterrist.openfat.data.db.OpenFatDatabase
import dev.sterrist.openfat.data.db.SnackEntity
import dev.sterrist.openfat.domain.Macros
import dev.sterrist.openfat.domain.Norm
import dev.sterrist.openfat.domain.Per100
import dev.sterrist.openfat.domain.PortionUnit
import dev.sterrist.openfat.domain.pickForDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Куда записывается еда: в приём пищи или в перекус. */
sealed interface EntrySlot {
    data class Meal(val mealId: String) : EntrySlot
    data class Snack(val snackId: String) : EntrySlot
}

/** То, что можно порционировать: продукт из справочника или снимок из прошлой записи. */
data class FoodItem(
    val productId: String?,
    val name: String,
    val brand: String?,
    val per100: Per100,
)

data class DayData(
    val meals: List<MealEntity>,
    val snacks: List<SnackEntity>,
    val entries: List<FoodEntryEntity>,
    val norm: Norm,
)

fun NormEntity.toNorm() = Norm(kcal, proteinPct, fatPct, carbsPct)

class DiaryRepository(
    private val db: OpenFatDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val meals = db.mealDao()
    private val snacks = db.snackDao()
    private val entries = db.foodEntryDao()
    private val norms = db.normDao()

    fun observeDay(day: Long): Flow<DayData> = combine(
        meals.observeForDay(day),
        snacks.observeForDay(day),
        entries.observeForDay(day),
        observeNorms(),
    ) { m, s, e, n ->
        DayData(m, s, e, pickForDay(day, n) { it.effectiveFrom }?.toNorm() ?: Norm.DEFAULT)
    }

    fun observeNorms(): Flow<List<NormEntity>> = norms.observeAll()

    fun observeNormFor(day: Long): Flow<Norm> =
        observeNorms().map { n -> pickForDay(day, n) { it.effectiveFrom }?.toNorm() ?: Norm.DEFAULT }

    /** Новая норма действует с [fromDay]; более ранние дни сохраняют прежнюю. */
    suspend fun saveNorm(norm: Norm, fromDay: Long) {
        norms.upsert(NormEntity(fromDay, norm.kcal, norm.proteinPct, norm.fatPct, norm.carbsPct))
    }

    fun observeDailyKcal(from: Long, to: Long): Flow<List<DayKcal>> = entries.observeDailyKcal(from, to)

    fun observeLoggedDays(): Flow<List<Long>> = entries.observeLoggedDays()

    fun observeRecent(limit: Int = 40): Flow<List<FoodEntryEntity>> = entries.observeRecent(limit)

    fun observePermanentMeals(): Flow<List<MealEntity>> = meals.observePermanent()

    suspend fun mealById(id: String): MealEntity? = meals.byId(id)

    suspend fun snackById(id: String): SnackEntity? = snacks.byId(id)

    // region Приёмы пищи

    suspend fun addMeal(name: String, oneTimeDate: Long?) {
        db.withTransaction {
            meals.insert(
                MealEntity(
                    id = UUID.randomUUID().toString(),
                    name = name.trim(),
                    position = meals.maxPosition() + 1,
                    oneTimeDate = oneTimeDate,
                    archived = false,
                ),
            )
        }
    }

    suspend fun renameMeal(id: String, name: String) {
        val meal = meals.byId(id) ?: return
        meals.update(meal.copy(name = name.trim()))
    }

    /** Приём без записей удаляется совсем, с записями — архивируется, чтобы не терять историю. */
    suspend fun deleteMeal(id: String) {
        db.withTransaction {
            val meal = meals.byId(id) ?: return@withTransaction
            if (meals.entryCount(id) == 0) meals.delete(id) else meals.update(meal.copy(archived = true))
        }
    }

    suspend fun restoreMeal(id: String) {
        db.withTransaction {
            val meal = meals.byId(id) ?: return@withTransaction
            meals.update(meal.copy(archived = false, position = meals.maxPosition() + 1))
        }
    }

    /** Перемещает постоянный активный приём на одну позицию вверх или вниз. */
    suspend fun moveMeal(id: String, up: Boolean) {
        db.withTransaction {
            val ordered = meals.all()
                .filter { it.oneTimeDate == null && !it.archived }
                .sortedWith(compareBy({ it.position }, { it.name }))
                .toMutableList()
            val index = ordered.indexOfFirst { it.id == id }
            val target = if (up) index - 1 else index + 1
            if (index < 0 || target !in ordered.indices) return@withTransaction
            ordered.add(target, ordered.removeAt(index))
            meals.updateAll(ordered.mapIndexed { i, m -> m.copy(position = i) })
        }
    }

    // endregion

    // region Перекусы

    suspend fun addSnack(day: Long, timeMinutes: Int): String {
        val id = UUID.randomUUID().toString()
        snacks.insert(SnackEntity(id, day, timeMinutes.coerceIn(0, 24 * 60 - 1)))
        return id
    }

    suspend fun updateSnackTime(id: String, timeMinutes: Int) {
        val snack = snacks.byId(id) ?: return
        snacks.update(snack.copy(timeMinutes = timeMinutes.coerceIn(0, 24 * 60 - 1)))
    }

    /** Удаляет перекус вместе с его записями. */
    suspend fun deleteSnack(id: String) = snacks.delete(id)

    // endregion

    // region Записи

    suspend fun addPortion(day: Long, slot: EntrySlot, item: FoodItem, amount: Double, unit: PortionUnit): FoodEntryEntity {
        val grams = requireNotNull(item.per100.gramsFor(amount, unit)) { "Unit $unit is not available" }
        val m = item.per100.forGrams(grams)
        val entry = FoodEntryEntity(
            id = UUID.randomUUID().toString(),
            date = day,
            mealId = (slot as? EntrySlot.Meal)?.mealId,
            snackId = (slot as? EntrySlot.Snack)?.snackId,
            productId = item.productId,
            name = item.name,
            brand = item.brand,
            amount = amount,
            unit = unit,
            grams = grams,
            kcal100 = item.per100.kcal,
            protein100 = item.per100.protein,
            fat100 = item.per100.fat,
            carbs100 = item.per100.carbs,
            servingGrams = item.per100.servingGrams,
            pieceGrams = item.per100.pieceGrams,
            kcal = m.kcal,
            protein = m.protein,
            fat = m.fat,
            carbs = m.carbs,
            createdAt = now(),
        )
        entries.insert(entry)
        return entry
    }

    suspend fun addQuick(day: Long, slot: EntrySlot, name: String, macros: Macros): FoodEntryEntity {
        val entry = FoodEntryEntity(
            id = UUID.randomUUID().toString(),
            date = day,
            mealId = (slot as? EntrySlot.Meal)?.mealId,
            snackId = (slot as? EntrySlot.Snack)?.snackId,
            productId = null,
            name = name.trim(),
            brand = null,
            amount = null,
            unit = null,
            grams = null,
            kcal100 = null,
            protein100 = null,
            fat100 = null,
            carbs100 = null,
            servingGrams = null,
            pieceGrams = null,
            kcal = macros.kcal,
            protein = macros.protein,
            fat = macros.fat,
            carbs = macros.carbs,
            createdAt = now(),
        )
        entries.insert(entry)
        return entry
    }

    suspend fun updateEntry(entry: FoodEntryEntity) = entries.update(entry)

    suspend fun deleteEntry(id: String): FoodEntryEntity? = db.withTransaction {
        entries.byId(id)?.also { entries.delete(id) }
    }

    /** Возврат удалённой записи («Отменить»). Если перекус успели удалить, запись не восстанавливается. */
    suspend fun restoreEntry(entry: FoodEntryEntity) {
        db.withTransaction {
            if (entry.snackId != null && snacks.byId(entry.snackId) == null) return@withTransaction
            if (entry.mealId != null && meals.byId(entry.mealId) == null) return@withTransaction
            entries.insert(entry)
        }
    }

    // endregion
}

/** Пересчёт записи после смены количества или единицы. */
fun FoodEntryEntity.withPortion(amount: Double, unit: PortionUnit): FoodEntryEntity? {
    val p = per100 ?: return null
    val grams = p.gramsFor(amount, unit) ?: return null
    val m = p.forGrams(grams)
    return copy(amount = amount, unit = unit, grams = grams, kcal = m.kcal, protein = m.protein, fat = m.fat, carbs = m.carbs)
}
