package com.example.calculator

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Section3DEngine {

    data class Vec3(val x: Double, val y: Double, val z: Double) {
        operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
        operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
        operator fun times(scalar: Double) = Vec3(x * scalar, y * scalar, z * scalar)
        operator fun div(scalar: Double) = Vec3(x / scalar, y / scalar, z / scalar)

        fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

        fun cross(other: Vec3): Vec3 = Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x
        )

        fun length(): Double = sqrt(x * x + y * y + z * z)

        fun normalized(): Vec3 {
            val len = length()
            return if (len > 1e-12) this * (1.0 / len) else Vec3(0.0, 0.0, 0.0)
        }

        fun distanceTo(other: Vec3): Double = (this - other).length()

        fun lerp(other: Vec3, t: Double): Vec3 = this + (other - this) * t
    }

    data class Edge3D(val v1: Int, val v2: Int)
    data class Face3D(val vertexIndices: List<Int>)

    data class Polyhedron3D(
        val id: String,
        val name: String,
        val shortName: String,
        val vertices: List<Vec3>,
        val vertexLabels: List<String>,
        val edges: List<Edge3D>,
        val faces: List<Face3D>
    )

    data class SnapPoint3D(
        val id: String,
        val pos: Vec3,
        val label: String,
        val isVertex: Boolean,
        val edgeIndex: Int? = null,
        val ratio: Double = 0.0
    )

    data class CrossSectionResult(
        val polygonVertices: List<Vec3>,
        val shapeName: String,
        val area: Double,
        val perimeter: Double,
        val isValid: Boolean,
        val errorMessage: String? = null
    )

    data class SectionPreset(
        val name: String,
        val description: String,
        val points: List<SnapPoint3D>
    )

    fun getPolyhedron(id: String): Polyhedron3D? {
        return getPolyhedraPresets().find { it.id == id }
    }

    fun getPolyhedraPresets(): List<Polyhedron3D> {
        return listOf(
            createCube(),
            createBox(),
            createTetrahedron(),
            createPyramid4(),
            createPrism3(),
            createPrism6()
        )
    }

    private fun createCube(): Polyhedron3D {
        val vertices = listOf(
            Vec3(-0.5, -0.5, -0.5), // 0: A
            Vec3(0.5, -0.5, -0.5),  // 1: B
            Vec3(0.5, 0.5, -0.5),   // 2: C
            Vec3(-0.5, 0.5, -0.5),  // 3: D
            Vec3(-0.5, -0.5, 0.5),  // 4: A1
            Vec3(0.5, -0.5, 0.5),   // 5: B1
            Vec3(0.5, 0.5, 0.5),    // 6: C1
            Vec3(-0.5, 0.5, 0.5)    // 7: D1
        )
        val labels = listOf("A", "B", "C", "D", "A₁", "B₁", "C₁", "D₁")
        val edges = listOf(
            Edge3D(0, 1), Edge3D(1, 2), Edge3D(2, 3), Edge3D(3, 0), // Base ABCD
            Edge3D(4, 5), Edge3D(5, 6), Edge3D(6, 7), Edge3D(7, 4), // Top A1B1C1D1
            Edge3D(0, 4), Edge3D(1, 5), Edge3D(2, 6), Edge3D(3, 7)  // Vertical
        )
        val faces = listOf(
            Face3D(listOf(0, 3, 2, 1)), // Bottom
            Face3D(listOf(4, 5, 6, 7)), // Top
            Face3D(listOf(0, 1, 5, 4)), // Front
            Face3D(listOf(1, 2, 6, 5)), // Right
            Face3D(listOf(2, 3, 7, 6)), // Back
            Face3D(listOf(3, 0, 4, 7))  // Left
        )
        return Polyhedron3D("cube", "Куб ABCDA₁B₁C₁D₁", "Куб", vertices, labels, edges, faces)
    }

    private fun createBox(): Polyhedron3D {
        val a = 1.4
        val b = 1.0
        val c = 0.8
        val hx = a / 2.0
        val hy = b / 2.0
        val hz = c / 2.0

        val vertices = listOf(
            Vec3(-hx, -hy, -hz), // 0: A
            Vec3(hx, -hy, -hz),  // 1: B
            Vec3(hx, hy, -hz),   // 2: C
            Vec3(-hx, hy, -hz),  // 3: D
            Vec3(-hx, -hy, hz),  // 4: A1
            Vec3(hx, -hy, hz),   // 5: B1
            Vec3(hx, hy, hz),    // 6: C1
            Vec3(-hx, hy, hz)    // 7: D1
        )
        val labels = listOf("A", "B", "C", "D", "A₁", "B₁", "C₁", "D₁")
        val edges = listOf(
            Edge3D(0, 1), Edge3D(1, 2), Edge3D(2, 3), Edge3D(3, 0),
            Edge3D(4, 5), Edge3D(5, 6), Edge3D(6, 7), Edge3D(7, 4),
            Edge3D(0, 4), Edge3D(1, 5), Edge3D(2, 6), Edge3D(3, 7)
        )
        val faces = listOf(
            Face3D(listOf(0, 3, 2, 1)),
            Face3D(listOf(4, 5, 6, 7)),
            Face3D(listOf(0, 1, 5, 4)),
            Face3D(listOf(1, 2, 6, 5)),
            Face3D(listOf(2, 3, 7, 6)),
            Face3D(listOf(3, 0, 4, 7))
        )
        return Polyhedron3D("box", "Параллелепипед ABCDA₁B₁C₁D₁", "Параллелепипед", vertices, labels, edges, faces)
    }

    private fun createTetrahedron(): Polyhedron3D {
        val r = 1.0 / sqrt(3.0)
        val zBase = -0.25 * sqrt(2.0 / 3.0)
        val zApex = 0.75 * sqrt(2.0 / 3.0)

        val vertices = listOf(
            Vec3(0.0, 0.0, zApex),        // 0: S
            Vec3(0.0, -r, zBase),         // 1: A
            Vec3(0.5, r / 2.0, zBase),    // 2: B
            Vec3(-0.5, r / 2.0, zBase)    // 3: C
        )
        val labels = listOf("S", "A", "B", "C")
        val edges = listOf(
            Edge3D(0, 1), Edge3D(0, 2), Edge3D(0, 3), // Slant
            Edge3D(1, 2), Edge3D(2, 3), Edge3D(3, 1)  // Base
        )
        val faces = listOf(
            Face3D(listOf(1, 2, 3)), // Base
            Face3D(listOf(0, 1, 2)), // SAB
            Face3D(listOf(0, 2, 3)), // SBC
            Face3D(listOf(0, 3, 1))  // SCA
        )
        return Polyhedron3D("tetrahedron", "Правильный тетраэдр SABC", "Тетраэдр", vertices, labels, edges, faces)
    }

    private fun createPyramid4(): Polyhedron3D {
        val vertices = listOf(
            Vec3(0.0, 0.0, 0.5),   // 0: S
            Vec3(-0.5, -0.5, -0.5), // 1: A
            Vec3(0.5, -0.5, -0.5),  // 2: B
            Vec3(0.5, 0.5, -0.5),   // 3: C
            Vec3(-0.5, 0.5, -0.5)   // 4: D
        )
        val labels = listOf("S", "A", "B", "C", "D")
        val edges = listOf(
            Edge3D(1, 2), Edge3D(2, 3), Edge3D(3, 4), Edge3D(4, 1), // Base
            Edge3D(0, 1), Edge3D(0, 2), Edge3D(0, 3), Edge3D(0, 4)  // Slant
        )
        val faces = listOf(
            Face3D(listOf(1, 2, 3, 4)),
            Face3D(listOf(0, 1, 2)),
            Face3D(listOf(0, 2, 3)),
            Face3D(listOf(0, 3, 4)),
            Face3D(listOf(0, 4, 1))
        )
        return Polyhedron3D("pyramid4", "Правильная 4-угольная пирамида SABCD", "4-угольная пирамида", vertices, labels, edges, faces)
    }

    private fun createPrism3(): Polyhedron3D {
        val r = 1.0 / sqrt(3.0)
        val vertices = listOf(
            Vec3(0.0, -r, -0.5),        // 0: A
            Vec3(0.5, r / 2.0, -0.5),   // 1: B
            Vec3(-0.5, r / 2.0, -0.5),  // 2: C
            Vec3(0.0, -r, 0.5),         // 3: A1
            Vec3(0.5, r / 2.0, 0.5),    // 4: B1
            Vec3(-0.5, r / 2.0, 0.5)   // 5: C1
        )
        val labels = listOf("A", "B", "C", "A₁", "B₁", "C₁")
        val edges = listOf(
            Edge3D(0, 1), Edge3D(1, 2), Edge3D(2, 0), // Bottom
            Edge3D(3, 4), Edge3D(4, 5), Edge3D(5, 3), // Top
            Edge3D(0, 3), Edge3D(1, 4), Edge3D(2, 5)  // Vertical
        )
        val faces = listOf(
            Face3D(listOf(0, 2, 1)),
            Face3D(listOf(3, 4, 5)),
            Face3D(listOf(0, 1, 4, 3)),
            Face3D(listOf(1, 2, 5, 4)),
            Face3D(listOf(2, 0, 3, 5))
        )
        return Polyhedron3D("prism3", "Правильная треугольная призма ABCA₁B₁C₁", "3-угольная призма", vertices, labels, edges, faces)
    }

    private fun createPrism6(): Polyhedron3D {
        val r = 0.7
        val hz = 0.55
        val verts = mutableListOf<Vec3>()
        for (i in 0..5) {
            val angle = i * Math.PI / 3.0
            verts.add(Vec3(r * cos(angle), r * sin(angle), -hz))
        }
        for (i in 0..5) {
            val angle = i * Math.PI / 3.0
            verts.add(Vec3(r * cos(angle), r * sin(angle), hz))
        }

        val labels = listOf("A", "B", "C", "D", "E", "F", "A₁", "B₁", "C₁", "D₁", "E₁", "F₁")
        val edges = mutableListOf<Edge3D>()
        for (i in 0..5) {
            edges.add(Edge3D(i, (i + 1) % 6))
            edges.add(Edge3D(i + 6, (i + 1) % 6 + 6))
            edges.add(Edge3D(i, i + 6))
        }

        val faces = mutableListOf<Face3D>()
        faces.add(Face3D(listOf(0, 5, 4, 3, 2, 1))) // Bottom
        faces.add(Face3D(listOf(6, 7, 8, 9, 10, 11))) // Top
        for (i in 0..5) {
            faces.add(Face3D(listOf(i, (i + 1) % 6, (i + 1) % 6 + 6, i + 6)))
        }

        return Polyhedron3D("prism6", "Правильная 6-угольная призма ABCDEFA₁B₁C₁D₁E₁F₁", "6-угольная призма", verts, labels, edges, faces)
    }

    fun generateSnapPoints(poly: Polyhedron3D): List<SnapPoint3D> {
        val list = mutableListOf<SnapPoint3D>()
        poly.vertices.forEachIndexed { idx, pos ->
            val label = poly.vertexLabels[idx]
            list.add(
                SnapPoint3D(
                    id = "v_$idx",
                    pos = pos,
                    label = label,
                    isVertex = true,
                    edgeIndex = null,
                    ratio = 0.0
                )
            )
        }

        val ratios = listOf(0.25, 1.0 / 3.0, 0.5, 2.0 / 3.0, 0.75)
        poly.edges.forEachIndexed { edgeIdx, edge ->
            val v1Label = poly.vertexLabels[edge.v1]
            val v2Label = poly.vertexLabels[edge.v2]
            val p1 = poly.vertices[edge.v1]
            val p2 = poly.vertices[edge.v2]

            ratios.forEach { r ->
                val label = formatRatioLabel(v1Label, v2Label, r)
                list.add(
                    SnapPoint3D(
                        id = "e_${edgeIdx}_${(r * 1000).toInt()}",
                        pos = p1.lerp(p2, r),
                        label = label,
                        isVertex = false,
                        edgeIndex = edgeIdx,
                        ratio = r
                    )
                )
            }
        }
        return list
    }

    private fun formatRatioLabel(v1Label: String, v2Label: String, ratio: Double): String {
        return when {
            abs(ratio - 0.5) < 1e-4 -> "сер. $v1Label$v2Label"
            abs(ratio - 0.25) < 1e-4 -> "1/4 $v1Label$v2Label"
            abs(ratio - 1.0 / 3.0) < 1e-4 -> "1/3 $v1Label$v2Label"
            abs(ratio - 2.0 / 3.0) < 1e-4 -> "2/3 $v1Label$v2Label"
            abs(ratio - 0.75) < 1e-4 -> "3/4 $v1Label$v2Label"
            else -> "${String.format(Locale.US, "%.2f", ratio)} $v1Label$v2Label"
        }
    }

    fun pointOnEdge(poly: Polyhedron3D, edgeIndex: Int, ratio: Double): SnapPoint3D {
        val clampedRatio = ratio.coerceIn(0.0, 1.0)
        val edge = poly.edges[edgeIndex]
        val p1 = poly.vertices[edge.v1]
        val p2 = poly.vertices[edge.v2]
        val v1Label = poly.vertexLabels[edge.v1]
        val v2Label = poly.vertexLabels[edge.v2]

        return if (clampedRatio == 0.0) {
            SnapPoint3D(
                id = "v_${edge.v1}",
                pos = p1,
                label = v1Label,
                isVertex = true,
                edgeIndex = edgeIndex,
                ratio = 0.0
            )
        } else if (clampedRatio == 1.0) {
            SnapPoint3D(
                id = "v_${edge.v2}",
                pos = p2,
                label = v2Label,
                isVertex = true,
                edgeIndex = edgeIndex,
                ratio = 1.0
            )
        } else {
            val label = formatRatioLabel(v1Label, v2Label, clampedRatio)
            SnapPoint3D(
                id = "e_${edgeIndex}_${(clampedRatio * 1000).toInt()}",
                pos = p1.lerp(p2, clampedRatio),
                label = label,
                isVertex = false,
                edgeIndex = edgeIndex,
                ratio = clampedRatio
            )
        }
    }

    fun computeCrossSection(
        poly: Polyhedron3D,
        p1: Vec3,
        p2: Vec3,
        p3: Vec3
    ): CrossSectionResult {
        val v1 = p2 - p1
        val v2 = p3 - p1
        val n = v1.cross(v2)
        if (n.length() < 1e-7) {
            return CrossSectionResult(
                polygonVertices = emptyList(),
                shapeName = "",
                area = 0.0,
                perimeter = 0.0,
                isValid = false,
                errorMessage = "Точки лежат на одной прямой"
            )
        }

        val f = { X: Vec3 -> n.dot(X - p1) }
        val rawPoints = mutableListOf<Vec3>()

        poly.edges.forEach { edge ->
            val A = poly.vertices[edge.v1]
            val B = poly.vertices[edge.v2]
            val fA = f(A)
            val fB = f(B)

            if (abs(fA) < 1e-7) {
                rawPoints.add(A)
            }
            if (abs(fB) < 1e-7) {
                rawPoints.add(B)
            }
            if (fA * fB < -1e-12) {
                val t = -fA / (fB - fA)
                if (t in 0.0..1.0) {
                    rawPoints.add(A + (B - A) * t)
                }
            }
        }

        val uniquePoints = mutableListOf<Vec3>()
        for (pt in rawPoints) {
            if (uniquePoints.none { it.distanceTo(pt) < 1e-5 }) {
                uniquePoints.add(pt)
            }
        }

        if (uniquePoints.size < 3) {
            return CrossSectionResult(
                polygonVertices = emptyList(),
                shapeName = "",
                area = 0.0,
                perimeter = 0.0,
                isValid = false,
                errorMessage = "Плоскость касается только грани/ребра"
            )
        }

        val nNorm = n.normalized()
        val centroid = Vec3(
            uniquePoints.sumOf { it.x } / uniquePoints.size,
            uniquePoints.sumOf { it.y } / uniquePoints.size,
            uniquePoints.sumOf { it.z } / uniquePoints.size
        )

        val temp = if (abs(nNorm.x) < 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val u = nNorm.cross(temp).normalized()
        val v = nNorm.cross(u)

        val sortedPoints = uniquePoints.sortedBy { pt ->
            val d = pt - centroid
            val uCoord = d.dot(u)
            val vCoord = d.dot(v)
            atan2(vCoord, uCoord)
        }

        val count = sortedPoints.size
        var perimeter = 0.0
        for (i in 0 until count) {
            val current = sortedPoints[i]
            val next = sortedPoints[(i + 1) % count]
            perimeter += current.distanceTo(next)
        }

        var areaVector = Vec3(0.0, 0.0, 0.0)
        val v0 = sortedPoints[0]
        for (i in 1 until count - 1) {
            val vi = sortedPoints[i] - v0
            val viNext = sortedPoints[i + 1] - v0
            areaVector = areaVector + vi.cross(viNext)
        }
        val area = 0.5 * areaVector.length()

        val shapeName = when (count) {
            3 -> "Треугольник"
            4 -> "Четырёхугольник"
            5 -> "Пятиугольник"
            6 -> "Шестиугольник"
            else -> "Многоугольник (${count}-уг.)"
        }

        return CrossSectionResult(
            polygonVertices = sortedPoints,
            shapeName = shapeName,
            area = area,
            perimeter = perimeter,
            isValid = true,
            errorMessage = null
        )
    }

    fun getPresetsForPolyhedron(polyId: String): List<SectionPreset> {
        val poly = getPolyhedron(polyId) ?: return emptyList()
        val allSnaps = generateSnapPoints(poly)

        fun findVertexSnap(label: String): SnapPoint3D {
            return allSnaps.first { it.isVertex && it.label == label }
        }

        fun findEdgeSnap(v1Label: String, v2Label: String, ratio: Double): SnapPoint3D {
            val idx1 = poly.vertexLabels.indexOf(v1Label)
            val idx2 = poly.vertexLabels.indexOf(v2Label)
            val edgeIdx = poly.edges.indexOfFirst {
                (it.v1 == idx1 && it.v2 == idx2) || (it.v1 == idx2 && it.v2 == idx1)
            }
            val edge = poly.edges[edgeIdx]
            val actualRatio = if (edge.v1 == idx1) ratio else (1.0 - ratio)
            return pointOnEdge(poly, edgeIdx, actualRatio)
        }

        return when (polyId) {
            "cube" -> listOf(
                SectionPreset(
                    name = "Правильный шестиугольник (через середины рёбер)",
                    description = "Сечение через середины AB, BC, CC₁, C₁D₁, D₁A₁, A₁A",
                    points = listOf(
                        findEdgeSnap("A", "B", 0.5),
                        findEdgeSnap("C", "C₁", 0.5),
                        findEdgeSnap("D₁", "A₁", 0.5)
                    )
                ),
                SectionPreset(
                    name = "Диагональное сечение AC B₁",
                    description = "Сечение через вершины A, C и B₁",
                    points = listOf(
                        findVertexSnap("A"),
                        findVertexSnap("C"),
                        findVertexSnap("B₁")
                    )
                ),
                SectionPreset(
                    name = "Сечение через A, C₁ и сер. BB₁",
                    description = "Сечение через вершину A, вершину C₁ и середину BB₁",
                    points = listOf(
                        findVertexSnap("A"),
                        findVertexSnap("C₁"),
                        findEdgeSnap("B", "B₁", 0.5)
                    )
                )
            )
            "box" -> listOf(
                SectionPreset(
                    name = "Диагональное сечение AC B₁",
                    description = "Сечение через вершины A, C и B₁",
                    points = listOf(
                        findVertexSnap("A"),
                        findVertexSnap("C"),
                        findVertexSnap("B₁")
                    )
                ),
                SectionPreset(
                    name = "Параллельное среднее сечение",
                    description = "Сечение через середины рёбер AA₁, BB₁, CC₁",
                    points = listOf(
                        findEdgeSnap("A", "A₁", 0.5),
                        findEdgeSnap("B", "B₁", 0.5),
                        findEdgeSnap("C", "C₁", 0.5)
                    )
                ),
                SectionPreset(
                    name = "Сечение через A, B и сер. C₁D₁",
                    description = "Сечение через рёбра A, B и середину C₁D₁",
                    points = listOf(
                        findVertexSnap("A"),
                        findVertexSnap("B"),
                        findEdgeSnap("C₁", "D₁", 0.5)
                    )
                )
            )
            "tetrahedron" -> listOf(
                SectionPreset(
                    name = "Медианное сечение S, A и сер. BC",
                    description = "Проходит через вершины S, A и середину ребра BC",
                    points = listOf(
                        findVertexSnap("S"),
                        findVertexSnap("A"),
                        findEdgeSnap("B", "C", 0.5)
                    )
                ),
                SectionPreset(
                    name = "Сечение, параллельное основанию",
                    description = "Сечение через середины рёбер SA, SB, SC",
                    points = listOf(
                        findEdgeSnap("S", "A", 0.5),
                        findEdgeSnap("S", "B", 0.5),
                        findEdgeSnap("S", "C", 0.5)
                    )
                ),
                SectionPreset(
                    name = "Сечение через A, B и сер. SC",
                    description = "Проходит через вершину A, вершину B и середину SC",
                    points = listOf(
                        findVertexSnap("A"),
                        findVertexSnap("B"),
                        findEdgeSnap("S", "C", 0.5)
                    )
                )
            )
            "pyramid4" -> listOf(
                SectionPreset(
                    name = "Диагональное сечение SAC",
                    description = "Проходит через вершину S и диагональ основания AC",
                    points = listOf(
                        findVertexSnap("S"),
                        findVertexSnap("A"),
                        findVertexSnap("C")
                    )
                ),
                SectionPreset(
                    name = "Сечение через вершину и середины оснований",
                    description = "Проходит через S, середину AB и середину CD",
                    points = listOf(
                        findVertexSnap("S"),
                        findEdgeSnap("A", "B", 0.5),
                        findEdgeSnap("C", "D", 0.5)
                    )
                ),
                SectionPreset(
                    name = "Сечение через A, B и сер. SC",
                    description = "Проходит через ребро основания AB и середину SC",
                    points = listOf(
                        findVertexSnap("A"),
                        findVertexSnap("B"),
                        findEdgeSnap("S", "C", 0.5)
                    )
                )
            )
            "prism3" -> listOf(
                SectionPreset(
                    name = "Диагональное сечение AB₁C₁",
                    description = "Проходит через вершину A и верхние вершины B₁, C₁",
                    points = listOf(
                        findVertexSnap("A"),
                        findVertexSnap("B₁"),
                        findVertexSnap("C₁")
                    )
                ),
                SectionPreset(
                    name = "Поперечное среднее сечение",
                    description = "Сечение через середины боковых рёбер AA₁, BB₁, CC₁",
                    points = listOf(
                        findEdgeSnap("A", "A₁", 0.5),
                        findEdgeSnap("B", "B₁", 0.5),
                        findEdgeSnap("C", "C₁", 0.5)
                    )
                ),
                SectionPreset(
                    name = "Сечение через A, B и сер. CC₁",
                    description = "Проходит через ребро основания AB и середину CC₁",
                    points = listOf(
                        findVertexSnap("A"),
                        findVertexSnap("B"),
                        findEdgeSnap("C", "C₁", 0.5)
                    )
                )
            )
            "prism6" -> listOf(
                SectionPreset(
                    name = "Главное диагональное сечение ADA₁",
                    description = "Проходит через противоположные вершины A, D и A₁",
                    points = listOf(
                        findVertexSnap("A"),
                        findVertexSnap("D"),
                        findVertexSnap("A₁")
                    )
                ),
                SectionPreset(
                    name = "Поперечное среднее сечение",
                    description = "Сечение через середины боковых рёбер AA₁, BB₁, CC₁",
                    points = listOf(
                        findEdgeSnap("A", "A₁", 0.5),
                        findEdgeSnap("B", "B₁", 0.5),
                        findEdgeSnap("C", "C₁", 0.5)
                    )
                ),
                SectionPreset(
                    name = "Сечение через A, C и B₁",
                    description = "Сечение через вершины A, C основания и B₁",
                    points = listOf(
                        findVertexSnap("A"),
                        findVertexSnap("C"),
                        findVertexSnap("B₁")
                    )
                )
            )
            else -> emptyList()
        }
    }
}
