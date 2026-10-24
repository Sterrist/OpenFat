package dev.sterrist.openfat.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.sterrist.openfat.R
import dev.sterrist.openfat.domain.Norm
import dev.sterrist.openfat.domain.PortionUnit
import java.util.UUID

class Converters {
    @TypeConverter
    fun unitToString(unit: PortionUnit?): String? = unit?.name

    @TypeConverter
    fun stringToUnit(value: String?): PortionUnit? =
        value?.let { v -> PortionUnit.entries.firstOrNull { it.name == v } }

    @TypeConverter
    fun sourceToString(source: ProductSource): String = source.name

    @TypeConverter
    fun stringToSource(value: String): ProductSource =
        ProductSource.entries.firstOrNull { it.name == value } ?: ProductSource.USER
}

@Database(
    entities = [
        ProductEntity::class,
        MealEntity::class,
        SnackEntity::class,
        FoodEntryEntity::class,
        WeightEntity::class,
        NormEntity::class,
        SettingEntity::class,
        MedicationEntity::class,
        MedicationIntakeEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Converters::class)
abstract class OpenFatDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun mealDao(): MealDao
    abstract fun snackDao(): SnackDao
    abstract fun foodEntryDao(): FoodEntryDao
    abstract fun weightDao(): WeightDao
    abstract fun normDao(): NormDao
    abstract fun settingDao(): SettingDao
    abstract fun medicationDao(): MedicationDao

    companion object {
        fun build(context: Context): OpenFatDatabase {
            val app = context.applicationContext
            return Room.databaseBuilder(app, OpenFatDatabase::class.java, "openfat.db")
                .addCallback(SeedCallback(defaultMealNames(app)))
                .build()
        }

        fun defaultMealNames(context: Context): List<String> = listOf(
            context.getString(R.string.meal_breakfast),
            context.getString(R.string.meal_lunch),
            context.getString(R.string.meal_dinner),
        )
    }

    /** Стандартные приёмы и норма по умолчанию, действующая с начала времён. */
    private class SeedCallback(private val mealNames: List<String>) : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            mealNames.forEachIndexed { index, name ->
                db.insert(
                    "meals",
                    SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply {
                        put("id", UUID.randomUUID().toString())
                        put("name", name)
                        put("position", index)
                        putNull("oneTimeDate")
                        put("archived", 0)
                    },
                )
            }
            val norm = Norm.DEFAULT
            db.insert(
                "norms",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("effectiveFrom", 0L)
                    put("kcal", norm.kcal)
                    put("proteinPct", norm.proteinPct)
                    put("fatPct", norm.fatPct)
                    put("carbsPct", norm.carbsPct)
                },
            )
        }
    }
}
