package dev.sterrist.openfat.ui.addfood

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import dev.sterrist.openfat.data.db.FoodEntryEntity
import dev.sterrist.openfat.data.db.ProductEntity
import dev.sterrist.openfat.data.db.ProductSource
import dev.sterrist.openfat.data.remote.RemoteFood
import dev.sterrist.openfat.data.remote.isValidBarcode
import dev.sterrist.openfat.data.repo.BarcodeResult
import dev.sterrist.openfat.data.repo.DiaryRepository
import dev.sterrist.openfat.data.repo.DuplicateBarcodeException
import dev.sterrist.openfat.data.repo.EntrySlot
import dev.sterrist.openfat.data.repo.FoodItem
import dev.sterrist.openfat.data.repo.ProductDraft
import dev.sterrist.openfat.data.repo.ProductRepository
import dev.sterrist.openfat.domain.Macros
import dev.sterrist.openfat.domain.PortionUnit
import dev.sterrist.openfat.domain.defaultAmount
import dev.sterrist.openfat.ui.AddFoodRoute
import dev.sterrist.openfat.ui.common.appContainer
import dev.sterrist.openfat.ui.common.formatNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface RemoteStatus {
    data object Loading : RemoteStatus
    data class Done(val items: List<RemoteFood>) : RemoteStatus
    data class Failed(val offline: Boolean) : RemoteStatus
    data object Disabled : RemoteStatus
}

sealed interface AddFoodDialog {
    /** [remote] — продукт ещё не в справочнике; кэшируется при добавлении. */
    data class Portion(
        val item: FoodItem,
        val amount: String,
        val unit: PortionUnit,
        val remote: RemoteFood? = null,
    ) : AddFoodDialog

    data class Quick(
        val name: String = "",
        val kcal: String = "",
        val protein: String = "",
        val fat: String = "",
        val carbs: String = "",
    ) : AddFoodDialog

    data class Editor(val draft: ProductDraft, val thenAdd: Boolean) : AddFoodDialog
}

enum class AddFoodMessage { ADDED, NOT_FOUND, INCOMPLETE, NETWORK_ERROR, SCANNER_ERROR, BAD_BARCODE, DUPLICATE_BARCODE }

data class AddFoodUiState(
    val query: String = "",
    val title: TitleInfo = TitleInfo.Loading,
    val recent: List<FoodEntryEntity> = emptyList(),
    val local: List<ProductEntity> = emptyList(),
    val remote: Map<ProductSource, RemoteStatus> = emptyMap(),
    val dialog: AddFoodDialog? = null,
    val busy: Boolean = false,
    val barcodeSearch: String? = null,
    val message: AddFoodMessage? = null,
    val messageArg: String = "",
    /** Растёт с каждым сообщением, чтобы одинаковые сообщения подряд тоже показывались. */
    val messageId: Long = 0,
)

sealed interface TitleInfo {
    data object Loading : TitleInfo
    data class Meal(val name: String) : TitleInfo
    data class Snack(val minutes: Int) : TitleInfo
}

private fun Double?.input() = this?.let { formatNumber(it) }.orEmpty()
private fun PortionUnit.defaultAmountInput() = formatNumber(defaultAmount())

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AddFoodViewModel(
    private val diary: DiaryRepository,
    private val products: ProductRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val route = saved.toRoute<AddFoodRoute>()
    private val day = route.day
    private val slotMutex = Mutex()

    private val query = saved.getStateFlow(KEY_QUERY, "")
    private val extra = MutableStateFlow(AddFoodUiState())

    private val recent = diary.observeRecent()
    private val local: Flow<List<ProductEntity>> = query.flatMapLatest { products.searchLocal(it) }

    private val remote: Flow<Map<ProductSource, RemoteStatus>> = query
        .map { it.trim() }
        .debounce(SEARCH_DEBOUNCE_MS)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.length < MIN_REMOTE_QUERY) flowOf(emptyMap()) else searchRemote(q)
        }

    val state: StateFlow<AddFoodUiState> = combine(query, recent, local, remote, extra) { q, r, l, rem, e ->
        val needle = q.trim().lowercase()
        e.copy(
            query = q,
            recent = if (needle.isEmpty()) r else r.filter { it.name.lowercase().contains(needle) },
            local = l,
            remote = if (needle.length < MIN_REMOTE_QUERY) emptyMap() else rem,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddFoodUiState())

    init {
        viewModelScope.launch {
            val title = when {
                route.mealId != null -> TitleInfo.Meal(diary.mealById(route.mealId)?.name.orEmpty())
                currentSnackId() != null -> TitleInfo.Snack(diary.snackById(currentSnackId()!!)?.timeMinutes ?: 0)
                else -> TitleInfo.Snack(route.newSnackMinutes.coerceAtLeast(0))
            }
            extra.update { it.copy(title = title) }
        }
    }

    private fun searchRemote(q: String): Flow<Map<ProductSource, RemoteStatus>> = channelFlow {
        var current = products.sources.associate { s ->
            s.source to if (s.isEnabled()) RemoteStatus.Loading else RemoteStatus.Disabled
        }
        send(current)
        products.sources.filter { it.isEnabled() }.forEach { source ->
            launch {
                val status = try {
                    RemoteStatus.Done(source.search(q))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RemoteStatus.Failed(offline = e.isOffline())
                }
                current = current + (source.source to status)
                send(current)
            }
        }
    }

    fun onQueryChange(value: String) {
        if (value.length <= 100) saved[KEY_QUERY] = value
    }

    fun selectProduct(product: ProductEntity) {
        val unit = product.per100.defaultUnit()
        openDialog(AddFoodDialog.Portion(FoodItem(product.id, product.name, product.brand, product.per100), unit.defaultAmountInput(), unit))
    }

    fun selectRecent(entry: FoodEntryEntity) {
        val per100 = entry.per100
        if (per100 == null) {
            openDialog(AddFoodDialog.Quick(entry.name, entry.kcal.input(), entry.protein.input(), entry.fat.input(), entry.carbs.input()))
        } else {
            // Недавнее блюдо открывается с прошлой порцией; если её единица недоступна — с единицей по умолчанию.
            val unit = entry.unit?.takeIf { per100.unitGrams(it) != null } ?: per100.defaultUnit()
            val amount = if (unit == entry.unit) entry.amount.input() else unit.defaultAmountInput()
            openDialog(AddFoodDialog.Portion(FoodItem(entry.productId, entry.name, entry.brand, per100), amount, unit))
        }
    }

    fun selectRemote(food: RemoteFood) {
        if (food.isComplete) {
            val per100 = dev.sterrist.openfat.domain.Per100(
                food.kcal100!!, food.protein100!!, food.fat100!!, food.carbs100!!, food.servingGrams, null,
            )
            val unit = per100.defaultUnit()
            openDialog(AddFoodDialog.Portion(FoodItem(null, food.name, food.brand, per100), unit.defaultAmountInput(), unit, remote = food))
        } else {
            openDialog(AddFoodDialog.Editor(ProductDraft.of(food), thenAdd = true))
            showMessage(AddFoodMessage.INCOMPLETE)
        }
    }

    fun openQuick() = openDialog(AddFoodDialog.Quick(name = query.value.trim()))

    fun createProduct() = openDialog(AddFoodDialog.Editor(ProductDraft.empty(name = query.value.trim()), thenAdd = true))

    /** Из листа порции: открыть карточку продукта, чтобы задать вес штуки или порции. */
    fun editPortionProduct() {
        val dialog = extra.value.dialog as? AddFoodDialog.Portion ?: return
        viewModelScope.launch {
            val draft = dialog.item.productId?.let { products.byId(it) }?.let { ProductDraft.of(it) }
                ?: dialog.remote?.let { ProductDraft.of(it) }
                ?: return@launch
            openDialog(AddFoodDialog.Editor(draft, thenAdd = true))
        }
    }

    fun dismissDialog() = extra.update { it.copy(dialog = null) }

    fun onScannerError() = showMessage(AddFoodMessage.SCANNER_ERROR)

    fun onBarcode(raw: String) {
        val code = raw.trim()
        if (!isValidBarcode(code)) {
            showMessage(AddFoodMessage.BAD_BARCODE)
            return
        }
        if (extra.value.barcodeSearch != null) return
        extra.update { it.copy(barcodeSearch = code) }
        viewModelScope.launch {
            try {
                when (val result = products.lookupBarcode(code)) {
                    is BarcodeResult.Found -> selectProduct(result.product)
                    is BarcodeResult.Incomplete -> {
                        openDialog(AddFoodDialog.Editor(result.draft, thenAdd = true))
                        showMessage(AddFoodMessage.INCOMPLETE)
                    }
                    is BarcodeResult.NotFound -> {
                        openDialog(AddFoodDialog.Editor(ProductDraft.empty(barcode = code), thenAdd = true))
                        showMessage(AddFoodMessage.NOT_FOUND)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(AddFoodMessage.NETWORK_ERROR)
            } finally {
                extra.update { it.copy(barcodeSearch = null) }
            }
        }
    }

    fun saveProduct(draft: ProductDraft, thenAdd: Boolean) = runBusy {
        try {
            val product = products.save(draft)
            if (thenAdd) selectProduct(product) else dismissDialog()
        } catch (e: DuplicateBarcodeException) {
            showMessage(AddFoodMessage.DUPLICATE_BARCODE)
        }
    }

    fun addPortion(amount: Double, unit: PortionUnit) {
        val dialog = extra.value.dialog as? AddFoodDialog.Portion ?: return
        runBusy {
            val item = if (dialog.remote != null) {
                val cached = products.cacheRemote(dialog.remote)
                FoodItem(cached.id, cached.name, cached.brand, cached.per100)
            } else {
                dialog.item.also { item -> item.productId?.let { products.byId(it) }?.let { products.touch(it) } }
            }
            val entry = diary.addPortion(day, resolveSlot(), item, amount, unit)
            dismissDialog()
            showMessage(AddFoodMessage.ADDED, entry.name)
        }
    }

    fun addQuick(name: String, macros: Macros) = runBusy {
        val entry = diary.addQuick(day, resolveSlot(), name, macros)
        dismissDialog()
        showMessage(AddFoodMessage.ADDED, entry.name)
    }

    private fun currentSnackId(): String? = saved.get<String>(KEY_SNACK) ?: route.snackId

    /** Новый перекус создаётся при первом добавлении; повторные добавления идут в него же. */
    private suspend fun resolveSlot(): EntrySlot = slotMutex.withLock {
        route.mealId?.let { return@withLock EntrySlot.Meal(it) }
        currentSnackId()?.let { return@withLock EntrySlot.Snack(it) }
        val id = diary.addSnack(day, route.newSnackMinutes.coerceAtLeast(0))
        saved[KEY_SNACK] = id
        EntrySlot.Snack(id)
    }

    private fun openDialog(dialog: AddFoodDialog) = extra.update { it.copy(dialog = dialog) }

    private fun showMessage(message: AddFoodMessage, arg: String = "") =
        extra.update { it.copy(message = message, messageArg = arg, messageId = it.messageId + 1) }

    /** Защита от двойного нажатия: пока операция идёт, новые не запускаются. */
    private fun runBusy(block: suspend () -> Unit) {
        if (extra.value.busy) return
        extra.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                extra.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        private const val KEY_QUERY = "query"
        private const val KEY_SNACK = "created_snack_id"
        private const val SEARCH_DEBOUNCE_MS = 600L
        private const val MIN_REMOTE_QUERY = 3

        val Factory = viewModelFactory {
            initializer {
                val c = appContainer()
                AddFoodViewModel(c.diary, c.products, createSavedStateHandle())
            }
        }
    }
}

private fun Throwable.isOffline(): Boolean =
    this is UnknownHostException || this is ConnectException || this is SocketTimeoutException ||
        (this is IOException && cause?.isOffline() == true)
