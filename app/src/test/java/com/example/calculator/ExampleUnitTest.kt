package com.example.calculator

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.pow

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
        assertEquals("1 · 10⁻¹⁶", CasEngine.Fraction.formatDoubleNorm1(1e-16))
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

    @Test
    fun testFactorialLargeNumberNoOverflow() {
        val engine = CasEngine()
        val res = engine.evaluateFull("50!")
        assertTrue("Expected displayFormula to start with '3.0414', got: ${res.displayFormula}", res.displayFormula.startsWith("3.0414"))
        assertTrue("Expected displayFormula to contain '10⁶⁴', got: ${res.displayFormula}", res.displayFormula.contains("10⁶⁴"))
    }

    @Test
    fun testPowerLargeNumberNoOverflow() {
        val engine = CasEngine()
        val res = engine.evaluateFull("2^65")
        assertTrue("Expected displayFormula to start with '3.6893', got: ${res.displayFormula}", res.displayFormula.startsWith("3.6893"))
        assertTrue("Expected displayFormula to contain '10¹⁹', got: ${res.displayFormula}", res.displayFormula.contains("10¹⁹"))
    }

    @Test
    fun testSimplifyPowerProductRadical() {
        val engine = CasEngine()
        val res = engine.evaluateFull("(10*√(2))^2 - 25")
        assertEquals("175", res.displayFormula)
    }

    @Test
    fun testEvenPowerEquation() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("x^4 - 16 = 0", "x")
        assertEquals("x₁ = -2;  x₂ = 2", res.displayFormula)
    }

    @Test
    fun testBiquadraticEquation() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("x^4 - 5x^2 + 4 = 0", "x")
        assertTrue(res.displayFormula.contains("-2") && res.displayFormula.contains("-1") && res.displayFormula.contains("1") && res.displayFormula.contains("2"))
    }

    @Test
    fun testIdentityWithOdzExclusion() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("1/(x - 1) - 1/(x - 1) = 0", "x")
        assertTrue("Expected displayFormula to contain 'x ≠ 1', got: ${res.displayFormula}", res.displayFormula.contains("x ≠ 1"))
    }

    @Test
    fun testIrrationalEquationWithExtraneousRoot() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("√(x + 2) = x", "x")
        assertEquals("x = 2", res.displayFormula)
    }

    @Test
    fun testLogarithmicEquationWithExtraneousRoot() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("ln(x^2 - 3) = ln(2x)", "x")
        assertEquals("x = 3", res.displayFormula)
    }

    @Test
    fun testModulusEquationBranching() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("abs(x - 3) = 5", "x")
        assertEquals("x₁ = -2;  x₂ = 8", res.displayFormula)
    }

    @Test
    fun testEvenPowerEquationBranching() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("(x - 1)^4 = 16", "x")
        assertEquals("x₁ = -1;  x₂ = 3", res.displayFormula)
    }

    @Test
    fun testExponentialEquationQuadraticInExponent() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("2^(2x^2) + 2^(x^2 + 2x + 2) = 2^(5 + 4x)", "x")
        assertTrue("Expected displayFormula to contain '1 - √3' and '1 + √3', got: ${res.displayFormula}",
            res.displayFormula.contains("1 - √3") && res.displayFormula.contains("1 + √3"))
    }

    @Test
    fun testExponentialEquationQuadraticBase2() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("4^x - 5*2^x + 4 = 0", "x")
        assertTrue("Expected displayFormula to contain '0' and '2', got: ${res.displayFormula}",
            res.displayFormula.contains("0") && res.displayFormula.contains("2"))
    }

    @Test
    fun testCombinatoricsEvaluation() {
        val engine = CasEngine()

        // 1. comb(5, 2) == 10 with non-empty detailedSteps
        val resC52 = engine.evaluateFull("comb(5, 2)")
        assertEquals("10", resC52.displayFormula)
        assertTrue(resC52.detailedSteps.isNotEmpty())

        // 2. perm(5, 2) == 20
        val resP52 = engine.evaluateFull("perm(5, 2)")
        assertEquals("20", resP52.displayFormula)

        // 3. comb(50, 3) == 19600 (no Long overflow)
        val resC503 = engine.evaluateFull("comb(50, 3)")
        assertEquals("19600", resC503.displayFormula)

        // 4. comb(5, 0) == 1 and comb(5, 5) == 1
        assertEquals("1", engine.evaluateFull("comb(5, 0)").displayFormula)
        assertEquals("1", engine.evaluateFull("comb(5, 5)").displayFormula)

        // 5. comb(3, 5) starts with "Ошибка"
        assertTrue(engine.evaluateFull("comb(3, 5)").displayFormula.startsWith("Ошибка"))
    }

    @Test
    fun testSchoolPhysicsConstants() {
        val engine = CasEngine()

        assertEquals("10", engine.evaluateFull("g").displayFormula)
        assertEquals("8.31", engine.evaluateFull("R").displayFormula)
        assertEquals("6 · 10²³", engine.evaluateFull("Nₐ").displayFormula)
        assertEquals("9000000000", engine.evaluateFull("k").displayFormula)
        assertEquals("1.38 · 10⁻²³", engine.evaluateFull("k_B").displayFormula)
        assertEquals("300000000", engine.evaluateFull("c").displayFormula)
        assertEquals("1.6 · 10⁻¹⁹", engine.evaluateFull("qₑ").displayFormula)
    }

    @Test
    fun testFractionalExponentSerialization() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("x^5 = y", "x")
        assertEquals("x = y^(1/5)", res.displayFormula)
        assertFalse(res.displayFormula.contains("y^1/5"))
        assertFalse(res.displayFormula.contains("y^1 / 5"))
    }

    @Test
    fun testIncompleteQuadraticSymbolicSimplification() {
        val engine = CasEngine()
        val res = engine.isolateVariableFull("x^2 - 2yx + ax = 0", "x")
        assertTrue(res.displayFormula.contains("0") && res.displayFormula.contains("2y - a"))
        assertFalse(res.displayFormula.contains("-(-"))
        assertFalse(res.displayFormula.contains("/ 2"))
    }

    @Test
    fun testSymbolicNegationAndLikeTermCombiner() {
        val engine = CasEngine()
        val res1 = engine.evaluateFull("(-(-2y + a) - (-2y + a)) / 2")
        assertEquals("2y - a", res1.displayFormula)

        val res2 = engine.evaluateFull("(-(-2y + a) + (-2y + a)) / 2")
        assertEquals("0", res2.displayFormula)
    }

    @Test
    fun testMultiVariableEquationSolving() {
        val engine = CasEngine()

        val resY = engine.isolateVariableFull("x^2 - 2xy + ax = 0", "y")
        assertTrue("Expected y = (x + a) / 2 or y = (a + x) / 2, got: ${resY.displayFormula}",
            resY.displayFormula == "y = (x + a) / 2" || resY.displayFormula == "y = (a + x) / 2")

        val resA = engine.isolateVariableFull("x^2 - 2xy + ax = 0", "a")
        assertTrue("Expected a = 2y - x or a = -x + 2y, got: ${resA.displayFormula}",
            resA.displayFormula == "a = 2y - x" || resA.displayFormula == "a = -x + 2y")

        val resODZ = engine.isolateVariableFull("(y^2 - 4) / (y - 2) = 0", "y")
        assertEquals("y = -2", resODZ.displayFormula)
    }

    @Test
    fun testArbitraryBaseLogarithms() {
        val engine = CasEngine()

        // 1. engine.evaluateFull("log(2, 8)").displayFormula == "3" with non-empty detailedSteps
        val res28 = engine.evaluateFull("log(2, 8)")
        assertEquals("3", res28.displayFormula)
        assertTrue("Expected non-empty detailedSteps", res28.detailedSteps.isNotEmpty())

        // 2. engine.evaluateFull("log(4, 8)").displayFormula == "3/2" and engine.evaluateFull("log(2, 1/8)").displayFormula == "-3"
        assertEquals("3/2", engine.evaluateFull("log(4, 8)").displayFormula)
        assertEquals("-3", engine.evaluateFull("log(2, 1/8)").displayFormula)

        // 3. engine.evaluateFull("log(1, 5)").displayFormula starts with "Ошибка"
        assertTrue(engine.evaluateFull("log(1, 5)").displayFormula.startsWith("Ошибка"))

        // 4. engine.isolateVariableFull("log(2, x - 1) = 3", "x").displayFormula == "x = 9"
        assertEquals("x = 9", engine.isolateVariableFull("log(2, x - 1) = 3", "x").displayFormula)

        // 5. engine.isolateVariableFull("log(2, x^2 - 3) = log(2, 2x)", "x").displayFormula == "x = 3"
        assertEquals("x = 3", engine.isolateVariableFull("log(2, x^2 - 3) = log(2, 2x)", "x").displayFormula)
    }

    @Test
    fun testSameBaseAndCommensurableExponentialEquations() {
        val engine = CasEngine()
        assertEquals("x = 99", engine.isolateVariableFull("2^x = 2^99", "x").displayFormula)
        assertEquals("x = 1000", engine.isolateVariableFull("2^x = 2^1000", "x").displayFormula)
        assertEquals("x = 99/2", engine.isolateVariableFull("4^x = 8^33", "x").displayFormula)
        assertEquals("x = 99", engine.isolateVariableFull("e^x = e^99", "x").displayFormula)
    }

    @Test
    fun testAdvancedLogarithmicEquations() {
        val engine = CasEngine()

        // 1. log(5, 2 - x) = log(25, x^4) -> x₁ = -2; x₂ = 1
        val res1 = engine.isolateVariableFull("log(5, 2 - x) = log(25, x^4)", "x")
        assertFalse(res1.displayFormula.contains("Не удалось"))
        assertTrue("Expected -2 in result, got ${res1.displayFormula}", res1.displayFormula.contains("-2"))
        assertTrue("Expected 1 in result, got ${res1.displayFormula}", res1.displayFormula.contains("1"))
        assertTrue("Expected non-empty detailedSteps", res1.detailedSteps.isNotEmpty())

        // 2. log(-x^2 - 32x + 33, 2x^2 + 136) = 1 / log(-33x, (1 - x)*(x + 33)) -> x₁ = -17/2; x₂ = -8
        val res2 = engine.isolateVariableFull("log(-x^2 - 32x + 33, 2x^2 + 136) = 1 / log(-33x, (1 - x)*(x + 33))", "x")
        assertFalse(res2.displayFormula.contains("Не удалось"))
        assertTrue("Expected -17/2 or -8.5 in result, got ${res2.displayFormula}", res2.displayFormula.contains("-17/2") || res2.displayFormula.contains("-8.5"))
        assertTrue("Expected -8 in result, got ${res2.displayFormula}", res2.displayFormula.contains("-8"))
        assertTrue("Expected non-empty detailedSteps", res2.detailedSteps.isNotEmpty())

        // 3. log(2, x - 3) = log(4, x - 1) -> x = 5 (x = 2 extraneous root rejected by ODZ)
        val res3 = engine.isolateVariableFull("log(2, x - 3) = log(4, x - 1)", "x")
        assertEquals("x = 5", res3.displayFormula)
        assertTrue("Expected non-empty detailedSteps", res3.detailedSteps.isNotEmpty())

        // 4. log(1/2, x + 1) = log(2, 1/3) -> x = 2
        val res4 = engine.isolateVariableFull("log(1/2, x + 1) = log(2, 1/3)", "x")
        assertEquals("x = 2", res4.displayFormula)
        assertTrue("Expected non-empty detailedSteps", res4.detailedSteps.isNotEmpty())
    }

    @Test
    fun testQuadraticSurdRecognitionInInequality() {
        // 1. Inequality test from screenshot
        val result = InequalitySolverEngine.solve("abs(log((4),((x+1)^2)) - 2) + abs(log((2),(2x+3)) - 1) <= 3")
        assertNull(result.errorMessage)
        val ans = result.answerFormula
        assertTrue("Expected answerFormula to contain (-5 + 3√57)/4, got: $ans", ans.contains("(-5 + 3√57)/4"))
        assertTrue("Expected answerFormula to contain -1/2, got: $ans", ans.contains("-1/2") || ans.contains("-1 / 2"))
        assertFalse("Expected answerFormula NOT to contain 4.412, got: $ans", ans.contains("4.412"))

        // 2. Direct formatExactLabel checks
        val (_, label1) = InequalitySolverEngine.formatExactLabel((-5.0 + kotlin.math.sqrt(513.0)) / 4.0)
        assertEquals("(-5 + 3√57)/4", label1)

        val (_, label2) = InequalitySolverEngine.formatExactLabel((1.0 + kotlin.math.sqrt(5.0)) / 2.0)
        assertEquals("(1 + √5)/2", label2)

        val (_, label3) = InequalitySolverEngine.formatExactLabel(2.0 - kotlin.math.sqrt(3.0))
        assertEquals("2 - √3", label3)

        val (_, label4) = InequalitySolverEngine.formatExactLabel(kotlin.math.sqrt(2.0) / 2.0)
        assertEquals("√2/2", label4)

        // 3. Transcendental false-positive guard
        val (_, labelPi) = InequalitySolverEngine.formatExactLabel(Math.PI, hasExpOrLog = false, isPeriodicTrig = false, knownBases = emptySet())
        assertFalse("Expected formatExactLabel(Math.PI) NOT to contain √, got: $labelPi", labelPi.contains("√"))
    }

    @Test
    fun testExponentialInequalityOverflowAndUnderflow() {
        val result = InequalitySolverEngine.solve("25 * 4^(1/2 - 2/x) - 133 * 10^(-2/x) + 4 * 5^(1 - 4/x) <= 0")
        assertNull(result.errorMessage)
        assertEquals("x ∈ (-∞; -1] ∪ [2; +∞)", result.answerFormula)
        val labels = result.criticalPoints.map { it.label }
        assertEquals(listOf("-1", "0", "2"), labels)
    }

    @Test
    fun testSteepExponentialNearSingularity() {
        val result = InequalitySolverEngine.solve("2^(1/x) - 4 >= 0")
        assertNull(result.errorMessage)
        assertEquals("x ∈ (0; 1/2]", result.answerFormula)
    }

    @Test
    fun testHigherOrderRadicalRecognition() {
        val result = InequalitySolverEngine.solve("(2 * (1/2)^(x + 2) - (1/2) * 2^(x + 2)) * (2 * (log((1/2),(x + 2)))^2 - (1/2) * log((2),(x + 2))) <= 0")
        assertNull(result.errorMessage)
        assertEquals("x ∈ {-1} ∪ [⁴√2 - 2; +∞)", result.answerFormula)
        assertFalse(result.answerFormula.contains("-0.8107"))

        val (_, label1) = InequalitySolverEngine.formatExactLabel(2.0.pow(0.25) - 2.0)
        assertEquals("⁴√2 - 2", label1)

        val (_, label2) = InequalitySolverEngine.formatExactLabel(3.0.pow(1.0 / 3.0) + 1.0)
        assertEquals("³√3 + 1", label2)

        val (_, label3) = InequalitySolverEngine.formatExactLabel(2.0 - 5.0.pow(1.0 / 3.0))
        assertEquals("2 - ³√5", label3)

        val (_, labelPi) = InequalitySolverEngine.formatExactLabel(Math.PI, hasExpOrLog = false, isPeriodicTrig = false, knownBases = emptySet())
        assertFalse(labelPi.contains("√"))
    }

    @Test
    fun testSinSquareInequality() {
        val casEngine = CasEngine()
        val result1 = InequalitySolverEngine.solve("(sin(x))^(2)+2*sin(x) ≤ 0", casEngine)
        assertNull("Expected no error for (sin(x))^(2)+2*sin(x) ≤ 0, got: ${result1.errorMessage}", result1.errorMessage)
        assertTrue("Expected isPeriodic = true", result1.isPeriodic)
        assertTrue("Expected answer to contain [π + 2πk; 2π + 2πk], got: ${result1.answerFormula}", result1.answerFormula.contains("[π + 2πk; 2π + 2πk]"))

        val result2 = InequalitySolverEngine.solve("(sin(x))^2 + 2*sin(x) <= 0", casEngine)
        assertNull("Expected no error for (sin(x))^2 + 2*sin(x) <= 0, got: ${result2.errorMessage}", result2.errorMessage)
        assertTrue("Expected isPeriodic = true", result2.isPeriodic)
        assertTrue("Expected answer to contain [π + 2πk; 2π + 2πk], got: ${result2.answerFormula}", result2.answerFormula.contains("[π + 2πk; 2π + 2πk]"))
    }

    @Test
    fun testMixedNonPeriodicTrigEquation() {
        val casEngine = CasEngine()
        val result = TrigSolverEngine.solve("log((2),(x))+ln(x^2)-sin(2x)+(tan(x))/(sin(x))=0", casEngine = casEngine)
        assertTrue("Expected generalSolutions to be empty for non-periodic mixed equation", result.generalSolutions.isEmpty())
        assertNotNull("Expected non-null errorMessage for non-periodic mixed equation", result.errorMessage)
        assertTrue(
            "Expected errorMessage to mention non-periodic equation, got: ${result.errorMessage}",
            result.errorMessage!!.contains("не является чисто тригонометрическим")
        )
    }

    @Test
    fun testGeneralModulusEquations() {
        val engine = CasEngine()

        // 1. |2x - 1| = 5 -> roots -2 and 3
        val res1 = engine.isolateVariableFull("|2x - 1| = 5", "x")
        assertTrue("Expected -2 and 3 in '${res1.displayFormula}'", res1.displayFormula.contains("-2") && res1.displayFormula.contains("3"))

        // 2. |2x - 1| = x + 3 -> roots -2/3 and 4
        val res2 = engine.isolateVariableFull("|2x - 1| = x + 3", "x")
        assertTrue("Expected -2/3 and 4 in '${res2.displayFormula}'", (res2.displayFormula.contains("-2/3") || res2.displayFormula.contains("-0.66")) && res2.displayFormula.contains("4"))

        // 3. |2x - 1| = x - 5 -> "Нет действительных корней" (extraneous roots rejected!)
        val res3 = engine.isolateVariableFull("|2x - 1| = x - 5", "x")
        assertEquals("Нет действительных корней", res3.displayFormula)

        // 4. |2x - 3| = |x + 1| -> roots 2/3 and 4
        val res4 = engine.isolateVariableFull("|2x - 3| = |x + 1|", "x")
        assertTrue("Expected 2/3 and 4 in '${res4.displayFormula}'", (res4.displayFormula.contains("2/3") || res4.displayFormula.contains("0.66")) && res4.displayFormula.contains("4"))

        // 5. |x - 2| + |x + 3| = 7 -> roots -4 and 3
        val res5 = engine.isolateVariableFull("|x - 2| + |x + 3| = 7", "x")
        assertTrue("Expected -4 and 3 in '${res5.displayFormula}'", res5.displayFormula.contains("-4") && res5.displayFormula.contains("3"))

        // 6. x^2 - 5|x| + 6 = 0 -> roots -3, -2, 2, 3
        val res6 = engine.isolateVariableFull("x^2 - 5|x| + 6 = 0", "x")
        assertTrue("Expected -3, -2, 2, 3 in '${res6.displayFormula}'", res6.displayFormula.contains("-3") && res6.displayFormula.contains("-2") && res6.displayFormula.contains("2") && res6.displayFormula.contains("3"))

        // 7. |x - 1| + |x - 5| = 4 -> interval x ∈ [1; 5]
        val res7 = engine.isolateVariableFull("|x - 1| + |x - 5| = 4", "x")
        assertTrue("Expected interval [1; 5] in '${res7.displayFormula}'", res7.displayFormula.contains("[1; 5]"))

        // 8. ||x| - 2| = 1 -> roots -3, -1, 1, 3
        val res8 = engine.isolateVariableFull("||x| - 2| = 1", "x")
        assertTrue("Expected -3, -1, 1, 3 in '${res8.displayFormula}'", res8.displayFormula.contains("-3") && res8.displayFormula.contains("-1") && res8.displayFormula.contains("1") && res8.displayFormula.contains("3"))
    }

    @Test
    fun testUpdateCheckerVersions() {
        // compareVersions: "2.1" > "2.0", "2.0.1" > "2.0", "2.10" > "2.2", "2.0" == "v2.0", "1.9" < "2.0".
        assertTrue(UpdateChecker.compareVersions("2.1", "2.0") > 0)
        assertTrue(UpdateChecker.compareVersions("2.0.1", "2.0") > 0)
        assertTrue(UpdateChecker.compareVersions("2.10", "2.2") > 0)
        assertEquals(0, UpdateChecker.compareVersions("2.0", "v2.0"))
        assertTrue(UpdateChecker.compareVersions("1.9", "2.0") < 0)
    }

    @Test
    fun testUpdateCheckerParseRelease() {
        val mockJson = """
        [
            {
                "tag_name": "ocr-models-v1",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/Markhoodwink/Calculator/releases/tag/ocr-models-v1",
                "body": "OCR models data"
            },
            {
                "tag_name": "v2.1",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/Markhoodwink/Calculator/releases/tag/v2.1",
                "body": "### New Features\n* Support for modulus equations\n- **Адаптивные скобки**: Описание\n---\nFull Changelog: https://github.com/Markhoodwink/Calculator/compare/v2.0...v2.1",
                "assets": [
                    {
                        "name": "MB-CAL-v2.1.apk",
                        "browser_download_url": "https://github.com/Markhoodwink/Calculator/releases/download/v2.1/MB-CAL-v2.1.apk"
                    }
                ]
            }
        ]
        """.trimIndent()

        // When current version is 2.0, should parse v2.1
        val update = UpdateChecker.parseLatestAppRelease(mockJson, "2.0")
        assertNotNull(update)
        assertEquals("v2.1", update!!.tagName)
        assertEquals("2.1", update.versionName)
        assertEquals("https://github.com/Markhoodwink/Calculator/releases/download/v2.1/MB-CAL-v2.1.apk", update.downloadUrl)
        assertTrue(update.releaseNotes.contains("Support for modulus equations"))
        assertTrue(update.releaseNotes.contains("• Адаптивные скобки: Описание"))
        assertFalse(update.releaseNotes.contains("#"))
        assertFalse(update.releaseNotes.contains("**"))
        assertFalse(update.releaseNotes.contains("Full Changelog"))
        assertFalse(update.releaseNotes.contains("---"))

        // When current version is 2.1, no update returned
        val noUpdate = UpdateChecker.parseLatestAppRelease(mockJson, "2.1")
        assertNull(noUpdate)
    }

    @Test
    fun testAdaptiveBracketScaling() {
        val textSize = 20f
        val density = 2f

        // 1. Flat expression (x + 1) -> isStretched is null
        val flatTokens = listOf("(", "x", "+", "1", ")")
        val flatWidths = floatArrayOf(10f, 12f, 14f, 12f, 10f)
        val flatAscents = floatArrayOf(18f, 16f, 16f, 16f, 18f)
        val flatDescents = floatArrayOf(5f, 4f, 4f, 4f, 5f)
        val flatBoxes = CasioDisplayView.computeAdaptiveBracketBoxes(
            flatTokens, flatWidths, flatAscents, flatDescents, textSize, density
        )
        assertNull(flatBoxes[0])
        assertNull(flatBoxes[4])

        // 2. Nested expression L(1 - (1 - 4√(1 - L_1/L))^2)
        // Tokens: outer '(', 'L', '-', inner '(', '1', '-', tall sqrt item, ')', '^2', ')'
        val nestedTokens = listOf("(", "L", "-", "(", "1", "-", null, ")", "^2", ")")
        val nWidths = floatArrayOf(10f, 12f, 14f, 10f, 12f, 14f, 50f, 10f, 15f, 10f)
        val nAscents = floatArrayOf(18f, 16f, 16f, 18f, 16f, 16f, 44f, 18f, 20f, 18f)
        val nDescents = floatArrayOf(5f, 4f, 4f, 5f, 4f, 4f, 30f, 5f, 0f, 5f)
        val nestedBoxes = CasioDisplayView.computeAdaptiveBracketBoxes(
            nestedTokens, nWidths, nAscents, nDescents, textSize, density
        )

        val innerOpen = nestedBoxes[3]
        val innerClose = nestedBoxes[7]
        assertNotNull(innerOpen)
        assertNotNull(innerClose)
        assertTrue(innerOpen!!.isStretched)
        assertTrue(innerOpen.ascent > 44f)
        assertTrue(innerOpen.descent > 30f)

        val outerOpen = nestedBoxes[0]
        val outerClose = nestedBoxes[9]
        assertNotNull(outerOpen)
        assertNotNull(outerClose)
        assertTrue(outerOpen!!.isStretched)
        assertTrue(outerOpen.ascent > innerOpen.ascent)
        assertTrue(outerOpen.descent > innerOpen.descent)

        // 3. Modulus wrapping a fraction |x/2|
        val modTokens = listOf("|", null, "|")
        val modWidths = floatArrayOf(6f, 30f, 6f)
        val modAscents = floatArrayOf(18f, 35f, 18f)
        val modDescents = floatArrayOf(5f, 25f, 5f)
        val modBoxes = CasioDisplayView.computeAdaptiveBracketBoxes(
            modTokens, modWidths, modAscents, modDescents, textSize, density
        )
        assertNotNull(modBoxes[0])
        assertNotNull(modBoxes[2])
        assertTrue(modBoxes[0]!!.isStretched)
        assertTrue(modBoxes[2]!!.isStretched)
        assertEquals(modBoxes[0]!!.ascent, modBoxes[2]!!.ascent, 0.001f)
        assertEquals(modBoxes[0]!!.descent, modBoxes[2]!!.descent, 0.001f)
    }

    @Test
    fun testEge15LogarithmicInequalityWithVariableBase() {
        val casEngine = CasEngine()
        val result = InequalitySolverEngine.solve("log((log((5),(x)) - 3),(2 * (log((5),(x)))^2 - 7 * log((5),(x)) + 3)) > 1", casEngine)
        assertNull("Expected no error for EGE #15 inequality, got: ${result.errorMessage}", result.errorMessage)
        assertEquals("x ∈ (625; +∞)", result.answerFormula)
        val cpLabels = result.criticalPoints.map { it.label }
        assertTrue("Expected critical points to contain 125, got: $cpLabels", cpLabels.contains("125"))
        assertTrue("Expected critical points to contain 625, got: $cpLabels", cpLabels.contains("625"))
        val cp125 = result.criticalPoints.find { it.label == "125" }
        val cp625 = result.criticalPoints.find { it.label == "625" }
        assertNotNull(cp125)
        assertNotNull(cp625)
        assertFalse("Expected 125 to be excluded by ODZ", cp125!!.isIncluded)
        assertFalse("Expected 625 to be excluded by ODZ", cp625!!.isIncluded)
    }

    @Test
    fun testTinyFractionalLogRoot() {
        val casEngine = CasEngine()
        val result = InequalitySolverEngine.solve("log((5),(x)) + 4 >= 0", casEngine)
        assertNull("Expected no error for log_5(x) + 4 >= 0, got: ${result.errorMessage}", result.errorMessage)
        assertEquals("x ∈ [1/625; +∞)", result.answerFormula)
    }

    @Test
    fun testLargeLogInequalityRoot() {
        val casEngine = CasEngine()
        val result = InequalitySolverEngine.solve("log((2),(x)) - 10 <= 0", casEngine)
        assertNull("Expected no error for log_2(x) - 10 <= 0, got: ${result.errorMessage}", result.errorMessage)
        assertEquals("x ∈ (0; 1024]", result.answerFormula)
    }

    @Test
    fun testNestedRadicalDenesting() {
        val engine = CasEngine()

        // 1. √(5 + 2√6) -> contains √3 and √2, does NOT contain √(5, detailedSteps contains "Выделение полного квадрата"
        val res1 = engine.evaluateFull("√(5 + 2√6)")
        assertTrue("Expected √3 in result, got: ${res1.displayFormula}", res1.displayFormula.contains("√3"))
        assertTrue("Expected √2 in result, got: ${res1.displayFormula}", res1.displayFormula.contains("√2"))
        assertFalse("Expected no √(5 in result, got: ${res1.displayFormula}", res1.displayFormula.contains("√(5"))
        assertTrue(
            "Expected detailedSteps to contain 'Выделение полного квадрата', got: ${res1.detailedSteps}",
            res1.detailedSteps.any { it.title.contains("Выделение полного квадрата") || it.formula.contains("Выделение полного квадрата") }
        )

        // 2. √(5 - 2√6) -> equals "√3 - √2"
        val res2 = engine.evaluateFull("√(5 - 2√6)")
        assertEquals("√3 - √2", res2.displayFormula)

        // 3. √(7 - 4√3) -> equals "2 - √3"
        val res3 = engine.evaluateFull("√(7 - 4√3)")
        assertEquals("2 - √3", res3.displayFormula)

        // 4. √(3 + 2√2) -> contains √2 and 1, no nested √(
        val res4 = engine.evaluateFull("√(3 + 2√2)")
        assertTrue("Expected √2 in result, got: ${res4.displayFormula}", res4.displayFormula.contains("√2"))
        assertTrue("Expected 1 in result, got: ${res4.displayFormula}", res4.displayFormula.contains("1"))
        assertFalse("Expected no nested √( in result, got: ${res4.displayFormula}", res4.displayFormula.contains("√("))

        // 5. √(11 - 6√2) -> equals "3 - √2"
        val res5 = engine.evaluateFull("√(11 - 6√2)")
        assertEquals("3 - √2", res5.displayFormula)

        // 6. √(5 + 2√6) - √(5 - 2√6) -> equals "2√2" (or "2·√2")
        val res6 = engine.evaluateFull("√(5 + 2√6) - √(5 - 2√6)")
        assertTrue(
            "Expected '2√2' or '2 · √2', got: ${res6.displayFormula}",
            res6.displayFormula == "2√2" || res6.displayFormula == "2 · √2" || res6.displayFormula == "2·√2"
        )

        // 7. √(2 + √3) -> contains √6 and √2, no nested √(), numeric value equals sqrt(2 + sqrt(3)) within 1e-9
        val res7 = engine.evaluateFull("√(2 + √3)")
        assertTrue("Expected √6 in result, got: ${res7.displayFormula}", res7.displayFormula.contains("√6"))
        assertTrue("Expected √2 in result, got: ${res7.displayFormula}", res7.displayFormula.contains("√2"))
        assertFalse("Expected no nested √( in result, got: ${res7.displayFormula}", res7.displayFormula.contains("√("))
        assertNotNull("Expected primaryNode for numeric evaluation", res7.primaryNode)
        val numVal7 = engine.evalNodeAtX(res7.primaryNode!!, 0.0)
        assertEquals(kotlin.math.sqrt(2.0 + kotlin.math.sqrt(3.0)), numVal7, 1e-9)

        // 8. Non-denestable guard: √(5 + 2√5) stays √(5 + 2√5)
        val res8 = engine.evaluateFull("√(5 + 2√5)")
        assertTrue("Expected displayFormula to contain 5 + 2√5, got: ${res8.displayFormula}", res8.displayFormula.contains("5 + 2√5"))

        // 9. Negative radicand guard: √(2√6 - 5) does not crash or return a bogus real radical
        val res9 = engine.evaluateFull("√(2√6 - 5)")
        assertTrue(
            "Expected error message for negative radicand, got: ${res9.displayFormula}",
            res9.displayFormula.startsWith("Ошибка") || res9.displayFormula.contains("отрицательного")
        )
    }

    @Test
    fun testAnsChainingNumericAndSymbolic() {
        val engine = CasEngine()

        // 1. Numeric chain
        val res1 = engine.evaluateFull("2 + 3")
        assertEquals("5", res1.displayFormula)
        assertEquals("5", engine.lastAnswer)

        val res2 = engine.evaluateFull("Ans + 4")
        assertEquals("9", res2.displayFormula)
        assertEquals("9", engine.lastAnswer)

        val res3 = engine.evaluateFull("Ans^2")
        assertEquals("81", res3.displayFormula)
        assertEquals("81", engine.lastAnswer)

        // 2. Fraction chain
        val resFrac1 = engine.evaluateFull("1 / 3")
        assertEquals("1/3", resFrac1.displayFormula)
        assertEquals("1/3", engine.lastAnswer)

        val resFrac2 = engine.evaluateFull("Ans * 6")
        assertEquals("2", resFrac2.displayFormula)
        assertEquals("2", engine.lastAnswer)

        // 3. Symbolic denested radical chain
        val resRad = engine.evaluateFull("√(5 + 2√6)")
        val radAns = engine.lastAnswer
        assertTrue("Expected √3 + √2 or √2 + √3, got: $radAns", radAns == "√3 + √2" || radAns == "√2 + √3")

        val resRadSub = engine.evaluateFull("Ans - √2")
        assertEquals("√3", resRadSub.displayFormula)

        engine.evaluateFull("√(5 + 2√6)")
        val resRadPow = engine.evaluateFull("Ans^2")
        val nodeToEval = resRadPow.primaryNode!!
        val evalVal = engine.evalNodeAtX(nodeToEval, 0.0)
        val expectedVal = 5.0 + 2.0 * kotlin.math.sqrt(6.0)
        assertEquals(expectedVal, evalVal, 1e-9)

        // 4. Error guard
        engine.lastAnswer = "42"
        val resErr = engine.evaluateFull("1 / 0")
        assertTrue(resErr.displayFormula.startsWith("Ошибка"))
        assertEquals("42", engine.lastAnswer)
    }
}