package dev.sterrist.openfat.ui.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sterrist.openfat.R
import dev.sterrist.openfat.data.db.FoodEntryEntity
import dev.sterrist.openfat.domain.Macros
import dev.sterrist.openfat.domain.Norm
import dev.sterrist.openfat.ui.common.ConfirmDialog
import dev.sterrist.openfat.ui.common.DayPickerDialog
import dev.sterrist.openfat.ui.common.TimePickerDialog
import dev.sterrist.openfat.ui.common.amountLabel
import dev.sterrist.openfat.ui.common.dayTitle
import dev.sterrist.openfat.ui.common.formatKcal
import dev.sterrist.openfat.ui.common.formatNumber
import dev.sterrist.openfat.ui.common.formatTime
import dev.sterrist.openfat.ui.common.macrosLine
import java.time.LocalTime

/** Куда добавлять еду с экрана дневника. */
sealed interface AddTarget {
    data class Meal(val mealId: String) : AddTarget
    data class Snack(val snackId: String) : AddTarget

    /** Новый перекус: создаётся при первом добавлении, чтобы не оставлять пустых. */
    data class NewSnack(val minutes: Int) : AddTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    onAddFood: (day: Long, target: AddTarget) -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: DiaryViewModel = viewModel(factory = DiaryViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastDeleted by viewModel.lastDeleted.collectAsStateWithLifecycle()
    val editingId by viewModel.editingEntryId.collectAsStateWithLifecycle()
    val streak by viewModel.streak.collectAsStateWithLifecycle()
    var showStreak by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showAddMeal by rememberSaveable { mutableStateOf(false) }
    var showNewSnackTime by rememberSaveable { mutableStateOf(false) }

    val deletedText = stringResource(R.string.entry_deleted)
    val undoText = stringResource(R.string.undo)
    LaunchedEffect(lastDeleted) {
        if (lastDeleted != null) {
            val result = snackbar.showSnackbar(deletedText, undoText, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.clearUndo()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        dayTitle(state.day),
                        modifier = Modifier
                            .clickable(onClickLabel = stringResource(R.string.pick_date)) { showDatePicker = true }
                            .padding(vertical = 8.dp),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.shiftDay(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_day))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.shiftDay(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_day))
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.BarChart, stringResource(R.string.calorie_history))
                    }
                    StreakBadge(streak, onClick = { showStreak = true })
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") { DayHeader(state.total, state.norm) }

            items(state.meals, key = { "meal-" + it.meal.id }) { section ->
                var menuOpen by remember { mutableStateOf(false) }
                var renaming by rememberSaveable { mutableStateOf(false) }
                var confirmDelete by rememberSaveable { mutableStateOf(false) }
                SlotCard(
                    title = section.meal.name,
                    total = section.total,
                    entries = section.entries,
                    onAdd = { onAddFood(state.day, AddTarget.Meal(section.meal.id)) },
                    onEntryClick = { viewModel.startEditing(it.id) },
                    onEntryDelete = { viewModel.deleteEntry(it.id) },
                    menu = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.meal_actions, section.meal.name))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menuOpen = false; renaming = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menuOpen = false; confirmDelete = true })
                            }
                        }
                    },
                )
                if (renaming) {
                    NameDialog(
                        title = stringResource(R.string.rename_meal),
                        initial = section.meal.name,
                        onConfirm = { viewModel.renameMeal(section.meal.id, it); renaming = false },
                        onDismiss = { renaming = false },
                    )
                }
                if (confirmDelete) {
                    ConfirmDialog(
                        title = stringResource(R.string.delete_meal_title, section.meal.name),
                        text = stringResource(R.string.delete_meal_text),
                        confirmLabel = stringResource(R.string.delete),
                        onConfirm = { viewModel.deleteMeal(section.meal.id); confirmDelete = false },
                        onDismiss = { confirmDelete = false },
                    )
                }
            }

            if (state.snacks.isNotEmpty()) {
                item(key = "snacks-title") {
                    Text(
                        stringResource(R.string.snacks),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(state.snacks, key = { "snack-" + it.snack.id }) { section ->
                var menuOpen by remember { mutableStateOf(false) }
                var editTime by rememberSaveable { mutableStateOf(false) }
                var confirmDelete by rememberSaveable { mutableStateOf(false) }
                SlotCard(
                    title = stringResource(R.string.snack_at, formatTime(section.snack.timeMinutes)),
                    total = section.total,
                    entries = section.entries,
                    onAdd = { onAddFood(state.day, AddTarget.Snack(section.snack.id)) },
                    onEntryClick = { viewModel.startEditing(it.id) },
                    onEntryDelete = { viewModel.deleteEntry(it.id) },
                    menu = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.snack_actions))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.change_time)) }, onClick = { menuOpen = false; editTime = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menuOpen = false; confirmDelete = true })
                            }
                        }
                    },
                )
                if (editTime) {
                    TimePickerDialog(
                        title = stringResource(R.string.snack_time),
                        initialMinutes = section.snack.timeMinutes,
                        onPick = { viewModel.updateSnackTime(section.snack.id, it); editTime = false },
                        onDismiss = { editTime = false },
                    )
                }
                if (confirmDelete) {
                    ConfirmDialog(
                        title = stringResource(R.string.delete_snack_title),
                        text = stringResource(R.string.delete_snack_text),
                        confirmLabel = stringResource(R.string.delete),
                        onConfirm = { viewModel.deleteSnack(section.snack.id); confirmDelete = false },
                        onDismiss = { confirmDelete = false },
                    )
                }
            }

            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { showNewSnackTime = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_snack))
                    }
                    OutlinedButton(onClick = { showAddMeal = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.add_meal))
                    }
                }
            }
        }
    }

    if (showStreak) {
        StreakDialog(
            streak = streak,
            onSave = { viewModel.setDaysBeforeToday(it); showStreak = false },
            onDismiss = { showStreak = false },
        )
    }
    if (showDatePicker) {
        DayPickerDialog(
            initialDay = state.day,
            onPick = { viewModel.setDay(it); showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showAddMeal) {
        AddMealDialog(
            day = state.day,
            onConfirm = { name, oneTime -> viewModel.addMeal(name, oneTime); showAddMeal = false },
            onDismiss = { showAddMeal = false },
        )
    }
    if (showNewSnackTime) {
        val now = LocalTime.now()
        TimePickerDialog(
            title = stringResource(R.string.snack_time),
            initialMinutes = now.hour * 60 + now.minute,
            onPick = { showNewSnackTime = false; onAddFood(state.day, AddTarget.NewSnack(it)) },
            onDismiss = { showNewSnackTime = false },
        )
    }
    val editing = editingId?.let { state.entriesById[it] }
    if (editing != null) {
        EntryEditSheet(
            entry = editing,
            state = state,
            onSave = viewModel::saveEntry,
            onMove = viewModel::moveEntry,
            onDelete = { viewModel.deleteEntry(editing.id) },
            onDismiss = { viewModel.startEditing(null) },
        )
    } else if (editingId != null && !state.loading) {
        // Запись исчезла (например, удалена с другого экрана) — закрываем лист.
        LaunchedEffect(editingId) { viewModel.startEditing(null) }
    }
}

@Composable
private fun DayHeader(total: Macros, norm: Norm) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val remaining = norm.kcal - total.kcal
            val over = remaining < 0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(formatKcal(total.kcal), style = MaterialTheme.typography.displaySmall)
                    Text(stringResource(R.string.eaten_of_norm, norm.kcal), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatKcal(kotlin.math.abs(remaining)),
                        style = MaterialTheme.typography.displaySmall,
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(if (over) R.string.over_norm else R.string.remaining),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            NormProgress(total.kcal, norm.kcal.toDouble())
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MacroProgress(stringResource(R.string.protein), total.protein ?: 0.0, norm.proteinGrams, Modifier.weight(1f))
                MacroProgress(stringResource(R.string.fat), total.fat ?: 0.0, norm.fatGrams, Modifier.weight(1f))
                MacroProgress(stringResource(R.string.carbs), total.carbs ?: 0.0, norm.carbsGrams, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NormProgress(value: Double, target: Double, modifier: Modifier = Modifier) {
    val over = value > target
    LinearProgressIndicator(
        progress = { if (target <= 0) 0f else (value / target).toFloat().coerceIn(0f, 1f) },
        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.outlineVariant,
        gapSize = 0.dp,
        drawStopIndicator = {},
        modifier = modifier.fillMaxWidth().height(6.dp),
    )
}

@Composable
private fun MacroProgress(label: String, value: Double, target: Double, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.grams_of, formatNumber(value), formatNumber(target)),
            style = MaterialTheme.typography.bodyMedium,
            color = if (value > target) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        NormProgress(value, target)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotCard(
    title: String,
    total: Macros,
    entries: List<FoodEntryEntity>,
    onAdd: () -> Unit,
    onEntryClick: (FoodEntryEntity) -> Unit,
    onEntryDelete: (FoodEntryEntity) -> Unit,
    menu: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Сумма калорий за приём — всегда видна рядом с названием, даже если пока 0.
            Text(
                stringResource(R.string.kcal_value, formatKcal(total.kcal)),
                style = MaterialTheme.typography.titleMedium,
                color = if (entries.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
            menu()
        }
        entries.forEach { entry ->
            key(entry.id) {
                val dismissState = rememberSwipeToDismissBoxState()
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onEntryDelete(entry) },
                    backgroundContent = {
                        Box(
                            Modifier.fillMaxSize().padding(horizontal = 24.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                        }
                    },
                ) {
                    EntryRow(entry, onClick = { onEntryClick(entry) })
                }
            }
        }
        if (entries.isNotEmpty()) HorizontalDivider()
        TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_food))
        }
    }
}

@Composable
private fun EntryRow(entry: FoodEntryEntity, onClick: () -> Unit) {
    val details = listOfNotNull(
        amountLabel(entry.amount, entry.unit, entry.grams),
        macrosLine(entry.protein, entry.fat, entry.carbs),
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = details.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
        trailingContent = { Text(stringResource(R.string.kcal_value, formatKcal(entry.kcal)), style = MaterialTheme.typography.bodyLarge) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = Modifier
            .semantics { role = Role.Button }
            .clickable(onClick = onClick),
    )
}

@Composable
fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 60) name = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AddMealDialog(day: Long, onConfirm: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var oneTime by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_meal)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 60) name = it },
                    label = { Text(stringResource(R.string.name)) },
                    placeholder = { Text(stringResource(R.string.meal_name_hint)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                RadioRow(stringResource(R.string.meal_every_day), selected = !oneTime) { oneTime = false }
                RadioRow(stringResource(R.string.meal_only_on, dayTitle(day).lowercase()), selected = oneTime) { oneTime = true }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), oneTime) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
