package com.example.calculator

import org.junit.Assert.*
import org.junit.Test

class DesmosPlotCompilerTest {

    @Test
    fun testExplicitY() {
        val p1 = DesmosPlotCompiler.compile("x^2 - 4")
        assertTrue(p1 is CompiledPlot.ExplicitY)
        val fn1 = (p1 as CompiledPlot.ExplicitY).fn
        assertEquals(0.0, fn1(2.0), 1e-6)
        assertEquals(-4.0, fn1(0.0), 1e-6)

        val p2 = DesmosPlotCompiler.compile("y = sin(x)")
        assertTrue(p2 is CompiledPlot.ExplicitY)
        val fn2 = (p2 as CompiledPlot.ExplicitY).fn
        assertEquals(0.0, fn2(0.0), 1e-6)

        val p3 = DesmosPlotCompiler.compile("f(x) = 2*x + 1")
        assertTrue(p3 is CompiledPlot.ExplicitY)
        val fn3 = (p3 as CompiledPlot.ExplicitY).fn
        assertEquals(5.0, fn3(2.0), 1e-6)
    }

    @Test
    fun testExplicitX() {
        val p1 = DesmosPlotCompiler.compile("x = y^2 - 2")
        assertTrue(p1 is CompiledPlot.ExplicitX)
        val fn1 = (p1 as CompiledPlot.ExplicitX).fn
        assertEquals(2.0, fn1(2.0), 1e-6)
        assertEquals(-2.0, fn1(0.0), 1e-6)

        val p2 = DesmosPlotCompiler.compile("g(y) = y + 5")
        assertTrue(p2 is CompiledPlot.ExplicitX)
        val fn2 = (p2 as CompiledPlot.ExplicitX).fn
        assertEquals(8.0, fn2(3.0), 1e-6)
    }

    @Test
    fun testImplicitXY() {
        val p1 = DesmosPlotCompiler.compile("x^2 + y^2 = 16")
        assertTrue(p1 is CompiledPlot.ImplicitXY)
        val fxy1 = (p1 as CompiledPlot.ImplicitXY).fxy
        assertEquals(0.0, fxy1(4.0, 0.0), 1e-6)
        assertEquals(0.0, fxy1(0.0, 4.0), 1e-6)
        assertEquals(-16.0, fxy1(0.0, 0.0), 1e-6)

        val p2 = DesmosPlotCompiler.compile("x^2 + y^2 = 0")
        assertTrue(p2 is CompiledPlot.ImplicitXY)
        val fxy2 = (p2 as CompiledPlot.ImplicitXY).fxy
        assertEquals(0.0, fxy2(0.0, 0.0), 1e-6)
    }

    @Test
    fun testInequalityXY() {
        val p1 = DesmosPlotCompiler.compile("y > x^2 - 4")
        assertTrue(p1 is CompiledPlot.InequalityXY)
        val ineq1 = p1 as CompiledPlot.InequalityXY
        assertEquals(">", ineq1.op)
        // fxy = y - (x^2 - 4). For (0, 0): 0 - (-4) = 4 > 0 -> true
        assertEquals(4.0, ineq1.fxy(0.0, 0.0), 1e-6)

        val p2 = DesmosPlotCompiler.compile("x^2 + y^2 <= 9")
        assertTrue(p2 is CompiledPlot.InequalityXY)
        val ineq2 = p2 as CompiledPlot.InequalityXY
        assertEquals("<=", ineq2.op)
        assertEquals(-9.0, ineq2.fxy(0.0, 0.0), 1e-6)
    }

    @Test
    fun testParameters() {
        val params = DesmosPlotCompiler.extractParameters("a * x^2 + b * x + d + c")
        assertTrue(params.contains("a"))
        assertTrue(params.contains("b"))
        assertTrue(params.contains("d"))
        // c - скорость света из PHYSICAL_CONSTANTS, поэтому не является свободным параметром
        assertFalse(params.contains("c"))
        assertFalse(params.contains("x"))
        assertFalse(params.contains("y"))

        val compiled = DesmosPlotCompiler.compile("a * x + b", mapOf("a" to 3.0, "b" to 4.0))
        assertTrue(compiled is CompiledPlot.ExplicitY)
        val fn = (compiled as CompiledPlot.ExplicitY).fn
        assertEquals(10.0, fn(2.0), 1e-6) // 3 * 2 + 4 = 10
    }

    @Test
    fun testAbsAndRussianLetters() {
        val p1 = DesmosPlotCompiler.compile("|x|")
        assertTrue(p1 is CompiledPlot.ExplicitY)
        val fn1 = (p1 as CompiledPlot.ExplicitY).fn
        assertEquals(3.0, fn1(-3.0), 1e-6)

        val p2 = DesmosPlotCompiler.compile("у = х^2") // русские буквы
        assertTrue(p2 is CompiledPlot.ExplicitY)
        val fn2 = (p2 as CompiledPlot.ExplicitY).fn
        assertEquals(9.0, fn2(3.0), 1e-6)
    }

    @Test
    fun testParseParamNumber() {
        assertEquals(0.05, GraphParamHelper.parseParamNumber("0.05")!!, 1e-6)
        assertEquals(0.25, GraphParamHelper.parseParamNumber("0,25")!!, 1e-6)
        assertEquals(0.25, GraphParamHelper.parseParamNumber("1/4")!!, 1e-6)
        assertEquals(Math.PI / 4, GraphParamHelper.parseParamNumber("π/4")!!, 1e-6)
        assertEquals(-2 * Math.PI, GraphParamHelper.parseParamNumber("-2π")!!, 1e-6)
        assertNull(GraphParamHelper.parseParamNumber("abc"))
    }

    @Test
    fun testSnapParamValue() {
        assertEquals(0.3, GraphParamHelper.snapParamValue(0.30000000000000004, -10.0, 10.0, 0.1), 1e-6)
        assertEquals(1.25, GraphParamHelper.snapParamValue(1.37, -10.0, 10.0, 0.25), 1e-6)
        assertEquals(1.5, GraphParamHelper.snapParamValue(1.38, -10.0, 10.0, 0.25), 1e-6)
        assertEquals(1.2345, GraphParamHelper.snapParamValue(1.2345, -10.0, 10.0, 0.0), 1e-6)
        assertEquals(5.0, GraphParamHelper.snapParamValue(15.0, -5.0, 5.0, 0.5), 1e-6)
    }

    @Test
    fun testStepParamValue() {
        assertEquals(1.05, GraphParamHelper.stepParamValue(1.0, 1, -10.0, 10.0, 0.05), 1e-6)
        assertEquals(10.0, GraphParamHelper.stepParamValue(10.0, 1, -10.0, 10.0, 0.1), 1e-6)
    }

    @Test
    fun testComputeSeekBarMaxAndProgressRoundTrip() {
        val maxSb = GraphParamHelper.computeSeekBarMax(-5.0, 5.0, 0.25)
        assertEquals(40, maxSb)
        val valAt21 = GraphParamHelper.progressToParamValue(21, maxSb, -5.0, 5.0, 0.25)
        assertEquals(0.25, valAt21, 1e-6)
        val progFor025 = GraphParamHelper.paramValueToProgress(valAt21, maxSb, -5.0, 5.0)
        assertEquals(21, progFor025)
    }
}
