package dev.sterrist.openfat.domain

import dev.sterrist.openfat.domain.MedicationSchedule.HOUR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MedicationScheduleTest {
    private val due = 1_000_000_000_000L
    private val minute = 60_000L

    @Test
    fun intervalsGrowByAnHourUntilADay() {
        // Напоминания: в срок, затем через 1, 2, 3… часа.
        assertEquals(listOf(0L, 1, 3, 6, 10, 15), (0..5).map { MedicationSchedule.offsetHours(it) })
        val steps = (1..30).map { MedicationSchedule.offsetHours(it) - MedicationSchedule.offsetHours(it - 1) }
        assertEquals((1L..24L).toList() + List(6) { 24L }, steps)
    }

    @Test
    fun nextAndLastReminder() {
        assertEquals(due, MedicationSchedule.nextReminderAt(due, due - minute))
        assertNull(MedicationSchedule.lastReminderAt(due, due - minute))

        assertEquals(due, MedicationSchedule.lastReminderAt(due, due))
        assertEquals(due + HOUR, MedicationSchedule.nextReminderAt(due, due))

        val afterTwoHours = due + 2 * HOUR
        assertEquals(due + HOUR, MedicationSchedule.lastReminderAt(due, afterTwoHours))
        assertEquals(due + 3 * HOUR, MedicationSchedule.nextReminderAt(due, afterTwoHours))

        // После выхода на суточный шаг: 300 ч, 324 ч, 348 ч…
        val late = due + 330 * HOUR
        assertEquals(due + 324 * HOUR, MedicationSchedule.lastReminderAt(due, late))
        assertEquals(due + 348 * HOUR, MedicationSchedule.nextReminderAt(due, late))
    }

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    @Test
    fun dailyRepeatOccursEveryDay() {
        assertTrue((0L..30L).all { Repeat.DAILY.occursOn(it) })
    }

    @Test
    fun everyNDaysCountsFromAnchor() {
        val anchor = day(2026, 10, 1)
        val repeat = Repeat(Repeat.UNIT_DAY, 3, anchor)
        assertFalse(repeat.occursOn(anchor - 3))
        assertEquals(listOf(0L, 3, 6, 9), (0L..9L).filter { repeat.occursOn(anchor + it) })
    }

    @Test
    fun weeklyKeepsWeekdayOfAnchor() {
        val anchor = day(2026, 10, 21) // среда
        val weekly = Repeat(Repeat.UNIT_WEEK, 1, anchor)
        assertEquals(listOf(0L, 7, 14), (0L..14L).filter { weekly.occursOn(anchor + it) })
        val biweekly = Repeat(Repeat.UNIT_WEEK, 2, anchor)
        assertEquals(listOf(0L, 14, 28), (0L..28L).filter { biweekly.occursOn(anchor + it) })
    }

    @Test
    fun monthlyKeepsDayOfMonthAndClampsToMonthEnd() {
        val monthly = Repeat(Repeat.UNIT_MONTH, 1, day(2026, 1, 31))
        assertTrue(monthly.occursOn(day(2026, 1, 31)))
        assertTrue(monthly.occursOn(day(2026, 2, 28)))
        assertFalse(monthly.occursOn(day(2026, 2, 27)))
        assertTrue(monthly.occursOn(day(2026, 3, 31)))
        assertTrue(monthly.occursOn(day(2028, 2, 29)))

        val quarterly = Repeat(Repeat.UNIT_MONTH, 3, day(2026, 10, 15))
        assertTrue(quarterly.occursOn(day(2027, 1, 15)))
        assertFalse(quarterly.occursOn(day(2026, 11, 15)))
        assertFalse(quarterly.occursOn(day(2026, 9, 15)))
    }

    @Test
    fun nextOccurrenceFindsFarAwayDose() {
        val anchor = day(2026, 10, 24)
        assertEquals(anchor + 7, Repeat(Repeat.UNIT_WEEK, 1, anchor).nextOccurrence(anchor + 2))
        assertEquals(day(2026, 11, 24), Repeat(Repeat.UNIT_MONTH, 1, anchor).nextOccurrence(anchor + 2))
        assertEquals(anchor + 40, Repeat(Repeat.UNIT_DAY, 1, anchor + 40).nextOccurrence(anchor))
        assertEquals(anchor + 2, Repeat.DAILY.nextOccurrence(anchor + 2))
    }

    @Test
    fun dosesFollowRepeat() {
        val zone = ZoneId.of("Europe/Moscow")
        val anchor = day(2026, 10, 21)
        val doses = MedicationSchedule.doses("m", listOf(9 * 60), 0, anchor, anchor + 14, zone, Repeat(Repeat.UNIT_WEEK, 1, anchor))
        assertEquals(listOf(anchor, anchor + 7, anchor + 14), doses.map { it.date })
    }

    @Test
    fun dosesSkipTimesBeforeScheduleStart() {
        val zone = ZoneId.of("Europe/Moscow")
        val day = LocalDate.of(2026, 10, 23).toEpochDay()
        val startAt = MedicationSchedule.dueAt(day, 12 * 60, zone)
        val doses = MedicationSchedule.doses("m", listOf(8 * 60, 20 * 60), startAt, day, day + 1, zone)
        assertEquals(listOf(day to 20 * 60, day + 1 to 8 * 60, day + 1 to 20 * 60), doses.map { it.date to it.time })
    }
}
