package dev.sterrist.openfat.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sterrist.openfat.R
import dev.sterrist.openfat.data.db.FoodEntryEntity
import dev.sterrist.openfat.data.repo.EntrySlot
import dev.sterrist.openfat.data.repo.withPortion
import dev.sterrist.openfat.domain.PortionUnit
import dev.sterrist.openfat.domain.parseDecimal
import dev.sterrist.openfat.ui.common.PortionEditor
import dev.sterrist.openfat.ui.common.QuickEntryFields
import dev.sterrist.openfat.ui.common.formatNumber
import dev.sterrist.openfat.ui.common.formatTime
import dev.sterrist.openfat.ui.common.parseQuick

private fun Double?.asInput(): String = this?.let { formatNumber(it) }.orEmpty()

/** Правка записи: количество или КБЖУ, перенос в другой приём или перекус, удаление. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditSheet(
    entry: FoodEntryEntity,
    state: DiaryUiState,
    onSave: (FoodEntryEntity) -> Unit,
    onMove: (FoodEntryEntity, EntrySlot) -> FoodEntryEntity,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val per100 = entry.per100

    var amount by rememberSaveable(entry.id) { mutableStateOf(entry.amount.asInput()) }
    var unit by rememberSaveable(entry.id) { mutableStateOf(entry.unit ?: PortionUnit.GRAM) }
    var name by rememberSaveable(entry.id) { mutableStateOf(entry.name) }
    var kcal by rememberSaveable(entry.id) { mutableStateOf(formatNumber(entry.kcal)) }
    var protein by rememberSaveable(entry.id) { mutableStateOf(entry.protein.asInput()) }
    var fat by rememberSaveable(entry.id) { mutableStateOf(entry.fat.asInput()) }
    var carbs by rememberSaveable(entry.id) { mutableStateOf(entry.carbs.asInput()) }
    var slotKey by rememberSaveable(entry.id) { mutableStateOf(entry.mealId?.let { "m:$it" } ?: "s:${entry.snackId}") }
    var showErrors by rememberSaveable(entry.id) { mutableStateOf(false) }

    val slots: List<Pair<String, String>> =
        state.meals.map { "m:${it.meal.id}" to it.meal.name } +
            state.snacks.map { "s:${it.snack.id}" to stringResource(R.string.snack_at, formatTime(it.snack.timeMinutes)) }

    fun buildResult(): FoodEntryEntity? {
        val updated = if (per100 != null) {
            val a = parseDecimal(amount)?.takeIf { it > 0 } ?: return null
            entry.withPortion(a, unit) ?: return null
        } else {
            val (n, m) = parseQuick(name, kcal, protein, fat, carbs) ?: return null
            entry.copy(name = n, kcal = m.kcal, protein = m.protein, fat = m.fat, carbs = m.carbs)
        }
        val slot = if (slotKey.startsWith("m:")) EntrySlot.Meal(slotKey.drop(2)) else EntrySlot.Snack(slotKey.drop(2))
        return onMove(updated, slot)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                listOfNotNull(entry.name, entry.brand).joinToString(" · "),
                style = MaterialTheme.typography.titleLarge,
            )
            if (per100 != null) {
                PortionEditor(
                    per100 = per100,
                    amountText = amount,
                    unit = unit,
                    onAmountChange = { amount = it },
                    onUnitChange = { u ->
                        unit = u
                        amount = if (u == PortionUnit.GRAM) "100" else "1"
                    },
                )
            } else {
                QuickEntryFields(
                    name, { name = it }, kcal, { kcal = it }, protein, { protein = it },
                    fat, { fat = it }, carbs, { carbs = it }, showErrors,
                )
            }

            SlotDropdown(slots, slotKey) { slotKey = it }

            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = {
                    val result = buildResult()
                    if (result == null) showErrors = true else onSave(result)
                }) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotDropdown(slots: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = slots.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.meal_or_snack)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            slots.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(key); expanded = false })
            }
        }
    }
}
