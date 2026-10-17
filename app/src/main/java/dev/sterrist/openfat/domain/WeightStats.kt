package dev.sterrist.openfat.domain

data class WeightPoint(val day: Long, val kg: Double)

/** Скользящее среднее по календарному окну [windowDays] дней (включая текущий). Вход отсортирован по дню. */
fun movingAverage(points: List<WeightPoint>, windowDays: Int = 7): List<WeightPoint> {
    val result = ArrayList<WeightPoint>(points.size)
    var start = 0
    var sum = 0.0
    for (end in points.indices) {
        sum += points[end].kg
        while (points[start].day <= points[end].day - windowDays) {
            sum -= points[start].kg
            start++
        }
        result += WeightPoint(points[end].day, sum / (end - start + 1))
    }
    return result
}
