package com.example.calculator

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * Интерактивная тригонометрическая окружность (ЕГЭ) для отображения:
 * - Координатных осей cos x и sin x
 * - Единичной окружности с ключевыми засечками (0, π/6, π/4, π/3, π/2, π, 3π/2)
 * - Выделенной дуги выбранного отрезка [a; b]
 * - Точек корней на окружности с аккуратными плашками (pill labels) без наложения.
 */
class UnitCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class UnitCirclePoint(
        val angleRad: Double,
        val label: String
    )

    var isDarkTheme: Boolean = true
        set(value) {
            field = value
            updatePaints()
            invalidate()
        }

    private var points: List<UnitCirclePoint> = emptyList()
    private var intervalStartRad: Double? = null
    private var intervalEndRad: Double? = null

    // Кисти
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val rootOuterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rootInnerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val tickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    init {
        updatePaints()
    }

    private fun updatePaints() {
        val dp = resources.displayMetrics.density

        axisPaint.strokeWidth = 1.5f * dp
        circlePaint.strokeWidth = 2f * dp
        tickPaint.strokeWidth = 1.5f * dp
        arcPaint.strokeWidth = 5.5f * dp
        pillStrokePaint.strokeWidth = 1.2f * dp

        pillTextPaint.textSize = 10.5f * resources.displayMetrics.scaledDensity
        labelPaint.textSize = 11.5f * resources.displayMetrics.scaledDensity
        tickTextPaint.textSize = 9.5f * resources.displayMetrics.scaledDensity

        if (isDarkTheme) {
            axisPaint.color = Color.parseColor("#64748B")
            arrowPaint.color = Color.parseColor("#94A3B8")
            circlePaint.color = Color.parseColor("#334155")
            tickPaint.color = Color.parseColor("#475569")
            arcPaint.color = Color.parseColor("#10B981")
            rootOuterDotPaint.color = Color.parseColor("#F59E0B")
            rootInnerDotPaint.color = Color.parseColor("#FFFFFF")
            pillBgPaint.color = Color.parseColor("#1E293B")
            pillStrokePaint.color = Color.parseColor("#F59E0B")
            pillTextPaint.color = Color.parseColor("#F8FAFC")
            labelPaint.color = Color.parseColor("#94A3B8")
            tickTextPaint.color = Color.parseColor("#64748B")
        } else {
            axisPaint.color = Color.parseColor("#94A3B8")
            arrowPaint.color = Color.parseColor("#64748B")
            circlePaint.color = Color.parseColor("#CBD5E1")
            tickPaint.color = Color.parseColor("#94A3B8")
            arcPaint.color = Color.parseColor("#059669")
            rootOuterDotPaint.color = Color.parseColor("#D97706")
            rootInnerDotPaint.color = Color.parseColor("#FFFFFF")
            pillBgPaint.color = Color.parseColor("#FFFFFF")
            pillStrokePaint.color = Color.parseColor("#D97706")
            pillTextPaint.color = Color.parseColor("#0F172A")
            labelPaint.color = Color.parseColor("#475569")
            tickTextPaint.color = Color.parseColor("#94A3B8")
        }
    }

    /**
     * Передать результат решения и границы отрезка для визуализации.
     */
    fun setSolution(
        result: TrigSolverEngine.TrigSolveResult,
        intervalStart: Double?,
        intervalEnd: Double?
    ) {
        this.intervalStartRad = intervalStart
        this.intervalEndRad = intervalEnd

        val newPoints = mutableListOf<UnitCirclePoint>()

        if (result.rootsOnInterval.isNotEmpty()) {
            // Группируем корни на отрезке по углу на окружности (чтобы избежать наложений)
            for (r in result.rootsOnInterval) {
                val existing = newPoints.find { abs(it.angleRad - r.angleOnCircle) < 0.05 }
                if (existing != null) {
                    val idx = newPoints.indexOf(existing)
                    val existingLabels = existing.label.split(", ")
                    val newLabel = if (!existingLabels.contains(r.symbolic)) {
                        "${existing.label}, ${r.symbolic}"
                    } else existing.label
                    newPoints[idx] = UnitCirclePoint(existing.angleRad, newLabel)
                } else {
                    newPoints.add(UnitCirclePoint(r.angleOnCircle, r.symbolic))
                }
            }
        } else {
            // Если отбора нет или на отрезке корней нет, показываем базовые точки общего решения
            for (series in result.generalSolutions) {
                for (s in series.seriesList) {
                    val angles = s.circleAngles()
                    for (ang in angles) {
                        val sym = formatAngle(ang)
                        if (newPoints.none { abs(it.angleRad - ang) < 0.05 }) {
                            newPoints.add(UnitCirclePoint(ang, sym))
                        }
                    }
                }
            }
        }

        this.points = newPoints
        invalidate()
    }

    fun clear() {
        points = emptyList()
        intervalStartRad = null
        intervalEndRad = null
        invalidate()
    }

    private fun formatAngle(rad: Double): String {
        val f = CasEngine.Fraction.fromDouble(rad / Math.PI)
        return if (abs(f.toDouble() * Math.PI - rad) < 1e-5 && f.den <= 12L) {
            TrigSolverEngine.formatFracPi(f)
        } else {
            CasEngine.Fraction.formatDoubleNorm1(rad)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val dp = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h / 2f

        // Радиус окружности (оставляем поля для плашек и стрелок осей)
        val R = (minOf(w, h) / 2f) - 38f * dp
        if (R <= 10f * dp) return

        // 1. Координатные оси cos x и sin x
        drawAxes(canvas, cx, cy, R, dp)

        // 2. Опорные засечки (0, π/6, π/4, π/3, π/2, π, 3π/2)
        drawReferenceTicks(canvas, cx, cy, R, dp)

        // 3. Единичная окружность
        canvas.drawCircle(cx, cy, R, circlePaint)

        // 4. Выделенная дуга отрезка [a; b]
        drawIntervalArc(canvas, cx, cy, R, dp)

        // 5. Точки корней и плашки с подписями
        drawRootPointsAndPills(canvas, cx, cy, R, dp)
    }

    private fun drawAxes(canvas: Canvas, cx: Float, cy: Float, R: Float, dp: Float) {
        val axisExtend = 22f * dp
        val arrowSize = 5f * dp

        // Горизонтальная ось: cos x
        val xStart = cx - R - axisExtend
        val xEnd = cx + R + axisExtend
        canvas.drawLine(xStart, cy, xEnd, cy, axisPaint)

        // Стрелка оси X
        val arrowX = Path().apply {
            moveTo(xEnd, cy)
            lineTo(xEnd - arrowSize * 1.5f, cy - arrowSize)
            lineTo(xEnd - arrowSize * 1.5f, cy + arrowSize)
            close()
        }
        canvas.drawPath(arrowX, arrowPaint)
        canvas.drawText("cos x", xEnd - 2f * dp, cy + 14f * dp, labelPaint)

        // Вертикальная ось: sin x
        val yStart = cy + R + axisExtend
        val yEnd = cy - R - axisExtend
        canvas.drawLine(cx, yStart, cx, yEnd, axisPaint)

        // Стрелка оси Y
        val arrowY = Path().apply {
            moveTo(cx, yEnd)
            lineTo(cx - arrowSize, yEnd + arrowSize * 1.5f)
            lineTo(cx + arrowSize, yEnd + arrowSize * 1.5f)
            close()
        }
        canvas.drawPath(arrowY, arrowPaint)
        canvas.drawText("sin x", cx + 18f * dp, yEnd + 8f * dp, labelPaint)
    }

    private fun drawReferenceTicks(canvas: Canvas, cx: Float, cy: Float, R: Float, dp: Float) {
        val refAngles = listOf(
            0.0,
            Math.PI / 6.0,
            Math.PI / 4.0,
            Math.PI / 3.0,
            Math.PI / 2.0,
            2.0 * Math.PI / 3.0,
            3.0 * Math.PI / 4.0,
            5.0 * Math.PI / 6.0,
            Math.PI,
            7.0 * Math.PI / 6.0,
            5.0 * Math.PI / 4.0,
            4.0 * Math.PI / 3.0,
            3.0 * Math.PI / 2.0,
            5.0 * Math.PI / 3.0,
            7.0 * Math.PI / 4.0,
            11.0 * Math.PI / 6.0
        )

        val tickLen = 3.5f * dp
        for (alpha in refAngles) {
            val cosA = cos(alpha).toFloat()
            val sinA = sin(alpha).toFloat()
            val x1 = cx + (R - tickLen) * cosA
            val y1 = cy - (R - tickLen) * sinA
            val x2 = cx + (R + tickLen) * cosA
            val y2 = cy - (R + tickLen) * sinA
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // Засечки 1 и -1 на осях
        canvas.drawText("1", cx + R - 6f * dp, cy + 12f * dp, tickTextPaint)
        canvas.drawText("-1", cx - R + 8f * dp, cy + 12f * dp, tickTextPaint)
        canvas.drawText("1", cx - 10f * dp, cy - R + 12f * dp, tickTextPaint)
        canvas.drawText("-1", cx - 12f * dp, cy + R - 4f * dp, tickTextPaint)
    }

    private fun drawIntervalArc(canvas: Canvas, cx: Float, cy: Float, R: Float, dp: Float) {
        val start = intervalStartRad ?: return
        val end = intervalEndRad ?: return
        if (start.isNaN() || end.isNaN() || start.isInfinite() || end.isInfinite()) return

        val a = minOf(start, end)
        val b = maxOf(start, end)
        val delta = b - a

        val oval = RectF(cx - R, cy - R, cx + R, cy + R)

        if (delta >= 2 * Math.PI - 1e-5) {
            // Полная окружность
            canvas.drawArc(oval, 0f, 360f, false, arcPaint)
        } else {
            // На Android Canvas: угол 0° - вправо (+X), положительный угол идёт ПО часовой стрелке!
            // В математике: положительный угол идёт ПРОТИВ часовой стрелки.
            // Поэтому startDeg = -a * (180 / π), sweepDeg = -delta * (180 / π).
            val startDeg = Math.toDegrees(-a).toFloat()
            val sweepDeg = Math.toDegrees(-delta).toFloat()
            canvas.drawArc(oval, startDeg, sweepDeg, false, arcPaint)

            // Точки-концы отрезка
            val pxa = cx + R * cos(a).toFloat()
            val pya = cy - R * sin(a).toFloat()
            val pxb = cx + R * cos(b).toFloat()
            val pyb = cy - R * sin(b).toFloat()

            val endDotPaint = Paint(arcPaint).apply { style = Paint.Style.FILL }
            canvas.drawCircle(pxa, pya, 3.5f * dp, endDotPaint)
            canvas.drawCircle(pxb, pyb, 3.5f * dp, endDotPaint)
        }
    }

    private fun drawRootPointsAndPills(canvas: Canvas, cx: Float, cy: Float, R: Float, dp: Float) {
        val pillDist = R + 16f * dp
        val padH = 5f * dp
        val padV = 2.5f * dp

        for (pt in points) {
            val ang = pt.angleRad
            val cosA = cos(ang).toFloat()
            val sinA = sin(ang).toFloat()

            // 1. Точка корня на самой окружности
            val px = cx + R * cosA
            val py = cy - R * sinA
            canvas.drawCircle(px, py, 5f * dp, rootOuterDotPaint)
            canvas.drawCircle(px, py, 2.2f * dp, rootInnerDotPaint)

            // 2. Плашка (Pill Label)
            val text = pt.label
            val textWidth = pillTextPaint.measureText(text)
            val pillW = textWidth + padH * 2
            val pillH = 15f * dp

            val lx = cx + pillDist * cosA
            val ly = cy - pillDist * sinA

            // Ограничение плашки внутри видимой области View
            val left = (lx - pillW / 2f).coerceIn(3f * dp, width - pillW - 3f * dp)
            val top = (ly - pillH / 2f).coerceIn(3f * dp, height - pillH - 3f * dp)
            val pillRect = RectF(left, top, left + pillW, top + pillH)

            // Рисуем плашку
            canvas.drawRoundRect(pillRect, 5f * dp, 5f * dp, pillBgPaint)
            canvas.drawRoundRect(pillRect, 5f * dp, 5f * dp, pillStrokePaint)

            // Текст внутри плашки
            val textY = top + (pillH / 2f) - ((pillTextPaint.descent() + pillTextPaint.ascent()) / 2f)
            canvas.drawText(text, left + pillW / 2f, textY, pillTextPaint)
        }
    }
}
