package dev.sterrist.openfat.ui.weight

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sterrist.openfat.R
import dev.sterrist.openfat.data.db.WeightEntity
import dev.sterrist.openfat.domain.parseDecimal
import dev.sterrist.openfat.ui.common.DayPickerDialog
import dev.sterrist.openfat.ui.common.DecimalField
import dev.sterrist.openfat.ui.common.dayTitle
import dev.sterrist.openfat.ui.common.formatNumber
import dev.sterrist.openfat.ui.common.formatSigned
import dev.sterrist.openfat.ui.common.today

/** Редактируемая запись: null — новая. Хранится как id, чтобы пережить смену конфигурации. */
private const val NEW_ENTRY = "__new__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeightScreen(viewModel: WeightViewModel = viewModel(factory = WeightViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastDeleted by viewModel.lastDeleted.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    val deletedText = stringResource(R.string.weight_deleted)
    val undoText = stringResource(R.string.undo)
    LaunchedEffect(lastDeleted) {
        if (lastDeleted != null) {
            val result = snackbar.showSnackbar(deletedText, undoText, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.clearUndo()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_weight)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = NEW_ENTRY },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.record_weight)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("stats") { StatsCard(state) }
            item("period") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    WeightPeriod.entries.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = state.period == p,
                            onClick = { viewModel.setPeriod(p) },
                            shape = SegmentedButtonDefaults.itemShape(i, WeightPeriod.entries.size),
                            icon = {},
                        ) { Text(periodLabel(p), maxLines = 1) }
                    }
                }
            }
            item("chart") {
                Card(Modifier.fillMaxWidth()) {
                    if (state.points.isEmpty()) {
                        Text(
                            stringResource(R.string.weight_no_data),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    } else {
                        WeightChart(
                            points = state.points,
                            trend = state.trend,
                            target = state.target,
                            fromDay = state.fromDay,
                            toDay = state.toDay,
                            description = stringResource(R.string.weight_chart_description, state.points.size),
                            modifier = Modifier.padding(12.dp),
                        )
                        Text(
                            stringResource(if (state.target != null) R.string.weight_chart_legend_target else R.string.weight_chart_legend),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        )
                    }
                }
            }
            if (state.history.isNotEmpty()) {
                item("history-title") {
                    Text(stringResource(R.string.weight_history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }
            itemsIndexed(state.history, key = { _, w -> w.id }) { index, w ->
                val previous = state.history.getOrNull(index + 1)
                WeightRow(w, previous, onClick = { editing = w.id }, onDelete = { viewModel.delete(w) })
            }
        }
    }

    editing?.let { id ->
        val entry = if (id == NEW_ENTRY) null else state.history.firstOrNull { it.id == id }
        if (id != NEW_ENTRY && entry == null && !state.loading) {
            LaunchedEffect(id) { editing = null }
        } else {
            WeightDialog(
                entry = entry,
                lastKg = state.history.firstOrNull()?.kg,
                viewModel = viewModel,
                onDismiss = { editing = null },
            )
        }
    }
}

@Composable
private fun periodLabel(p: WeightPeriod) = stringResource(
    when (p) {
        WeightPeriod.WEEK -> R.string.period_week
        WeightPeriod.MONTH -> R.string.period_month
        WeightPeriod.QUARTER -> R.string.period_quarter
        WeightPeriod.YEAR -> R.string.period_year
        WeightPeriod.ALL -> R.string.period_all
    },
)

@Composable
private fun StatsCard(state: WeightUiState) {
    val stats = state.stats
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        stats?.let { stringResource(R.string.kg_value, formatNumber(it.current)) } ?: "—",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(stringResource(R.string.current_weight), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Text(
                        stats?.change?.let { stringResource(R.string.kg_value, formatSigned(it)) } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(stringResource(R.string.change_for_period), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (stats != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatCell(stringResource(R.string.min_weight), stringResource(R.string.kg_value, formatNumber(stats.min)))
                    StatCell(stringResource(R.string.max_weight), stringResource(R.string.kg_value, formatNumber(stats.max)))
                    stats.toTarget?.let { StatCell(stringResource(R.string.to_target), stringResource(R.string.kg_value, formatSigned(it))) }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeightRow(entry: WeightEntity, previous: WeightEntity?, onClick: () -> Unit, onDelete: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
            }
        },
    ) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.kg_value, formatNumber(entry.kg))) },
            overlineContent = { Text(dayTitle(entry.date)) },
            supportingContent = entry.note?.let { { Text(it) } },
            trailingContent = previous?.let { { Text(stringResource(R.string.kg_value, formatSigned(entry.kg - it.kg))) } },
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

@Composable
private fun WeightDialog(entry: WeightEntity?, lastKg: Double?, viewModel: WeightViewModel, onDismiss: () -> Unit) {
    var day by rememberSaveable { mutableLongStateOf(entry?.date ?: today()) }
    var kg by rememberSaveable { mutableStateOf((entry?.kg ?: lastKg)?.let { formatNumber(it) }.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(entry?.note.orEmpty()) }
    var pickDate by rememberSaveable { mutableStateOf(false) }

    val existing by produceState<WeightEntity?>(null, day) { value = viewModel.existingFor(day) }
    val willOverwrite = existing != null && existing?.id != entry?.id
    val value = parseDecimal(kg)?.takeIf { it in 1.0..700.0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (entry == null) R.string.record_weight else R.string.edit_weight)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { pickDate = true }) { Text(stringResource(R.string.date_value, dayTitle(day))) }
                DecimalField(
                    value = kg,
                    onValueChange = { kg = it },
                    label = stringResource(R.string.weight),
                    suffix = stringResource(R.string.kg),
                    isError = kg.isNotEmpty() && value == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 200) note = it },
                    label = { Text(stringResource(R.string.note_optional)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (willOverwrite) {
                    Text(
                        stringResource(R.string.weight_overwrite, formatNumber(existing!!.kg)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = value != null,
                onClick = {
                    value?.let { viewModel.save(day, it, note, replacingId = entry?.id) }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (pickDate) {
        DayPickerDialog(
            initialDay = day,
            maxDay = today(),
            onPick = { day = it; pickDate = false },
            onDismiss = { pickDate = false },
        )
    }
}
