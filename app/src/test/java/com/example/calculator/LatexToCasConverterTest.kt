package com.example.calculator

import org.junit.Assert.*
import org.junit.Test

class LatexToCasConverterTest {

    private val casEngine = CasEngine().apply { isDegreeMode = false }

    @Test
    fun test1RationalInequality() {
        val raw = """\frac{x - 2}{x + 1} \leqslant 0"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.INEQUALITY, converted.recommendedTarget)
        val result = InequalitySolverEngine.solve(converted.casExpression, casEngine)
        assertNull(result.errorMessage)
        assertEquals("x ∈ (-1; 2]", result.answerFormula)
    }

    @Test
    fun test2ExponentialEge508573() {
        val raw = """3^{x} + 10 \cdot 3^{3 - x} \geqslant 37"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.INEQUALITY, converted.recommendedTarget)
        val result = InequalitySolverEngine.solve(converted.casExpression, casEngine)
        assertNull(result.errorMessage)
        assertEquals("x ∈ (-∞; log₃(10)] ∪ [3; +∞)", result.answerFormula)
    }

    @Test
    fun test3RationalExponentialEge() {
        val raw = """\frac{2^{x}}{2^{x} - 8} + \frac{2^{x} + 8}{2^{x} - 4} + \frac{66}{4^{x} - 12 \cdot 2^{x} + 32} \leqslant 0"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.INEQUALITY, converted.recommendedTarget)
        val result = InequalitySolverEngine.solve(converted.casExpression, casEngine)
        assertNull(result.errorMessage)
        assertEquals("x ∈ {0} ∪ (2; 3)", result.answerFormula)
    }

    @Test
    fun test4ArbitraryBaseLogDecimalComma() {
        val raw = """\log_{0,5} \frac{2x + 3}{2} + \log_{2}(x + 3) \geqslant 0"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.INEQUALITY, converted.recommendedTarget)
        assertTrue(
            "Expected converted expression to contain log((0.5),, got: ${converted.casExpression}",
            converted.casExpression.contains("log((0.5),")
        )
        assertTrue(
            "Expected converted expression to contain log((2),, got: ${converted.casExpression}",
            converted.casExpression.contains("log((2),")
        )
    }

    @Test
    fun test5ModulusFraction() {
        val raw = """\frac{\left|x^{2} + 2x - 3\right| - \left|x^{2} + 3x + 5\right|}{2x + 1} \geqslant 0"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.INEQUALITY, converted.recommendedTarget)
        val result = InequalitySolverEngine.solve(converted.casExpression, casEngine)
        assertNull("Converted expr: '${converted.casExpression}', error: ${result.errorMessage}", result.errorMessage)
        assertEquals("x ∈ [-8; -2]", result.answerFormula)
    }

    @Test
    fun test6RadicalNestedLog() {
        val raw = """\sqrt{x + \frac{2}{3}} \left(\log_{2} \log_{\frac{1}{2}} \left|1 + x\right|\right) \leqslant 0"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.INEQUALITY, converted.recommendedTarget)
        val result = InequalitySolverEngine.solve(converted.casExpression, casEngine)
        assertNull("Converted expr: '${converted.casExpression}', error: ${result.errorMessage}", result.errorMessage)
        assertEquals("x ∈ {-2/3} ∪ [-1/2; 0)", result.answerFormula)
    }

    @Test
    fun test7PeriodicTrigLogEge526809() {
        val raw = """4 \log_{4}^{2}(\sin^{3} x) + 8 \log_{2}(\sin x) \geqslant 1"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.INEQUALITY, converted.recommendedTarget)
        val result = InequalitySolverEngine.solve(converted.casExpression, casEngine)
        assertNull(result.errorMessage)
        assertTrue("Expected isPeriodic to be true", result.isPeriodic)
    }

    @Test
    fun test8TrigEquation() {
        val raw = """2\sin^{2} x - 3\sin x + 1 = 0"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.TRIG, converted.recommendedTarget)
        assertTrue(
            "Expected sin(x) in converted expression, got: ${converted.casExpression}",
            converted.casExpression.contains("sin(x)")
        )
    }

    @Test
    fun test9QuadraticEquationWithDelimitersAndDot() {
        val raw = "\$\$x^{2} - 5x + 6 = 0.\$\$"
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.CALCULATOR, converted.recommendedTarget)
        val evalRes = casEngine.evaluateFull(converted.casExpression)
        assertTrue(
            "Expected solutions 2 and 3 in displayFormula: ${evalRes.displayFormula}",
            evalRes.displayFormula.contains("2") && evalRes.displayFormula.contains("3")
        )
    }

    @Test
    fun test10PureArithmeticWithFracSqrtCdot() {
        val raw = """\frac{\sqrt{16} + 2^{3}}{3} \cdot 6"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.CALCULATOR, converted.recommendedTarget)
        val evalRes = casEngine.evaluateFull(converted.casExpression)
        assertEquals("24", evalRes.displayFormula)
    }

    @Test
    fun test11OversetArrowsQuadraticEquation() {
        val raw = """\overset { \leftarrow } { ( 2 x + 7 ) ^ { 2 } } = \overset { \rightarrow } { ( 2 x - 1 ) ^ { 2 } } ."""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.CALCULATOR, converted.recommendedTarget)
        assertFalse(
            "Expected no 'overset' in casExpression: ${converted.casExpression}",
            converted.casExpression.contains("overset")
        )
        assertFalse(
            "Expected no 'arrow' in casExpression: ${converted.casExpression}",
            converted.casExpression.contains("arrow")
        )

        val evalRes = casEngine.evaluateFull(converted.casExpression)
        assertEquals("x = -3/2", evalRes.displayFormula)
    }

    @Test
    fun test12AlignedEnvironmentAndEmptyBraces() {
        val raw = """\begin{aligned} { ( 2 x + 7 ) ^ { 2 } } & { { } = ( 2 x - 1 ) ^ { 2 } } \\ \end{aligned}"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.CALCULATOR, converted.recommendedTarget)
        assertFalse("Expected no 'begin' in casExpression: ${converted.casExpression}", converted.casExpression.contains("begin"))
        assertFalse("Expected no 'aligned' in casExpression: ${converted.casExpression}", converted.casExpression.contains("aligned"))
        assertFalse("Expected no '&' in casExpression: ${converted.casExpression}", converted.casExpression.contains("&"))

        val evalRes = casEngine.evaluateFull(converted.casExpression)
        assertEquals("x = -3/2", evalRes.displayFormula)
    }

    @Test
    fun test13OverlineRedundantBraces() {
        val raw = """\overline { { ( 2 x + 7 ) ^ { 2 } } } = \overline { { ( 2 x - 1 ) ^ { 2 } } } ."""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.CALCULATOR, converted.recommendedTarget)
        assertFalse("Expected no triple parens in casExpression: ${converted.casExpression}", converted.casExpression.contains("((("))

        val evalRes = casEngine.evaluateFull(converted.casExpression)
        assertEquals("x = -3/2", evalRes.displayFormula)
    }

    @Test
    fun test14BareTrigArguments() {
        val raw = """\sin 2 x + \sqrt { 3 } \sin x = 0 ."""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.TRIG, converted.recommendedTarget)
        assertTrue("Expected sin(2x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("sin(2x)"))
        assertTrue("Expected sin(x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("sin(x)"))
        assertFalse("Expected no sin(2) in casExpr: ${converted.casExpression}", converted.casExpression.contains("sin(2)"))
    }

    @Test
    fun test15BareTrigPowerAndFrac() {
        val raw = """\cos ^ { 2 } x - \frac { 1 } { 2 } \sin 2 x + \cos x = \sin x ."""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.TRIG, converted.recommendedTarget)
        assertTrue("Expected (cos(x))^(2) in casExpr: ${converted.casExpression}", converted.casExpression.contains("(cos(x))^(2)"))
        assertTrue("Expected sin(2x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("sin(2x)"))
    }

    @Test
    fun test16MultiRowGarbageArrayFilter() {
        val raw = """\begin{array} { l } { \tan ^ { \infty } \stackrel { \v _ { \mathrm { u } } \neq \mathcal { A } } { x } } \\ { \cos ^ { 2 } x - \frac { 1 } { 2 } \sin 2 x + \cos x = \sin x } \end{array}"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.TRIG, converted.recommendedTarget)
        assertFalse("Expected no tan or infty in casExpr: ${converted.casExpression}", converted.casExpression.contains("tan") || converted.casExpression.contains("infty"))
        assertTrue("Expected sin(2x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("sin(2x)"))
        assertEquals("(cos(x))^(2)-((1)/(2)) * sin(2x)+cos(x)=sin(x)", converted.casExpression)
    }

    @Test
    fun test17OperatorNameSinBracedArg() {
        val raw = """\operatorname { s i n } { 2 x } + \sqrt { 3 } \operatorname { s i n } { x } = 0 ."""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.TRIG, converted.recommendedTarget)
        assertTrue("Expected sin(2x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("sin(2x)"))
        assertTrue("Expected sin(x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("sin(x)"))
        assertFalse("Expected no sin(2) in casExpr: ${converted.casExpression}", converted.casExpression.contains("sin(2)"))

        val trigRes = TrigSolverEngine.solve(converted.casExpression, casEngine = casEngine)
        assertTrue("Expected generalSolutions to be non-empty", trigRes.generalSolutions.isNotEmpty())
    }

    @Test
    fun test18OperatorNameSinBareArg() {
        val raw = """\operatorname { s i n } 2 x + \sqrt { 2 } \operatorname { s i n } x = 2 \operatorname { c o s } x + \sqrt { 2 } ."""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.TRIG, converted.recommendedTarget)
        assertTrue("Expected sin(2x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("sin(2x)"))
        assertTrue("Expected sin(x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("sin(x)"))
        assertTrue("Expected cos(x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("cos(x)"))

        val trigRes = TrigSolverEngine.solve(converted.casExpression, casEngine = casEngine)
        assertTrue("Expected generalSolutions to be non-empty", trigRes.generalSolutions.isNotEmpty())
    }

    @Test
    fun test19OperatorNameCosPowerTrigEquation() {
        val raw = """\operatorname { s i n } 2 x - 2 \sqrt { 3 } \operatorname { c o s } ^ { 2 } x - 4 \operatorname { s i n } x + 4 \sqrt { 3 } \operatorname { c o s } x = 0"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.TRIG, converted.recommendedTarget)
        assertTrue("Expected sin(2x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("sin(2x)"))
        assertTrue("Expected (cos(x))^(2) in casExpr: ${converted.casExpression}", converted.casExpression.contains("(cos(x))^(2)"))
        assertTrue("Expected sin(x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("sin(x)"))
        assertTrue("Expected cos(x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("cos(x)"))

        val trigRes = TrigSolverEngine.solve(converted.casExpression, casEngine = casEngine)
        assertTrue("Expected generalSolutions to be non-empty", trigRes.generalSolutions.isNotEmpty())
    }

    @Test
    fun test20TrigReductionFractionArgument() {
        val raw = """\cos \left( { \frac { 3 \pi } { 2 } } + 2 x \right) = \cos x ."""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.TRIG, converted.recommendedTarget)
        assertTrue("Expected cos( in casExpr: ${converted.casExpression}", converted.casExpression.contains("cos("))
        assertTrue("Expected cos(x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("cos(x)"))
    }

    @Test
    fun test21StackrelLongarrow() {
        val raw = """\stackrel { \longleftarrow } { x } = 5"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals("x=5", converted.casExpression)
        assertFalse("Expected no longleftarrow in casExpr: ${converted.casExpression}", converted.casExpression.contains("longleftarrow"))
    }

    @Test
    fun test22CfracTrigEquation() {
        val raw = """1 + \mathrm { c t g } \, 2 x = \cfrac { 1 } { \operatorname { c o s } \left( \cfrac { 3 \pi } { 2 } - 2 x \right) } ."""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.TRIG, converted.recommendedTarget)
        assertFalse("Expected no cfrac in casExpr: ${converted.casExpression}", converted.casExpression.contains("cfrac"))
        assertTrue("Expected ctg(2x) in casExpr: ${converted.casExpression}", converted.casExpression.contains("ctg(2x)"))
        assertTrue("Expected cos( in casExpr: ${converted.casExpression}", converted.casExpression.contains("cos("))

        val trigRes = TrigSolverEngine.solve(converted.casExpression, casEngine = casEngine)
        assertTrue("Expected generalSolutions to be non-empty", trigRes.generalSolutions.isNotEmpty())
    }

    @Test
    fun test23NestedBoldLn() {
        val raw = """\mathbf { \operatorname { l n } ( x ^ { 2 } - 3 ) = \operatorname { l n } ( 2 x ) }"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.CALCULATOR, converted.recommendedTarget)
        assertTrue(
            "Expected casExpression to contain ln(x^(2)-3) or ln(x^(2) - 3), got: ${converted.casExpression}",
            converted.casExpression.contains("ln(x^(2)-3)") || converted.casExpression.contains("ln(x^(2) - 3)")
        )
        assertTrue(
            "Expected casExpression to contain ln(2x), got: ${converted.casExpression}",
            converted.casExpression.contains("ln(2x)")
        )
        assertFalse(
            "Expected casExpression NOT to contain ^(2-3), got: ${converted.casExpression}",
            converted.casExpression.contains("^(2-3)")
        )
        assertFalse(
            "Expected casExpression NOT to contain mathbf, got: ${converted.casExpression}",
            converted.casExpression.contains("mathbf")
        )

        val evalRes = casEngine.evaluateFull(converted.casExpression)
        assertTrue("Expected non-empty displayFormula, got: ${evalRes.displayFormula}", evalRes.displayFormula.isNotEmpty())
    }

    @Test
    fun test24NestedBoldFractionInequality() {
        val raw = """\mathbf { \frac { x ^ { 2 } - 4 } { x + 1 } \leqslant 0 }"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.INEQUALITY, converted.recommendedTarget)
        val result = InequalitySolverEngine.solve(converted.casExpression, casEngine)
        assertNull("Expected no error when solving inequality: ${converted.casExpression}, got error: ${result.errorMessage}", result.errorMessage)
    }

    @Test
    fun test25HallucinatedDecorativeSymbol() {
        val raw = """\mathbf { \operatorname { l n } ( x ^ { 2 } - 3 ) = \noclubsuit ( 2 x ) \Longrightarrow }"""
        val converted = LatexToCasConverter.convert(raw)

        assertFalse(
            "Expected casExpression NOT to contain noclubsuit, got: ${converted.casExpression}",
            converted.casExpression.contains("noclubsuit")
        )
        assertFalse(
            "Expected casExpression NOT to contain ^(2-3), got: ${converted.casExpression}",
            converted.casExpression.contains("^(2-3)")
        )
    }

    @Test
    fun test26LogMisreadOcrTypoRecovery() {
        val raw = """2 x ^ { 2 } + 4 x = 1 0 g _ { 2 } 8"""
        val converted = LatexToCasConverter.convert(raw)

        assertTrue(
            "Expected casExpression to contain log((2),(8)), got: ${converted.casExpression}",
            converted.casExpression.contains("log((2),(8))")
        )

        val rawRoots = """x ^ { 2 } + 2 x = 1 0 g _ { 2 } 8"""
        val convertedRoots = LatexToCasConverter.convert(rawRoots)
        val evalRes = casEngine.evaluateFull(convertedRoots.casExpression)
        assertTrue(
            "Expected solution to contain exact roots -3 and 1, got: ${evalRes.displayFormula}",
            evalRes.displayFormula.contains("-3") && evalRes.displayFormula.contains("1")
        )
    }

    @Test
    fun test27PlusMinusOperator() {
        val raw = """x = 1 \pm 2"""
        val converted = LatexToCasConverter.convert(raw)

        assertFalse("Expected no 'pm' in casExpression: ${converted.casExpression}", converted.casExpression.contains("pm"))
        assertTrue("Expected no warnings, got: ${converted.warnings}", converted.warnings.isEmpty())
        assertTrue("Expected ± in casExpression: ${converted.casExpression}", converted.casExpression.contains("±"))
    }

    @Test
    fun test28StripLeadingTaskLabels() {
        val raw1 = """а) \sin 2x = 0"""
        val conv1 = LatexToCasConverter.convert(raw1)
        assertEquals("sin(2x)=0", conv1.casExpression)
        assertEquals(ScanTarget.TRIG, conv1.recommendedTarget)

        val raw2 = """15. \frac{x - 2}{x + 1} \leqslant 0"""
        val conv2 = LatexToCasConverter.convert(raw2)
        assertEquals("((x-2)/(x+1)) ≤ 0", conv2.casExpression)
        assertEquals(ScanTarget.INEQUALITY, conv2.recommendedTarget)

        val raw3 = """13) 2x^{2} - 5x + 2 = 0"""
        val conv3 = LatexToCasConverter.convert(raw3)
        assertEquals("2x^(2)-5x+2=0", conv3.casExpression)
        assertEquals(ScanTarget.CALCULATOR, conv3.recommendedTarget)

        // Regression guards
        val raw4 = """15.5 + x = 20"""
        val conv4 = LatexToCasConverter.convert(raw4)
        assertTrue("Expected 15.5 in expression, got: ${conv4.casExpression}", conv4.casExpression.contains("15.5"))

        val raw5 = """1 + \mathrm{ctg}\, 2x = 0"""
        val conv5 = LatexToCasConverter.convert(raw5)
        assertEquals("1+ctg(2x)=0", conv5.casExpression)

        val raw6 = """(2x + 7)^{2} = (2x - 1)^{2}"""
        val conv6 = LatexToCasConverter.convert(raw6)
        assertEquals("(2x+7)^(2)=(2x-1)^(2)", conv6.casExpression)
    }

    @Test
    fun test29StackrelInsideNumber() {
        val raw = """x + \frac { 8 x - 4 5 } { x - 7 } + \frac { x ^ { 2 } + 1 5 x - 1 3 \stackrel { \circ } { 2 } } { x ^ { 2 } - 1 6 x + 6 3 } \leq 1 ."""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.INEQUALITY, converted.recommendedTarget)
        assertTrue(
            "Expected casExpression to contain 132, got: ${converted.casExpression}",
            converted.casExpression.contains("132")
        )
        assertFalse(
            "Expected casExpression NOT to contain 13 * 2, got: ${converted.casExpression}",
            converted.casExpression.contains("13 * 2")
        )

        val result = InequalitySolverEngine.solve(converted.casExpression, casEngine)
        assertNull("Expected no error when solving inequality: ${converted.casExpression}, got error: ${result.errorMessage}", result.errorMessage)
    }

    @Test
    fun test30EgeLogSquareDenominatorTouchingZero() {
        val raw = """\frac{9^{x} - 3^{x + 2} + 8}{\log_{\frac{1}{6}}^{2}(5^{x} - 2) + \log_{\frac{1}{6}}(5^{x} - 2)^{2} + 1} \leqslant 0"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.INEQUALITY, converted.recommendedTarget)
        assertTrue(
            "Expected casExpression to contain ((5^(x)-2)^(2)) inside second log, got: ${converted.casExpression}",
            converted.casExpression.contains("((5^(x)-2)^(2))")
        )

        val result = InequalitySolverEngine.solve(converted.casExpression, casEngine)
        assertNull("Expected no error when solving inequality: ${converted.casExpression}, got error: ${result.errorMessage}", result.errorMessage)
        assertEquals("x ∈ (log₅(2); log₅(8)) ∪ (log₅(8); log₃(8)]", result.answerFormula)
    }

    @Test
    fun test31LegacyTexFontAndSizeSwitches() {
        val raw = """{ \bf 3 } \cdot { \bf 4 } { \bf 5 } ^ { \mathrm { \scriptsize { x } } } - { \bf 3 } \cdot { \bf 2 } { \bf 7 } ^ { \mathrm { \scriptsize { x } } } - { \bf 2 8 } \cdot { \bf 1 5 } ^ { \mathrm { \scriptsize { x } } } + { \bf 2 8 } \cdot { \bf 9 } ^ { \mathrm { \scriptsize { x } } } + { \bf 9 } \cdot { \bf 5 } ^ { \mathrm { \scriptsize { x } } } - { \bf 3 } ^ { \mathrm { \scriptsize { x + 2 } } } \leq 0"""
        val converted = LatexToCasConverter.convert(raw)

        assertEquals(ScanTarget.INEQUALITY, converted.recommendedTarget)
        assertFalse("Expected casExpression NOT to contain 'bf', got: ${converted.casExpression}", converted.casExpression.contains("bf"))
        assertFalse("Expected casExpression NOT to contain 'scriptsize', got: ${converted.casExpression}", converted.casExpression.contains("scriptsize"))
        assertFalse("Expected casExpression NOT to contain '4 * 5', got: ${converted.casExpression}", converted.casExpression.contains("4 * 5"))
        assertTrue("Expected casExpression to contain '45^(x)', got: ${converted.casExpression}", converted.casExpression.contains("45^(x)"))
        assertTrue("Expected casExpression to contain '27^(x)', got: ${converted.casExpression}", converted.casExpression.contains("27^(x)"))
        assertTrue("Expected casExpression to contain '15^(x)', got: ${converted.casExpression}", converted.casExpression.contains("15^(x)"))
        assertTrue("Expected casExpression to contain '3^((x+2))' or '3^(x+2)', got: ${converted.casExpression}", converted.casExpression.contains("3^((x+2))") || converted.casExpression.contains("3^(x+2)"))

        val result = InequalitySolverEngine.solve(converted.casExpression, casEngine)
        assertNull("Expected no error when solving inequality: ${converted.casExpression}, got error: ${result.errorMessage}", result.errorMessage)
    }
}
