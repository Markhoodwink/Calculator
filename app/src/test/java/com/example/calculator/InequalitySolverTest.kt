package com.example.calculator

import org.junit.Assert.*
import org.junit.Test

class InequalitySolverTest {

    private val casEngine = CasEngine().apply { isDegreeMode = false }

    @Test
    fun test1RationalInequality() {
        // (x - 2) / (x + 1) <= 0 => x ∈ (-1; 2]
        val result = InequalitySolverEngine.solve("(x - 2) / (x + 1) <= 0", casEngine)
        assertNull(result.errorMessage)
        assertEquals("x ∈ (-1; 2]", result.answerFormula)
        assertTrue(result.detailedSteps.isNotEmpty())
    }

    @Test
    fun test2ExponentialEge508573ExactLogLabel() {
        // 3^x + 10 * 3^(3 - x) >= 37 => x ∈ (-∞; log₃(10)] ∪ [3; +∞)
        val result = InequalitySolverEngine.solve("3^x + 10 * 3^(3 - x) >= 37", casEngine)
        assertNull(result.errorMessage)
        assertEquals("x ∈ (-∞; log₃(10)] ∪ [3; +∞)", result.answerFormula)
    }

    @Test
    fun test3RationalExponentialEge() {
        // 2^x / (2^x - 8) + (2^x + 8) / (2^x - 4) + 66 / (4^x - 12 * 2^x + 32) <= 0 => x ∈ {0} ∪ (2; 3)
        val result = InequalitySolverEngine.solve(
            "2^x / (2^x - 8) + (2^x + 8) / (2^x - 4) + 66 / (4^x - 12 * 2^x + 32) <= 0",
            casEngine
        )
        assertNull(result.errorMessage)
        assertEquals("x ∈ {0} ∪ (2; 3)", result.answerFormula)
        assertEquals(listOf("0", "2", "3"), result.criticalPoints.map { it.label })

        val stepsText = result.detailedSteps.joinToString("\n") { "${it.formula} ${it.explanation}" }
        val odzText = result.odzConstraints.joinToString("\n")
        assertFalse("Should not contain 122^x", stepsText.contains("122^x") || odzText.contains("122^x"))
        assertFalse("Should not contain - 0 ≤ 0", stepsText.contains("- 0 ≤ 0"))
        assertFalse("Should not contain typo множества", stepsText.contains("множества"))
    }

    @Test
    fun test4VariableBaseLogShorthandAndSolve() {
        val norm1 = InequalitySolverEngine.normalizeInput("log_x(x^3 - 8)")
        assertEquals("log(x, x^3 - 8)", norm1)

        val norm2 = InequalitySolverEngine.normalizeInput("log_{0.5}^2(x + 1)")
        assertEquals("(log(0.5, x + 1))^2", norm2)

        val norm3 = InequalitySolverEngine.normalizeInput("|x^2 + 2x - 3| - |x^2 + 3x + 5|")
        assertEquals("abs(x^2 + 2x - 3) - abs(x^2 + 3x + 5)", norm3)

        val result = InequalitySolverEngine.solve("log_x(x^3 + 2x - 13) <= log_x(x^3 - 8)", casEngine)
        assertNull(result.errorMessage)
        assertTrue("Answer should contain 5/2: ${result.answerFormula}", result.answerFormula.contains("5/2]"))
    }

    @Test
    fun test5QuadraticIrrationalRoots() {
        // x^2 - x - 3 <= 0 => x ∈ [(1 - √13)/2; (1 + √13)/2]
        val result = InequalitySolverEngine.solve("x^2 - x - 3 <= 0", casEngine)
        assertNull(result.errorMessage)
        assertTrue("Formula should contain (1 - √13)/2: ${result.answerFormula}", result.answerFormula.contains("(1 - √13)/2"))
        assertTrue("Formula should contain (1 + √13)/2: ${result.answerFormula}", result.answerFormula.contains("(1 + √13)/2"))
        assertEquals("x ∈ [(1 - √13)/2; (1 + √13)/2]", result.answerFormula)
    }

    @Test
    fun test6VariableBaseLogEge562493() {
        // log_(2x)(3x - 1) * log_(3x)(2x - 1) / (2^x - 4) >= 0 => x ∈ [2/3; 1] ∪ (2; +∞)
        val result = InequalitySolverEngine.solve(
            "log_(2x)(3x - 1) * log_(3x)(2x - 1) / (2^x - 4) >= 0",
            casEngine
        )
        assertNull(result.errorMessage)
        assertEquals("x ∈ [2/3; 1] ∪ (2; +∞)", result.answerFormula)
    }

    @Test
    fun test7PeriodicTrigLogEge526809() {
        // 4 * log_4^2(sin^3(x)) + 8 * log_2(sin(x)) >= 1
        val result = InequalitySolverEngine.solve(
            "4 * log_4^2(sin^3(x)) + 8 * log_2(sin(x)) >= 1",
            casEngine
        )
        assertNull(result.errorMessage)
        assertTrue("Should be periodic: ${result.isPeriodic}", result.isPeriodic)
        assertTrue("Formula should contain π/6 + 2πk: ${result.answerFormula}", result.answerFormula.contains("π/6 + 2πk"))
        assertTrue("Formula should contain 5π/6 + 2πk: ${result.answerFormula}", result.answerFormula.contains("5π/6 + 2πk"))
        assertTrue("Formula should contain k ∈ ℤ: ${result.answerFormula}", result.answerFormula.contains("k ∈ ℤ"))
        assertEquals("x ∈ (2πk; π/6 + 2πk] ∪ [5π/6 + 2πk; π + 2πk), k ∈ ℤ", result.answerFormula)
    }

    @Test
    fun test8SampleTestPointsInIntervals() {
        val result = InequalitySolverEngine.solve("(x - 2) / (x + 1) <= 0", casEngine)
        assertNull(result.errorMessage)

        val validSegs = result.intervals.filter { it.isInDomain }
        assertTrue("Should have valid segments", validSegs.isNotEmpty())
        for (seg in validSegs) {
            assertTrue("sampleLabel should not be empty", seg.sampleLabel.isNotEmpty())
            assertTrue("sampleValueLabel should not be empty", seg.sampleValueLabel.isNotEmpty())
        }
    }

    @Test
    fun test9ModulusParsingAndSolve() {
        assertEquals("6*abs(x)", InequalitySolverEngine.normalizeInput("6|x|"))
        assertEquals("abs(x)*abs(x - 1)", InequalitySolverEngine.normalizeInput("|x||x - 1|"))
        assertEquals("abs(1 - 2*abs(x))", InequalitySolverEngine.normalizeInput("|1 - 2|x||"))

        val result = InequalitySolverEngine.solve("(3/(x^2-6|x|+9))-(4/(|x|-3))≥-1", casEngine)
        assertNull(result.errorMessage)
        assertEquals("x ∈ (-∞; -6] ∪ [-4; -3) ∪ (-3; 3) ∪ (3; 4] ∪ [6; +∞)", result.answerFormula)
        assertEquals(listOf("-6", "-4", "-3", "3", "4", "6"), result.criticalPoints.map { it.label })
    }
}
