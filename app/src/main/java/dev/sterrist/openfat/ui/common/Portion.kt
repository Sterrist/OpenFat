package dev.sterrist.openfat.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.sterrist.openfat.R
import dev.sterrist.openfat.domain.Macros
import dev.sterrist.openfat.domain.Per100
import dev.sterrist.openfat.domain.PortionUnit
import dev.sterrist.openfat.domain.parseDecimal

/** Выбор количества и единицы с живым пересчётом КБЖУ. */
@Composable
fun PortionEditor(
    per100: Per100,
    amountText: String,
    unit: PortionUnit,
    onAmountChange: (String) -> Unit,
    onUnitChange: (PortionUnit) -> Unit,
    onSetPieceWeight: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PortionUnit.entries.forEachIndexed { index, u ->
                SegmentedButton(
                    selected = unit == u,
                    onClick = { onUnitChange(u) },
                    enabled = per100.unitGrams(u) != null,
                    shape = SegmentedButtonDefaults.itemShape(index, PortionUnit.entries.size),
                ) { Text(unitLabel(u)) }
            }
        }
        if (per100.pieceGrams == null && onSetPieceWeight != null) {
            TextButton(onClick = onSetPieceWeight) { Text(stringResource(R.string.set_piece_weight)) }
        }
        val amount = parseDecimal(amountText)
        val grams = amount?.let { per100.gramsFor(it, unit) }
        DecimalField(
            value = amountText,
            onValueChange = onAmountChange,
            label = stringResource(R.string.amount),
            suffix = unitLabel(unit),
            isError = amount == null || amount <= 0,
            supportingText = if (unit != PortionUnit.GRAM && grams != null) {
                stringResource(R.string.equals_grams, formatNumber(grams))
            } else {
                null
            },
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
        )
        MacroSummary(grams?.takeIf { it > 0 }?.let { per100.forGrams(it) } ?: Macros.ZERO)
    }
}

/** Строка «ккал · Б · Ж · У». */
@Composable
fun MacroSummary(macros: Macros, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacroCell(stringResource(R.string.kcal), formatKcal(macros.kcal))
        MacroCell(stringResource(R.string.protein_short), formatGrams(macros.protein))
        MacroCell(stringResource(R.string.fat_short), formatGrams(macros.fat))
        MacroCell(stringResource(R.string.carbs_short), formatGrams(macros.carbs))
    }
}

@Composable
private fun MacroCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Поля быстрого ввода: название и ккал обязательны, Б/Ж/У — по желанию. */
@Composable
fun QuickEntryFields(
    name: String,
    onNameChange: (String) -> Unit,
    kcal: String,
    onKcalChange: (String) -> Unit,
    protein: String,
    onProteinChange: (String) -> Unit,
    fat: String,
    onFatChange: (String) -> Unit,
    carbs: String,
    onCarbsChange: (String) -> Unit,
    showErrors: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 200) onNameChange(it) },
            label = { Text(stringResource(R.string.food_name)) },
            isError = showErrors && name.isBlank(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DecimalField(
            value = kcal,
            onValueChange = onKcalChange,
            label = stringResource(R.string.kcal),
            isError = showErrors && parseDecimal(kcal) == null,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.macros_optional),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecimalField(protein, onProteinChange, stringResource(R.string.protein_short), Modifier.weight(1f), suffix = stringResource(R.string.unit_gram))
            DecimalField(fat, onFatChange, stringResource(R.string.fat_short), Modifier.weight(1f), suffix = stringResource(R.string.unit_gram))
            DecimalField(carbs, onCarbsChange, stringResource(R.string.carbs_short), Modifier.weight(1f), suffix = stringResource(R.string.unit_gram), imeAction = ImeAction.Done)
        }
    }
}

/** Значения быстрого ввода или null, если обязательные поля не заполнены. */
fun parseQuick(name: String, kcal: String, protein: String, fat: String, carbs: String): Pair<String, Macros>? {
    if (name.isBlank()) return null
    val k = parseDecimal(kcal)?.takeIf { it <= 100_000 } ?: return null
    fun opt(s: String) = if (s.isBlank()) null else parseDecimal(s)
    return name.trim() to Macros(k, opt(protein), opt(fat), opt(carbs))
}
