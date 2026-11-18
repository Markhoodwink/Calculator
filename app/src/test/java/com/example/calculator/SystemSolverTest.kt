package com.example.calculator

import org.junit.Assert.*
import org.junit.Test

class SystemSolverTest {

    private val solver = EquationSystemSolver()

    @Test
    fun testLinearSystem2x2SubstitutionStepsExample1() {
        val result = solver.solve(listOf("2x + 3y = 5", "x - 8y = 10"))
        assertTrue("Result should be UniqueSolution", result is LinearSystemResult.UniqueSolution)
        val unique = result as LinearSystemResult.UniqueSolution

        assertEquals(2, unique.values.size)
        assertEquals(CasEngine.Fraction(70, 19), unique.values[0])
        assertEquals(CasEngine.Fraction(-15, 19), unique.values[1])

        val steps = unique.detailedSteps
        assertTrue("detailedSteps should not be empty", steps.isNotEmpty())

        // Шаг 1: Запись системы в стандартном виде
        val step1 = steps.find { it.badge == "Шаг 1" }
        assertNotNull("Should have Step 1", step1)
        assertEquals("Запись системы в стандартном виде", step1!!.title)
        assertTrue(step1.formula.contains("2x + 3y = 5"))

        // Шаг 2: Выражаем переменную из уравнения
        val step2 = steps.find { it.badge == "Шаг 2" }
        assertNotNull("Should have Step 2", step2)
        assertEquals("Выражаем переменную из уравнения", step2!!.title)
        assertTrue(step2.formula.contains("x = 10 + 8y"))

        // Шаг 3: Подставляем в другое уравнение
        val step3 = steps.find { it.badge == "Шаг 3" }
        assertNotNull("Should have Step 3", step3)
        assertEquals("Подставляем в другое уравнение", step3!!.title)
        assertTrue(step3.formula.contains("y = −15/19") || step3.formula.contains("y = -15/19"))

        // Шаг 4: Находим вторую переменную
        val step4 = steps.find { it.badge == "Шаг 4" }
        assertNotNull("Should have Step 4", step4)
        assertEquals("Находим вторую переменную", step4!!.title)
        assertTrue(step4.formula.contains("70/19"))

        // Ответ
        val stepAnswer = steps.find { it.badge == "Ответ" }
        assertNotNull("Should have Answer step", stepAnswer)

        // Проверка отсутствия матричной / определительной терминологии
        for (step in steps) {
            assertFalse("Steps must not mention matrix", step.title.lowercase().contains("матрица") || step.explanation.lowercase().contains("матрица"))
            assertFalse("Steps must not contain Δ", step.formula.contains("Δ") || step.title.contains("Δ"))
        }
    }

    @Test
    fun testLinearSystem2x2SubstitutionStepsExample2() {
        val result = solver.solve(listOf("5x + 2y = 8", "2y - 3x = 6"))
        assertTrue("Result should be UniqueSolution", result is LinearSystemResult.UniqueSolution)
        val unique = result as LinearSystemResult.UniqueSolution

        assertEquals(2, unique.values.size)
        assertEquals(CasEngine.Fraction(1, 4), unique.values[0])
        assertEquals(CasEngine.Fraction(27, 8), unique.values[1])

        val steps = unique.detailedSteps
        assertTrue("detailedSteps should not be empty", steps.isNotEmpty())

        val step2 = steps.find { it.badge == "Шаг 2" }
        assertNotNull("Should have Step 2", step2)
        assertTrue(step2!!.explanation.contains("Выражаем") || step2.title.contains("Выражаем"))

        val step3 = steps.find { it.badge == "Шаг 3" }
        assertNotNull("Should have Step 3", step3)
        assertTrue(step3!!.explanation.contains("Подставляем") || step3.title.contains("Подставляем"))

        for (step in steps) {
            assertFalse("Steps must not mention matrix", step.formula.contains("матрица") || step.explanation.contains("матрица"))
            assertFalse("Steps must not contain Δ", step.formula.contains("Δ"))
        }
    }

    @Test
    fun testInconsistentSystemDetailedSteps() {
        val result = solver.solve(listOf("x + y = 2", "x + y = 5"))
        assertTrue("Result should be NoSolution", result is LinearSystemResult.NoSolution)

        val steps = result.detailedSteps
        assertTrue("detailedSteps should not be empty for NoSolution", steps.isNotEmpty())

        val analysisStep = steps.find { it.badge == "Анализ" }
        assertNotNull("Should contain an Analysis step for inconsistent system", analysisStep)
        assertTrue(
            "Analysis step explanation must explain why there are no solutions",
            analysisStep!!.explanation.contains("решений нет") || analysisStep.explanation.contains("несовместна")
        )

        for (step in steps) {
            assertFalse("Steps must not mention matrix", step.formula.contains("матрица") || step.explanation.contains("матрица"))
            assertFalse("Steps must not contain Δ", step.formula.contains("Δ"))
        }
    }
}
