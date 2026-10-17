package dev.sterrist.openfat.ui.diary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.sterrist.openfat.data.db.FoodEntryEntity
import dev.sterrist.openfat.data.db.MealEntity
import dev.sterrist.openfat.data.db.SnackEntity
import dev.sterrist.openfat.data.repo.DayData
import dev.sterrist.openfat.data.repo.DiaryRepository
import dev.sterrist.openfat.data.repo.EntrySlot
import dev.sterrist.openfat.data.repo.SettingsRepository
import dev.sterrist.openfat.domain.ManualStreak
import dev.sterrist.openfat.domain.Streak
import dev.sterrist.openfat.domain.computeStreak
import dev.sterrist.openfat.domain.Macros
import dev.sterrist.openfat.domain.Norm
import dev.sterrist.openfat.ui.common.appContainer
import dev.sterrist.openfat.ui.common.today
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MealSection(val meal: MealEntity, val entries: List<FoodEntryEntity>, val total: Macros)

data class SnackSection(val snack: SnackEntity, val entries: List<FoodEntryEntity>, val total: Macros)

data class DiaryUiState(
    val day: Long,
    val loading: Boolean,
    val norm: Norm,
    val total: Macros,
    val meals: List<MealSection>,
    val snacks: List<SnackSection>,
) {
    val entriesById: Map<String, FoodEntryEntity> by lazy {
        (meals.flatMap { it.entries } + snacks.flatMap { it.entries }).associateBy { it.id }
    }
}

private fun List<FoodEntryEntity>.sum(): Macros = fold(Macros.ZERO) { acc, e -> acc + e.macros }

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(
    private val diary: DiaryRepository,
    private val settings: SettingsRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {

    val day: StateFlow<Long> = saved.getStateFlow(KEY_DAY, today())

    val state: StateFlow<DiaryUiState> = day
        .flatMapLatest { d -> diary.observeDay(d).map { build(d, it) } }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DiaryUiState(day.value, loading = true, Norm.DEFAULT, Macros.ZERO, emptyList(), emptyList()),
        )

    /** Серия всегда считается на сегодня, независимо от открытого дня. */
    val streak: StateFlow<Streak> = combine(diary.observeLoggedDays(), settings.observeManualStreak()) { days, manual ->
        computeStreak(days.toHashSet(), today(), manual)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Streak(0, loggedToday = false))

    /** Пользователь указывает, сколько дней подряд вёл учёт до сегодняшнего дня. */
    fun setDaysBeforeToday(days: Int) = launch {
        val value = days.coerceIn(0, SettingsRepository.MAX_MANUAL_STREAK_DAYS)
        settings.setManualStreak(if (value > 0) ManualStreak(value, anchorDay = today() - 1) else null)
    }

    /** Последняя удалённая запись — для снекбара «Отменить». */
    private val _lastDeleted = MutableStateFlow<FoodEntryEntity?>(null)
    val lastDeleted: StateFlow<FoodEntryEntity?> = _lastDeleted.asStateFlow()

    val editingEntryId: StateFlow<String?> = saved.getStateFlow(KEY_EDITING, null)

    private fun build(day: Long, data: DayData): DiaryUiState {
        val byMeal = data.entries.filter { it.mealId != null }.groupBy { it.mealId }
        val bySnack = data.entries.filter { it.snackId != null }.groupBy { it.snackId }
        return DiaryUiState(
            day = day,
            loading = false,
            norm = data.norm,
            total = data.entries.sum(),
            meals = data.meals.map { m -> byMeal[m.id].orEmpty().let { MealSection(m, it, it.sum()) } },
            snacks = data.snacks.map { s -> bySnack[s.id].orEmpty().let { SnackSection(s, it, it.sum()) } },
        )
    }

    fun setDay(value: Long) {
        saved[KEY_DAY] = value
    }

    fun shiftDay(delta: Int) = setDay(day.value + delta)

    fun addMeal(name: String, oneTime: Boolean) = launch {
        diary.addMeal(name, oneTimeDate = if (oneTime) day.value else null)
    }

    fun renameMeal(id: String, name: String) = launch { diary.renameMeal(id, name) }

    fun deleteMeal(id: String) = launch { diary.deleteMeal(id) }

    fun updateSnackTime(id: String, minutes: Int) = launch { diary.updateSnackTime(id, minutes) }

    fun deleteSnack(id: String) = launch { diary.deleteSnack(id) }

    fun startEditing(entryId: String?) {
        saved[KEY_EDITING] = entryId
    }

    fun saveEntry(entry: FoodEntryEntity) = launch {
        diary.updateEntry(entry)
        startEditing(null)
    }

    fun moveEntry(entry: FoodEntryEntity, slot: EntrySlot) = entry.copy(
        mealId = (slot as? EntrySlot.Meal)?.mealId,
        snackId = (slot as? EntrySlot.Snack)?.snackId,
    )

    fun deleteEntry(id: String) = launch {
        startEditing(null)
        _lastDeleted.value = diary.deleteEntry(id)
    }

    fun undoDelete() = launch {
        _lastDeleted.value?.let { diary.restoreEntry(it) }
        _lastDeleted.value = null
    }

    fun clearUndo() {
        _lastDeleted.value = null
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        private const val KEY_DAY = "day"
        private const val KEY_EDITING = "editing_entry"

        val Factory = viewModelFactory {
            initializer {
                val c = appContainer()
                DiaryViewModel(c.diary, c.settings, createSavedStateHandle())
            }
        }
    }
}
