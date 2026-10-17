package dev.sterrist.openfat.ui.weight

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.sterrist.openfat.data.db.WeightEntity
import dev.sterrist.openfat.data.repo.SettingsRepository
import dev.sterrist.openfat.data.repo.WeightRepository
import dev.sterrist.openfat.domain.WeightPoint
import dev.sterrist.openfat.domain.movingAverage
import dev.sterrist.openfat.ui.common.appContainer
import dev.sterrist.openfat.ui.common.today
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class WeightPeriod(val days: Long?) { WEEK(7), MONTH(30), QUARTER(90), YEAR(365), ALL(null) }

data class WeightStats(
    val current: Double,
    val change: Double?,
    val min: Double,
    val max: Double,
    val toTarget: Double?,
)

data class WeightUiState(
    val loading: Boolean = true,
    val period: WeightPeriod = WeightPeriod.MONTH,
    /** Записи периода по возрастанию даты. */
    val points: List<WeightPoint> = emptyList(),
    val trend: List<WeightPoint> = emptyList(),
    val fromDay: Long = 0,
    val toDay: Long = 0,
    val target: Double? = null,
    val stats: WeightStats? = null,
    /** Все записи по убыванию даты — для списка. */
    val history: List<WeightEntity> = emptyList(),
)

class WeightViewModel(
    private val weights: WeightRepository,
    settings: SettingsRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val period = saved.getStateFlow(KEY_PERIOD, WeightPeriod.MONTH)

    private val _lastDeleted = MutableStateFlow<WeightEntity?>(null)
    val lastDeleted: StateFlow<WeightEntity?> = _lastDeleted.asStateFlow()

    val state: StateFlow<WeightUiState> = combine(weights.observeAll(), settings.observeTargetWeight(), period) { all, target, p ->
        build(all, target, p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUiState())

    private fun build(all: List<WeightEntity>, target: Double?, period: WeightPeriod): WeightUiState {
        val t = today()
        val allPoints = all.map { WeightPoint(it.date, it.kg) }
        // Тренд считается по всем данным, чтобы начало периода не было «обрезанным».
        val allTrend = movingAverage(allPoints)
        val from = period.days?.let { t - it + 1 } ?: (allPoints.firstOrNull()?.day ?: t)
        val points = allPoints.filter { it.day >= from }
        val trend = allTrend.filter { it.day >= from }
        val stats = points.lastOrNull()?.let { last ->
            WeightStats(
                current = last.kg,
                change = if (points.size >= 2) last.kg - points.first().kg else null,
                min = points.minOf { it.kg },
                max = points.maxOf { it.kg },
                toTarget = target?.let { last.kg - it },
            )
        }
        return WeightUiState(
            loading = false,
            period = period,
            points = points,
            trend = trend,
            fromDay = minOf(from, points.firstOrNull()?.day ?: from),
            toDay = maxOf(t, points.lastOrNull()?.day ?: t),
            target = target,
            stats = stats,
            history = all.asReversed(),
        )
    }

    fun setPeriod(value: WeightPeriod) {
        saved[KEY_PERIOD] = value
    }

    suspend fun existingFor(day: Long): WeightEntity? = weights.byDate(day)

    fun save(day: Long, kg: Double, note: String?, replacingId: String?) {
        viewModelScope.launch { weights.save(day, kg, note, replacingId) }
    }

    fun delete(entry: WeightEntity) {
        viewModelScope.launch {
            weights.delete(entry)
            _lastDeleted.value = entry
        }
    }

    fun undoDelete() {
        val entry = _lastDeleted.value ?: return
        _lastDeleted.value = null
        viewModelScope.launch { weights.restore(entry) }
    }

    fun clearUndo() {
        _lastDeleted.value = null
    }

    companion object {
        private const val KEY_PERIOD = "period"

        val Factory = viewModelFactory {
            initializer {
                val c = appContainer()
                WeightViewModel(c.weights, c.settings, createSavedStateHandle())
            }
        }
    }
}
