package dev.sterrist.openfat.ui.product

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import dev.sterrist.openfat.data.db.ProductEntity
import dev.sterrist.openfat.data.repo.DuplicateBarcodeException
import dev.sterrist.openfat.data.repo.ProductDraft
import dev.sterrist.openfat.data.repo.ProductRepository
import dev.sterrist.openfat.ui.common.ConfirmDialog
import dev.sterrist.openfat.ui.common.appContainer
import dev.sterrist.openfat.ui.common.formatKcal
import dev.sterrist.openfat.ui.common.macrosLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ProductsViewModel(private val products: ProductRepository, private val saved: SavedStateHandle) : ViewModel() {
    val query: StateFlow<String> = saved.getStateFlow(KEY_QUERY, "")

    val items: StateFlow<List<ProductEntity>> = query
        .flatMapLatest { q -> if (q.isBlank()) products.observeAll() else products.searchLocal(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Открытый редактор: черновик продукта или null. */
    private val _editing = MutableStateFlow<ProductDraft?>(null)
    val editing: StateFlow<ProductDraft?> = _editing.asStateFlow()

    private val _duplicateBarcode = MutableStateFlow(0)
    val duplicateBarcode: StateFlow<Int> = _duplicateBarcode.asStateFlow()

    fun setQuery(value: String) {
        if (value.length <= 100) saved[KEY_QUERY] = value
    }

    fun edit(product: ProductEntity?) {
        _editing.value = product?.let { ProductDraft.of(it) } ?: ProductDraft.empty()
    }

    fun closeEditor() {
        _editing.value = null
    }

    fun save(draft: ProductDraft) = viewModelScope.launch {
        try {
            products.save(draft)
            _editing.value = null
        } catch (e: DuplicateBarcodeException) {
            _duplicateBarcode.value++
        }
    }

    fun delete(id: String) = viewModelScope.launch { products.delete(id) }

    companion object {
        private const val KEY_QUERY = "query"

        val Factory = viewModelFactory {
            initializer { ProductsViewModel(appContainer().products, createSavedStateHandle()) }
        }
    }
}

/** Справочник продуктов: поиск, правка и удаление. Записи в дневнике хранят свой снимок КБЖУ. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen(onBack: () -> Unit, viewModel: ProductsViewModel = viewModel(factory = ProductsViewModel.Factory)) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val duplicate by viewModel.duplicateBarcode.collectAsStateWithLifecycle()
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val duplicateText = stringResource(R.string.msg_duplicate_barcode)
    LaunchedEffect(duplicate) { if (duplicate > 0) snackbar.showSnackbar(duplicateText) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_products)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.edit(null) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_product)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text(stringResource(R.string.search_products)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp), modifier = Modifier.fillMaxSize()) {
                if (items.isEmpty()) {
                    item {
                        Text(
                            stringResource(if (query.isBlank()) R.string.products_empty else R.string.nothing_found),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(items, key = { it.id }) { p ->
                    val subtitle = listOfNotNull(
                        p.brand,
                        stringResource(R.string.kcal_per_100, formatKcal(p.kcal100)),
                        macrosLine(p.protein100, p.fat100, p.carbs100),
                    ).joinToString(" · ")
                    ListItem(
                        headlineContent = { Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(subtitle) },
                        trailingContent = {
                            IconButton(onClick = { deletingId = p.id }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                        },
                        modifier = Modifier.clickable { viewModel.edit(p) },
                    )
                }
            }
        }
    }

    editing?.let { draft ->
        ProductEditorDialog(initial = draft, busy = false, onSave = viewModel::save, onDismiss = viewModel::closeEditor)
    }
    deletingId?.let { id ->
        val product = items.firstOrNull { it.id == id }
        ConfirmDialog(
            title = stringResource(R.string.delete_product_title, product?.name.orEmpty()),
            text = stringResource(R.string.delete_product_text),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { viewModel.delete(id); deletingId = null },
            onDismiss = { deletingId = null },
        )
    }
}
