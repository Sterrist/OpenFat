package dev.sterrist.openfat.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.sterrist.openfat.domain.Macros
import dev.sterrist.openfat.domain.Per100
import dev.sterrist.openfat.domain.PortionUnit
import dev.sterrist.openfat.domain.Repeat
import kotlinx.serialization.Serializable

/** Откуда продукт попал в справочник. USDA больше не ищется, но остаётся для продуктов и бэкапов, сохранённых раньше. */
enum class ProductSource { USER, YAZIO, OFF, USDA }

@Serializable
@Entity(
    tableName = "products",
    indices = [Index(value = ["barcode"], unique = true), Index("sourceRef"), Index("searchKey")],
)
data class ProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val brand: String?,
    val barcode: String?,
    val kcal100: Double,
    val protein100: Double,
    val fat100: Double,
    val carbs100: Double,
    val servingGrams: Double?,
    val pieceGrams: Double?,
    val source: ProductSource,
    /** Идентификатор во внешнем источнике, например "OFF:4600..." или "USDA:123". */
    val sourceRef: String?,
    /** Название и бренд в нижнем регистре: SQLite LIKE не умеет регистронезависимо для кириллицы. */
    val searchKey: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val per100: Per100 get() = Per100(kcal100, protein100, fat100, carbs100, servingGrams, pieceGrams)

    companion object {
        fun searchKeyOf(name: String, brand: String?) = listOfNotNull(name, brand).joinToString(" ").lowercase()
    }
}

@Serializable
@Entity(tableName = "meals")
data class MealEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Int,
    /** Разовый приём: показывается только в этот день (epochDay). null — постоянный. */
    val oneTimeDate: Long?,
    /** Удалённый приём не показывается в новых днях, но остаётся там, где есть записи. */
    val archived: Boolean,
)

@Serializable
@Entity(tableName = "snacks", indices = [Index("date")])
data class SnackEntity(
    @PrimaryKey val id: String,
    val date: Long,
    val timeMinutes: Int,
)

/**
 * Запись еды. Хранит снимок КБЖУ на момент добавления, поэтому правка продукта
 * в справочнике не меняет историю. Привязана либо к приёму, либо к перекусу.
 */
@Serializable
@Entity(
    tableName = "food_entries",
    foreignKeys = [
        ForeignKey(MealEntity::class, ["id"], ["mealId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(SnackEntity::class, ["id"], ["snackId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ProductEntity::class, ["id"], ["productId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("date"), Index("mealId"), Index("snackId"), Index("productId"), Index("createdAt")],
)
data class FoodEntryEntity(
    @PrimaryKey val id: String,
    val date: Long,
    val mealId: String?,
    val snackId: String?,
    val productId: String?,
    val name: String,
    val brand: String?,
    /** Количество в выбранной единице; null для быстрого ввода. */
    val amount: Double?,
    val unit: PortionUnit?,
    val grams: Double?,
    val kcal100: Double?,
    val protein100: Double?,
    val fat100: Double?,
    val carbs100: Double?,
    val servingGrams: Double?,
    val pieceGrams: Double?,
    val kcal: Double,
    val protein: Double?,
    val fat: Double?,
    val carbs: Double?,
    val createdAt: Long,
) {
    val macros: Macros get() = Macros(kcal, protein, fat, carbs)

    val per100: Per100?
        get() = if (kcal100 != null && protein100 != null && fat100 != null && carbs100 != null) {
            Per100(kcal100, protein100, fat100, carbs100, servingGrams, pieceGrams)
        } else {
            null
        }
}

@Serializable
@Entity(tableName = "weights", indices = [Index(value = ["date"], unique = true)])
data class WeightEntity(
    @PrimaryKey val id: String,
    val date: Long,
    val kg: Double,
    val note: String?,
)

/** Норма, действующая начиная с [effectiveFrom] (epochDay). */
@Serializable
@Entity(tableName = "norms")
data class NormEntity(
    @PrimaryKey val effectiveFrom: Long,
    val kcal: Int,
    val proteinPct: Int,
    val fatPct: Int,
    val carbsPct: Int,
)

@Serializable
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** Препарат: приёмы в заданное время в дни, которые определяет повтор (каждый день, раз в N дней, недель или месяцев). */
@Serializable
@Entity(tableName = "medications")
data class MedicationEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Дозировка свободным текстом: «1 таблетка», «500 мг». */
    val dose: String?,
    /** Время приёмов в минутах от полуночи через запятую, по возрастанию: "480,1200". */
    val times: String,
    val active: Boolean,
    /**
     * Момент (epoch millis), с которого действует расписание. Приёмы раньше него не считаются
     * пропущенными: сдвигается при создании, смене времени и возобновлении после паузы.
     */
    val startAt: Long,
    val createdAt: Long,
    /** Единица повтора: [Repeat.UNIT_DAY], [Repeat.UNIT_WEEK] или [Repeat.UNIT_MONTH]. */
    @ColumnInfo(defaultValue = "0") val repeatUnit: Int = Repeat.UNIT_DAY,
    /** Через сколько единиц повтор: 1 — каждый день/неделю/месяц. */
    @ColumnInfo(defaultValue = "1") val repeatEvery: Int = 1,
    /** День (epochDay), от которого считается повтор. */
    @ColumnInfo(defaultValue = "0") val repeatAnchor: Long = 0,
) {
    val timeList: List<Int> get() = parseTimes(times)

    val repeat: Repeat get() = Repeat(repeatUnit, repeatEvery, repeatAnchor)

    companion object {
        fun parseTimes(value: String): List<Int> =
            value.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0 until 24 * 60 }.distinct().sorted()

        fun formatTimes(times: Collection<Int>): String = times.distinct().sorted().joinToString(",")
    }
}

/** Отметка о приёме препарата [medicationId] в день [date] (epochDay) в слот [time] (минуты). */
@Serializable
@Entity(
    tableName = "medication_intakes",
    foreignKeys = [ForeignKey(MedicationEntity::class, ["id"], ["medicationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["medicationId", "date", "time"], unique = true)],
)
data class MedicationIntakeEntity(
    @PrimaryKey val id: String,
    val medicationId: String,
    val date: Long,
    val time: Int,
    /** true — принят, false — пропущен намеренно. */
    val taken: Boolean,
    /** Когда сделана отметка, epoch millis. */
    val at: Long,
)

data class DayKcal(val date: Long, val kcal: Double)
