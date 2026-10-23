package dev.sterrist.openfat.domain

import dev.sterrist.openfat.domain.MedicationSchedule.HOUR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun dosesSkipTimesBeforeScheduleStart() {
        val zone = ZoneId.of("Europe/Moscow")
        val day = LocalDate.of(2026, 10, 23).toEpochDay()
        val startAt = MedicationSchedule.dueAt(day, 12 * 60, zone)
        val doses = MedicationSchedule.doses("m", listOf(8 * 60, 20 * 60), startAt, day, day + 1, zone)
        assertEquals(listOf(day to 20 * 60, day + 1 to 8 * 60, day + 1 to 20 * 60), doses.map { it.date to it.time })
    }
}
