package dev.sterrist.openfat.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sterrist.openfat.R
import dev.sterrist.openfat.data.backup.ImportMode
import dev.sterrist.openfat.data.backup.ImportSummary
import dev.sterrist.openfat.domain.Norm
import dev.sterrist.openfat.domain.PortionUnit
import dev.sterrist.openfat.domain.parseDecimal
import dev.sterrist.openfat.ui.common.ConfirmDialog
import dev.sterrist.openfat.ui.common.DecimalField
import dev.sterrist.openfat.ui.common.formatNumber
import dev.sterrist.openfat.ui.common.formatTime
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenMeals: () -> Unit,
    onOpenProducts: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current

    val messageText = state.message?.let { messageText(it) }
    LaunchedEffect(state.messageId) {
        if (messageText != null) snackbar.showSnackbar(messageText)
    }

    val labels = CsvLabels(
        diaryHeader = stringArrayResource(R.array.csv_diary_header).toList(),
        weightHeader = stringArrayResource(R.array.csv_weight_header).toList(),
        snackLabel = { resources.getString(R.string.snack_at, formatTime(it.timeMinutes)) },
        unitLabel = { u ->
            resources.getString(
                when (u) {
                    PortionUnit.GRAM -> R.string.unit_gram
                    PortionUnit.SERVING -> R.string.unit_serving
                    PortionUnit.PIECE -> R.string.unit_piece
                },
            )
        },
    )
    val date = LocalDate.now().toString()
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportJson)
    }
    val exportDiary = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.exportDiaryCsv(it, labels) }
    }
    val exportWeight = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.exportWeightCsv(it, labels) }
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::prepareImport)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            NormCard(state.norm, viewModel::saveNorm)
            TargetWeightCard(state.targetWeight, viewModel::saveTargetWeight)

            Card(Modifier.fillMaxWidth()) {
                NavRow(stringResource(R.string.meals_management), onOpenMeals)
                NavRow(stringResource(R.string.my_products), onOpenProducts)
            }

            YazioCard(
                configured = state.yazioConfigured,
                maskedEmail = state.yazioEmail,
                onLogin = viewModel::loginYazio,
                onLogout = viewModel::logoutYazio,
                enabled = !state.busy,
            )

            SectionCard(stringResource(R.string.data_section)) {
                Text(stringResource(R.string.data_local_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { exportJson.launch("openfat-$date.json") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.export_json))
                }
                OutlinedButton(onClick = { exportDiary.launch("openfat-diary-$date.csv") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.export_diary_csv))
                }
                OutlinedButton(onClick = { exportWeight.launch("openfat-weight-$date.csv") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.export_weight_csv))
                }
                Button(onClick = { importJson.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.import_json))
                }
            }

            Text(
                stringResource(R.string.about_sources),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    state.importPreview?.let { preview ->
        var confirmReplace by rememberSaveable { mutableStateOf(false) }
        if (!confirmReplace) {
            AlertDialog(
                onDismissRequest = viewModel::cancelImport,
                title = { Text(stringResource(R.string.import_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.import_merge_title), style = MaterialTheme.typography.titleSmall)
                        Text(summaryText(preview.merge, merge = true))
                        Text(stringResource(R.string.import_replace_title), style = MaterialTheme.typography.titleSmall)
                        Text(summaryText(preview.replace, merge = false))
                        if (preview.merge.skippedInvalid > 0) {
                            Text(stringResource(R.string.import_skipped, preview.merge.skippedInvalid), color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        TextButton(onClick = { viewModel.applyImport(ImportMode.MERGE) }) { Text(stringResource(R.string.import_merge)) }
                        TextButton(onClick = { confirmReplace = true }) {
                            Text(stringResource(R.string.import_replace), color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.cancel)) }
                    }
                },
            )
        } else {
            ConfirmDialog(
                title = stringResource(R.string.import_replace_confirm_title),
                text = stringResource(R.string.import_replace_confirm_text),
                confirmLabel = stringResource(R.string.import_replace),
                onConfirm = { confirmReplace = false; viewModel.applyImport(ImportMode.REPLACE) },
                onDismiss = { confirmReplace = false },
            )
        }
    }
}

@Composable
private fun summaryText(s: ImportSummary, merge: Boolean): String {
    val base = stringResource(R.string.import_summary, s.entries, s.weights, s.products, s.meals)
    return if (merge && s.weightConflicts > 0) base + "\n" + stringResource(R.string.import_conflicts, s.weightConflicts) else base
}

@Composable
private fun messageText(message: SettingsMessage): String = stringResource(
    when (message) {
        SettingsMessage.NORM_SAVED -> R.string.msg_norm_saved
        SettingsMessage.TARGET_SAVED -> R.string.msg_target_saved
        SettingsMessage.YAZIO_SAVED -> R.string.msg_yazio_saved
        SettingsMessage.YAZIO_ERROR -> R.string.msg_yazio_error
        SettingsMessage.EXPORTED -> R.string.msg_exported
        SettingsMessage.IMPORTED -> R.string.msg_imported
        SettingsMessage.IMPORT_INVALID -> R.string.msg_import_invalid
        SettingsMessage.IO_ERROR -> R.string.msg_io_error
    },
)

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun NormCard(norm: Norm, onSave: (Norm) -> Unit) {
    var kcal by rememberSaveable(norm) { mutableStateOf(norm.kcal.toString()) }
    var p by rememberSaveable(norm) { mutableStateOf(norm.proteinPct.toString()) }
    var f by rememberSaveable(norm) { mutableStateOf(norm.fatPct.toString()) }
    var c by rememberSaveable(norm) { mutableStateOf(norm.carbsPct.toString()) }
    val k = kcal.toIntOrNull()
    val pi = p.toIntOrNull()
    val fi = f.toIntOrNull()
    val ci = c.toIntOrNull()
    val valid = k != null && pi != null && fi != null && ci != null && Norm.isValid(k, pi, fi, ci)
    val sum = (pi ?: 0) + (fi ?: 0) + (ci ?: 0)
    val draft = if (valid) Norm(k, pi, fi, ci) else null

    SectionCard(stringResource(R.string.daily_norm)) {
        IntField(kcal, { kcal = it }, stringResource(R.string.kcal_per_day), Modifier.fillMaxWidth(), isError = k == null || k !in 500..10000)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField(p, { p = it }, stringResource(R.string.protein), Modifier.weight(1f), suffix = "%", isError = sum != 100)
            IntField(f, { f = it }, stringResource(R.string.fat), Modifier.weight(1f), suffix = "%", isError = sum != 100)
            IntField(c, { c = it }, stringResource(R.string.carbs), Modifier.weight(1f), suffix = "%", isError = sum != 100)
        }
        if (sum != 100) {
            Text(stringResource(R.string.pct_sum_error, sum), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else if (draft != null) {
            Text(
                stringResource(R.string.norm_grams, formatNumber(draft.proteinGrams), formatNumber(draft.fatGrams), formatNumber(draft.carbsGrams)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(stringResource(R.string.norm_history_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { draft?.let(onSave) }, enabled = draft != null && draft != norm, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.save))
        }
    }
}

@Composable
private fun TargetWeightCard(target: Double?, onSave: (Double?) -> Unit) {
    var text by rememberSaveable(target) { mutableStateOf(target?.let { formatNumber(it) }.orEmpty()) }
    val value = parseDecimal(text)?.takeIf { it in 1.0..700.0 }
    val validInput = text.isBlank() || value != null
    SectionCard(stringResource(R.string.target_weight)) {
        DecimalField(
            value = text,
            onValueChange = { text = it },
            label = stringResource(R.string.target_weight_optional),
            suffix = stringResource(R.string.kg),
            isError = !validInput,
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSave(value) },
            enabled = validInput && value != target,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun YazioCard(
    configured: Boolean,
    maskedEmail: String?,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
    enabled: Boolean,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    SectionCard(stringResource(R.string.yazio_title)) {
        val note = when {
            !configured -> stringResource(R.string.yazio_note)
            maskedEmail != null -> stringResource(R.string.yazio_configured_as, maskedEmail)
            else -> stringResource(R.string.yazio_configured)
        }
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (configured) {
            OutlinedButton(onClick = onLogout, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.yazio_logout)) }
        } else {
            OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.yazio_email)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.yazio_password)) }, singleLine = true, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Button(onClick = { onLogin(email, password) }, enabled = enabled && email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.yazio_login)) }
        }
    }
}

@Composable
private fun IntField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= 5 && v.all { it.isDigit() }) onValueChange(v) },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = modifier,
    )
}
