package dev.sterrist.openfat.ui.addfood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.sterrist.openfat.R
import dev.sterrist.openfat.data.db.FoodEntryEntity
import dev.sterrist.openfat.data.db.ProductEntity
import dev.sterrist.openfat.data.db.ProductSource
import dev.sterrist.openfat.data.remote.RemoteFood
import dev.sterrist.openfat.domain.parseDecimal
import dev.sterrist.openfat.ui.common.PortionEditor
import dev.sterrist.openfat.ui.common.QuickEntryFields
import dev.sterrist.openfat.ui.common.amountLabel
import dev.sterrist.openfat.ui.common.formatKcal
import dev.sterrist.openfat.ui.common.formatTime
import dev.sterrist.openfat.ui.common.macrosLine
import dev.sterrist.openfat.ui.common.parseQuick
import dev.sterrist.openfat.ui.product.ProductEditorDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodScreen(
    onBack: () -> Unit,
    viewModel: AddFoodViewModel = viewModel(factory = AddFoodViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val messageText = state.message?.let { messageText(it, state.messageArg) }
    LaunchedEffect(state.messageId) {
        if (messageText != null) snackbar.showSnackbar(messageText)
    }

    fun scan() {
        scope.launch {
            try {
                val options = GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                    .enableAutoZoom()
                    .build()
                val barcode = GmsBarcodeScanning.getClient(context, options).startScan().await()
                barcode.rawValue?.let(viewModel::onBarcode)
            } catch (e: CancellationException) {
                // Пользователь закрыл сканер.
                throw e
            } catch (e: MlKitException) {
                if (e.errorCode != MlKitException.CODE_SCANNER_CANCELLED) viewModel.onScannerError()
            } catch (e: Exception) {
                viewModel.onScannerError()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleText(state.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = viewModel::openQuick) { Icon(Icons.Default.Bolt, stringResource(R.string.quick_entry)) }
                    IconButton(onClick = ::scan, enabled = state.barcodeSearch == null) {
                        Icon(Icons.Default.QrCodeScanner, stringResource(R.string.scan_barcode))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.imePadding(),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text(stringResource(R.string.search_food_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) { Icon(Icons.Default.Clear, stringResource(R.string.clear)) }
                    }
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.barcodeSearch != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text(
                    stringResource(R.string.searching_barcode, state.barcodeSearch!!),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val searching = state.query.isNotBlank()
                if (state.recent.isNotEmpty()) {
                    sectionHeader("recent", R.string.recent)
                    items(state.recent, key = { "r-" + it.id }) { RecentRow(it) { viewModel.selectRecent(it) } }
                } else if (!searching) {
                    item("empty") {
                        Text(
                            stringResource(R.string.recent_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                if (searching) {
                    sectionHeader("local", R.string.my_products)
                    if (state.local.isEmpty()) {
                        item("local-empty") { HintText(stringResource(R.string.nothing_found)) }
                    } else {
                        items(state.local, key = { "p-" + it.id }) { ProductRow(it) { viewModel.selectProduct(it) } }
                    }
                    state.remote.forEach { (source, status) -> remoteSection(source, status, viewModel::selectRemote) }
                    item("create") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = viewModel::createProduct, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.create_product_named, state.query.trim()), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(onClick = viewModel::openQuick, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.quick_entry))
                            }
                        }
                    }
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        is AddFoodDialog.Portion -> PortionSheet(dialog, state.busy, viewModel)
        is AddFoodDialog.Quick -> QuickDialog(dialog, state.busy, viewModel)
        is AddFoodDialog.Editor -> ProductEditorDialog(
            initial = dialog.draft,
            busy = state.busy,
            onSave = { viewModel.saveProduct(it, dialog.thenAdd) },
            onDismiss = viewModel::dismissDialog,
        )
        null -> Unit
    }
}

@Composable
private fun titleText(title: TitleInfo): String = when (title) {
    TitleInfo.Loading -> stringResource(R.string.add_food)
    is TitleInfo.Meal -> stringResource(R.string.add_to, title.name)
    is TitleInfo.Snack -> stringResource(R.string.add_to, stringResource(R.string.snack_at, formatTime(title.minutes)))
}

@Composable
private fun messageText(message: AddFoodMessage, arg: String): String = when (message) {
    AddFoodMessage.ADDED -> stringResource(R.string.msg_added, arg)
    AddFoodMessage.NOT_FOUND -> stringResource(R.string.msg_not_found)
    AddFoodMessage.INCOMPLETE -> stringResource(R.string.msg_incomplete)
    AddFoodMessage.NETWORK_ERROR -> stringResource(R.string.msg_network_error)
    AddFoodMessage.SCANNER_ERROR -> stringResource(R.string.msg_scanner_error)
    AddFoodMessage.BAD_BARCODE -> stringResource(R.string.msg_bad_barcode)
    AddFoodMessage.DUPLICATE_BARCODE -> stringResource(R.string.msg_duplicate_barcode)
}

private fun LazyListScope.sectionHeader(key: String, title: Int, trailing: (@Composable () -> Unit)? = null) {
    item("h-$key") {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            trailing?.invoke()
        }
    }
}

private fun LazyListScope.remoteSection(source: ProductSource, status: RemoteStatus, onSelect: (RemoteFood) -> Unit) {
    val title = when (source) {
        ProductSource.YAZIO -> R.string.source_yazio
        ProductSource.OFF -> R.string.source_off
        ProductSource.USDA -> R.string.source_usda
        ProductSource.USER -> R.string.my_products
    }
    sectionHeader(source.name, title) {
        if (status is RemoteStatus.Loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    }
    when (status) {
        RemoteStatus.Loading -> Unit
        RemoteStatus.Disabled -> item("${source.name}-off") {
            HintText(stringResource(R.string.yazio_disabled))
        }
        is RemoteStatus.Failed -> item("${source.name}-err") {
            HintText(stringResource(if (status.offline) R.string.no_network else R.string.source_error))
        }
        is RemoteStatus.Done -> {
            if (status.items.isEmpty()) {
                item("${source.name}-empty") { HintText(stringResource(R.string.nothing_found)) }
            } else {
                items(status.items, key = { "${source.name}-${it.sourceRef}" }) { RemoteRow(it) { onSelect(it) } }
            }
            if (source == ProductSource.OFF) item("off-attr") { HintText(stringResource(R.string.off_attribution)) }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun per100Line(kcal: Double?, protein: Double?, fat: Double?, carbs: Double?): String {
    if (kcal == null) return stringResource(R.string.no_nutrition)
    val macros = macrosLine(protein, fat, carbs)
    return listOfNotNull(stringResource(R.string.kcal_per_100, formatKcal(kcal)), macros).joinToString(" · ")
}

@Composable
private fun FoodRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Icon(Icons.Default.Add, contentDescription = null) },
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.add), onClick = onClick),
    )
}

@Composable
private fun RecentRow(entry: FoodEntryEntity, onClick: () -> Unit) {
    val subtitle = listOfNotNull(
        entry.brand,
        amountLabel(entry.amount, entry.unit, entry.grams),
        stringResource(R.string.kcal_value, formatKcal(entry.kcal)),
    ).joinToString(" · ")
    FoodRow(entry.name, subtitle, onClick)
}

@Composable
private fun ProductRow(product: ProductEntity, onClick: () -> Unit) {
    val subtitle = listOfNotNull(product.brand, per100Line(product.kcal100, product.protein100, product.fat100, product.carbs100))
        .joinToString(" · ")
    FoodRow(product.name, subtitle, onClick)
}

@Composable
private fun RemoteRow(food: RemoteFood, onClick: () -> Unit) {
    val nutrition = if (food.isComplete) {
        per100Line(food.kcal100, food.protein100, food.fat100, food.carbs100)
    } else {
        stringResource(R.string.incomplete_nutrition)
    }
    FoodRow(food.name, listOfNotNull(food.brand, nutrition).joinToString(" · "), onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortionSheet(dialog: AddFoodDialog.Portion, busy: Boolean, viewModel: AddFoodViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val key = dialog.item.productId ?: dialog.remote?.sourceRef ?: dialog.item.name
    var amount by rememberSaveable(key) { mutableStateOf(dialog.amount) }
    var unit by rememberSaveable(key) { mutableStateOf(dialog.unit) }
    val canEditProduct = dialog.item.productId != null || dialog.remote != null
    ModalBottomSheet(onDismissRequest = viewModel::dismissDialog, sheetState = sheetState) {
        Column(
            Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(dialog.item.name, style = MaterialTheme.typography.titleLarge)
            dialog.item.brand?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            PortionEditor(
                per100 = dialog.item.per100,
                amountText = amount,
                unit = unit,
                onAmountChange = { amount = it },
                onUnitChange = { u ->
                    unit = u
                    amount = if (u == dev.sterrist.openfat.domain.PortionUnit.GRAM) "100" else "1"
                },
                onSetPieceWeight = if (canEditProduct) viewModel::editPortionProduct else null,
            )
            val value = parseDecimal(amount)?.takeIf { it > 0 && it <= 100_000 }
            Button(
                onClick = { value?.let { viewModel.addPortion(it, unit) } },
                enabled = value != null && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.add)) }
        }
    }
}

@Composable
private fun QuickDialog(dialog: AddFoodDialog.Quick, busy: Boolean, viewModel: AddFoodViewModel) {
    var name by rememberSaveable { mutableStateOf(dialog.name) }
    var kcal by rememberSaveable { mutableStateOf(dialog.kcal) }
    var protein by rememberSaveable { mutableStateOf(dialog.protein) }
    var fat by rememberSaveable { mutableStateOf(dialog.fat) }
    var carbs by rememberSaveable { mutableStateOf(dialog.carbs) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = { Text(stringResource(R.string.quick_entry)) },
        text = {
            QuickEntryFields(
                name, { name = it }, kcal, { kcal = it }, protein, { protein = it },
                fat, { fat = it }, carbs, { carbs = it }, showErrors,
            )
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val parsed = parseQuick(name, kcal, protein, fat, carbs)
                    if (parsed == null) showErrors = true else viewModel.addQuick(parsed.first, parsed.second)
                },
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.cancel)) } },
    )
}
