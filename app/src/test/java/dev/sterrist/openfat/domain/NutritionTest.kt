package dev.sterrist.openfat.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionTest {
    private val buckwheat = Per100(kcal = 340.0, protein = 13.0, fat = 3.0, carbs = 66.0, servingGrams = 60.0, pieceGrams = null)

    @Test
    fun gramsScaleLinearly() {
        val m = buckwheat.forGrams(150.0)
        assertEquals(510.0, m.kcal, 1e-9)
        assertEquals(19.5, m.protein!!, 1e-9)
        assertEquals(4.5, m.fat!!, 1e-9)
        assertEquals(99.0, m.carbs!!, 1e-9)
    }

    @Test
    fun servingAndPieceUnits() {
        assertEquals(120.0, buckwheat.gramsFor(2.0, PortionUnit.SERVING)!!, 1e-9)
        assertNull("нет веса штуки — единица недоступна", buckwheat.gramsFor(1.0, PortionUnit.PIECE))
        assertEquals(35.0, buckwheat.gramsFor(35.0, PortionUnit.GRAM)!!, 1e-9)
    }

    @Test
    fun normConvertsPercentToGrams() {
        val n = Norm(kcal = 2000, proteinPct = 30, fatPct = 30, carbsPct = 40)
        assertEquals(150.0, n.proteinGrams, 1e-9) // 600 ккал / 4
        assertEquals(66.666, n.fatGrams, 1e-3) // 600 ккал / 9
        assertEquals(200.0, n.carbsGrams, 1e-9) // 800 ккал / 4
    }

    @Test
    fun normValidation() {
        assertTrue(Norm.isValid(1800, 25, 25, 50))
        assertFalse(Norm.isValid(1800, 30, 30, 30))
        assertFalse(Norm.isValid(100, 30, 30, 40))
    }

    @Test
    fun quickEntryMacrosSumTreatsUnknownAsZero() {
        val sum = Macros(100.0, null, null, null) + Macros(200.0, 10.0, 5.0, 20.0)
        assertEquals(300.0, sum.kcal, 1e-9)
        assertEquals(10.0, sum.protein!!, 1e-9)
    }

    @Test
    fun pickForDayUsesNormInEffectThatDay() {
        data class N(val from: Long, val kcal: Int)
        val norms = listOf(N(0, 2000), N(100, 1800))
        assertEquals(2000, pickForDay(99, norms) { it.from }!!.kcal)
        assertEquals(1800, pickForDay(100, norms) { it.from }!!.kcal)
        assertEquals(1800, pickForDay(500, norms) { it.from }!!.kcal)
        // До первой нормы — самая ранняя.
        assertEquals(1800, pickForDay(5, listOf(N(100, 1800))) { it.from }!!.kcal)
    }

    @Test
    fun parseDecimalAcceptsComma() {
        assertEquals(72.5, parseDecimal("72,5")!!, 1e-9)
        assertEquals(72.5, parseDecimal(" 72.5 ")!!, 1e-9)
        assertNull(parseDecimal("abc"))
        assertNull(parseDecimal(""))
    }

    @Test
    fun movingAverageUsesCalendarWindow() {
        val points = listOf(
            WeightPoint(0, 80.0),
            WeightPoint(1, 82.0),
            WeightPoint(6, 81.0),
            WeightPoint(7, 79.0), // день 0 выпадает из окна 7 дней
        )
        val avg = movingAverage(points, windowDays = 7)
        assertEquals(80.0, avg[0].kg, 1e-9)
        assertEquals(81.0, avg[1].kg, 1e-9)
        assertEquals(81.0, avg[2].kg, 1e-9)
        assertEquals((82.0 + 81.0 + 79.0) / 3, avg[3].kg, 1e-9)
    }

    @Test
    fun defaultUnitPrefersPieceThenServing() {
        assertEquals(PortionUnit.SERVING, buckwheat.defaultUnit())
        assertEquals(PortionUnit.PIECE, buckwheat.copy(pieceGrams = 50.0).defaultUnit())
        assertEquals(PortionUnit.GRAM, buckwheat.copy(servingGrams = null).defaultUnit())
        assertEquals(PortionUnit.GRAM, buckwheat.copy(servingGrams = 0.0).defaultUnit())
        assertEquals(1.0, PortionUnit.PIECE.defaultAmount(), 0.0)
        assertEquals(100.0, PortionUnit.GRAM.defaultAmount(), 0.0)
    }
}
