package dev.sterrist.openfat.ui.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.sterrist.openfat.data.db.MedicationEntity
import dev.sterrist.openfat.data.db.MedicationIntakeEntity
import dev.sterrist.openfat.data.reminders.MedicationReminders
import dev.sterrist.openfat.data.repo.MedicationRepository
import dev.sterrist.openfat.domain.MedicationSchedule
import dev.sterrist.openfat.domain.Repeat
import dev.sterrist.openfat.ui.common.appContainer
import dev.sterrist.openfat.ui.common.today
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Приём препарата в конкретный день и время с отметкой, если она есть. */
data class DoseUi(
    val medication: MedicationEntity,
    val date: Long,
    val time: Int,
    val dueAt: Long,
    val intake: MedicationIntakeEntity?,
) {
    val key: String get() = "${medication.id}|$date|$time"

    /** Время пришло, а отметки нет, и приём входит в расписание (не раньше его начала). */
    fun overdue(now: Long): Boolean = intake == null && dueAt <= now && dueAt >= medication.startAt
}

data class MedicationsUiState(
    val loading: Boolean = true,
    val now: Long = 0,
    /** Все приёмы сегодняшнего дня по времени. */
    val today: List<DoseUi> = emptyList(),
    /** Неотмеченные приёмы прошлых дней, новые сверху. */
    val missed: List<DoseUi> = emptyList(),
    val medications: List<MedicationEntity> = emptyList(),
)

class MedicationsViewModel(
    private val repo: MedicationRepository,
    private val reminders: MedicationReminders,
) : ViewModel() {
    /** Раз в полминуты: чтобы наступившие приёмы подсвечивались без перезахода на экран. */
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    val state: StateFlow<MedicationsUiState> = combine(
        repo.observeAll(),
        repo.observeIntakesSince(today() - MedicationSchedule.LOOKBACK_DAYS),
        clock,
    ) { medications, intakes, now -> build(medications, intakes, now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MedicationsUiState())

    private fun build(medications: List<MedicationEntity>, intakes: List<MedicationIntakeEntity>, now: Long): MedicationsUiState {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone).toEpochDay()
        val intakeByKey = intakes.associateBy { "${it.medicationId}|${it.date}|${it.time}" }
        val active = medications.filter { it.active }

        fun dose(m: MedicationEntity, date: Long, time: Int): DoseUi =
            DoseUi(m, date, time, MedicationSchedule.dueAt(date, time, zone), intakeByKey["${m.id}|$date|$time"])

        val today = active
            .filter { it.repeat.occursOn(day) }
            .flatMap { m -> m.timeList.map { dose(m, day, it) } }
            .sortedWith(compareBy({ it.time }, { it.medication.name.lowercase() }))
        val missed = active
            .flatMap { m ->
                MedicationSchedule.doses(m.id, m.timeList, m.startAt, day - MedicationSchedule.LOOKBACK_DAYS, day - 1, zone, m.repeat)
                    .map { dose(m, it.date, it.time) }
            }
            .filter { it.intake == null }
            .sortedByDescending { it.dueAt }
        return MedicationsUiState(loading = false, now = now, today = today, missed = missed, medications = medications)
    }

    fun canScheduleExact(): Boolean = reminders.canScheduleExact()

    fun take(dose: DoseUi) = mark(dose, taken = true)

    fun skip(dose: DoseUi) = mark(dose, taken = false)

    private fun mark(dose: DoseUi, taken: Boolean) {
        viewModelScope.launch { repo.mark(dose.medication.id, dose.date, dose.time, taken) }
    }

    fun unmark(dose: DoseUi) {
        viewModelScope.launch { repo.unmark(dose.medication.id, dose.date, dose.time) }
    }

    fun skipAll(doses: List<DoseUi>) {
        viewModelScope.launch { doses.forEach { repo.mark(it.medication.id, it.date, it.time, taken = false) } }
    }

    fun save(id: String?, name: String, dose: String?, times: List<Int>, repeat: Repeat) {
        viewModelScope.launch { repo.save(id, name, dose, times, repeat) }
    }

    fun setActive(medication: MedicationEntity, active: Boolean) {
        viewModelScope.launch { repo.setActive(medication, active) }
    }

    fun delete(medication: MedicationEntity) {
        viewModelScope.launch { repo.delete(medication) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                MedicationsViewModel(container.medications, container.reminders)
            }
        }
    }
}
