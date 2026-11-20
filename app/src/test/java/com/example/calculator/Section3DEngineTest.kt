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

    @Test
    fun testEveryPolyhedronOutwardFacesAndTwoAdjacentFacesPerEdge() {
        val polyhedra = Section3DEngine.getPolyhedraPresets()
        assertEquals(6, polyhedra.size)

        for (poly in polyhedra) {
            val polyCenter = Section3DEngine.Vec3(
                poly.vertices.sumOf { it.x } / poly.vertices.size,
                poly.vertices.sumOf { it.y } / poly.vertices.size,
                poly.vertices.sumOf { it.z } / poly.vertices.size
            )

            poly.faces.forEachIndexed { fIdx, face ->
                val v0 = poly.vertices[face.vertexIndices[0]]
                val v1 = poly.vertices[face.vertexIndices[1]]
                val v2 = poly.vertices[face.vertexIndices[2]]
                val normal = (v1 - v0).cross(v2 - v0)

                val faceCenter = Section3DEngine.Vec3(
                    face.vertexIndices.sumOf { poly.vertices[it].x } / face.vertexIndices.size,
                    face.vertexIndices.sumOf { poly.vertices[it].y } / face.vertexIndices.size,
                    face.vertexIndices.sumOf { poly.vertices[it].z } / face.vertexIndices.size
                )
                val dot = normal.dot(faceCenter - polyCenter)
                assertTrue("Face $fIdx of ${poly.id} must point outward (dot = $dot)", dot > 1e-6)
            }

            poly.edges.forEachIndexed { eIdx, edge ->
                val adjacent = Section3DEngine.getAdjacentFacesForEdge(poly, edge)
                assertEquals("Edge $eIdx (${edge.v1}-${edge.v2}) of ${poly.id} must have 2 adjacent faces", 2, adjacent.size)
            }
        }
    }

    @Test
    fun testPrism3EdgeVisibilityAndRotation() {
        val prism = Section3DEngine.getPolyhedron("prism3")!!

        val yaw1 = -120.0
        val pitch = 25.0

        val visFaces1 = BooleanArray(prism.faces.size) { fIdx ->
            Section3DEngine.isFaceFrontFacing(prism.faces[fIdx], prism.vertices, yaw1, pitch)
        }

        val topFaceIdx = prism.faces.indexOfFirst { it.vertexIndices.containsAll(listOf(3, 4, 5)) }
        val frontFace1Idx = prism.faces.indexOfFirst { it.vertexIndices.containsAll(listOf(0, 1, 4, 3)) }
        val frontFace2Idx = prism.faces.indexOfFirst { it.vertexIndices.containsAll(listOf(1, 2, 5, 4)) }
        val backFaceIdx = prism.faces.indexOfFirst { it.vertexIndices.containsAll(listOf(2, 0, 3, 5)) }
        val bottomFaceIdx = prism.faces.indexOfFirst { it.vertexIndices.containsAll(listOf(0, 2, 1)) }

        assertTrue("Top face A1B1C1 must be front-facing", visFaces1[topFaceIdx])
        assertTrue("Side face ABB1A1 must be front-facing", visFaces1[frontFace1Idx])
        assertTrue("Side face BCC1B1 must be front-facing", visFaces1[frontFace2Idx])
        assertFalse("Back face ACC1A1 must be back-facing", visFaces1[backFaceIdx])
        assertFalse("Bottom face ABC must be back-facing", visFaces1[bottomFaceIdx])

        val edgeAB = prism.edges.first { (it.v1 == 0 && it.v2 == 1) || (it.v1 == 1 && it.v2 == 0) }
        val edgeBC = prism.edges.first { (it.v1 == 1 && it.v2 == 2) || (it.v1 == 2 && it.v2 == 1) }
        val edgeBB1 = prism.edges.first { (it.v1 == 1 && it.v2 == 4) || (it.v1 == 4 && it.v2 == 1) }
        val edgeAC = prism.edges.first { (it.v1 == 0 && it.v2 == 2) || (it.v1 == 2 && it.v2 == 0) }

        assertTrue("Edge AB must be solid", Section3DEngine.isEdgeVisible(prism, edgeAB, visFaces1))
        assertTrue("Edge BC must be solid", Section3DEngine.isEdgeVisible(prism, edgeBC, visFaces1))
        assertTrue("Edge BB1 must be solid", Section3DEngine.isEdgeVisible(prism, edgeBB1, visFaces1))
        assertFalse("Edge AC must be dashed", Section3DEngine.isEdgeVisible(prism, edgeAC, visFaces1))

        val yaw2 = 60.0
        val visFaces2 = BooleanArray(prism.faces.size) { fIdx ->
            Section3DEngine.isFaceFrontFacing(prism.faces[fIdx], prism.vertices, yaw2, pitch)
        }

        assertTrue("After 180deg rotation, edge AC must be solid", Section3DEngine.isEdgeVisible(prism, edgeAC, visFaces2))
        assertFalse("After 180deg rotation, edge BB1 must be dashed", Section3DEngine.isEdgeVisible(prism, edgeBB1, visFaces2))
    }
}
