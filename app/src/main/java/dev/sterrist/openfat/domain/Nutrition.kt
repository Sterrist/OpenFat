package dev.sterrist.openfat.domain

enum class PortionUnit { GRAM, SERVING, PIECE }

/** КБЖУ. Б/Ж/У могут быть неизвестны (быстрый ввод только калорий). */
data class Macros(
    val kcal: Double,
    val protein: Double?,
    val fat: Double?,
    val carbs: Double?,
) {
    operator fun plus(other: Macros) = Macros(
        kcal = kcal + other.kcal,
        protein = (protein ?: 0.0) + (other.protein ?: 0.0),
        fat = (fat ?: 0.0) + (other.fat ?: 0.0),
        carbs = (carbs ?: 0.0) + (other.carbs ?: 0.0),
    )

    companion object {
        val ZERO = Macros(0.0, 0.0, 0.0, 0.0)
    }
}

/** Пищевая ценность на 100 г плюс веса порции и штуки. */
data class Per100(
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val servingGrams: Double?,
    val pieceGrams: Double?,
) {
    fun forGrams(grams: Double): Macros {
        val k = grams / 100.0
        return Macros(kcal * k, protein * k, fat * k, carbs * k)
    }

    fun unitGrams(unit: PortionUnit): Double? = when (unit) {
        PortionUnit.GRAM -> 1.0
        PortionUnit.SERVING -> servingGrams?.takeIf { it > 0 }
        PortionUnit.PIECE -> pieceGrams?.takeIf { it > 0 }
    }

    fun gramsFor(amount: Double, unit: PortionUnit): Double? = unitGrams(unit)?.let { it * amount }

    /** Единица по умолчанию при добавлении: штука, если известен её вес, затем порция, иначе граммы. */
    fun defaultUnit(): PortionUnit = when {
        unitGrams(PortionUnit.PIECE) != null -> PortionUnit.PIECE
        unitGrams(PortionUnit.SERVING) != null -> PortionUnit.SERVING
        else -> PortionUnit.GRAM
    }
}

/** Количество по умолчанию: 100 г или 1 штука/порция. */
fun PortionUnit.defaultAmount(): Double = if (this == PortionUnit.GRAM) 100.0 else 1.0

const val KCAL_PER_G_PROTEIN = 4.0
const val KCAL_PER_G_FAT = 9.0
const val KCAL_PER_G_CARBS = 4.0

/** Дневная норма: калории и доли Б/Ж/У в процентах от калорий. */
data class Norm(
    val kcal: Int,
    val proteinPct: Int,
    val fatPct: Int,
    val carbsPct: Int,
) {
    val proteinGrams: Double get() = kcal * proteinPct / 100.0 / KCAL_PER_G_PROTEIN
    val fatGrams: Double get() = kcal * fatPct / 100.0 / KCAL_PER_G_FAT
    val carbsGrams: Double get() = kcal * carbsPct / 100.0 / KCAL_PER_G_CARBS

    companion object {
        val DEFAULT = Norm(kcal = 2000, proteinPct = 30, fatPct = 30, carbsPct = 40)

        fun isValid(kcal: Int, p: Int, f: Int, c: Int): Boolean =
            kcal in 500..10000 && p in 0..100 && f in 0..100 && c in 0..100 && p + f + c == 100
    }
}

/** Норма, действовавшая в [day]: последняя с датой начала не позже дня, иначе самая ранняя. */
fun <T> pickForDay(day: Long, items: List<T>, effectiveFrom: (T) -> Long): T? =
    items.filter { effectiveFrom(it) <= day }.maxByOrNull(effectiveFrom)
        ?: items.minByOrNull(effectiveFrom)

/** Разбор числа из поля ввода: допускает запятую. */
fun parseDecimal(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
