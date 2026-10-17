package dev.sterrist.openfat.data.repo

import dev.sterrist.openfat.data.db.ProductDao
import dev.sterrist.openfat.data.db.ProductEntity
import dev.sterrist.openfat.data.db.ProductSource
import dev.sterrist.openfat.data.remote.FoodSource
import dev.sterrist.openfat.data.remote.RemoteFood
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/** Черновик продукта для формы редактирования. */
data class ProductDraft(
    val id: String?,
    val name: String,
    val brand: String?,
    val barcode: String?,
    val kcal100: Double?,
    val protein100: Double?,
    val fat100: Double?,
    val carbs100: Double?,
    val servingGrams: Double?,
    val pieceGrams: Double?,
    val source: ProductSource,
    val sourceRef: String?,
) {
    companion object {
        fun empty(barcode: String? = null, name: String = "") =
            ProductDraft(null, name, null, barcode, null, null, null, null, null, null, ProductSource.USER, null)

        fun of(p: ProductEntity) = ProductDraft(
            p.id, p.name, p.brand, p.barcode, p.kcal100, p.protein100, p.fat100, p.carbs100,
            p.servingGrams, p.pieceGrams, p.source, p.sourceRef,
        )

        fun of(r: RemoteFood) = ProductDraft(
            null, r.name, r.brand, r.barcode, r.kcal100, r.protein100, r.fat100, r.carbs100,
            r.servingGrams, null, r.source, r.sourceRef,
        )
    }
}

sealed interface BarcodeResult {
    data class Found(val product: ProductEntity) : BarcodeResult

    /** Найден во внешнем источнике, но КБЖУ неполное — нужно дозаполнить вручную. */
    data class Incomplete(val draft: ProductDraft) : BarcodeResult
    data class NotFound(val barcode: String) : BarcodeResult
}

class DuplicateBarcodeException : IllegalStateException("Barcode already used")

class ProductRepository(
    private val dao: ProductDao,
    val sources: List<FoodSource>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observeAll(): Flow<List<ProductEntity>> = dao.observeAll()

    fun searchLocal(query: String): Flow<List<ProductEntity>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return flowOf(emptyList())
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return dao.search("%$escaped%")
    }

    suspend fun byId(id: String): ProductEntity? = dao.byId(id)

    /** Сохраняет продукт из формы. Штрихкод уникален в справочнике. */
    suspend fun save(draft: ProductDraft): ProductEntity {
        val barcode = draft.barcode?.trim()?.takeIf { it.isNotEmpty() }
        val existing = draft.id?.let { dao.byId(it) }
        if (barcode != null) {
            val owner = dao.byBarcode(barcode)
            if (owner != null && owner.id != existing?.id) throw DuplicateBarcodeException()
        }
        val time = now()
        val name = draft.name.trim()
        val brand = draft.brand?.trim()?.takeIf { it.isNotEmpty() }
        val product = ProductEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            brand = brand,
            barcode = barcode,
            kcal100 = requireNotNull(draft.kcal100),
            protein100 = draft.protein100 ?: 0.0,
            fat100 = draft.fat100 ?: 0.0,
            carbs100 = draft.carbs100 ?: 0.0,
            servingGrams = draft.servingGrams?.takeIf { it > 0 },
            pieceGrams = draft.pieceGrams?.takeIf { it > 0 },
            source = draft.source,
            sourceRef = draft.sourceRef,
            searchKey = ProductEntity.searchKeyOf(name, brand),
            createdAt = existing?.createdAt ?: time,
            updatedAt = time,
        )
        dao.upsert(product)
        return product
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** Отмечает продукт как недавно использованный (поднимает в поиске). */
    suspend fun touch(product: ProductEntity) = dao.upsert(product.copy(updatedAt = now()))

    /** Кэширует полный продукт из внешнего источника; повторный выбор переиспользует запись. */
    suspend fun cacheRemote(remote: RemoteFood): ProductEntity {
        require(remote.isComplete)
        val existing = remote.barcode?.let { dao.byBarcode(it) } ?: dao.bySourceRef(remote.sourceRef)
        if (existing != null) return existing.also { touch(it) }
        return save(ProductDraft.of(remote))
    }

    /** Штрихкод: сначала локальный справочник, затем источники по порядку до первого найденного. */
    suspend fun lookupBarcode(barcode: String): BarcodeResult {
        dao.byBarcode(barcode)?.let { return BarcodeResult.Found(it) }
        var firstError: Exception? = null
        for (source in sources.filter { it.isEnabled() }) {
            val found = try {
                source.findByBarcode(barcode)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                null
            } ?: continue
            return if (found.isComplete) {
                BarcodeResult.Found(cacheRemote(found))
            } else {
                BarcodeResult.Incomplete(ProductDraft.of(found).copy(barcode = barcode))
            }
        }
        // Если все источники упали с ошибкой, «не найдено» было бы неправдой.
        firstError?.let { throw it }
        return BarcodeResult.NotFound(barcode)
    }
}
