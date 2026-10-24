package dev.sterrist.openfat.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Плановый приём препарата [medicationId] в день [date] (epochDay) в [time] (минуты от полуночи). */
data class Dose(val medicationId: String, val date: Long, val time: Int, val dueAt: Long)

/**
 * Как часто принимать препарат: каждые [every] дней, недель или месяцев, считая от дня [anchorDate] (epochDay).
 * Неделя повторяется в тот же день недели, что и [anchorDate], месяц — в то же число (в коротких месяцах — в последний день).
 */
data class Repeat(val unit: Int = UNIT_DAY, val every: Int = 1, val anchorDate: Long = 0) {
    /** Приём назначен на день [day] (epochDay). */
    fun occursOn(day: Long): Boolean {
        if (day < anchorDate) return false
        val n = every.coerceAtLeast(1)
        return when (unit) {
            UNIT_WEEK -> (day - anchorDate) % (7L * n) == 0L
            UNIT_MONTH -> {
                val anchor = LocalDate.ofEpochDay(anchorDate)
                val date = LocalDate.ofEpochDay(day)
                val months = (date.year - anchor.year) * 12L + (date.monthValue - anchor.monthValue)
                months % n == 0L && date.dayOfMonth == minOf(anchor.dayOfMonth, date.lengthOfMonth())
            }
            else -> (day - anchorDate) % n == 0L
        }
    }

    /** Ближайший день приёма не раньше [fromDay] (epochDay) или null, если он дальше [MAX_SPAN_DAYS]. */
    fun nextOccurrence(fromDay: Long): Long? = (fromDay..fromDay + MAX_SPAN_DAYS).firstOrNull { occursOn(it) }

    companion object {
        const val UNIT_DAY = 0
        const val UNIT_WEEK = 1
        const val UNIT_MONTH = 2
        const val MAX_EVERY = 99

        /** Самый длинный промежуток между приёмами: [MAX_EVERY] месяцев с запасом. */
        const val MAX_SPAN_DAYS = 31L * MAX_EVERY + 31

        val DAILY = Repeat()
    }
}

/** Расписание приёмов и нарастающих напоминаний. Чистые функции без Android — покрыты тестами. */
object MedicationSchedule {
    const val HOUR = 60 * 60 * 1000L

    /** Сколько дней назад ещё напоминаем о неотмеченном приёме: хватает, чтобы шаг дошёл до суток. */
    const val LOOKBACK_DAYS = 14

    /** Предельный шаг между напоминаниями, часов. */
    const val MAX_STEP_HOURS = 24

    /**
     * Смещение k-го напоминания от времени приёма, часов: 0, 1, 3, 6, 10… —
     * каждый следующий интервал на час длиннее предыдущего, пока не дойдёт до суток.
     */
    fun offsetHours(k: Int): Long {
        val capped = minOf(k, MAX_STEP_HOURS).toLong()
        return capped * (capped + 1) / 2 + maxOf(0, k - MAX_STEP_HOURS).toLong() * MAX_STEP_HOURS
    }

    /** Последнее напоминание о приёме со временем [dueAt], наступившее к [now], или null, если приём ещё впереди. */
    fun lastReminderAt(dueAt: Long, now: Long): Long? {
        if (now < dueAt) return null
        return dueAt + offsetHours(indexAtOrBefore(now - dueAt)) * HOUR
    }

    /** Ближайшее напоминание строго после [now]. */
    fun nextReminderAt(dueAt: Long, now: Long): Long {
        if (now < dueAt) return dueAt
        return dueAt + offsetHours(indexAtOrBefore(now - dueAt) + 1) * HOUR
    }

    /** Наибольший k, для которого offsetHours(k) часов ≤ [elapsed]. */
    private fun indexAtOrBefore(elapsed: Long): Int {
        val hours = elapsed / HOUR
        val rampEnd = offsetHours(MAX_STEP_HOURS)
        if (hours >= rampEnd) return MAX_STEP_HOURS + ((hours - rampEnd) / MAX_STEP_HOURS).toInt()
        var k = 0
        while (offsetHours(k + 1) <= hours) k++
        return k
    }

    fun dueAt(date: Long, time: Int, zone: ZoneId): Long =
        LocalDate.ofEpochDay(date).atTime(LocalTime.of(time / 60, time % 60)).atZone(zone).toInstant().toEpochMilli()

    /**
     * Приёмы активного препарата за дни [fromDay]..[toDay], не раньше начала расписания [startAt].
     * Время считается в локальной зоне: при переходе на летнее время несуществующее время сдвигается вперёд.
     */
    fun doses(
        medicationId: String,
        times: List<Int>,
        startAt: Long,
        fromDay: Long,
        toDay: Long,
        zone: ZoneId,
        repeat: Repeat = Repeat.DAILY,
    ): List<Dose> = buildList {
        for (day in fromDay..toDay) {
            if (!repeat.occursOn(day)) continue
            for (time in times) {
                val due = dueAt(day, time, zone)
                if (due >= startAt) add(Dose(medicationId, day, time, due))
            }
        }
    }
}
