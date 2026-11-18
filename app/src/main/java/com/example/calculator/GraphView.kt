package com.example.calculator

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.*

data class PlotSeries(
    val id: Int,
    val formula: String,
    val color: Int,
    val visible: Boolean = true,
    val compiled: CompiledPlot? = null
) {
    val isVisible: Boolean get() = visible

    constructor(
        id: Int,
        formula: String,
        color: Int,
        compiled: CompiledPlot?
    ) : this(id, formula, color, visible = true, compiled = compiled)
}

enum class PoiType {
    Y_INTERCEPT,
    ROOT,
    LOCAL_MIN,
    LOCAL_MAX,
    INTERSECTION
}

data class PointOfInterest(
    val mathX: Double,
    val mathY: Double,
    val type: PoiType,
    val seriesId: Int,
    val color: Int,
    val label: String
)

class GraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Область видимости графика ---
    var centerX: Double = 0.0
    var centerY: Double = 0.0
    var pixelsPerUnit: Float = 60f
        set(value) {
            field = value.coerceIn(0.5f, 5000f)
            invalidate()
        }

    // Список отображаемых графиков
    val seriesList: MutableList<PlotSeries> = mutableListOf()

    fun setSeriesList(list: List<PlotSeries>) {
        seriesList.clear()
        seriesList.addAll(list)
        clearTrace()
        invalidate()
    }

    fun setSeries(series: List<PlotSeries>) = setSeriesList(series)

    // Обратная совместимость с одиночной функцией
    fun setFunction(fn: ((Double) -> Double)?) {
        if (fn != null) {
            setSeries(listOf(PlotSeries(1, "y", Color.parseColor("#38BDF8"), visible = true, compiled = CompiledPlot.ExplicitY(fn))))
        } else {
            seriesList.clear()
            clearTrace()
            invalidate()
        }
    }

    @get:JvmName("getFunctionValue")
    @set:JvmName("setFunctionValue")
    var function: ((Double) -> Double)?
        get() = (seriesList.firstOrNull { it.compiled is CompiledPlot.ExplicitY }?.compiled as? CompiledPlot.ExplicitY)?.fn
        set(value) {
            setFunction(value)
        }

    // Координата X для обратной совместимости
    var selectedX: Double? = null
        private set

    // --- Светлая / Тёмная тема ---
    var isDarkTheme: Boolean = true
        set(value) {
            field = value
            updateThemePaints()
            invalidate()
        }

    // --- Режим плавной трассировки вдоль кривой (Desmos-style) ---
    var isTracing: Boolean = false
        private set
    var activeTraceSeries: PlotSeries? = null
        private set
    var traceMathX: Double = 0.0
        private set
    var traceMathY: Double = 0.0
        private set
    var snappedPoi: PointOfInterest? = null
        private set

    // Текущие точки интереса на экране
    private val currentPoiList: MutableList<PointOfInterest> = mutableListOf()

    // --- Кисти отрисовки ---
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        strokeWidth = 1f * dp
        style = Paint.Style.STROKE
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        strokeWidth = 2f * dp
        style = Paint.Style.STROKE
    }

    private val axisFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 10f * sp
        typeface = Typeface.MONOSPACE
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = 2.8f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val dashedCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.8f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(8f * dp, 6f * dp), 0f)
    }

    private val poiFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val poiStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1.5f * dp
        style = Paint.Style.STROKE
    }

    private val traceOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val traceStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * dp
        style = Paint.Style.STROKE
    }

    private val traceCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val projectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        strokeWidth = 1.2f * dp
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(5f * dp, 4f * dp), 0f)
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.FILL
    }

    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = 1.2f * dp
        style = Paint.Style.STROKE
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textSize = 12f * sp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun updateThemePaints() {
        if (isDarkTheme) {
            gridPaint.color = Color.parseColor("#1E293B")
            axisPaint.color = Color.parseColor("#64748B")
            axisFillPaint.color = Color.parseColor("#64748B")
            labelPaint.color = Color.parseColor("#94A3B8")
            projectionPaint.color = Color.parseColor("#94A3B8")
            badgeBgPaint.color = Color.parseColor("#1E293B")
            badgeBorderPaint.color = Color.parseColor("#334155")
            badgeTextPaint.color = Color.parseColor("#F8FAFC")
        } else {
            gridPaint.color = Color.parseColor("#E2E8F0")
            axisPaint.color = Color.parseColor("#64748B")
            axisFillPaint.color = Color.parseColor("#64748B")
            labelPaint.color = Color.parseColor("#475569")
            projectionPaint.color = Color.parseColor("#64748B")
            badgeBgPaint.color = Color.parseColor("#FFFFFF")
            badgeBorderPaint.color = Color.parseColor("#CBD5E1")
            badgeTextPaint.color = Color.parseColor("#0F172A")
        }
    }

    private val bitmapFilterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    private val arrowPath = Path()
    private val curvePath = Path()
    private val implicitPath = Path()
    private val projectionPath = Path()
    private val badgeRect = RectF()

    // Буферы для быстрого рендеринга неравенств
    private var ineqBitmap: Bitmap? = null
    private var ineqPixels: IntArray? = null

    // --- Преобразования координат ---
    private fun toScreenX(mx: Double, w: Float): Float = (w / 2.0 + (mx - centerX) * pixelsPerUnit).toFloat()
    private fun toScreenY(my: Double, h: Float): Float = (h / 2.0 - (my - centerY) * pixelsPerUnit).toFloat()
    private fun toMathX(sx: Float, w: Float): Double = centerX + (sx - w / 2.0) / pixelsPerUnit
    private fun toMathY(sy: Float, h: Float): Double = centerY - (sy - h / 2.0) / pixelsPerUnit

    // --- Обработка жестов ---
    private var wasScaling: Boolean = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val oldPpu = pixelsPerUnit
            val newPpu = (oldPpu * scaleFactor).coerceIn(0.5f, 5000f)
            if (newPpu != oldPpu) {
                val focusX = detector.focusX
                val focusY = detector.focusY
                val mathFocusX = centerX + (focusX - width / 2.0) / oldPpu
                val mathFocusY = centerY - (focusY - height / 2.0) / oldPpu
                pixelsPerUnit = newPpu
                centerX = mathFocusX - (focusX - width / 2.0) / pixelsPerUnit
                centerY = mathFocusY + (focusY - height / 2.0) / pixelsPerUnit
                invalidate()
            }
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (!isTracing) {
                centerX += distanceX / pixelsPerUnit
                centerY -= distanceY / pixelsPerUnit
                invalidate()
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w > 0f && h > 0f) {
                val threshold = 32f * dp
                val nearestPoi = findNearestPoi(e.x, e.y, threshold, w, h)
                val nearestCurve = findNearestCurve(e.x, e.y, threshold, w, h)
                if (nearestPoi == null && nearestCurve == null) {
                    clearTrace()
                    invalidate()
                }
            }
            performClick()
            return true
        }
    })

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return super.onTouchEvent(event)

        scaleDetector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            wasScaling = false
        }

        if (scaleDetector.isInProgress || event.pointerCount > 1) {
            wasScaling = true
            if (isTracing) {
                isTracing = false
                snappedPoi = null
                invalidate()
            }
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            wasScaling = true
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (wasScaling) {
                wasScaling = false
                if (isTracing) {
                    isTracing = false
                    snappedPoi = null
                    invalidate()
                }
                return true
            }
        }

        val canPassToGesture = !scaleDetector.isInProgress && !wasScaling && event.pointerCount == 1
        if (!canPassToGesture) {
            return true
        }

        val touchX = event.x
        val touchY = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touchThreshold = 32f * dp
                val nearestPoi = findNearestPoi(touchX, touchY, touchThreshold, w, h)
                if (nearestPoi != null) {
                    isTracing = true
                    activeTraceSeries = seriesList.firstOrNull { it.id == nearestPoi.seriesId }
                        ?: seriesList.firstOrNull { it.visible }
                    traceMathX = nearestPoi.mathX
                    traceMathY = nearestPoi.mathY
                    snappedPoi = nearestPoi
                    selectedX = nearestPoi.mathX
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }

                val nearestCurve = findNearestCurve(touchX, touchY, touchThreshold, w, h)
                if (nearestCurve != null) {
                    isTracing = true
                    activeTraceSeries = nearestCurve
                    updateTrace(touchX, touchY, w, h)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }

                // Касание дальше 32dp от кривых — стандартный pan
                isTracing = false
                gestureDetector.onTouchEvent(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTracing) {
                    updateTrace(touchX, touchY, w, h)
                    invalidate()
                    return true
                } else {
                    gestureDetector.onTouchEvent(event)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isTracing) {
                    isTracing = false
                    invalidate()
                    return true
                } else {
                    gestureDetector.onTouchEvent(event)
                    return true
                }
            }
        }

        return gestureDetector.onTouchEvent(event)
    }

    private fun updateTrace(touchX: Float, touchY: Float, w: Float, h: Float) {
        val series = activeTraceSeries ?: return
        val compiled = series.compiled ?: return

        // Деликатный радиус магнитного прилипания к особым точкам (10dp)
        val snapThreshold = 10f * dp
        var closestPoi: PointOfInterest? = null
        var minPoiDist = snapThreshold

        for (poi in currentPoiList) {
            if (poi.seriesId == series.id || poi.type == PoiType.INTERSECTION) {
                val px = toScreenX(poi.mathX, w)
                val py = toScreenY(poi.mathY, h)
                val d = hypot(touchX - px, touchY - py)
                if (d < minPoiDist) {
                    minPoiDist = d
                    closestPoi = poi
                }
            }
        }

        if (closestPoi != null) {
            traceMathX = closestPoi.mathX
            traceMathY = closestPoi.mathY
            snappedPoi = closestPoi
            selectedX = traceMathX
            return
        }
        snappedPoi = null

        when (compiled) {
            is CompiledPlot.ExplicitY -> {
                val yMargin = 20f * dp
                val yMinScreen = -yMargin
                val yMaxScreen = h + yMargin

                val scanSteps = 320
                var bestDist = Float.MAX_VALUE
                var bestMx = toMathX(touchX, w)
                var bestMy = 0.0
                var bestSx = touchX
                var found = false

                for (i in 0..scanSteps) {
                    val sx = i * w / scanSteps.toFloat()
                    val mx = toMathX(sx, w)
                    val my = try { compiled.fn(mx) } catch (_: Exception) { Double.NaN }
                    if (my.isNaN() || my.isInfinite()) continue
                    val sy = toScreenY(my, h)
                    if (sy < yMinScreen || sy > yMaxScreen) continue
                    val d = hypot(sx - touchX, sy - touchY)
                    if (d < bestDist) {
                        bestDist = d
                        bestMx = mx
                        bestMy = my
                        bestSx = sx
                        found = true
                    }
                }

                if (found) {
                    val stepWidthScreen = w / scanSteps.toFloat()
                    val mathDeltaX = abs(toMathX(bestSx + stepWidthScreen, w) - toMathX(bestSx - stepWidthScreen, w)) / 2.0
                    var refinedDist = bestDist
                    var refinedMx = bestMx
                    var refinedMy = bestMy

                    val refineSteps = 20
                    val refineStep = (2.0 * mathDeltaX) / refineSteps
                    for (j in 0..refineSteps) {
                        val curMx = (bestMx - mathDeltaX) + j * refineStep
                        val curMy = try { compiled.fn(curMx) } catch (_: Exception) { Double.NaN }
                        if (curMy.isNaN() || curMy.isInfinite()) continue
                        val curSy = toScreenY(curMy, h)
                        if (curSy < yMinScreen || curSy > yMaxScreen) continue
                        val curSx = toScreenX(curMx, w)
                        val curDist = hypot(curSx - touchX, curSy - touchY)
                        if (curDist < refinedDist) {
                            refinedDist = curDist
                            refinedMx = curMx
                            refinedMy = curMy
                        }
                    }

                    traceMathX = refinedMx
                    traceMathY = refinedMy
                } else {
                    val rawMx = toMathX(touchX, w)
                    val rawMy = try { compiled.fn(rawMx) } catch (_: Exception) { Double.NaN }
                    traceMathX = rawMx
                    traceMathY = if (rawMy.isNaN() || rawMy.isInfinite()) 0.0 else rawMy
                }
                selectedX = traceMathX
            }
            is CompiledPlot.ExplicitX -> {
                val xMargin = 20f * dp
                val xMinScreen = -xMargin
                val xMaxScreen = w + xMargin

                val scanSteps = 320
                var bestDist = Float.MAX_VALUE
                var bestMy = toMathY(touchY, h)
                var bestMx = 0.0
                var bestSy = touchY
                var found = false

                for (i in 0..scanSteps) {
                    val sy = i * h / scanSteps.toFloat()
                    val my = toMathY(sy, h)
                    val mx = try { compiled.fn(my) } catch (_: Exception) { Double.NaN }
                    if (mx.isNaN() || mx.isInfinite()) continue
                    val sx = toScreenX(mx, w)
                    if (sx < xMinScreen || sx > xMaxScreen) continue
                    val d = hypot(sx - touchX, sy - touchY)
                    if (d < bestDist) {
                        bestDist = d
                        bestMy = my
                        bestMx = mx
                        bestSy = sy
                        found = true
                    }
                }

                if (found) {
                    val stepHeightScreen = h / scanSteps.toFloat()
                    val mathDeltaY = abs(toMathY(bestSy + stepHeightScreen, h) - toMathY(bestSy - stepHeightScreen, h)) / 2.0
                    var refinedDist = bestDist
                    var refinedMy = bestMy
                    var refinedMx = bestMx

                    val refineSteps = 20
                    val refineStep = (2.0 * mathDeltaY) / refineSteps
                    for (j in 0..refineSteps) {
                        val curMy = (bestMy - mathDeltaY) + j * refineStep
                        val curMx = try { compiled.fn(curMy) } catch (_: Exception) { Double.NaN }
                        if (curMx.isNaN() || curMx.isInfinite()) continue
                        val curSx = toScreenX(curMx, w)
                        if (curSx < xMinScreen || curSx > xMaxScreen) continue
                        val curSy = toScreenY(curMy, h)
                        val curDist = hypot(curSx - touchX, curSy - touchY)
                        if (curDist < refinedDist) {
                            refinedDist = curDist
                            refinedMy = curMy
                            refinedMx = curMx
                        }
                    }

                    traceMathX = refinedMx
                    traceMathY = refinedMy
                } else {
                    val rawMy = toMathY(touchY, h)
                    val rawMx = try { compiled.fn(rawMy) } catch (_: Exception) { Double.NaN }
                    traceMathX = if (rawMx.isNaN() || rawMx.isInfinite()) 0.0 else rawMx
                    traceMathY = rawMy
                }
                selectedX = traceMathX
            }
            is CompiledPlot.ImplicitXY, is CompiledPlot.InequalityXY -> {
                val fxy = if (compiled is CompiledPlot.ImplicitXY) compiled.fxy else (compiled as CompiledPlot.InequalityXY).fxy
                var curX = toMathX(touchX, w)
                var curY = toMathY(touchY, h)
                val epsX = (0.5 / pixelsPerUnit).coerceAtLeast(1e-5)
                val epsY = (0.5 / pixelsPerUnit).coerceAtLeast(1e-5)

                for (iter in 0 until 4) {
                    val fVal = try { fxy(curX, curY) } catch (_: Exception) { Double.NaN }
                    if (fVal.isNaN() || fVal.isInfinite()) break
                    val fXPlus = try { fxy(curX + epsX, curY) } catch (_: Exception) { Double.NaN }
                    val fXMinus = try { fxy(curX - epsX, curY) } catch (_: Exception) { Double.NaN }
                    val fYPlus = try { fxy(curX, curY + epsY) } catch (_: Exception) { Double.NaN }
                    val fYMinus = try { fxy(curX, curY - epsY) } catch (_: Exception) { Double.NaN }
                    if (fXPlus.isNaN() || fXMinus.isNaN() || fYPlus.isNaN() || fYMinus.isNaN()) break

                    val gradX = (fXPlus - fXMinus) / (2.0 * epsX)
                    val gradY = (fYPlus - fYMinus) / (2.0 * epsY)
                    val gradSq = gradX * gradX + gradY * gradY
                    if (gradSq > 1e-12) {
                        val step = fVal / gradSq
                        curX -= step * gradX
                        curY -= step * gradY
                    } else {
                        break
                    }
                }

                traceMathX = curX
                traceMathY = curY
                selectedX = traceMathX
            }
        }
    }

    private fun findNearestPoi(touchX: Float, touchY: Float, threshold: Float, w: Float, h: Float): PointOfInterest? {
        var nearest: PointOfInterest? = null
        var minDist = threshold
        for (poi in currentPoiList) {
            val px = toScreenX(poi.mathX, w)
            val py = toScreenY(poi.mathY, h)
            val d = hypot(touchX - px, touchY - py)
            if (d < minDist) {
                minDist = d
                nearest = poi
            }
        }
        return nearest
    }

    private fun findNearestCurve(touchX: Float, touchY: Float, threshold: Float, w: Float, h: Float): PlotSeries? {
        var bestSeries: PlotSeries? = null
        var bestDist = threshold

        for (series in seriesList) {
            if (!series.visible) continue
            val compiled = series.compiled ?: continue
            when (compiled) {
                is CompiledPlot.ExplicitY -> {
                    val steps = 16
                    for (i in -steps..steps) {
                        val sx = touchX + (i * threshold / steps)
                        val mx = toMathX(sx, w)
                        val my = try { compiled.fn(mx) } catch (_: Exception) { Double.NaN }
                        if (my.isNaN() || my.isInfinite()) continue
                        val sy = toScreenY(my, h)
                        val d = hypot(touchX - sx, touchY - sy)
                        if (d < bestDist) {
                            bestDist = d
                            bestSeries = series
                        }
                    }
                }
                is CompiledPlot.ExplicitX -> {
                    val steps = 16
                    for (i in -steps..steps) {
                        val sy = touchY + (i * threshold / steps)
                        val my = toMathY(sy, h)
                        val mx = try { compiled.fn(my) } catch (_: Exception) { Double.NaN }
                        if (mx.isNaN() || mx.isInfinite()) continue
                        val sx = toScreenX(mx, w)
                        val d = hypot(touchX - sx, touchY - sy)
                        if (d < bestDist) {
                            bestDist = d
                            bestSeries = series
                        }
                    }
                }
                is CompiledPlot.ImplicitXY, is CompiledPlot.InequalityXY -> {
                    val mx = toMathX(touchX, w)
                    val my = toMathY(touchY, h)
                    val fxy = if (compiled is CompiledPlot.ImplicitXY) compiled.fxy else (compiled as CompiledPlot.InequalityXY).fxy
                    val v = try { abs(fxy(mx, my)) } catch (_: Exception) { Double.NaN }
                    if (!v.isNaN()) {
                        val dx = (toMathX(touchX + 2f, w) - mx)
                        val dy = (toMathY(touchY - 2f, h) - my)
                        val vx = try { (fxy(mx + dx, my) - fxy(mx - dx, my)) / (2.0 * dx) } catch (_: Exception) { 1.0 }
                        val vy = try { (fxy(mx, my + dy) - fxy(mx, my - dy)) / (2.0 * dy) } catch (_: Exception) { 1.0 }
                        val gradLen = hypot(vx, vy)
                        val distUnits = if (gradLen > 1e-6) v / gradLen else 1e9
                        val distPx = (distUnits * pixelsPerUnit).toFloat()
                        if (distPx < bestDist) {
                            bestDist = distPx
                            bestSeries = series
                        }
                    }
                }
            }
        }
        return bestSeries
    }

    fun zoomIn() {
        pixelsPerUnit = (pixelsPerUnit * 1.3f).coerceIn(0.5f, 5000f)
        invalidate()
    }

    fun zoomOut() {
        pixelsPerUnit = (pixelsPerUnit / 1.3f).coerceIn(0.5f, 5000f)
        invalidate()
    }

    fun resetViewport() {
        centerX = 0.0
        centerY = 0.0
        val w = width.toFloat()
        pixelsPerUnit = if (w > 0f) (w / 20f).coerceIn(0.5f, 5000f) else 60f
        clearTrace()
        invalidate()
    }

    fun clearTrace() {
        isTracing = false
        activeTraceSeries = null
        snappedPoi = null
        selectedX = null
    }

    fun clearSelection() {
        clearTrace()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Фон
        canvas.drawColor(if (isDarkTheme) Color.parseColor("#0F172A") else Color.WHITE)

        val safePpu = pixelsPerUnit.coerceIn(0.5f, 5000f)
        val leftX = centerX - (w / 2.0) / safePpu
        val rightX = centerX + (w / 2.0) / safePpu
        val bottomY = centerY - (h / 2.0) / safePpu
        val topY = centerY + (h / 2.0) / safePpu

        // Оптимальный шаг координатной сетки
        val minGridPx = (50f * dp).coerceAtLeast(10f)
        val minUnitStep = (minGridPx / safePpu).toDouble().coerceAtLeast(1e-7)
        val pow10 = 10.0.pow(floor(log10(minUnitStep)))
        val ratio = if (pow10 > 0.0) minUnitStep / pow10 else 1.0
        val rawUnitStep = when {
            ratio <= 1.0 -> 1.0 * pow10
            ratio <= 2.0 -> 2.0 * pow10
            ratio <= 5.0 -> 5.0 * pow10
            else -> 10.0 * pow10
        }
        val unitStep = if (rawUnitStep > 0.0 && !rawUnitStep.isNaN() && !rawUnitStep.isInfinite()) {
            rawUnitStep
        } else {
            1.0
        }

        val screenX0 = (w / 2.0 - centerX * safePpu).toFloat()
        val screenY0 = (h / 2.0 + centerY * safePpu).toFloat()

        // 2. Координатная сетка
        val maxGridLines = 200
        val firstX = floor(leftX / unitStep) * unitStep
        var gx = firstX
        var lineCountX = 0
        while (gx <= rightX + unitStep && lineCountX++ < maxGridLines) {
            val sx = (w / 2.0 + (gx - centerX) * safePpu).toFloat()
            if (sx in 0f..w) {
                canvas.drawLine(sx, 0f, sx, h, gridPaint)
            }
            gx += unitStep
        }

        val firstY = floor(bottomY / unitStep) * unitStep
        var gy = firstY
        var lineCountY = 0
        while (gy <= topY + unitStep && lineCountY++ < maxGridLines) {
            val sy = (h / 2.0 - (gy - centerY) * safePpu).toFloat()
            if (sy in 0f..h) {
                canvas.drawLine(0f, sy, w, sy, gridPaint)
            }
            gy += unitStep
        }

        // 3. Главные оси X и Y
        val arrowSize = 6f * dp
        val arrowLen = 10f * dp

        if (screenY0 in -50f..(h + 50f)) {
            val clampedY0 = screenY0.coerceIn(2f * dp, h - 2f * dp)
            canvas.drawLine(0f, clampedY0, w, clampedY0, axisPaint)
            arrowPath.reset()
            arrowPath.moveTo(w - arrowLen, clampedY0 - arrowSize)
            arrowPath.lineTo(w - 2f * dp, clampedY0)
            arrowPath.lineTo(w - arrowLen, clampedY0 + arrowSize)
            arrowPath.close()
            canvas.drawPath(arrowPath, axisFillPaint)

            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("x", w - arrowLen - 4f * dp, clampedY0 - 6f * dp, labelPaint)
        }

        if (screenX0 in -50f..(w + 50f)) {
            val clampedX0 = screenX0.coerceIn(2f * dp, w - 2f * dp)
            canvas.drawLine(clampedX0, h, clampedX0, 0f, axisPaint)
            arrowPath.reset()
            arrowPath.moveTo(clampedX0 - arrowSize, arrowLen)
            arrowPath.lineTo(clampedX0, 2f * dp)
            arrowPath.lineTo(clampedX0 + arrowSize, arrowLen)
            arrowPath.close()
            canvas.drawPath(arrowPath, axisFillPaint)

            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("y", clampedX0 + 6f * dp, arrowLen + 10f * dp, labelPaint)
        }

        // 4. Числовые подписи делений
        val textYPos = screenY0.coerceIn(14f * sp + 4f * dp, h - 6f * dp)
        gx = firstX
        labelPaint.textAlign = Paint.Align.CENTER
        lineCountX = 0
        while (gx <= rightX + unitStep && lineCountX++ < maxGridLines) {
            if (abs(gx) > unitStep * 0.1) {
                val sx = (w / 2.0 + (gx - centerX) * safePpu).toFloat()
                if (sx in 15f * dp..(w - 25f * dp)) {
                    canvas.drawText(formatUnit(gx), sx, textYPos + 12f * dp, labelPaint)
                }
            }
            gx += unitStep
        }

        val textXPos = screenX0.coerceIn(40f * dp, w - 10f * dp)
        gy = firstY
        labelPaint.textAlign = Paint.Align.RIGHT
        lineCountY = 0
        while (gy <= topY + unitStep && lineCountY++ < maxGridLines) {
            if (abs(gy) > unitStep * 0.1) {
                val sy = (h / 2.0 - (gy - centerY) * safePpu).toFloat()
                if (sy in 25f * dp..(h - 15f * dp)) {
                    canvas.drawText(formatUnit(gy), textXPos - 6f * dp, sy + 4f * dp, labelPaint)
                }
            }
            gy += unitStep
        }

        // 5. Отрисовка неравенств (InequalityXY) — заливка первым слоем
        for (series in seriesList) {
            if (!series.visible) continue
            val compiled = series.compiled as? CompiledPlot.InequalityXY ?: continue
            drawInequalityFill(canvas, compiled, series.color, w, h, leftX, rightX, bottomY, topY)
        }

        // 6. Отрисовка кривых всех типов
        for (series in seriesList) {
            if (!series.visible) continue
            val compiled = series.compiled ?: continue
            curvePaint.color = series.color
            dashedCurvePaint.color = series.color

            when (compiled) {
                is CompiledPlot.ExplicitY -> {
                    drawExplicitY(canvas, compiled.fn, w, h)
                }
                is CompiledPlot.ExplicitX -> {
                    drawExplicitX(canvas, compiled.fn, w, h)
                }
                is CompiledPlot.ImplicitXY -> {
                    drawImplicitXY(canvas, compiled.fxy, series.color, w, h, leftX, rightX, bottomY, topY)
                }
                is CompiledPlot.InequalityXY -> {
                    drawInequalityBoundary(canvas, compiled, series.color, w, h, leftX, rightX, bottomY, topY)
                }
            }
        }

        // 7. Поиск и отрисовка автоматических особых точек (Points of Interest)
        computePointsOfInterest(w, h, leftX, rightX, bottomY, topY)
        drawPointsOfInterest(canvas, w, h)

        // 8. Маркер активной точки трассировки, пунктирные проекции к осям и плашка Desmos
        if (activeTraceSeries != null) {
            drawTracingMarkerAndBadge(canvas, w, h)
        }
    }

    private fun drawExplicitY(canvas: Canvas, fn: (Double) -> Double, w: Float, h: Float) {
        curvePath.reset()
        val totalSteps = (w / 1.5f).toInt().coerceIn(400, 1000)
        var hasPrev = false
        var prevSy = 0f

        for (i in 0..totalSteps) {
            val sx = i * w / totalSteps.toFloat()
            val mx = toMathX(sx, w)
            val my = try { fn(mx) } catch (_: Exception) { Double.NaN }

            if (my.isNaN() || my.isInfinite()) {
                hasPrev = false
                continue
            }

            val sy = toScreenY(my, h)

            if (hasPrev) {
                // Защита от вертикальных асимптот (разрывов)
                if (abs(sy - prevSy) > h * 1.2f || (prevSy < -h * 0.2f && sy > h * 1.2f) || (prevSy > h * 1.2f && sy < -h * 0.2f)) {
                    curvePath.moveTo(sx, sy)
                } else {
                    curvePath.lineTo(sx, sy)
                }
            } else {
                curvePath.moveTo(sx, sy)
                hasPrev = true
            }
            prevSy = sy
        }

        canvas.drawPath(curvePath, curvePaint)
    }

    private fun drawExplicitX(canvas: Canvas, fn: (Double) -> Double, w: Float, h: Float) {
        curvePath.reset()
        val totalSteps = (h / 1.5f).toInt().coerceIn(400, 1000)
        var hasPrev = false
        var prevSx = 0f

        for (i in 0..totalSteps) {
            val sy = i * h / totalSteps.toFloat()
            val my = toMathY(sy, h)
            val mx = try { fn(my) } catch (_: Exception) { Double.NaN }

            if (mx.isNaN() || mx.isInfinite()) {
                hasPrev = false
                continue
            }

            val sx = toScreenX(mx, w)

            if (hasPrev) {
                if (abs(sx - prevSx) > w * 1.2f || (prevSx < -w * 0.2f && sx > w * 1.2f) || (prevSx > w * 1.2f && sx < -w * 0.2f)) {
                    curvePath.moveTo(sx, sy)
                } else {
                    curvePath.lineTo(sx, sy)
                }
            } else {
                curvePath.moveTo(sx, sy)
                hasPrev = true
            }
            prevSx = sx
        }

        canvas.drawPath(curvePath, curvePaint)
    }

    private fun drawImplicitXY(
        canvas: Canvas,
        fxy: (Double, Double) -> Double,
        color: Int,
        w: Float,
        h: Float,
        leftX: Double,
        rightX: Double,
        bottomY: Double,
        topY: Double
    ) {
        val nx = 80
        val ny = 120
        val dx = (rightX - leftX) / nx
        val dy = (topY - bottomY) / ny

        val vals = Array(nx + 1) { DoubleArray(ny + 1) }

        for (ix in 0..nx) {
            val x = leftX + ix * dx
            for (iy in 0..ny) {
                val y = bottomY + iy * dy
                vals[ix][iy] = try { fxy(x, y) } catch (_: Exception) { Double.NaN }
            }
        }

        implicitPath.reset()
        val degeneratePoints = mutableListOf<PointF>()

        // Проверка вырожденной точки (0, 0)
        if (0.0 in leftX..rightX && 0.0 in bottomY..topY) {
            val v00 = try { fxy(0.0, 0.0) } catch (_: Exception) { Double.NaN }
            if (!v00.isNaN() && abs(v00) < 1e-3) {
                val vRight = try { fxy(dx * 0.5, 0.0) } catch (_: Exception) { Double.NaN }
                val vUp = try { fxy(0.0, dy * 0.5) } catch (_: Exception) { Double.NaN }
                if (vRight >= 0.0 && vUp >= 0.0) {
                    degeneratePoints.add(PointF(toScreenX(0.0, w), toScreenY(0.0, h)))
                }
            }
        }

        for (ix in 0 until nx) {
            val x0 = leftX + ix * dx
            val x1 = x0 + dx
            for (iy in 0 until ny) {
                val y0 = bottomY + iy * dy
                val y1 = y0 + dy

                val v0 = vals[ix][iy]
                val v1 = vals[ix + 1][iy]
                val v2 = vals[ix + 1][iy + 1]
                val v3 = vals[ix][iy + 1]

                if (v0.isNaN() || v1.isNaN() || v2.isNaN() || v3.isNaN()) continue

                val b0 = if (v0 > 0) 1 else 0
                val b1 = if (v1 > 0) 2 else 0
                val b2 = if (v2 > 0) 4 else 0
                val b3 = if (v3 > 0) 8 else 0
                val cell = b0 or b1 or b2 or b3

                if (cell == 0 || cell == 15) {
                    val xc = x0 + 0.5 * dx
                    val yc = y0 + 0.5 * dy
                    val vc = try { fxy(xc, yc) } catch (_: Exception) { Double.NaN }
                    if (!vc.isNaN() && abs(vc) < 1e-3) {
                        degeneratePoints.add(PointF(toScreenX(xc, w), toScreenY(yc, h)))
                    }
                    continue
                }

                fun edge0(): PointF {
                    val t = (-v0 / (v1 - v0)).coerceIn(0.0, 1.0)
                    return PointF(toScreenX(x0 + t * dx, w), toScreenY(y0, h))
                }

                fun edge1(): PointF {
                    val t = (-v1 / (v2 - v1)).coerceIn(0.0, 1.0)
                    return PointF(toScreenX(x1, w), toScreenY(y0 + t * dy, h))
                }

                fun edge2(): PointF {
                    val t = (-v3 / (v2 - v3)).coerceIn(0.0, 1.0)
                    return PointF(toScreenX(x0 + t * dx, w), toScreenY(y1, h))
                }

                fun edge3(): PointF {
                    val t = (-v0 / (v3 - v0)).coerceIn(0.0, 1.0)
                    return PointF(toScreenX(x0, w), toScreenY(y0 + t * dy, h))
                }

                fun connect(p1: PointF, p2: PointF) {
                    implicitPath.moveTo(p1.x, p1.y)
                    implicitPath.lineTo(p2.x, p2.y)
                }

                when (cell) {
                    1, 14 -> connect(edge3(), edge0())
                    2, 13 -> connect(edge0(), edge1())
                    3, 12 -> connect(edge3(), edge1())
                    4, 11 -> connect(edge1(), edge2())
                    5 -> {
                        val vc = try { fxy(x0 + 0.5 * dx, y0 + 0.5 * dy) } catch (_: Exception) { 0.0 }
                        if (vc > 0) {
                            connect(edge3(), edge2())
                            connect(edge0(), edge1())
                        } else {
                            connect(edge3(), edge0())
                            connect(edge1(), edge2())
                        }
                    }
                    6, 9 -> connect(edge0(), edge2())
                    7, 8 -> connect(edge3(), edge2())
                    10 -> {
                        val vc = try { fxy(x0 + 0.5 * dx, y0 + 0.5 * dy) } catch (_: Exception) { 0.0 }
                        if (vc > 0) {
                            connect(edge3(), edge0())
                            connect(edge1(), edge2())
                        } else {
                            connect(edge3(), edge2())
                            connect(edge0(), edge1())
                        }
                    }
                }
            }
        }

        canvas.drawPath(implicitPath, curvePaint)

        // Отрисовка изолированных точек / минимумов
        if (degeneratePoints.isNotEmpty()) {
            poiFillPaint.color = color
            for (pt in degeneratePoints) {
                canvas.drawCircle(pt.x, pt.y, 4.5f * dp, poiFillPaint)
                canvas.drawCircle(pt.x, pt.y, 4.5f * dp, poiStrokePaint)
            }
        }
    }

    private fun drawInequalityFill(
        canvas: Canvas,
        ineq: CompiledPlot.InequalityXY,
        color: Int,
        w: Float,
        h: Float,
        leftX: Double,
        rightX: Double,
        bottomY: Double,
        topY: Double
    ) {
        val bw = 100
        val bh = 150
        val total = bw * bh
        if (ineqPixels == null || ineqPixels!!.size != total) {
            ineqPixels = IntArray(total)
        }
        val pixels = ineqPixels!!

        val fillAlphaColor = Color.argb(45, Color.red(color), Color.green(color), Color.blue(color))
        val dx = (rightX - leftX) / bw
        val dy = (topY - bottomY) / bh

        for (iy in 0 until bh) {
            val y = topY - (iy + 0.5) * dy
            val rowOffset = iy * bw
            for (ix in 0 until bw) {
                val x = leftX + (ix + 0.5) * dx
                val v = try { ineq.fxy(x, y) } catch (_: Exception) { Double.NaN }
                val inside = when (ineq.op) {
                    "<" -> v < 0.0
                    "<=" -> v <= 0.0
                    ">" -> v > 0.0
                    ">=" -> v >= 0.0
                    else -> false
                }
                pixels[rowOffset + ix] = if (inside) fillAlphaColor else 0
            }
        }

        var bmp = ineqBitmap
        if (bmp == null || bmp.width != bw || bmp.height != bh) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            ineqBitmap = bmp
        }
        bmp.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        canvas.drawBitmap(bmp, null, RectF(0f, 0f, w, h), bitmapFilterPaint)
    }

    private fun drawInequalityBoundary(
        canvas: Canvas,
        ineq: CompiledPlot.InequalityXY,
        color: Int,
        w: Float,
        h: Float,
        leftX: Double,
        rightX: Double,
        bottomY: Double,
        topY: Double
    ) {
        val isStrict = ineq.op == "<" || ineq.op == ">"
        val paint = if (isStrict) dashedCurvePaint else curvePaint
        paint.color = color

        val nx = 80
        val ny = 120
        val dx = (rightX - leftX) / nx
        val dy = (topY - bottomY) / ny
        val vals = Array(nx + 1) { DoubleArray(ny + 1) }

        for (ix in 0..nx) {
            val x = leftX + ix * dx
            for (iy in 0..ny) {
                val y = bottomY + iy * dy
                vals[ix][iy] = try { ineq.fxy(x, y) } catch (_: Exception) { Double.NaN }
            }
        }

        implicitPath.reset()
        for (ix in 0 until nx) {
            val x0 = leftX + ix * dx
            val x1 = x0 + dx
            for (iy in 0 until ny) {
                val y0 = bottomY + iy * dy
                val y1 = y0 + dy

                val v0 = vals[ix][iy]
                val v1 = vals[ix + 1][iy]
                val v2 = vals[ix + 1][iy + 1]
                val v3 = vals[ix][iy + 1]

                if (v0.isNaN() || v1.isNaN() || v2.isNaN() || v3.isNaN()) continue

                val b0 = if (v0 > 0) 1 else 0
                val b1 = if (v1 > 0) 2 else 0
                val b2 = if (v2 > 0) 4 else 0
                val b3 = if (v3 > 0) 8 else 0
                val cell = b0 or b1 or b2 or b3
                if (cell == 0 || cell == 15) continue

                fun edge0(): PointF {
                    val t = (-v0 / (v1 - v0)).coerceIn(0.0, 1.0)
                    return PointF(toScreenX(x0 + t * dx, w), toScreenY(y0, h))
                }
                fun edge1(): PointF {
                    val t = (-v1 / (v2 - v1)).coerceIn(0.0, 1.0)
                    return PointF(toScreenX(x1, w), toScreenY(y0 + t * dy, h))
                }
                fun edge2(): PointF {
                    val t = (-v3 / (v2 - v3)).coerceIn(0.0, 1.0)
                    return PointF(toScreenX(x0 + t * dx, w), toScreenY(y1, h))
                }
                fun edge3(): PointF {
                    val t = (-v0 / (v3 - v0)).coerceIn(0.0, 1.0)
                    return PointF(toScreenX(x0, w), toScreenY(y0 + t * dy, h))
                }
                fun connect(p1: PointF, p2: PointF) {
                    implicitPath.moveTo(p1.x, p1.y)
                    implicitPath.lineTo(p2.x, p2.y)
                }

                when (cell) {
                    1, 14 -> connect(edge3(), edge0())
                    2, 13 -> connect(edge0(), edge1())
                    3, 12 -> connect(edge3(), edge1())
                    4, 11 -> connect(edge1(), edge2())
                    5, 10 -> {
                        connect(edge3(), edge0())
                        connect(edge1(), edge2())
                    }
                    6, 9 -> connect(edge0(), edge2())
                    7, 8 -> connect(edge3(), edge2())
                }
            }
        }
        canvas.drawPath(implicitPath, paint)
    }

    private fun computePointsOfInterest(
        w: Float,
        h: Float,
        leftX: Double,
        rightX: Double,
        bottomY: Double,
        topY: Double
    ) {
        currentPoiList.clear()
        val explicitSeries = seriesList.filter { it.visible && it.compiled is CompiledPlot.ExplicitY }
        if (explicitSeries.isEmpty()) return

        val rawPoi = mutableListOf<PointOfInterest>()
        val sampleN = 240
        val step = (rightX - leftX) / sampleN

        for (series in explicitSeries) {
            val fn = (series.compiled as CompiledPlot.ExplicitY).fn

            // 1. Пересечение с осью Y: (0, f(0))
            if (0.0 in leftX..rightX) {
                val y0 = try { fn(0.0) } catch (_: Exception) { Double.NaN }
                if (!y0.isNaN() && !y0.isInfinite() && y0 in bottomY..topY) {
                    rawPoi.add(
                        PointOfInterest(
                            0.0,
                            y0,
                            PoiType.Y_INTERCEPT,
                            series.id,
                            series.color,
                            "(0; ${formatCoord(y0)})"
                        )
                    )
                }
            }

            // Выборка точек для нулей функции и экстремумов
            val xs = DoubleArray(sampleN + 1) { leftX + it * step }
            val ys = DoubleArray(sampleN + 1) {
                try { fn(xs[it]) } catch (_: Exception) { Double.NaN }
            }

            // 2. Нули функции (корни f(x) = 0 методом бисекции)
            for (i in 0 until sampleN) {
                val y1 = ys[i]
                val y2 = ys[i + 1]
                if (abs(y1) < 1e-12 && abs(y2) < 1e-12) continue
                if (!y1.isNaN() && !y2.isNaN() && !y1.isInfinite() && !y2.isInfinite() && y1 * y2 <= 0.0) {
                    if (abs(y2 - y1) < (topY - bottomY) * 2.0) {
                        val rootX = findRoot(fn, xs[i], xs[i + 1])
                        val rootY = 0.0
                        if (rootX in leftX..rightX) {
                            rawPoi.add(
                                PointOfInterest(
                                    rootX,
                                    rootY,
                                    PoiType.ROOT,
                                    series.id,
                                    series.color,
                                    "(${formatCoord(rootX)}; 0)"
                                )
                            )
                        }
                    }
                }
            }

            // 3. Локальные минимумы и максимумы
            for (i in 1 until sampleN) {
                val y0 = ys[i - 1]
                val y1 = ys[i]
                val y2 = ys[i + 1]
                if (y0.isNaN() || y1.isNaN() || y2.isNaN()) continue
                if (abs(y1 - y0) > (topY - bottomY) * 0.5 || abs(y2 - y1) > (topY - bottomY) * 0.5) continue

                if (y1 > y0 && y1 > y2) {
                    val extX = findLocalExtremum(fn, xs[i - 1], xs[i + 1], isMax = true)
                    val extY = try { fn(extX) } catch (_: Exception) { Double.NaN }
                    if (!extY.isNaN() && !extY.isInfinite() && extY in bottomY..topY) {
                        rawPoi.add(
                            PointOfInterest(
                                extX,
                                extY,
                                PoiType.LOCAL_MAX,
                                series.id,
                                series.color,
                                "(${formatCoord(extX)}; ${formatCoord(extY)})"
                            )
                        )
                    }
                } else if (y1 < y0 && y1 < y2) {
                    val extX = findLocalExtremum(fn, xs[i - 1], xs[i + 1], isMax = false)
                    val extY = try { fn(extX) } catch (_: Exception) { Double.NaN }
                    if (!extY.isNaN() && !extY.isInfinite() && extY in bottomY..topY) {
                        rawPoi.add(
                            PointOfInterest(
                                extX,
                                extY,
                                PoiType.LOCAL_MIN,
                                series.id,
                                series.color,
                                "(${formatCoord(extX)}; ${formatCoord(extY)})"
                            )
                        )
                    }
                }
            }
        }

        // 4. Точки пересечения между разными графиками ExplicitY
        if (explicitSeries.size > 1) {
            for (i in explicitSeries.indices) {
                for (j in (i + 1) until explicitSeries.size) {
                    val sA = explicitSeries[i]
                    val sB = explicitSeries[j]
                    val fnA = (sA.compiled as CompiledPlot.ExplicitY).fn
                    val fnB = (sB.compiled as CompiledPlot.ExplicitY).fn

                    val diff = { x: Double -> fnA(x) - fnB(x) }
                    for (stepIdx in 0 until sampleN) {
                        val x1 = leftX + stepIdx * step
                        val x2 = x1 + step
                        val d1 = try { diff(x1) } catch (_: Exception) { Double.NaN }
                        val d2 = try { diff(x2) } catch (_: Exception) { Double.NaN }
                        if (!d1.isNaN() && !d2.isNaN() && d1 * d2 <= 0.0 && abs(d2 - d1) < (topY - bottomY) * 2.0) {
                            val ix = findRoot(diff, x1, x2)
                            val iy = try { fnA(ix) } catch (_: Exception) { Double.NaN }
                            if (!iy.isNaN() && !iy.isInfinite() && iy in bottomY..topY) {
                                rawPoi.add(
                                    PointOfInterest(
                                        ix,
                                        iy,
                                        PoiType.INTERSECTION,
                                        sA.id,
                                        Color.parseColor("#94A3B8"),
                                        "(${formatCoord(ix)}; ${formatCoord(iy)})"
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Дедупликация точек интереса на экране (не ближе 8dp друг к другу)
        for (poi in rawPoi) {
            val px = toScreenX(poi.mathX, w)
            val py = toScreenY(poi.mathY, h)
            if (px in 0f..w && py in 0f..h) {
                val exists = currentPoiList.any { other ->
                    val ox = toScreenX(other.mathX, w)
                    val oy = toScreenY(other.mathY, h)
                    hypot(px - ox, py - oy) < 8f * dp
                }
                if (!exists) {
                    currentPoiList.add(poi)
                }
            }
        }
    }

    private fun findRoot(fn: (Double) -> Double, x1: Double, x2: Double): Double {
        var a = x1
        var b = x2
        var fa = try { fn(a) } catch (_: Exception) { 0.0 }
        for (iter in 0 until 22) {
            val mid = (a + b) / 2.0
            val fmid = try { fn(mid) } catch (_: Exception) { 0.0 }
            if (abs(fmid) < 1e-12) return mid
            if (fa * fmid <= 0.0) {
                b = mid
            } else {
                a = mid
                fa = fmid
            }
        }
        return (a + b) / 2.0
    }

    private fun findLocalExtremum(
        fn: (Double) -> Double,
        x0: Double,
        x2: Double,
        isMax: Boolean
    ): Double {
        var a = x0
        var b = x2
        val phi = 0.6180339887
        var c = b - (b - a) * phi
        var d = a + (b - a) * phi
        for (iter in 0 until 24) {
            val fc = try { fn(c) } catch (_: Exception) { Double.NaN }
            val fd = try { fn(d) } catch (_: Exception) { Double.NaN }
            if (fc.isNaN() || fd.isNaN()) break
            val cond = if (isMax) fc > fd else fc < fd
            if (cond) {
                b = d
                d = c
                c = b - (b - a) * phi
            } else {
                a = c
                c = d
                d = a + (b - a) * phi
            }
        }
        return (a + b) / 2.0
    }

    private fun drawPointsOfInterest(canvas: Canvas, w: Float, h: Float) {
        for (poi in currentPoiList) {
            val px = toScreenX(poi.mathX, w)
            val py = toScreenY(poi.mathY, h)
            if (px in -10f..(w + 10f) && py in -10f..(h + 10f)) {
                // Если точка совпадает с активным маркером трассировки, пропускаем базовую отрисовку
                if (activeTraceSeries != null && abs(px - toScreenX(traceMathX, w)) < 2f && abs(py - toScreenY(traceMathY, h)) < 2f) {
                    continue
                }
                poiFillPaint.color = poi.color
                canvas.drawCircle(px, py, 4.5f * dp, poiFillPaint)
                canvas.drawCircle(px, py, 4.5f * dp, poiStrokePaint)
            }
        }
    }

    private fun drawTracingMarkerAndBadge(canvas: Canvas, w: Float, h: Float) {
        val series = activeTraceSeries ?: return
        val ptX = toScreenX(traceMathX, w)
        val ptY = toScreenY(traceMathY, h)

        if (ptX !in -100f..(w + 100f) || ptY !in -100f..(h + 100f)) return

        val screenX0 = (w / 2.0 - centerX * pixelsPerUnit).toFloat()
        val screenY0 = (h / 2.0 + centerY * pixelsPerUnit).toFloat()

        // 1. Пунктирные проекции к осям
        projectionPath.reset()
        val targetYOnAxis = screenY0.coerceIn(0f, h)
        projectionPath.moveTo(ptX, ptY)
        projectionPath.lineTo(ptX, targetYOnAxis)

        val targetXOnAxis = screenX0.coerceIn(0f, w)
        projectionPath.moveTo(ptX, ptY)
        projectionPath.lineTo(targetXOnAxis, ptY)
        canvas.drawPath(projectionPath, projectionPaint)

        // 2. Крупный маркер точки (7dp, цвет кривой с белым центром/обводкой)
        traceOuterPaint.color = series.color
        canvas.drawCircle(ptX, ptY, 7f * dp, traceOuterPaint)
        canvas.drawCircle(ptX, ptY, 7f * dp, traceStrokePaint)
        canvas.drawCircle(ptX, ptY, 2.8f * dp, traceCenterPaint)

        // 3. Аккуратная плашка с координатами (x; y) в стиле Desmos
        val coordText = if (snappedPoi != null) {
            when (snappedPoi?.type) {
                PoiType.ROOT -> "(${formatCoord(traceMathX)}; 0)"
                PoiType.Y_INTERCEPT -> "(0; ${formatCoord(traceMathY)})"
                else -> "(${formatCoord(traceMathX)}; ${formatCoord(traceMathY)})"
            }
        } else {
            "(${formatCoord(traceMathX)}; ${formatCoord(traceMathY)})"
        }

        val textW = badgeTextPaint.measureText(coordText)
        val padH = 10f * dp
        val badgeW = textW + padH * 2f
        val badgeH = 26f * dp

        val badgeLeft = (ptX - badgeW / 2f).coerceIn(8f * dp, w - badgeW - 8f * dp)
        var badgeTop = ptY - badgeH - 12f * dp
        if (badgeTop < 10f * dp) {
            badgeTop = ptY + 12f * dp
        }

        badgeRect.set(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH)
        canvas.drawRoundRect(badgeRect, 6f * dp, 6f * dp, badgeBgPaint)
        badgeBorderPaint.color = series.color
        canvas.drawRoundRect(badgeRect, 6f * dp, 6f * dp, badgeBorderPaint)

        val fontMetrics = badgeTextPaint.fontMetrics
        val textY = badgeTop + (badgeH - fontMetrics.bottom - fontMetrics.top) / 2f
        canvas.drawText(coordText, badgeLeft + padH, textY, badgeTextPaint)
    }

    private fun formatUnit(v: Double): String {
        if (abs(v) < 1e-10) return "0"
        val r = round(v * 10000.0) / 10000.0
        return if (r == floor(r)) {
            r.toLong().toString()
        } else {
            val formatted = String.format(Locale.US, "%.4f", r).trimEnd('0').trimEnd('.')
            if (formatted.isEmpty() || formatted == "-0") "0" else formatted
        }
    }

    private fun formatCoord(v: Double): String {
        if (abs(v) < 1e-12) return "0"
        val r = round(v * 10000.0) / 10000.0
        return if (r == floor(r)) {
            r.toLong().toString()
        } else {
            val formatted = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
            if (formatted.isEmpty() || formatted == "-0") "0" else formatted
        }
    }
}
