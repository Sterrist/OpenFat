package dev.sterrist.openfat.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakTest {
    private val today = 1000L

    @Test
    fun countsConsecutiveDaysEndingToday() {
        val s = computeStreak(setOf(998, 999, 1000), today, manual = null)
        assertEquals(3, s.days)
        assertTrue(s.loggedToday)
    }

    @Test
    fun emptyTodayDoesNotBreakStreakButIsNotCounted() {
        val s = computeStreak(setOf(997, 998, 999), today, manual = null)
        assertEquals(3, s.days)
        assertFalse(s.loggedToday)
    }

    @Test
    fun emptyDayInThePastBreaksStreak() {
        assertEquals(2, computeStreak(setOf(995, 996, 999, 1000), today, manual = null).days)
        assertEquals(0, computeStreak(setOf(990), today, manual = null).days)
    }

    @Test
    fun manualDaysContinueIntoAppData() {
        // Пользователь сказал «уже 10 дней» до вчерашнего дня включительно.
        val manual = ManualStreak(days = 10, anchorDay = 999)
        assertEquals(10, computeStreak(emptySet(), today, manual).days)
        assertEquals(11, computeStreak(setOf(1000), today, manual).days)
    }

    @Test
    fun manualStreakBreaksIfDayAfterAnchorIsEmpty() {
        val manual = ManualStreak(days = 10, anchorDay = 995)
        // 996 пропущен, 997..1000 записаны — ручные дни уже не примыкают.
        assertEquals(4, computeStreak(setOf(997, 998, 999, 1000), today, manual).days)
        // Все дни после якоря записаны — серия продолжается.
        assertEquals(15, computeStreak(setOf(996, 997, 998, 999, 1000), today, manual).days)
    }

    @Test
    fun appDataBeforeManualRangeExtendsStreak() {
        val manual = ManualStreak(days = 3, anchorDay = 999) // 997..999
        assertEquals(5, computeStreak(setOf(995, 996), today, manual).days)
    }

    @Test
    fun futureEntriesAreIgnored() {
        assertEquals(1, computeStreak(setOf(1000, 1001), today, manual = null).days)
    }
}
