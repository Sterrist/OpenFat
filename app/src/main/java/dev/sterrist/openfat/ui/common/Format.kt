package dev.sterrist.openfat.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.sterrist.openfat.R
import dev.sterrist.openfat.domain.PortionUnit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val ru: Locale = Locale.forLanguageTag("ru")
private val dayFormat = DateTimeFormatter.ofPattern("EE, d MMMM", ru)
private val dayYearFormat = DateTimeFormatter.ofPattern("EE, d MMMM yyyy", ru)
private val shortDateFormat = DateTimeFormatter.ofPattern("d MMM", ru)

fun today(): Long = LocalDate.now().toEpochDay()

fun formatKcal(value: Double): String = value.roundToInt().toString()

/** Число с одним знаком после запятой; целые — без дробной части. */
fun formatNumber(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
    else String.format(ru, "%.1f", rounded)
}

fun formatGrams(value: Double?): String = value?.let { formatNumber(it) } ?: "—"

fun formatShortDate(day: Long): String = LocalDate.ofEpochDay(day).format(shortDateFormat)

fun formatTime(minutes: Int): String = String.format(ru, "%02d:%02d", minutes / 60, minutes % 60)

fun formatSigned(value: Double): String {
    val text = formatNumber(abs(value))
    return when {
        value > 0.05 -> "+$text"
        value < -0.05 -> "−$text"
        else -> text
    }
}

@Composable
fun dayTitle(day: Long): String {
    val t = today()
    return when (day) {
        t -> stringResource(R.string.today)
        t - 1 -> stringResource(R.string.yesterday)
        t + 1 -> stringResource(R.string.tomorrow)
        else -> {
            val date = LocalDate.ofEpochDay(day)
            val text = if (date.year == LocalDate.now().year) date.format(dayFormat) else date.format(dayYearFormat)
            text.replaceFirstChar { it.titlecase(ru) }
        }
    }
}

@Composable
fun unitLabel(unit: PortionUnit): String = stringResource(
    when (unit) {
        PortionUnit.GRAM -> R.string.unit_gram
        PortionUnit.SERVING -> R.string.unit_serving
        PortionUnit.PIECE -> R.string.unit_piece
    },
)

@Composable
fun amountLabel(amount: Double?, unit: PortionUnit?, grams: Double?): String? {
    if (amount == null || unit == null) return null
    return when (unit) {
        PortionUnit.GRAM -> stringResource(R.string.amount_grams, formatNumber(amount))
        else -> stringResource(R.string.amount_with_grams, formatNumber(amount), unitLabel(unit), formatGrams(grams))
    }
}

@Composable
fun macrosLine(protein: Double?, fat: Double?, carbs: Double?): String? {
    if (protein == null && fat == null && carbs == null) return null
    return stringResource(R.string.macros_line, formatGrams(protein), formatGrams(fat), formatGrams(carbs))
}
