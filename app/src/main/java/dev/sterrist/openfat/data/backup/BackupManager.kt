package dev.sterrist.openfat.data.backup

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import dev.sterrist.openfat.data.readAtMost
import dev.sterrist.openfat.data.db.FoodEntryEntity
import dev.sterrist.openfat.data.db.MealEntity
import dev.sterrist.openfat.data.db.OpenFatDatabase
import dev.sterrist.openfat.data.db.SnackEntity
import dev.sterrist.openfat.data.db.WeightEntity
import dev.sterrist.openfat.domain.PortionUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

class InvalidBackupException(message: String) : IOException(message)

/** Экспорт и импорт через системный выбор файла (SAF): разрешения на хранилище не нужны. */
class BackupManager(
    private val db: OpenFatDatabase,
    private val resolver: ContentResolver,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportJson(uri: Uri) = withContext(Dispatchers.IO) {
        val file = BackupFile(
            exportedAt = now(),
            products = db.productDao().all(),
            meals = db.mealDao().all(),
            snacks = db.snackDao().all(),
            entries = db.foodEntryDao().all(),
            weights = db.weightDao().all(),
            norms = db.normDao().all(),
            settings = db.settingDao().all(),
            medications = db.medicationDao().all(),
            intakes = db.medicationDao().allIntakes(),
        )
        write(uri, json.encodeToString(BackupFile.serializer(), file))
    }

    /** CSV для Excel с русской локалью: разделитель «;», десятичная запятая, UTF-8 с BOM. */
    suspend fun exportDiaryCsv(uri: Uri, mealLabel: (MealEntity) -> String, snackLabel: (SnackEntity) -> String, unitLabel: (PortionUnit) -> String, header: List<String>) =
        withContext(Dispatchers.IO) {
            val meals = db.mealDao().all().associateBy { it.id }
            val snacks = db.snackDao().all().associateBy { it.id }
            val sb = StringBuilder().append(UTF8_BOM).appendCsvRow(header)
            db.foodEntryDao().all().forEach { e: FoodEntryEntity ->
                val slot = e.mealId?.let { meals[it]?.let(mealLabel) } ?: e.snackId?.let { snacks[it]?.let(snackLabel) } ?: ""
                sb.appendCsvRow(
                    listOf(
                        LocalDate.ofEpochDay(e.date).toString(),
                        slot,
                        e.name,
                        e.brand.orEmpty(),
                        e.amount.csv(),
                        e.unit?.let(unitLabel).orEmpty(),
                        e.grams.csv(),
                        e.kcal.csv(),
                        e.protein.csv(),
                        e.fat.csv(),
                        e.carbs.csv(),
                    ),
                )
            }
            write(uri, sb.toString())
        }

    suspend fun exportWeightCsv(uri: Uri, header: List<String>) = withContext(Dispatchers.IO) {
        val sb = StringBuilder().append(UTF8_BOM).appendCsvRow(header)
        db.weightDao().all().forEach { w: WeightEntity ->
            sb.appendCsvRow(listOf(LocalDate.ofEpochDay(w.date).toString(), w.kg.csv(), w.note.orEmpty()))
        }
        write(uri, sb.toString())
    }

    /** Читает и проверяет файл; ничего не меняет в БД. */
    suspend fun read(uri: Uri): ImportPlanner.Sanitized = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = input.readAtMost(MAX_IMPORT_BYTES + 1)
            if (buffer.size > MAX_IMPORT_BYTES) throw InvalidBackupException("too large")
            buffer
        } ?: throw IOException("Cannot open file")
        val file = try {
            json.decodeFromString(BackupFile.serializer(), bytes.toString(Charsets.UTF_8).removePrefix(UTF8_BOM.toString()))
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupException("not a backup: ${e.message}")
        }
        if (file.format != BackupFile.FORMAT) throw InvalidBackupException("wrong format")
        if (file.version > BackupFile.VERSION) throw InvalidBackupException("newer version")
        ImportPlanner.sanitize(file)
    }

    suspend fun plan(sanitized: ImportPlanner.Sanitized, mode: ImportMode): ImportPlan = when (mode) {
        ImportMode.REPLACE -> ImportPlanner.planReplace(sanitized)
        ImportMode.MERGE -> {
            val intakes = db.medicationDao().allIntakes()
            val local = LocalSnapshot(
                products = db.productDao().all(),
                meals = db.mealDao().all(),
                snackIds = db.snackDao().all().mapTo(HashSet()) { it.id },
                entryIds = db.foodEntryDao().allIds().toHashSet(),
                weights = db.weightDao().all(),
                medicationIds = db.medicationDao().all().mapTo(HashSet()) { it.id },
                intakeKeys = intakes.mapTo(HashSet()) { ImportPlanner.intakeKey(it) },
                intakeIds = intakes.mapTo(HashSet()) { it.id },
            )
            ImportPlanner.planMerge(sanitized, local)
        }
    }

    suspend fun apply(plan: ImportPlan) {
        db.withTransaction {
            if (plan.mode == ImportMode.REPLACE) {
                val sql = db.openHelper.writableDatabase
                listOf("food_entries", "snacks", "meals", "products", "weights", "norms", "settings", "medication_intakes", "medications")
                    .forEach { sql.execSQL("DELETE FROM $it") }
            }
            db.productDao().insertAll(plan.products)
            db.mealDao().insertAll(plan.meals)
            db.snackDao().insertAll(plan.snacks)
            db.foodEntryDao().insertAll(plan.entries)
            val weights = db.weightDao()
            plan.weights.forEach { w ->
                weights.deleteByDate(w.date)
                weights.upsert(w)
            }
            db.normDao().upsertAll(plan.norms)
            db.settingDao().upsertAll(plan.settings)
            db.medicationDao().insertAll(plan.medications)
            db.medicationDao().insertIntakes(plan.intakes)
        }
    }

    private fun write(uri: Uri, text: String) {
        val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open file")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun StringBuilder.appendCsvRow(cells: List<String>): StringBuilder {
        cells.joinTo(this, ";") { cell ->
            if (cell.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
        }
        return append("\r\n")
    }

    private fun Double?.csv(): String =
        this?.let { String.format(Locale.forLanguageTag("ru"), "%.1f", it) }.orEmpty()

    companion object {
        private const val MAX_IMPORT_BYTES = 50 * 1024 * 1024

        /** BOM нужен Excel, чтобы распознать UTF-8. */
        private val UTF8_BOM = Char(0xFEFF)
    }
}
