package dev.sterrist.openfat.data.repo

import androidx.room.withTransaction
import dev.sterrist.openfat.data.db.MedicationEntity
import dev.sterrist.openfat.data.db.MedicationIntakeEntity
import dev.sterrist.openfat.data.db.OpenFatDatabase
import dev.sterrist.openfat.domain.Repeat
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class MedicationRepository(private val db: OpenFatDatabase) {
    private val dao = db.medicationDao()

    fun observeAll(): Flow<List<MedicationEntity>> = dao.observeAll()

    fun observeIntakesSince(fromDay: Long): Flow<List<MedicationIntakeEntity>> = dao.observeIntakesSince(fromDay)

    /** Срабатывает при любом изменении препаратов или отметок, включая импорт бэкапа. */
    fun observeChanges(): Flow<Set<String>> = db.invalidationTracker.createFlow("medications", "medication_intakes")

    suspend fun all(): List<MedicationEntity> = dao.all()

    suspend fun intakesSince(fromDay: Long): List<MedicationIntakeEntity> = dao.intakesSince(fromDay)

    /** Создаёт препарат или обновляет [id]. Смена времени или повтора начинает расписание заново с текущего момента. */
    suspend fun save(id: String?, name: String, dose: String?, times: List<Int>, repeat: Repeat) {
        val now = System.currentTimeMillis()
        val timesValue = MedicationEntity.formatTimes(times)
        db.withTransaction {
            val existing = id?.let { dao.byId(it) }
            val unchanged = existing != null && existing.times == timesValue && existing.repeat == repeat
            dao.upsert(
                MedicationEntity(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name.trim(),
                    dose = dose?.trim()?.takeIf { it.isNotEmpty() },
                    times = timesValue,
                    active = existing?.active ?: true,
                    startAt = if (unchanged) existing.startAt else now,
                    createdAt = existing?.createdAt ?: now,
                    repeatUnit = repeat.unit,
                    repeatEvery = repeat.every,
                    repeatAnchor = repeat.anchorDate,
                ),
            )
        }
    }

    /** Пауза и возобновление. После паузы приёмы за время перерыва не считаются пропущенными. */
    suspend fun setActive(medication: MedicationEntity, active: Boolean) {
        dao.upsert(
            medication.copy(
                active = active,
                startAt = if (active && !medication.active) System.currentTimeMillis() else medication.startAt,
            ),
        )
    }

    suspend fun delete(medication: MedicationEntity) = dao.delete(medication.id)

    /** Отмечает приём принятым ([taken] = true) или пропущенным. Повторная отметка перезаписывает прежнюю. */
    suspend fun mark(medicationId: String, date: Long, time: Int, taken: Boolean) {
        db.withTransaction {
            // Препарат могли удалить, пока висело уведомление.
            if (dao.byId(medicationId) == null) return@withTransaction
            val existing = dao.intake(medicationId, date, time)
            dao.upsertIntake(
                MedicationIntakeEntity(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    medicationId = medicationId,
                    date = date,
                    time = time,
                    taken = taken,
                    at = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun unmark(medicationId: String, date: Long, time: Int) = dao.deleteIntake(medicationId, date, time)
}
