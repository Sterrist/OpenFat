package dev.sterrist.openfat.data.repo

import androidx.room.withTransaction
import dev.sterrist.openfat.data.db.OpenFatDatabase
import dev.sterrist.openfat.data.db.WeightEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class WeightRepository(private val db: OpenFatDatabase) {
    private val dao = db.weightDao()

    fun observeAll(): Flow<List<WeightEntity>> = dao.observeAll()

    suspend fun byDate(day: Long): WeightEntity? = dao.byDate(day)

    /** Одна запись в день: повторный ввод на ту же дату перезаписывает значение. */
    suspend fun save(day: Long, kg: Double, note: String?, replacingId: String? = null) {
        db.withTransaction {
            // При переносе записи на другую дату старая удаляется.
            replacingId?.let { id -> dao.delete(id) }
            val existing = dao.byDate(day)
            dao.upsert(
                WeightEntity(
                    id = existing?.id ?: replacingId ?: UUID.randomUUID().toString(),
                    date = day,
                    kg = kg,
                    note = note?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }
    }

    suspend fun delete(entry: WeightEntity) = dao.delete(entry.id)

    suspend fun restore(entry: WeightEntity) {
        db.withTransaction {
            if (dao.byDate(entry.date) == null) dao.upsert(entry)
        }
    }
}
