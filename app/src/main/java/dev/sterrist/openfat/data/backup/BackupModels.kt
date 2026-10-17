package dev.sterrist.openfat.data.backup

import dev.sterrist.openfat.data.db.FoodEntryEntity
import dev.sterrist.openfat.data.db.MealEntity
import dev.sterrist.openfat.data.db.NormEntity
import dev.sterrist.openfat.data.db.ProductEntity
import dev.sterrist.openfat.data.db.SettingEntity
import dev.sterrist.openfat.data.db.SnackEntity
import dev.sterrist.openfat.data.db.WeightEntity
import kotlinx.serialization.Serializable

@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0,
    val products: List<ProductEntity> = emptyList(),
    val meals: List<MealEntity> = emptyList(),
    val snacks: List<SnackEntity> = emptyList(),
    val entries: List<FoodEntryEntity> = emptyList(),
    val weights: List<WeightEntity> = emptyList(),
    val norms: List<NormEntity> = emptyList(),
    val settings: List<SettingEntity> = emptyList(),
) {
    companion object {
        const val FORMAT = "openfat-backup"
        const val VERSION = 1
    }
}

enum class ImportMode { MERGE, REPLACE }

data class ImportSummary(
    val products: Int,
    val meals: Int,
    val snacks: Int,
    val entries: Int,
    val weights: Int,
    val weightConflicts: Int,
    val skippedInvalid: Int,
)

/** Что именно будет записано в БД. Строится заранее, чтобы показать сводку до применения. */
data class ImportPlan(
    val mode: ImportMode,
    val products: List<ProductEntity>,
    val meals: List<MealEntity>,
    val snacks: List<SnackEntity>,
    val entries: List<FoodEntryEntity>,
    /** Записи веса к вставке; при конфликте локальная запись на ту же дату заменяется. */
    val weights: List<WeightEntity>,
    val norms: List<NormEntity>,
    val settings: List<SettingEntity>,
    val summary: ImportSummary,
)

/** Текущие данные, нужные для слияния. */
data class LocalSnapshot(
    val products: List<ProductEntity>,
    val meals: List<MealEntity>,
    val snackIds: Set<String>,
    val entryIds: Set<String>,
    val weights: List<WeightEntity>,
)
