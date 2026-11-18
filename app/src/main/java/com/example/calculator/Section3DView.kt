package com.example.calculator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class Section3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var polyhedron: Section3DEngine.Polyhedron3D = Section3DEngine.getPolyhedraPresets().first()
        set(value) {
            field = value
            selectedPoints.clear()
            activePointIndex = -1
            crossSectionResult = null
            onSelectionChanged?.invoke(selectedPoints, activePointIndex, crossSectionResult)
            invalidate()
        }

    val selectedPoints: MutableList<Section3DEngine.SnapPoint3D> = mutableListOf()
    var activePointIndex: Int = -1
    var crossSectionResult: Section3DEngine.CrossSectionResult? = null
        private set

    var isDarkTheme: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var cameraYaw: Float = -35f
    var cameraPitch: Float = 25f
    var zoomScale: Float = 1.0f

    var onSelectionChanged: ((List<Section3DEngine.SnapPoint3D>, Int, Section3DEngine.CrossSectionResult?) -> Unit)? = null

    // Touch gesture state
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var wasScaling = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomScale = (zoomScale * detector.scaleFactor).coerceIn(0.5f, 3.0f)
            wasScaling = true
            invalidate()
            return true
        }
    })

    // Reusable Paints & Path
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1f)
    }
    private val xAxisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
    }
    private val yAxisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
    }
    private val zAxisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(11f)
        isFakeBoldText = true
    }
    private val solidEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val dashedEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.4f)
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dpToPx(6f), dpToPx(4f)), 0f)
    }
    private val vertexLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(14f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val snapVertexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val snapMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val snapSubdivPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sectionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0284C7")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.8f)
        strokeJoin = Paint.Join.ROUND
    }
    private val sectionNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.FILL
    }
    private val sectionNodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.5f)
    }
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0284C7")
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = spToPx(12f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val activeHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.2f)
    }

    private val polyPath = Path()

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.scaledDensity

    fun setSelectedPoints(points: List<Section3DEngine.SnapPoint3D>, activeIndex: Int = -1) {
        selectedPoints.clear()
        selectedPoints.addAll(points.take(3))
        activePointIndex = if (activeIndex in 0 until selectedPoints.size) {
            activeIndex
        } else {
            if (selectedPoints.isNotEmpty()) selectedPoints.size - 1 else -1
        }
        recomputeCrossSection()
        onSelectionChanged?.invoke(selectedPoints, activePointIndex, crossSectionResult)
        invalidate()
    }

    fun updateActivePointRatio(ratio: Double) {
        if (activePointIndex in 0 until selectedPoints.size) {
            val current = selectedPoints[activePointIndex]
            val edgeIdx = current.edgeIndex
            if (edgeIdx != null && edgeIdx in 0 until polyhedron.edges.size) {
                val updated = Section3DEngine.pointOnEdge(polyhedron, edgeIdx, ratio)
                selectedPoints[activePointIndex] = updated
                recomputeCrossSection()
                onSelectionChanged?.invoke(selectedPoints, activePointIndex, crossSectionResult)
                invalidate()
            }
        }
    }

    fun clearSelection() {
        selectedPoints.clear()
        activePointIndex = -1
        crossSectionResult = null
        onSelectionChanged?.invoke(selectedPoints, activePointIndex, crossSectionResult)
        invalidate()
    }

    private fun recomputeCrossSection() {
        if (selectedPoints.size == 3) {
            crossSectionResult = Section3DEngine.computeCrossSection(
                polyhedron,
                selectedPoints[0].pos,
                selectedPoints[1].pos,
                selectedPoints[2].pos
            )
        } else {
            crossSectionResult = null
        }
    }

    private data class ProjectedPoint(val x: Float, val y: Float, val zDepth: Double)

    private fun project(v: Section3DEngine.Vec3): ProjectedPoint {
        val yawRad = Math.toRadians(cameraYaw.toDouble())
        val pitchRad = Math.toRadians(cameraPitch.toDouble())

        val cosY = cos(yawRad)
        val sinY = sin(yawRad)
        val cosP = cos(pitchRad)
        val sinP = sin(pitchRad)

        val x1 = v.x * cosY - v.y * sinY
        val y1 = v.x * sinY + v.y * cosY
        val z1 = v.z

        val x2 = x1
        val y2 = y1 * cosP - z1 * sinP
        val z2 = y1 * sinP + z1 * cosP

        val dist = 4.0
        val perspectiveFactor = dist / (dist + y2)

        val scale = min(width.toFloat(), height.toFloat()) * 0.38f * zoomScale
        val screenX = width / 2f + (x2 * perspectiveFactor * scale).toFloat()
        val screenY = height / 2f - (z2 * perspectiveFactor * scale).toFloat()

        return ProjectedPoint(screenX, screenY, y2)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        if (event.pointerCount > 1) {
            wasScaling = true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                wasScaling = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && !wasScaling && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    val distFromDown = hypot(event.x - downX, event.y - downY)
                    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

                    if (distFromDown > touchSlop) {
                        isDragging = true
                    }

                    if (isDragging) {
                        cameraYaw += dx * 0.4f
                        cameraPitch = (cameraPitch + dy * 0.4f).coerceIn(-85f, 85f)
                        invalidate()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging && !wasScaling) {
                    performClick()
                    handleTap(event.x, event.y)
                }
                isDragging = false
                wasScaling = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                wasScaling = true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleTap(tapX: Float, tapY: Float) {
        val snapPoints = Section3DEngine.generateSnapPoints(polyhedron)
        val maxDistPx = dpToPx(26f)

        var closestSnap: Section3DEngine.SnapPoint3D? = null
        var minDist = Float.MAX_VALUE

        for (sp in snapPoints) {
            val proj = project(sp.pos)
            val dist = hypot(proj.x - tapX, proj.y - tapY)
            if (dist < minDist) {
                minDist = dist
                closestSnap = sp
            }
        }

        if (closestSnap != null && minDist <= maxDistPx) {
            val target = closestSnap
            val existingIndex = selectedPoints.indexOfFirst {
                it.id == target.id || it.pos.distanceTo(target.pos) < 1e-4
            }

            if (existingIndex != -1) {
                activePointIndex = existingIndex
            } else {
                if (selectedPoints.size < 3) {
                    selectedPoints.add(target)
                    activePointIndex = selectedPoints.size - 1
                } else {
                    val replaceIdx = if (activePointIndex in 0..2) activePointIndex else 0
                    selectedPoints[replaceIdx] = target
                    activePointIndex = replaceIdx
                }
            }

            recomputeCrossSection()
            onSelectionChanged?.invoke(selectedPoints, activePointIndex, crossSectionResult)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Color theme configuration
        if (isDarkTheme) {
            bgPaint.color = Color.parseColor("#0F172A")
            gridPaint.color = Color.parseColor("#334155")
            solidEdgePaint.color = Color.parseColor("#F8FAFC")
            dashedEdgePaint.color = Color.parseColor("#64748B")
            vertexLabelPaint.color = Color.parseColor("#F8FAFC")
            snapVertexPaint.color = Color.parseColor("#38BDF8")
            snapMidPaint.color = Color.argb(180, 96, 165, 250)
            snapSubdivPaint.color = Color.argb(140, 148, 163, 184)
            sectionFillPaint.color = Color.argb(85, 56, 189, 248)
            sectionBorderPaint.color = Color.parseColor("#38BDF8")
        } else {
            bgPaint.color = Color.parseColor("#F8FAFC")
            gridPaint.color = Color.parseColor("#E2E8F0")
            solidEdgePaint.color = Color.parseColor("#0F172A")
            dashedEdgePaint.color = Color.parseColor("#94A3B8")
            vertexLabelPaint.color = Color.parseColor("#0F172A")
            snapVertexPaint.color = Color.parseColor("#0284C7")
            snapMidPaint.color = Color.argb(180, 59, 130, 246)
            snapSubdivPaint.color = Color.argb(140, 100, 116, 139)
            sectionFillPaint.color = Color.argb(75, 2, 132, 199)
            sectionBorderPaint.color = Color.parseColor("#0284C7")
        }

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 1. Reference Grid in XY-plane at z = -0.6
        val gridZ = -0.6
        val gridStep = 0.5
        val gridLimit = 1.5
        var g = -gridLimit
        while (g <= gridLimit + 1e-5) {
            val p1 = project(Section3DEngine.Vec3(g, -gridLimit, gridZ))
            val p2 = project(Section3DEngine.Vec3(g, gridLimit, gridZ))
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, gridPaint)

            val p3 = project(Section3DEngine.Vec3(-gridLimit, g, gridZ))
            val p4 = project(Section3DEngine.Vec3(gridLimit, g, gridZ))
            canvas.drawLine(p3.x, p3.y, p4.x, p4.y, gridPaint)
            g += gridStep
        }

        // 2. Coordinate Axes
        val axisOrigin = Section3DEngine.Vec3(0.0, 0.0, gridZ)
        val xAxisEnd = Section3DEngine.Vec3(1.6, 0.0, gridZ)
        val yAxisEnd = Section3DEngine.Vec3(0.0, 1.6, gridZ)
        val zAxisEnd = Section3DEngine.Vec3(0.0, 0.0, 1.4)

        val oP = project(axisOrigin)
        val xP = project(xAxisEnd)
        val yP = project(yAxisEnd)
        val zP = project(zAxisEnd)

        canvas.drawLine(oP.x, oP.y, xP.x, xP.y, xAxisPaint)
        canvas.drawLine(oP.x, oP.y, yP.x, yP.y, yAxisPaint)
        canvas.drawLine(oP.x, oP.y, zP.x, zP.y, zAxisPaint)

        axisLabelPaint.color = xAxisPaint.color
        canvas.drawText("X", xP.x + dpToPx(6f), xP.y, axisLabelPaint)
        axisLabelPaint.color = yAxisPaint.color
        canvas.drawText("Y", yP.x + dpToPx(6f), yP.y, axisLabelPaint)
        axisLabelPaint.color = zAxisPaint.color
        canvas.drawText("Z", zP.x, zP.y - dpToPx(6f), axisLabelPaint)

        // 3. Compute Camera Position in World Space for Back-Face Culling
        val yawRad = Math.toRadians(cameraYaw.toDouble())
        val pitchRad = Math.toRadians(cameraPitch.toDouble())
        val dist = 4.0
        val camPos = Section3DEngine.Vec3(
            sin(yawRad) * cos(pitchRad) * dist,
            -cos(yawRad) * cos(pitchRad) * dist,
            sin(pitchRad) * dist
        )

        // Determine visibility of each face
        val faceVisible = BooleanArray(polyhedron.faces.size)
        polyhedron.faces.forEachIndexed { fIdx, face ->
            if (face.vertexIndices.size >= 3) {
                val v0 = polyhedron.vertices[face.vertexIndices[0]]
                val v1 = polyhedron.vertices[face.vertexIndices[1]]
                val v2 = polyhedron.vertices[face.vertexIndices[2]]
                var normal = (v1 - v0).cross(v2 - v0)

                val centroid = Section3DEngine.Vec3(
                    face.vertexIndices.sumOf { polyhedron.vertices[it].x } / face.vertexIndices.size,
                    face.vertexIndices.sumOf { polyhedron.vertices[it].y } / face.vertexIndices.size,
                    face.vertexIndices.sumOf { polyhedron.vertices[it].z } / face.vertexIndices.size
                )

                if (normal.dot(centroid) < 0) {
                    normal = normal * -1.0
                }

                val viewDir = camPos - centroid
                faceVisible[fIdx] = normal.dot(viewDir) > 0
            } else {
                faceVisible[fIdx] = true
            }
        }

        // 4. Draw Edges (Hidden back edges dashed, Front edges solid)
        val projVerts = polyhedron.vertices.map { project(it) }

        polyhedron.edges.forEach { edge ->
            val p1 = projVerts[edge.v1]
            val p2 = projVerts[edge.v2]

            val containingFaces = polyhedron.faces.indices.filter { fIdx ->
                val indices = polyhedron.faces[fIdx].vertexIndices
                indices.contains(edge.v1) && indices.contains(edge.v2)
            }

            val isVisibleEdge = containingFaces.isEmpty() || containingFaces.any { faceVisible[it] }

            if (isVisibleEdge) {
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, solidEdgePaint)
            } else {
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, dashedEdgePaint)
            }
        }

        // 5. Draw Cross-Section Polygon
        val result = crossSectionResult
        if (selectedPoints.size == 3 && result != null && result.isValid && result.polygonVertices.isNotEmpty()) {
            polyPath.reset()
            val secProj = result.polygonVertices.map { project(it) }
            polyPath.moveTo(secProj[0].x, secProj[0].y)
            for (i in 1 until secProj.size) {
                polyPath.lineTo(secProj[i].x, secProj[i].y)
            }
            polyPath.close()

            canvas.drawPath(polyPath, sectionFillPaint)
            canvas.drawPath(polyPath, sectionBorderPaint)

            secProj.forEach { p ->
                canvas.drawCircle(p.x, p.y, dpToPx(4.5f), sectionNodePaint)
                canvas.drawCircle(p.x, p.y, dpToPx(4.5f), sectionNodeStrokePaint)
            }
        }

        // 6. Draw Snap Dots on Edges
        val snapPoints = Section3DEngine.generateSnapPoints(polyhedron)
        snapPoints.forEach { sp ->
            val p = project(sp.pos)
            when {
                sp.isVertex -> {
                    canvas.drawCircle(p.x, p.y, dpToPx(4.5f), snapVertexPaint)
                }
                abs(sp.ratio - 0.5) < 1e-4 -> {
                    canvas.drawCircle(p.x, p.y, dpToPx(3.5f), snapMidPaint)
                }
                else -> {
                    canvas.drawCircle(p.x, p.y, dpToPx(2.5f), snapSubdivPaint)
                }
            }
        }

        // 7. Draw Vertex Text Labels
        polyhedron.vertices.forEachIndexed { idx, v ->
            val label = polyhedron.vertexLabels[idx]
            val outwardDir = v.normalized()
            val labelPos = v + outwardDir * 0.15
            val p = project(labelPos)
            canvas.drawText(label, p.x, p.y + dpToPx(4f), vertexLabelPaint)
        }

        // 8. Highlight Selected Points (1, 2, 3 Badges & Halo)
        selectedPoints.forEachIndexed { index, sp ->
            val p = project(sp.pos)

            if (index == activePointIndex) {
                canvas.drawCircle(p.x, p.y, dpToPx(17f), activeHaloPaint)
            }

            canvas.drawCircle(p.x, p.y, dpToPx(11f), badgeFillPaint)
            canvas.drawText(
                "${index + 1}",
                p.x,
                p.y - (badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2f,
                badgeTextPaint
            )
        }
    }
}
