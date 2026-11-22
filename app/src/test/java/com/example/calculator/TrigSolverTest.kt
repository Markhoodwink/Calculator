package com.example.calculator

import org.junit.Assert.*
import org.junit.Test

class TrigSolverTest {

    private val casEngine = CasEngine()

    @Test
    fun testParseIntervalBoundDecimalPi() {
        val p1 = TrigSolverEngine.parseIntervalBound("2.5π", casEngine)
        assertNotNull(p1)
        assertEquals(2.5 * Math.PI, p1!!.first, 1e-6)
        assertEquals(5L, p1.second?.num)
        assertEquals(2L, p1.second?.den)

        val p2 = TrigSolverEngine.parseIntervalBound("2.5п", casEngine)
        assertNotNull(p2)
        assertEquals(2.5 * Math.PI, p2!!.first, 1e-6)
        assertEquals(5L, p2.second?.num)
        assertEquals(2L, p2.second?.den)
    }

    @Test
    fun testFourTermGroupingEge() {
        // sin(2x) - 2*sqrt(3)*cos(x)^2 - 4*sin(x) + 4*sqrt(3)*cos(x) = 0 => x = π/3 + πk, k ∈ ℤ
        val result = TrigSolverEngine.solve(
            "sin(2x) - 2*sqrt(3)*cos(x)^2 - 4*sin(x) + 4*sqrt(3)*cos(x) = 0",
            casEngine = casEngine
        )
        assertTrue(result.generalSolutions.isNotEmpty())
        val allFormulas = result.generalSolutions.joinToString("; ") { it.displayFormula }
        assertTrue("Expected π/3 + πk in $allFormulas", allFormulas.contains("π/3 + πk"))
    }

    @Test
    fun testCotOdzFilteringEge() {
        // cot(x) - sin(x) - sqrt(3)*cos(x) + 1/sin(x) = 0
        // verifying x = π + 2πk is properly excluded by ODZ sin x != 0!
        val result = TrigSolverEngine.solve(
            "cot(x) - sin(x) - sqrt(3)*cos(x) + 1/sin(x) = 0",
            casEngine = casEngine
        )
        assertTrue(result.generalSolutions.isNotEmpty())
        val allFormulas = result.generalSolutions.joinToString("; ") { it.displayFormula }
        assertFalse("x = π + 2πk must be excluded by ODZ", allFormulas.contains("x = π +") || allFormulas.contains("x = π,"))
    }

    @Test
    fun testReductionFormulaCosEge() {
        // cos(1.5π + 2x) = cos(x) => x = π/2 + πk and x = π/6 + 2πk; x = 5π/6 + 2πk
        val result = TrigSolverEngine.solve("cos(1.5π + 2x) = cos(x)", casEngine = casEngine)
        assertTrue(result.generalSolutions.isNotEmpty())
        val allFormulas = result.generalSolutions.joinToString("; ") { it.displayFormula }
        assertTrue("Expected π/2 in $allFormulas", allFormulas.contains("π/2"))
        assertTrue("Expected π/6 in $allFormulas", allFormulas.contains("π/6"))
        assertTrue("Expected 5π/6 in $allFormulas", allFormulas.contains("5π/6"))
    }

    @Test
    fun testSinDoubleAngleCommonFactorEge() {
        // sin(2x) + sin(x)*(√(3)) = 0 => x = πk and x = ±5π/6 + 2πk
        val result = TrigSolverEngine.solve("sin(2x) + sin(x)*(√(3)) = 0", casEngine = casEngine)
        assertTrue(result.generalSolutions.isNotEmpty())
        val allFormulas = result.generalSolutions.joinToString("; ") { it.displayFormula }
        assertTrue("Expected πk in $allFormulas", allFormulas.contains("πk"))
        assertTrue("Expected 5π/6 in $allFormulas", allFormulas.contains("5π/6"))
    }

    @Test
    fun testSinSquaredMinusTwoSin() {
        // sin(x)^2 - 2sin(x) = 0 => x = πk, k ∈ ℤ
        val result = TrigSolverEngine.solve("sin(x)^2 - 2sin(x) = 0", casEngine = casEngine)
        assertTrue(result.generalSolutions.isNotEmpty())
        val formula = result.generalSolutions.first().displayFormula
        assertEquals("x = πk, k ∈ ℤ", formula)

        val intervalResult = TrigSolverEngine.solve(
            equation = "sin(x)^2 - 2sin(x) = 0",
            intervalStartStr = "-2π",
            intervalEndStr = "-π/2",
            casEngine = casEngine
        )
        val roots = intervalResult.rootsOnInterval
        assertEquals(2, roots.size)
        assertEquals("-2π", roots[0].symbolic)
        assertEquals("-π", roots[1].symbolic)
    }

    @Test
    fun testCosIntervalRoots() {
        // 2cos(x) - 1 = 0 на [0, 2π] => x = ±π/3 + 2πk, k ∈ ℤ; корни: π/3, 5π/3
        val result = TrigSolverEngine.solve(
            equation = "2cos(x) - 1 = 0",
            intervalStartStr = "0",
            intervalEndStr = "2π",
            casEngine = casEngine
        )
        assertTrue(result.generalSolutions.isNotEmpty())
        val formula = result.generalSolutions.first().displayFormula
        assertEquals("x = ±π/3 + 2πk, k ∈ ℤ", formula)

        val roots = result.rootsOnInterval
        assertEquals(2, roots.size)
        assertEquals("π/3", roots[0].symbolic)
        assertEquals("5π/3", roots[1].symbolic)
    }

    @Test
    fun testSinElementary() {
        val result = TrigSolverEngine.solve("sin(x) = 1/2", casEngine = casEngine)
        assertTrue(result.generalSolutions.isNotEmpty())
        val formula = result.generalSolutions.first().displayFormula
        assertTrue(formula.contains("π/6"))
        assertTrue(formula.contains("5π/6"))
        assertTrue(formula.contains("2πk"))
    }

    @Test
    fun testTrigReductionMixed() {
        val result = TrigSolverEngine.solve("2cos(x)^2 - sin(x) - 1 = 0", casEngine = casEngine)
        assertTrue(result.generalSolutions.isNotEmpty())
        val allFormulas = result.generalSolutions.joinToString("; ") { it.displayFormula }
        assertTrue(allFormulas.contains("π/6") || allFormulas.contains("-π/2"))
    }

    @Test
    fun testDoubleAngleExpansion() {
        val result = TrigSolverEngine.solve("sin(2x) = cos(x)", casEngine = casEngine)
        assertTrue(result.generalSolutions.isNotEmpty())
        val allFormulas = result.generalSolutions.joinToString("; ") { it.displayFormula }
        assertTrue(allFormulas.contains("π/2"))
        assertTrue(allFormulas.contains("π/6"))
    }

    @Test
    fun testTanElementary() {
        val result = TrigSolverEngine.solve("tan(x) = sqrt(3)", casEngine = casEngine)
        assertTrue(result.generalSolutions.isNotEmpty())
        val formula = result.generalSolutions.first().displayFormula
        assertEquals("x = π/3 + πk, k ∈ ℤ", formula)
    }

    @Test
    fun testCosNegativeHalf() {
        val result = TrigSolverEngine.solve("cos(x) = -1/2", casEngine = casEngine)
        assertTrue(result.generalSolutions.isNotEmpty())
        val formula = result.generalSolutions.first().displayFormula
        assertEquals("x = ±2π/3 + 2πk, k ∈ ℤ", formula)
    }

    @Test
    fun testCommonFactorSinWithSqrt() {
        val result = TrigSolverEngine.solve(
            equation = "sin(2x) + sin(x)*(√(3)) = 0",
            intervalStartStr = "-2π",
            intervalEndStr = "-π/2",
            casEngine = casEngine
        )
        assertTrue(result.generalSolutions.isNotEmpty())
        val allFormulas = result.generalSolutions.joinToString("; ") { it.displayFormula }
        assertTrue("Expected πk in $allFormulas", allFormulas.contains("πk"))
        assertTrue("Expected 5π/6 in $allFormulas", allFormulas.contains("5π/6"))

        val roots = result.rootsOnInterval
        assertEquals(4, roots.size)
        assertEquals("-2π", roots[0].symbolic)
        assertEquals("-7π/6", roots[1].symbolic)
        assertEquals("-π", roots[2].symbolic)
        assertEquals("-5π/6", roots[3].symbolic)
    }

    @Test
    fun testCasEngineDelegation() {
        val res = casEngine.isolateVariableFull("sin(x)^2 - 2sin(x) = 0", "x")
        assertEquals("x = πk, k ∈ ℤ", res.displayFormula)

        val resCos = casEngine.isolateVariableFull("2cos(x) - 1 = 0", "x")
        assertEquals("x = ±π/3 + 2πk, k ∈ ℤ", resCos.displayFormula)
    }

    @Test
    fun testDetailedStepsSinSquaredMinusTwoSin() {
        val result = TrigSolverEngine.solve(
            equation = "sin(x)^2 - 2sin(x) = 0",
            intervalStartStr = "-2π",
            intervalEndStr = "-π/2",
            casEngine = casEngine
        )

        assertTrue("detailedSteps must not be empty", result.detailedSteps.isNotEmpty())

        // Verify "ОДЗ" section is absent
        val odzSteps = result.detailedSteps.filter { it.section == "ОДЗ" }
        assertTrue("ОДЗ section must be absent for polynomial sin eq", odzSteps.isEmpty())

        // Verify "Пункт а" mentions sin(x) = 2 has no solutions
        val partASteps = result.detailedSteps.filter { it.section == "Пункт а" }
        val noSolStep = partASteps.find {
            (it.formula.contains("sin(x) = 2") || it.explanation.contains("sin(x) = 2")) &&
                    (it.explanation.contains("не имеет") || it.formula.contains("∅"))
        }
        assertNotNull("Expected step stating sin(x) = 2 has no real solutions", noSolStep)

        // Verify "Пункт б" finds k = -2, -1 giving -2π and -π
        val partBSteps = result.detailedSteps.filter { it.section == "Пункт б" }
        assertTrue("Part б steps must be present", partBSteps.isNotEmpty())
        val stepB = partBSteps.find {
            (it.formula.contains("k ∈ {-2, -1}") || it.formula.contains("-2 ≤ k ≤ -0.5") || it.explanation.contains("k = -2")) &&
                    it.explanation.contains("-2π") && it.explanation.contains("-π")
        }
        assertNotNull("Expected Part б step with k = -2, -1 and roots -2π, -π", stepB)
    }

    @Test
    fun testDetailedStepsCotOdzAndRejection() {
        val result = TrigSolverEngine.solve(
            equation = "cot(x) - sin(x) - sqrt(3)*cos(x) + 1/sin(x) = 0",
            casEngine = casEngine
        )

        assertTrue("detailedSteps must not be empty", result.detailedSteps.isNotEmpty())

        // Verify "ОДЗ" step sin(x) ≠ 0
        val odzStep = result.detailedSteps.find {
            it.section == "ОДЗ" && it.formula.contains("sin(x) ≠ 0")
        }
        assertNotNull("Expected ОДЗ step with sin(x) ≠ 0", odzStep)

        // Verify rejection of x = π
        val rejectionStep = result.detailedSteps.find {
            (it.formula.contains("x = π") || it.explanation.contains("x = π")) &&
                    (it.explanation.contains("ОДЗ") || it.explanation.contains("посторонний") || it.explanation.contains("исключа"))
        }
        assertNotNull("Expected step showing rejection of x = π by ODZ", rejectionStep)
    }

    @Test
    fun testTanCotReciprocalEquation() {
        val result = TrigSolverEngine.solve(
            equation = "((1)/(tan(x)))+((1)/(ctg(x)))-2 * ctg(x)=2",
            casEngine = casEngine
        )
        assertTrue("generalSolutions must be non-empty", result.generalSolutions.isNotEmpty())
        assertNull("errorMessage must be null", result.errorMessage)

        val intervalRes1 = TrigSolverEngine.solve(
            equation = "((1)/(tan(x)))+((1)/(ctg(x)))-2 * ctg(x)=2",
            intervalStartStr = "0",
            intervalEndStr = "π",
            casEngine = casEngine
        )
        val roots1 = intervalRes1.rootsOnInterval
        assertEquals(2, roots1.size)
        assertEquals("3π/8", roots1[0].symbolic)
        assertEquals("7π/8", roots1[1].symbolic)

        val intervalRes2 = TrigSolverEngine.solve(
            equation = "((1)/(tan(x)))+((1)/(ctg(x)))-2 * ctg(x)=2",
            intervalStartStr = "π/2",
            intervalEndStr = "π",
            casEngine = casEngine
        )
        val roots2 = intervalRes2.rootsOnInterval
        assertEquals(1, roots2.size)
        assertEquals("7π/8", roots2[0].symbolic)
    }

    @Test
    fun testReductionCotDoubleAngleOdz() {
        val result = TrigSolverEngine.solve(
            equation = "1 + ctg(2x) = 1 / cos(3π/2 - 2x)",
            casEngine = casEngine
        )
        assertTrue("generalSolutions must be non-empty", result.generalSolutions.isNotEmpty())
        assertNull("errorMessage must be null", result.errorMessage)

        val allFormulas = result.generalSolutions.joinToString("; ") { it.displayFormula }
        assertTrue(
            "Expected -π/4 or 3π/4 in generalSolutions: $allFormulas",
            allFormulas.contains("-π/4") || allFormulas.contains("3π/4") || allFormulas.contains("7π/4")
        )
        assertFalse(
            "Extraneous root π/2 must be excluded by ODZ",
            allFormulas.contains("π/2")
        )
    }
}
