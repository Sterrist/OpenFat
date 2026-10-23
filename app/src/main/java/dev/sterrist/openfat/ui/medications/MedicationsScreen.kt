package dev.sterrist.openfat.ui.medications

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sterrist.openfat.R
import dev.sterrist.openfat.data.db.MedicationEntity
import dev.sterrist.openfat.ui.common.ConfirmDialog
import dev.sterrist.openfat.ui.common.TimePickerDialog
import dev.sterrist.openfat.ui.common.formatShortDate
import dev.sterrist.openfat.ui.common.formatTime
import java.time.Instant
import java.time.ZoneId

/** Признак «новый препарат» в состоянии редактора. */
private const val NEW_MEDICATION = ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationsScreen(viewModel: MedicationsViewModel = viewModel(factory = MedicationsViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    // Разрешения могут поменять в системных настройках, пока экран в фоне.
    var notificationsEnabled by remember { mutableStateOf(true) }
    var exactAlarms by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        exactAlarms = viewModel.canScheduleExact()
        onPauseOrDispose { }
    }
    val openNotificationSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!granted && !notificationsEnabled) openNotificationSettings()
    }
    val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val enableNotifications = {
        if (needsRuntimePermission) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_medications)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = NEW_MEDICATION },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.med_add)) },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val hasMedications = state.medications.isNotEmpty()
            if (hasMedications && !notificationsEnabled) {
                item("notifications-off") {
                    WarningCard(stringResource(R.string.med_notifications_off), stringResource(R.string.med_enable), enableNotifications)
                }
            }
            if (hasMedications && !exactAlarms && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item("exact-off") {
                    WarningCard(stringResource(R.string.med_exact_off), stringResource(R.string.med_allow)) {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()),
                        )
                    }
                }
            }
            if (!state.loading && !hasMedications) {
                item("empty") {
                    Text(
                        stringResource(R.string.med_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            if (hasMedications) {
                item("today-title") { SectionTitle(stringResource(R.string.med_today)) }
                if (state.today.isEmpty()) {
                    item("today-empty") {
                        Text(
                            stringResource(R.string.med_no_doses_today),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.today, key = { "today-" + it.key }, contentType = { "dose" }) { dose ->
                    DoseRow(dose, state.now, showDate = false, viewModel::take, viewModel::skip, viewModel::unmark)
                }
            }
            if (state.missed.isNotEmpty()) {
                item("missed-title") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.med_missed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.skipAll(state.missed) }) { Text(stringResource(R.string.med_skip_all)) }
                    }
                }
                items(state.missed, key = { "missed-" + it.key }, contentType = { "dose" }) { dose ->
                    DoseRow(dose, state.now, showDate = true, viewModel::take, viewModel::skip, viewModel::unmark)
                }
            }
            if (hasMedications) {
                item("list-title") { SectionTitle(stringResource(R.string.med_list), Modifier.padding(top = 12.dp)) }
                items(state.medications, key = { "med-" + it.id }, contentType = { "medication" }) { medication ->
                    MedicationRow(medication, onClick = { editing = medication.id }, onActiveChange = { viewModel.setActive(medication, it) })
                }
            }
        }
    }

    editing?.let { id ->
        val existing = state.medications.firstOrNull { it.id == id }
        if (id != NEW_MEDICATION && existing == null && !state.loading) {
            editing = null
            return@let
        }
        MedicationDialog(
            medication = existing,
            onSave = { name, dose, times ->
                viewModel.save(existing?.id, name, dose, times)
                editing = null
                // Разрешение спрашиваем, когда напоминания действительно понадобились.
                if (existing == null && needsRuntimePermission) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onDelete = {
                existing?.let(viewModel::delete)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 4.dp))
}

@Composable
private fun WarningCard(text: String, action: String, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun DoseRow(
    dose: DoseUi,
    now: Long,
    showDate: Boolean,
    onTake: (DoseUi) -> Unit,
    onSkip: (DoseUi) -> Unit,
    onUnmark: (DoseUi) -> Unit,
) {
    val intake = dose.intake
    val overdue = dose.overdue(now)
    val timeText = if (showDate) "${formatShortDate(dose.date)}, ${formatTime(dose.time)}" else formatTime(dose.time)
    val status = when {
        intake == null -> dose.medication.dose
        intake.taken -> stringResource(R.string.med_taken_at, formatTime(minutesOfDay(intake.at)))
        else -> stringResource(R.string.med_skipped)
    }
    Card {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Text(
                    timeText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (overdue) FontWeight.Bold else null,
                    color = when {
                        overdue -> MaterialTheme.colorScheme.error
                        intake != null -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            },
            headlineContent = { Text(dose.medication.name) },
            supportingContent = status?.let { { Text(it) } },
            trailingContent = {
                if (intake == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onSkip(dose) }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.med_action_skip))
                        }
                        FilledTonalIconButton(onClick = { onTake(dose) }) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.med_action_taken))
                        }
                    }
                } else {
                    IconButton(onClick = { onUnmark(dose) }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo))
                    }
                }
            },
        )
    }
}

@Composable
private fun MedicationRow(medication: MedicationEntity, onClick: () -> Unit, onActiveChange: (Boolean) -> Unit) {
    val details = buildList {
        medication.dose?.let(::add)
        add(medication.timeList.joinToString(", ") { formatTime(it) })
        if (!medication.active) add(stringResource(R.string.med_paused))
    }.filter { it.isNotEmpty() }.joinToString(" · ")
    Card(Modifier.clickable(onClick = onClick)) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(medication.name) },
            supportingContent = { Text(details) },
            trailingContent = { Switch(checked = medication.active, onCheckedChange = onActiveChange) },
        )
    }
}

@Composable
private fun MedicationDialog(
    medication: MedicationEntity?,
    onSave: (name: String, dose: String?, times: List<Int>) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(medication?.name.orEmpty()) }
    var dose by rememberSaveable { mutableStateOf(medication?.dose.orEmpty()) }
    val times = remember { mutableStateListOf<Int>().apply { addAll(medication?.timeList ?: listOf(9 * 60)) } }
    var pickingTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (medication == null) R.string.med_new else R.string.med_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.med_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dose,
                    onValueChange = { dose = it },
                    label = { Text(stringResource(R.string.med_dose)) },
                    placeholder = { Text(stringResource(R.string.med_dose_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.med_times), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    times.sorted().forEach { time ->
                        val label = formatTime(time)
                        InputChip(
                            selected = false,
                            onClick = { times.remove(time) },
                            label = { Text(label) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.med_remove_time, label),
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                    AssistChip(
                        onClick = { pickingTime = true },
                        label = { Text(stringResource(R.string.med_add_time)) },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                if (medication != null) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, dose, times.toList()) },
                enabled = name.isNotBlank() && times.isNotEmpty(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    if (pickingTime) {
        TimePickerDialog(
            title = stringResource(R.string.med_times),
            initialMinutes = times.maxOrNull()?.let { (it + 4 * 60) % (24 * 60) } ?: (9 * 60),
            onPick = {
                if (it !in times) times.add(it)
                pickingTime = false
            },
            onDismiss = { pickingTime = false },
        )
    }
    if (confirmDelete && medication != null) {
        ConfirmDialog(
            title = stringResource(R.string.med_delete_title),
            text = stringResource(R.string.med_delete_text, medication.name),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun minutesOfDay(millis: Long): Int {
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
    return time.hour * 60 + time.minute
}
