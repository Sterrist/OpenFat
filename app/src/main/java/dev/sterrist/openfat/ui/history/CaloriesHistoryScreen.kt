package dev.sterrist.openfat.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.sterrist.openfat.R
import dev.sterrist.openfat.data.repo.DiaryRepository
import dev.sterrist.openfat.data.repo.toNorm
import dev.sterrist.openfat.domain.Norm
import dev.sterrist.openfat.domain.pickForDay
import dev.sterrist.openfat.ui.common.appContainer
import dev.sterrist.openfat.ui.common.formatKcal
import dev.sterrist.openfat.ui.common.formatShortDate
import dev.sterrist.openfat.ui.common.today
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class DayBar(val day: Long, val kcal: Double, val norm: Int)

data class HistoryUiState(
    val days: Int = 7,
    val bars: List<DayBar> = emptyList(),
    /** Среднее только по дням, где что-то записано. */
    val average: Double? = null,
    val overDays: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CaloriesHistoryViewModel(diary: DiaryRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val days = saved.getStateFlow(KEY_DAYS, 7)

    val state: StateFlow<HistoryUiState> = days.flatMapLatest { n ->
        val to = today()
        val from = to - n + 1
        combine(diary.observeDailyKcal(from, to), diary.observeNorms()) { kcal, norms ->
            val byDay = kcal.associate { it.date to it.kcal }
            val bars = (from..to).map { d ->
                DayBar(d, byDay[d] ?: 0.0, (pickForDay(d, norms) { it.effectiveFrom }?.toNorm() ?: Norm.DEFAULT).kcal)
            }
            val logged = bars.filter { it.kcal > 0 }
            HistoryUiState(
                days = n,
                bars = bars,
                average = logged.takeIf { it.isNotEmpty() }?.map { it.kcal }?.average(),
                overDays = logged.count { it.kcal > it.norm },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun setDays(value: Int) {
        saved[KEY_DAYS] = value
    }

    companion object {
        private const val KEY_DAYS = "days"

        val Factory = viewModelFactory {
            initializer { CaloriesHistoryViewModel(appContainer().diary, createSavedStateHandle()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaloriesHistoryScreen(
    onBack: () -> Unit,
    viewModel: CaloriesHistoryViewModel = viewModel(factory = CaloriesHistoryViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calorie_history)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val options = listOf(7 to R.string.period_week, 30 to R.string.period_month)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (n, label) ->
                    SegmentedButton(
                        selected = state.days == n,
                        onClick = { viewModel.setDays(n) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(stringResource(label)) }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column {
                        Text(state.average?.let { formatKcal(it) } ?: "—", style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.average_kcal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column {
                        Text(state.overDays.toString(), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.days_over_norm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                BarChart(
                    state.bars,
                    description = stringResource(R.string.history_chart_description, state.days),
                    modifier = Modifier.padding(12.dp),
                )
                Text(
                    stringResource(R.string.history_legend),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun BarChart(bars: List<DayBar>, description: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics { contentDescription = description },
    ) {
        if (bars.isEmpty()) return@Canvas
        val maxValue = maxOf(bars.maxOf { it.kcal }, bars.maxOf { it.norm.toDouble() }) * 1.1
        val left = 40.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val top = 8.dp.toPx()
        val width = size.width - left
        val slot = width / bars.size
        val barWidth = slot * 0.6f
        fun y(v: Double) = (bottom - (bottom - top) * (v / maxValue)).toFloat()

        listOf(0.0, maxValue / 2, maxValue / 1.1).forEach { v ->
            val text = measurer.measure(formatKcal(v), labelStyle)
            drawText(text, topLeft = Offset(0f, y(v) - text.size.height / 2))
            drawLine(colors.outlineVariant, Offset(left, y(v)), Offset(size.width, y(v)), strokeWidth = 1.dp.toPx())
        }
        bars.forEachIndexed { i, bar ->
            val x = left + slot * i + (slot - barWidth) / 2
            if (bar.kcal > 0) {
                drawRoundRect(
                    color = if (bar.kcal > bar.norm) colors.error else colors.primary,
                    topLeft = Offset(x, y(bar.kcal)),
                    size = Size(barWidth, bottom - y(bar.kcal)),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
            drawLine(
                colors.secondary,
                Offset(left + slot * i, y(bar.norm.toDouble())),
                Offset(left + slot * (i + 1), y(bar.norm.toDouble())),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
        }
        listOf(bars.first().day, bars.last().day).forEachIndexed { i, d ->
            val text = measurer.measure(formatShortDate(d), labelStyle)
            val tx = if (i == 0) left else size.width - text.size.width
            drawText(text, topLeft = Offset(tx, bottom + 4.dp.toPx()))
        }
    }
}
