package com.example.calculator

import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun testSmallNumberFraction() {
        val frac = CasEngine.Fraction.fromDecimalString("0.000000005")
        assertEquals(1L, frac.num)
        assertEquals(200000000L, frac.den)

        val fracD = CasEngine.Fraction.fromDouble(5e-9)
        assertEquals(1L, fracD.num)
        assertEquals(200000000L, fracD.den)
    }

    @Test
    fun testCasioNorm1Formatting() {
        assertEquals("5 · 10⁻⁹", CasEngine.Fraction.formatDoubleNorm1(5e-9))
        assertEquals("-5 · 10⁻⁹", CasEngine.Fraction.formatDoubleNorm1(-5e-9))
        assertEquals("0", CasEngine.Fraction.formatDoubleNorm1(0.0))
        assertEquals("0", CasEngine.Fraction.formatDoubleNorm1(1e-16))
        assertEquals("0.5", CasEngine.Fraction.formatDoubleNorm1(0.5))
        assertEquals("0.001", CasEngine.Fraction.formatDoubleNorm1(0.001))
        assertEquals("9.99 · 10⁻⁴", CasEngine.Fraction.formatDoubleNorm1(0.000999))
        assertEquals("1.2 · 10¹⁰", CasEngine.Fraction.formatDoubleNorm1(1.2e10))
    }

    @Test
    fun testFractionToPlainDecimalString() {
        val f1 = CasEngine.Fraction(1L, 200000000L)
        assertEquals("0.000000005", CasEngine.Fraction.fractionToPlainDecimalString(f1))

        val f2 = CasEngine.Fraction(5L, 2L)
        assertEquals("2.5", CasEngine.Fraction.fractionToPlainDecimalString(f2))
    }

    @Test
    fun testSingleNumberLiteralPreservation() {
        val engine = CasEngine()
        val res = engine.evaluateFull("0.000000005")
        assertEquals("0.000000005", res.displayFormula)
        assertEquals(CasEngine.Fraction(1L, 200000000L), res.exactFraction)
    }

    @Test
    fun testCalculationWithSmallNumbers() {
        val engine = CasEngine()
        val res = engine.evaluateFull("0.000000001 + 0.000000004")
        assertEquals("5 · 10⁻⁹", res.displayFormula)
        assertEquals(CasEngine.Fraction(1L, 200000000L), res.exactFraction)
    }

    @Test
    fun testDivisionToFraction() {
        val engine = CasEngine()
        val res = engine.evaluateFull("1 / 200000000")
        assertEquals("1/200000000", res.displayFormula)
        assertEquals(CasEngine.Fraction(1L, 200000000L), res.exactFraction)
    }

    @Test
    fun testAnsWithScientificNotation() {
        val engine = CasEngine()
        engine.lastAnswer = "5 · 10⁻⁹"
        val res = engine.evaluateFull("Ans * 2")
        assertEquals("1 · 10⁻⁸", res.displayFormula)
        assertEquals(CasEngine.Fraction(1L, 100000000L), res.exactFraction)
    }

    @Test
    fun testParseCellToFraction() {
        val engine = CasEngine()
        assertEquals(CasEngine.Fraction(0L), engine.parseCellToFraction(""))
        assertEquals(CasEngine.Fraction(0L), engine.parseCellToFraction("   "))
        assertEquals(CasEngine.Fraction(-3L, 1L), engine.parseCellToFraction("-3"))
        assertEquals(CasEngine.Fraction(1L, 2L), engine.parseCellToFraction("0.5"))
        assertEquals(CasEngine.Fraction(-5L, 4L), engine.parseCellToFraction("-1.25"))
        assertEquals(CasEngine.Fraction(1L, 3L), engine.parseCellToFraction("1/3"))
        assertEquals(CasEngine.Fraction(-5L, 2L), engine.parseCellToFraction("-5/2"))
        assertEquals(CasEngine.Fraction(5L, 1L), engine.parseCellToFraction("2 + 3"))
    }

    @Test
    fun testLinearSystem2x2UniqueFractionSolution() {
        val engine = CasEngine()
        // 3x + 2y = 1
        // 4x - y = 2
        // => x = 5/11, y = -2/11
        val rows = listOf(
            listOf("3", "2", "1"),
            listOf("4", "-1", "2")
        )
        val res = engine.solveLinearSystem(rows)
        assertTrue(res is LinearSystemResult.UniqueSolution)
        val unique = res as LinearSystemResult.UniqueSolution
        assertEquals(2, unique.values.size)
        assertEquals(CasEngine.Fraction(5L, 11L), unique.values[0])
        assertEquals(CasEngine.Fraction(-2L, 11L), unique.values[1])

        assertTrue(unique.formattedLines[0].startsWith("x = 5/11"))
        assertTrue(unique.formattedLines[1].startsWith("y = -2/11"))
    }

    @Test
    fun testLinearSystem3x3UniqueIntegerSolution() {
        val engine = CasEngine()
        // x + y + z = 6
        // 2x - y + z = 3
        // x + 2y - z = 2
        // => x = 1, y = 2, z = 3
        val rows = listOf(
            listOf("1", "1", "1", "6"),
            listOf("2", "-1", "1", "3"),
            listOf("1", "2", "-1", "2")
        )
        val res = engine.solveLinearSystem(rows)
        assertTrue(res is LinearSystemResult.UniqueSolution)
        val unique = res as LinearSystemResult.UniqueSolution
        assertEquals(3, unique.values.size)
        assertEquals(CasEngine.Fraction(1L, 1L), unique.values[0])
        assertEquals(CasEngine.Fraction(2L, 1L), unique.values[1])
        assertEquals(CasEngine.Fraction(3L, 1L), unique.values[2])

        assertEquals("x = 1", unique.formattedLines[0])
        assertEquals("y = 2", unique.formattedLines[1])
        assertEquals("z = 3", unique.formattedLines[2])
    }

    @Test
    fun testLinearSystemNoSolution() {
        val engine = CasEngine()
        // x + y = 2
        // 2x + 2y = 5
        val rows = listOf(
            listOf("1", "1", "2"),
            listOf("2", "2", "5")
        )
        val res = engine.solveLinearSystem(rows)
        assertEquals(LinearSystemResult.NoSolution(), res)
    }

    @Test
    fun testLinearSystemInfiniteSolutions() {
        val engine = CasEngine()
        // x + y = 2
        // 2x + 2y = 4
        val rows = listOf(
            listOf("1", "1", "2"),
            listOf("2", "2", "4")
        )
        val res = engine.solveLinearSystem(rows)
        assertEquals(LinearSystemResult.InfiniteSolutions(), res)
    }

    @Test
    fun testSolvePolynomialQuadratic() {
        val engine = CasEngine()
        // x² - 5x + 6 = 0 => x₁ = 2, x₂ = 3
        val res = engine.solvePolynomialFromCoeffs(listOf("1", "-5", "6"))
        assertEquals("x₁ = 2;  x₂ = 3", res.displayFormula)

        // x² - 2 = 0 => x₁ = -√2, x₂ = √2
        val resSqrt = engine.solvePolynomialFromCoeffs(listOf("1", "0", "-2"))
        assertEquals("x₁ = -√2;  x₂ = √2", resSqrt.displayFormula)
    }

    @Test
    fun testSolvePolynomialCubic() {
        val engine = CasEngine()
        // x³ - 6x² + 11x - 6 = 0 => x₁ = 1, x₂ = 2, x₃ = 3
        val res = engine.solvePolynomialFromCoeffs(listOf("1", "-6", "11", "-6"))
        assertEquals("x₁ = 1;  x₂ = 2;  x₃ = 3", res.displayFormula)
    }

    @Test
    fun testMatrixDeterminant2x2And3x3() {
        val engine = CasEngine()
        // A = [1 2; 3 4] => det A = 1*4 - 2*3 = -2
        val mat2 = listOf(
            listOf("1", "2"),
            listOf("3", "4")
        )
        val res2 = engine.matrixDeterminant(mat2)
        assertTrue(res2 is MatrixResult.Scalar)
        val scalar2 = res2 as MatrixResult.Scalar
        assertEquals(CasEngine.Fraction(-2L, 1L), scalar2.value)
        assertEquals("-2", scalar2.formatted)

        // A = [1 2 3; 0 1 4; 5 6 0] => det A = 1*(0-24) - 2*(0-20) + 3*(0-5) = -24 + 40 - 15 = 1
        val mat3 = listOf(
            listOf("1", "2", "3"),
            listOf("0", "1", "4"),
            listOf("5", "6", "0")
        )
        val res3 = engine.matrixDeterminant(mat3)
        assertTrue(res3 is MatrixResult.Scalar)
        val scalar3 = res3 as MatrixResult.Scalar
        assertEquals(CasEngine.Fraction(1L, 1L), scalar3.value)
        assertEquals("1", scalar3.formatted)
    }

    @Test
    fun testMatrixInverse2x2And3x3() {
        val engine = CasEngine()
        // A = [1 2; 3 4] => A⁻¹ = [-2 1; 3/2 -1/2]
        val mat2 = listOf(
            listOf("1", "2"),
            listOf("3", "4")
        )
        val res2 = engine.matrixInverse(mat2)
        assertTrue(res2 is MatrixResult.MatrixValue)
        val val2 = res2 as MatrixResult.MatrixValue
        assertEquals(listOf(listOf("-2", "1"), listOf("3/2", "-1/2")), val2.formattedRows)

        // A = [1 0 0; 0 2 0; 0 0 4] => A⁻¹ = [1 0 0; 0 1/2 0; 0 0 1/4]
        val mat3 = listOf(
            listOf("1", "0", "0"),
            listOf("0", "2", "0"),
            listOf("0", "0", "4")
        )
        val res3 = engine.matrixInverse(mat3)
        assertTrue(res3 is MatrixResult.MatrixValue)
        val val3 = res3 as MatrixResult.MatrixValue
        assertEquals(
            listOf(
                listOf("1", "0", "0"),
                listOf("0", "1/2", "0"),
                listOf("0", "0", "1/4")
            ),
            val3.formattedRows
        )
    }

    @Test
    fun testMatrixSingularInverse() {
        val engine = CasEngine()
        // A = [1 2; 2 4] => det A = 0
        val singular = listOf(
            listOf("1", "2"),
            listOf("2", "4")
        )
        val res = engine.matrixInverse(singular)
        assertTrue(res is MatrixResult.Error)
        val err = res as MatrixResult.Error
        assertEquals("Матрица вырождена (det A = 0), обратной не существует", err.message)
    }

    @Test
    fun testMatrixMultiplication() {
        val engine = CasEngine()
        // A = [1 2; 3 4], B = [2 0; 1 2]
        // A * B = [1*2 + 2*1, 1*0 + 2*2; 3*2 + 4*1, 3*0 + 4*2] = [4 4; 10 8]
        val a = listOf(
            listOf("1", "2"),
            listOf("3", "4")
        )
        val b = listOf(
            listOf("2", "0"),
            listOf("1", "2")
        )
        val res = engine.matrixBinaryOp(a, b, "*")
        assertTrue(res is MatrixResult.MatrixValue)
        val mv = res as MatrixResult.MatrixValue
        assertEquals(listOf(listOf("4", "4"), listOf("10", "8")), mv.formattedRows)
    }

    @Test
    fun testMatrixAdditionSubtractionTranspose() {
        val engine = CasEngine()
        val a = listOf(
            listOf("1", "2"),
            listOf("3", "4")
        )
        val b = listOf(
            listOf("5", "6"),
            listOf("7", "8")
        )
        val addRes = engine.matrixBinaryOp(a, b, "+") as MatrixResult.MatrixValue
        assertEquals(listOf(listOf("6", "8"), listOf("10", "12")), addRes.formattedRows)

        val subRes = engine.matrixBinaryOp(a, b, "-") as MatrixResult.MatrixValue
        assertEquals(listOf(listOf("-4", "-4"), listOf("-4", "-4")), subRes.formattedRows)

        val transRes = engine.matrixTranspose(a) as MatrixResult.MatrixValue
        assertEquals(listOf(listOf("1", "3"), listOf("2", "4")), transRes.formattedRows)
    }

    @Test
    fun testEquationSystemSolverBasic() {
        val solver = EquationSystemSolver()
        val eqs = listOf("x - y = 2", "2y - x = 4")
        val res = solver.solve(eqs)
        assertTrue("Expected UniqueSolution but got $res", res is LinearSystemResult.UniqueSolution)
        val sol = res as LinearSystemResult.UniqueSolution
        assertEquals(listOf("x = 8", "y = 6"), sol.formattedLines)
        assertEquals(listOf(CasEngine.Fraction(8L), CasEngine.Fraction(6L)), sol.values)
    }

    @Test
    fun testEquationSystemSolverAlternativeOrder() {
        val solver = EquationSystemSolver()
        val eqs = listOf("y = 3x - 1", "2x + y = 9")
        val res = solver.solve(eqs)
        assertTrue("Expected UniqueSolution but got $res", res is LinearSystemResult.UniqueSolution)
        val sol = res as LinearSystemResult.UniqueSolution
        assertEquals(listOf("x = 2", "y = 5"), sol.formattedLines)
    }

    @Test
    fun testEquationSystemSolverFractions() {
        val solver = EquationSystemSolver()
        val eqs = listOf("x/2 + y/3 = 5", "x - y = 2")
        val res = solver.solve(eqs)
        assertTrue("Expected UniqueSolution but got $res", res is LinearSystemResult.UniqueSolution)
        val sol = res as LinearSystemResult.UniqueSolution
        assertEquals(CasEngine.Fraction(34L, 5L), sol.values[0])
        assertEquals(CasEngine.Fraction(24L, 5L), sol.values[1])
        assertTrue(sol.formattedLines[0].startsWith("x = 34/5"))
        assertTrue(sol.formattedLines[1].startsWith("y = 24/5"))
    }

    @Test
    fun testEquationSystemSolver3x3() {
        val solver = EquationSystemSolver()
        val eqs = listOf(
            "x + y + z = 6",
            "2x - y + z = 3",
            "3x + 2y - z = 4"
        )
        val res = solver.solve(eqs)
        assertTrue("Expected UniqueSolution but got $res", res is LinearSystemResult.UniqueSolution)
        val sol = res as LinearSystemResult.UniqueSolution
        assertEquals(listOf("x = 1", "y = 2", "z = 3"), sol.formattedLines)
        assertEquals(
            listOf(CasEngine.Fraction(1L), CasEngine.Fraction(2L), CasEngine.Fraction(3L)),
            sol.values
        )
    }

    @Test
    fun testEquationSystemSolverNonlinear2D() {
        val solver = EquationSystemSolver()
        val eqs = listOf("y = x^2", "y = x + 2")
        val res = solver.solve(eqs)
        assertTrue("Expected UniqueSolution but got $res", res is LinearSystemResult.UniqueSolution)
        val sol = res as LinearSystemResult.UniqueSolution
        assertEquals(2, sol.formattedLines.size)
        assertTrue(sol.formattedLines.any { it.contains("x = -1") && it.contains("y = 1") })
        assertTrue(sol.formattedLines.any { it.contains("x = 2") && it.contains("y = 4") })
    }

    @Test
    fun testEquationSystemSolverErrorsAndEdgeCases() {
        val solver = EquationSystemSolver()

        // Count mismatch
        val mismatchRes = solver.solve(listOf("x + y + z = 1", "x - y = 2"))
        assertTrue(mismatchRes is LinearSystemResult.Error)
        val err = mismatchRes as LinearSystemResult.Error
        assertTrue(err.message.contains("Уравнений: 2, переменных: 3"))

        // Inconsistent
        val noSol = solver.solve(listOf("x + y = 1", "x + y = 2"))
        assertTrue(noSol is LinearSystemResult.NoSolution)

        // Dependent
        val infSol = solver.solve(listOf("x + y = 1", "2x + 2y = 2"))
        assertTrue(infSol is LinearSystemResult.InfiniteSolutions)
    }

    @Test
    fun testMixedFractionEvaluation() {
        val engine = CasEngine()
        val res1 = engine.evaluateFull("((2)+(3)/(4)) + ((1)+(1)/(2))")
        assertEquals("17/4", res1.displayFormula)
        assertNotNull(res1.exactFraction)
        assertEquals(CasEngine.Fraction(17L, 4L), res1.exactFraction)
        val mixed1 = engine.toMixedFraction(res1.exactFraction!!)
        assertNotNull(mixed1)
        assertEquals(Triple(4L, 1L, 4L), mixed1)

        val res2 = engine.evaluateFull("-((2)+(3)/(4))")
        assertEquals("-11/4", res2.displayFormula)
        assertNotNull(res2.exactFraction)
        assertEquals(CasEngine.Fraction(-11L, 4L), res2.exactFraction)
    }

    @Test
    fun testDetailedStepsQuadraticEquation() {
        val engine = CasEngine()
        val res = engine.evaluateFull("x^2 - 5x + 6 = 0")
        assertTrue(res.detailedSteps.isNotEmpty())
        assertTrue(res.detailedSteps.any { it.badge == "Шаг 1" })
        assertTrue(res.detailedSteps.any { it.formula.contains("D =") || it.formula.contains("1") })
        val answerStep = res.detailedSteps.find { it.badge == "Ответ" }
        assertNotNull(answerStep)
        assertTrue(answerStep!!.formula.contains("2") && answerStep.formula.contains("3"))
    }

    @Test
    fun testDetailedStepsMixedFractionArithmetic() {
        val engine = CasEngine()
        val res = engine.evaluateFull("((2)+(3)/(4)) + ((1)+(1)/(2))")
        assertTrue(res.detailedSteps.isNotEmpty())
        assertTrue(res.detailedSteps.any { it.badge == "Условие" })
        val answerStep = res.detailedSteps.find { it.badge == "Ответ" }
        assertNotNull(answerStep)
        assertEquals("17/4", answerStep!!.formula)
    }

    @Test
    fun testMultiplicationAndDivisionSymbols() {
        val engine = CasEngine()
        assertEquals("81", engine.evaluateFull("9·9").displayFormula)
        assertEquals("81", engine.evaluateFull("9×9").displayFormula)
        assertEquals("6", engine.evaluateFull("18÷3").displayFormula)
    }

    @Test
    fun testQuadraticRadicalSimplificationAndReduction320() {
        val engine = CasEngine()
        val res = engine.evaluateFull("x^2 - 8x - 64 = 0")
        assertEquals("x₁ = 4 - 4√5;  x₂ = 4 + 4√5", res.displayFormula)

        val answerStep = res.detailedSteps.find { it.badge == "Ответ" }
        assertNotNull(answerStep)
        assertEquals("x₁ = 4 - 4√5\nx₂ = 4 + 4√5", answerStep!!.formula)

        val step2 = res.detailedSteps.find { it.badge == "Шаг 2" }
        assertNotNull(step2)
        assertTrue(step2!!.formula.contains("√D = √320 = 8√5"))

        val step3 = res.detailedSteps.find { it.badge == "Шаг 3" }
        assertNotNull(step3)
        assertTrue(step3!!.formula.contains("x₁ = (8 - 8√5) / 2 = 4 - 4√5"))
        assertTrue(step3.formula.contains("x₂ = (8 + 8√5) / 2 = 4 + 4√5"))
    }

    @Test
    fun testQuadraticRadicalSimplificationAndReduction12() {
        val engine = CasEngine()
        val res = engine.evaluateFull("x^2 - 2x - 2 = 0")
        assertEquals("x₁ = 1 - √3;  x₂ = 1 + √3", res.displayFormula)

        val answerStep = res.detailedSteps.find { it.badge == "Ответ" }
        assertNotNull(answerStep)
        assertEquals("x₁ = 1 - √3\nx₂ = 1 + √3", answerStep!!.formula)

        val step2 = res.detailedSteps.find { it.badge == "Шаг 2" }
        assertNotNull(step2)
        assertTrue(step2!!.formula.contains("√D = √12 = 2√3"))

        val step3 = res.detailedSteps.find { it.badge == "Шаг 3" }
        assertNotNull(step3)
        assertTrue(step3!!.formula.contains("x₁ = (2 - 2√3) / 2 = 1 - √3"))
        assertTrue(step3.formula.contains("x₂ = (2 + 2√3) / 2 = 1 + √3"))
    }

    @Test
    fun testTrigEquationNoRealSolutions() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("sin(x) = e^(1) ÷ 2", "x")
        assertEquals("Нет действительных корней", res.displayFormula)
        assertNull(res.primaryNode)
    }

    @Test
    fun testRadicalEquationNegativeRight() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("√(x) = -3", "x")
        assertEquals("Нет действительных корней", res.displayFormula)
        assertNull(res.primaryNode)
    }

    @Test
    fun testRationalEquationExtraneousRoot() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("(x^2 - 4) / (x - 2) = 0", "x")
        assertEquals("x = -2", res.displayFormula)
        assertTrue(res.steps.any { it.contains("посторонний корень по ОДЗ") } || res.detailedSteps.any { it.formula.contains("посторонний корень по ОДЗ") })
    }

    @Test
    fun testTranscendentalEquations() {
        val engine = CasEngine()
        val resLn = engine.isolateVariableFull("ln(x) = 2", "x")
        assertEquals("x = e^2", resLn.displayFormula)

        val resExp = engine.isolateVariableFull("2^x = 8", "x")
        assertEquals("x = 3", resExp.displayFormula)
    }
}