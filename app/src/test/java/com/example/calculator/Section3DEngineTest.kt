package com.example.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class Section3DEngineTest {

    @Test
    fun testUnitCubeDiagonalSectionACB1() {
        val cube = Section3DEngine.getPolyhedron("cube")!!
        val pA = cube.vertices[0]   // A
        val pC = cube.vertices[2]   // C
        val pB1 = cube.vertices[5]  // B1

        val result = Section3DEngine.computeCrossSection(cube, pA, pC, pB1)

        assertTrue(result.isValid)
        assertNull(result.errorMessage)
        assertEquals(3, result.polygonVertices.size)
        assertEquals("Треугольник", result.shapeName)

        val expectedArea = sqrt(3.0) / 2.0 // ~0.8660254
        assertEquals(expectedArea, result.area, 1e-5)
    }

    @Test
    fun testUnitCubeMidSectionAA1_BB1_CC1() {
        val cube = Section3DEngine.getPolyhedron("cube")!!
        val pAA1 = cube.vertices[0].lerp(cube.vertices[4], 0.5)
        val pBB1 = cube.vertices[1].lerp(cube.vertices[5], 0.5)
        val pCC1 = cube.vertices[2].lerp(cube.vertices[6], 0.5)

        val result = Section3DEngine.computeCrossSection(cube, pAA1, pBB1, pCC1)

        assertTrue(result.isValid)
        assertNull(result.errorMessage)
        assertEquals(4, result.polygonVertices.size)
        assertEquals("Четырёхугольник", result.shapeName)
        assertEquals(1.0, result.area, 1e-5)
        assertEquals(4.0, result.perimeter, 1e-5)
    }

    @Test
    fun testUnitCubeRegularHexagonSection() {
        val cube = Section3DEngine.getPolyhedron("cube")!!
        val pAB = cube.vertices[0].lerp(cube.vertices[1], 0.5)
        val pCC1 = cube.vertices[2].lerp(cube.vertices[6], 0.5)
        val pD1A1 = cube.vertices[7].lerp(cube.vertices[4], 0.5)

        val result = Section3DEngine.computeCrossSection(cube, pAB, pCC1, pD1A1)

        assertTrue(result.isValid)
        assertNull(result.errorMessage)
        assertEquals(6, result.polygonVertices.size)
        assertEquals("Шестиугольник", result.shapeName)

        val expectedArea = (3.0 * sqrt(3.0)) / 4.0 // ~1.2990381
        assertEquals(expectedArea, result.area, 1e-5)
    }

    @Test
    fun testCollinearPointsError() {
        val cube = Section3DEngine.getPolyhedron("cube")!!
        val p1 = cube.vertices[0] // A (-0.5, -0.5, -0.5)
        val p2 = cube.vertices[1] // B (0.5, -0.5, -0.5)
        val p3 = p1.lerp(p2, 0.5) // Midpoint of AB (0.0, -0.5, -0.5)

        val result = Section3DEngine.computeCrossSection(cube, p1, p2, p3)

        assertFalse(result.isValid)
        assertEquals("Точки лежат на одной прямой", result.errorMessage)
        assertTrue(result.polygonVertices.isEmpty())
    }

    @Test
    fun testPolyhedraPresetsLoading() {
        val polyhedra = Section3DEngine.getPolyhedraPresets()
        assertEquals(6, polyhedra.size)

        val expectedIds = listOf("cube", "box", "tetrahedron", "pyramid4", "prism3", "prism6")
        expectedIds.forEach { id ->
            val poly = Section3DEngine.getPolyhedron(id)
            assertNotNull(poly)
            assertTrue(poly!!.vertices.isNotEmpty())
            assertTrue(poly.edges.isNotEmpty())
            assertTrue(poly.faces.isNotEmpty())

            val presets = Section3DEngine.getPresetsForPolyhedron(id)
            assertTrue(presets.size >= 2)
            presets.forEach { preset ->
                assertEquals(3, preset.points.size)
                val section = Section3DEngine.computeCrossSection(
                    poly,
                    preset.points[0].pos,
                    preset.points[1].pos,
                    preset.points[2].pos
                )
                assertTrue("Preset '${preset.name}' for $id should be valid", section.isValid)
            }
        }
    }

    @Test
    fun testPointOnEdge() {
        val cube = Section3DEngine.getPolyhedron("cube")!!
        val snap0 = Section3DEngine.pointOnEdge(cube, 0, 0.0)
        assertTrue(snap0.isVertex)
        assertEquals(cube.vertices[0], snap0.pos)

        val snap1 = Section3DEngine.pointOnEdge(cube, 0, 1.0)
        assertTrue(snap1.isVertex)
        assertEquals(cube.vertices[1], snap1.pos)

        val snapMid = Section3DEngine.pointOnEdge(cube, 0, 0.5)
        assertFalse(snapMid.isVertex)
        assertEquals("сер. AB", snapMid.label)
        assertEquals(cube.vertices[0].lerp(cube.vertices[1], 0.5), snapMid.pos)
    }
}
