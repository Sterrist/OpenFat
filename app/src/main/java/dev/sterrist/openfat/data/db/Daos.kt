package dev.sterrist.openfat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    /** [pattern] уже содержит экранированные % и _ и обрамляющие %. */
    @Query("SELECT * FROM products WHERE searchKey LIKE :pattern ESCAPE '\\' ORDER BY updatedAt DESC LIMIT 50")
    fun search(pattern: String): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun byId(id: String): ProductEntity?

    @Query("SELECT * FROM products WHERE barcode = :barcode")
    suspend fun byBarcode(barcode: String): ProductEntity?

    @Query("SELECT * FROM products WHERE sourceRef = :ref LIMIT 1")
    suspend fun bySourceRef(ref: String): ProductEntity?

    @Upsert
    suspend fun upsert(product: ProductEntity)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM products")
    suspend fun all(): List<ProductEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<ProductEntity>)
}

@Dao
interface MealDao {
    /** Приёмы, видимые в день: активные постоянные, разовые этого дня и любые с записями в этот день. */
    @Query(
        """
        SELECT * FROM meals
        WHERE (archived = 0 AND (oneTimeDate IS NULL OR oneTimeDate = :day))
           OR id IN (SELECT mealId FROM food_entries WHERE date = :day AND mealId IS NOT NULL)
        ORDER BY position, name
        """,
    )
    fun observeForDay(day: Long): Flow<List<MealEntity>>

    @Query("SELECT * FROM meals WHERE oneTimeDate IS NULL ORDER BY position, name")
    fun observePermanent(): Flow<List<MealEntity>>

    @Query("SELECT * FROM meals WHERE id = :id")
    suspend fun byId(id: String): MealEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM meals")
    suspend fun maxPosition(): Int

    @Query("SELECT COUNT(*) FROM food_entries WHERE mealId = :mealId")
    suspend fun entryCount(mealId: String): Int

    @Insert
    suspend fun insert(meal: MealEntity)

    @Update
    suspend fun update(meal: MealEntity)

    @Update
    suspend fun updateAll(meals: List<MealEntity>)

    @Query("DELETE FROM meals WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM meals")
    suspend fun all(): List<MealEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<MealEntity>)
}

@Dao
interface SnackDao {
    @Query("SELECT * FROM snacks WHERE date = :day ORDER BY timeMinutes")
    fun observeForDay(day: Long): Flow<List<SnackEntity>>

    @Query("SELECT * FROM snacks WHERE id = :id")
    suspend fun byId(id: String): SnackEntity?

    @Insert
    suspend fun insert(snack: SnackEntity)

    @Update
    suspend fun update(snack: SnackEntity)

    @Query("DELETE FROM snacks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM snacks")
    suspend fun all(): List<SnackEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<SnackEntity>)
}

@Dao
interface FoodEntryDao {
    @Query("SELECT * FROM food_entries WHERE date = :day ORDER BY createdAt")
    fun observeForDay(day: Long): Flow<List<FoodEntryEntity>>

    /** Последняя запись для каждого уникального блюда (по продукту или названию). */
    @Query(
        """
        SELECT * FROM food_entries AS e
        WHERE e.createdAt = (
            SELECT MAX(e2.createdAt) FROM food_entries AS e2
            WHERE COALESCE(e2.productId, '#' || e2.name) = COALESCE(e.productId, '#' || e.name)
        )
        GROUP BY COALESCE(e.productId, '#' || e.name)
        ORDER BY e.createdAt DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<FoodEntryEntity>>

    /** Дни, в которых есть хотя бы одна запись (для серии). */
    @Query("SELECT DISTINCT date FROM food_entries")
    fun observeLoggedDays(): Flow<List<Long>>

    @Query("SELECT date, SUM(kcal) AS kcal FROM food_entries WHERE date BETWEEN :from AND :to GROUP BY date ORDER BY date")
    fun observeDailyKcal(from: Long, to: Long): Flow<List<DayKcal>>

    @Query("SELECT * FROM food_entries WHERE id = :id")
    suspend fun byId(id: String): FoodEntryEntity?

    @Insert
    suspend fun insert(entry: FoodEntryEntity)

    @Update
    suspend fun update(entry: FoodEntryEntity)

    @Query("DELETE FROM food_entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM food_entries ORDER BY date, createdAt")
    suspend fun all(): List<FoodEntryEntity>

    @Query("SELECT id FROM food_entries")
    suspend fun allIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<FoodEntryEntity>)
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weights ORDER BY date")
    fun observeAll(): Flow<List<WeightEntity>>

    @Query("SELECT * FROM weights WHERE date = :day")
    suspend fun byDate(day: Long): WeightEntity?

    @Upsert
    suspend fun upsert(weight: WeightEntity)

    @Query("DELETE FROM weights WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM weights WHERE date = :day")
    suspend fun deleteByDate(day: Long)

    @Query("SELECT * FROM weights ORDER BY date")
    suspend fun all(): List<WeightEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<WeightEntity>)
}

@Dao
interface NormDao {
    @Query("SELECT * FROM norms ORDER BY effectiveFrom")
    fun observeAll(): Flow<List<NormEntity>>

    @Upsert
    suspend fun upsert(norm: NormEntity)

    @Upsert
    suspend fun upsertAll(items: List<NormEntity>)

    @Query("SELECT * FROM norms ORDER BY effectiveFrom")
    suspend fun all(): List<NormEntity>
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun upsert(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM settings")
    suspend fun all(): List<SettingEntity>

    @Upsert
    suspend fun upsertAll(items: List<SettingEntity>)
}
