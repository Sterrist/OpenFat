package dev.sterrist.openfat.domain

/** Серия дней подряд с записями еды. [loggedToday] — есть ли записи сегодня (цвет огонька). */
data class Streak(val days: Int, val loggedToday: Boolean)

/**
 * Дни, которые пользователь указал вручную: [days] дней подряд, последний из них — [anchorDay].
 * Нужны, чтобы серия не начиналась с нуля, если учёт вёлся до установки приложения.
 */
data class ManualStreak(val days: Int, val anchorDay: Long) {
    fun covers(day: Long): Boolean = days > 0 && day <= anchorDay && day > anchorDay - days
}

/**
 * Серия считается назад от сегодняшнего дня, если сегодня есть записи, иначе от вчерашнего:
 * пустой сегодняшний день серию не прерывает, но и не засчитывается.
 * День засчитывается, если в нём есть записи или он входит в указанные вручную дни.
 */
fun computeStreak(loggedDays: Set<Long>, today: Long, manual: ManualStreak?): Streak {
    fun counted(day: Long) = day in loggedDays || manual?.covers(day) == true
    val loggedToday = today in loggedDays
    var day = if (loggedToday) today else today - 1
    var count = 0
    while (counted(day)) {
        count++
        day--
    }
    return Streak(count, loggedToday)
}
