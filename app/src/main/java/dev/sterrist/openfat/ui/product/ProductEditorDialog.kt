package dev.sterrist.openfat.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.sterrist.openfat.R
import dev.sterrist.openfat.data.remote.isValidBarcode
import dev.sterrist.openfat.data.repo.ProductDraft
import dev.sterrist.openfat.domain.parseDecimal
import dev.sterrist.openfat.ui.common.DecimalField
import dev.sterrist.openfat.ui.common.formatNumber

private fun Double?.input() = this?.let { formatNumber(it) }.orEmpty()

/** Полноэкранная форма продукта: КБЖУ на 100 г, вес порции и штуки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductEditorDialog(
    initial: ProductDraft,
    busy: Boolean,
    onSave: (ProductDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val key = initial.id ?: initial.sourceRef ?: initial.barcode ?: initial.name
    var name by rememberSaveable(key) { mutableStateOf(initial.name) }
    var brand by rememberSaveable(key) { mutableStateOf(initial.brand.orEmpty()) }
    var barcode by rememberSaveable(key) { mutableStateOf(initial.barcode.orEmpty()) }
    var kcal by rememberSaveable(key) { mutableStateOf(initial.kcal100.input()) }
    var protein by rememberSaveable(key) { mutableStateOf(initial.protein100.input()) }
    var fat by rememberSaveable(key) { mutableStateOf(initial.fat100.input()) }
    var carbs by rememberSaveable(key) { mutableStateOf(initial.carbs100.input()) }
    var serving by rememberSaveable(key) { mutableStateOf(initial.servingGrams.input()) }
    var piece by rememberSaveable(key) { mutableStateOf(initial.pieceGrams.input()) }
    var showErrors by rememberSaveable(key) { mutableStateOf(false) }

    val kcalV = parseDecimal(kcal)?.takeIf { it <= 1000 }
    val pV = parseDecimal(protein)?.takeIf { it <= 100 }
    val fV = parseDecimal(fat)?.takeIf { it <= 100 }
    val cV = parseDecimal(carbs)?.takeIf { it <= 100 }
    val macrosTooBig = pV != null && fV != null && cV != null && pV + fV + cV > 100.5
    fun optPositive(s: String) = if (s.isBlank()) true else parseDecimal(s)?.let { it > 0 && it <= 10_000 } == true
    val barcodeOk = barcode.isBlank() || isValidBarcode(barcode.trim())
    val valid = name.isNotBlank() && kcalV != null && pV != null && fV != null && cV != null && !macrosTooBig &&
        optPositive(serving) && optPositive(piece) && barcodeOk

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (initial.id == null) R.string.new_product else R.string.edit_product)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    },
                    actions = {
                        Button(
                            enabled = !busy,
                            onClick = {
                                if (!valid) {
                                    showErrors = true
                                } else {
                                    onSave(
                                        initial.copy(
                                            name = name.trim(),
                                            brand = brand.trim().ifEmpty { null },
                                            barcode = barcode.trim().ifEmpty { null },
                                            kcal100 = kcalV,
                                            protein100 = pV,
                                            fat100 = fV,
                                            carbs100 = cV,
                                            servingGrams = parseDecimal(serving),
                                            pieceGrams = parseDecimal(piece),
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text(stringResource(R.string.save)) }
                    },
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 200) name = it },
                    label = { Text(stringResource(R.string.product_name_required)) },
                    isError = showErrors && name.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = brand,
                    onValueChange = { if (it.length <= 200) brand = it },
                    label = { Text(stringResource(R.string.brand)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = barcode,
                    onValueChange = { v -> if (v.length <= 14 && v.all { it.isDigit() }) barcode = v },
                    label = { Text(stringResource(R.string.barcode)) },
                    isError = !barcodeOk,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.per_100g),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                DecimalField(
                    kcal, { kcal = it }, stringResource(R.string.kcal_required),
                    Modifier.fillMaxWidth(), isError = showErrors && kcalV == null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val g = stringResource(R.string.unit_gram)
                    DecimalField(protein, { protein = it }, stringResource(R.string.protein), Modifier.weight(1f), suffix = g, isError = showErrors && (pV == null || macrosTooBig))
                    DecimalField(fat, { fat = it }, stringResource(R.string.fat), Modifier.weight(1f), suffix = g, isError = showErrors && (fV == null || macrosTooBig))
                    DecimalField(carbs, { carbs = it }, stringResource(R.string.carbs), Modifier.weight(1f), suffix = g, isError = showErrors && (cV == null || macrosTooBig))
                }
                if (showErrors && macrosTooBig) {
                    Text(stringResource(R.string.macros_over_100), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(R.string.portion_weights),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val g = stringResource(R.string.unit_gram)
                    DecimalField(serving, { serving = it }, stringResource(R.string.serving_weight), Modifier.weight(1f), suffix = g, isError = !optPositive(serving))
                    DecimalField(piece, { piece = it }, stringResource(R.string.piece_weight), Modifier.weight(1f), suffix = g, isError = !optPositive(piece))
                }
            }
        }
    }
}
