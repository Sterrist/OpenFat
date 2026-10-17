package dev.sterrist.openfat.ui.weight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.sterrist.openfat.domain.WeightPoint
import dev.sterrist.openfat.ui.common.formatNumber
import dev.sterrist.openfat.ui.common.formatShortDate

/**
 * График веса: точки — отдельные взвешивания, линия — тренд (скользящее среднее за 7 дней),
 * пунктир — целевой вес. Точные значения доступны в списке под графиком.
 */
@Composable
fun WeightChart(
    points: List<WeightPoint>,
    trend: List<WeightPoint>,
    target: Double?,
    fromDay: Long,
    toDay: Long,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val measurer: TextMeasurer = rememberTextMeasurer()

    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics { contentDescription = description },
    ) {
        val values = points.map { it.kg } + trend.map { it.kg } + listOfNotNull(target)
        if (values.isEmpty()) return@Canvas
        var minY = values.min()
        var maxY = values.max()
        if (maxY - minY < 1.0) {
            val mid = (maxY + minY) / 2
            minY = mid - 0.5
            maxY = mid + 0.5
        }
        val pad = (maxY - minY) * 0.1
        minY -= pad
        maxY += pad

        val left = 44.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val top = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val spanDays = (toDay - fromDay).coerceAtLeast(1)
        fun x(day: Long) = left + (right - left) * (day - fromDay).toFloat() / spanDays
        fun y(kg: Double) = (bottom - (bottom - top) * ((kg - minY) / (maxY - minY))).toFloat()

        // Сетка и подписи по оси Y
        val gridSteps = 4
        for (i in 0..gridSteps) {
            val kg = minY + (maxY - minY) * i / gridSteps
            val yy = y(kg)
            drawLine(colors.outlineVariant, Offset(left, yy), Offset(right, yy), strokeWidth = 1.dp.toPx())
            val text = measurer.measure(formatNumber(kg), labelStyle)
            drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), yy - text.size.height / 2))
        }
        // Подписи дат
        listOf(fromDay, toDay).forEachIndexed { i, day ->
            val text = measurer.measure(formatShortDate(day), labelStyle)
            val tx = if (i == 0) left else right - text.size.width
            drawText(text, topLeft = Offset(tx, bottom + 4.dp.toPx()))
        }

        target?.let { t ->
            val ty = y(t)
            drawLine(
                colors.secondary,
                Offset(left, ty),
                Offset(right, ty),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
            )
        }

        points.forEach { p ->
            drawCircle(colors.onSurfaceVariant, radius = 3.dp.toPx(), center = Offset(x(p.day), y(p.kg)))
        }

        if (trend.size >= 2) {
            val path = Path()
            trend.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(x(p.day), y(p.kg)) else path.lineTo(x(p.day), y(p.kg))
            }
            drawPath(path, colors.primary, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        } else if (trend.size == 1) {
            drawCircle(colors.primary, radius = 4.dp.toPx(), center = Offset(x(trend[0].day), y(trend[0].kg)))
        }
    }
}
