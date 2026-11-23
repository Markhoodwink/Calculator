package com.example.calculator

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.*

class NumberLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var result: InequalityResult? = null

    var isDarkTheme: Boolean = true
        set(value) {
            field = value
            updatePaints()
            invalidate()
        }

    var onPointOrIntervalSelected: ((String) -> Unit)? = null

    // Selection & scroll state
    private var selectedPoint: CriticalPoint? = null
    private var selectedInterval: IntervalSegment? = null
    private var scrollXOffset: Float = 0f
    private var maxScrollX: Float = 0f

    // Touch tracking
    private var touchStartX: Float = 0f
    private var touchStartY: Float = 0f
    private var initialScrollX: Float = 0f
    private var isDragging: Boolean = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Layout cache
    private val pointXMap = mutableMapOf<Int, Float>()
    private val intervalBoundsMap = mutableListOf<Triple<IntervalSegment, Float, Float>>()

    // Paints
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val solutionAxisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arcFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val pointIncludedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pointOdzExcludedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pointStrictExcludedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pointInnerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val odzBandBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val odzBandHatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val odzTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val signBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val signBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emptyPlaceholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tooltipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tooltipTextTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val tooltipTextDetailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val leaderLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        updatePaints()
    }

    private fun updatePaints() {
        val dp = resources.displayMetrics.density
        val sp = resources.displayMetrics.scaledDensity

        axisPaint.strokeWidth = 2f * dp
        solutionAxisPaint.strokeWidth = 4f * dp
        arcPaint.strokeWidth = 2.5f * dp
        hatchPaint.strokeWidth = 1.5f * dp
        pointOdzExcludedPaint.strokeWidth = 2.5f * dp
        pointStrictExcludedPaint.strokeWidth = 2.5f * dp
        odzBandHatchPaint.strokeWidth = 1f * dp
        tooltipStrokePaint.strokeWidth = 1.2f * dp

        leaderLinePaint.strokeWidth = 1.2f * dp
        leaderLinePaint.pathEffect = DashPathEffect(floatArrayOf(4f * dp, 4f * dp), 0f)

        labelTextPaint.textSize = 11f * sp
        signBadgeTextPaint.textSize = 12f * sp
        odzTextPaint.textSize = 9.5f * sp
        legendTextPaint.textSize = 10f * sp
        emptyPlaceholderPaint.textSize = 13f * sp
        tooltipTextTitlePaint.textSize = 11.5f * sp
        tooltipTextDetailPaint.textSize = 10.5f * sp

        if (isDarkTheme) {
            axisPaint.color = Color.parseColor("#64748B")
            arrowPaint.color = Color.parseColor("#94A3B8")
            solutionAxisPaint.color = Color.parseColor("#10B981")
            arcPaint.color = Color.parseColor("#64748B")
            arcFillPaint.color = Color.parseColor("#10B981")
            arcFillPaint.alpha = 35
            hatchPaint.color = Color.parseColor("#10B981")

            pointIncludedPaint.color = Color.parseColor("#10B981")
            pointOdzExcludedPaint.color = Color.parseColor("#F43F5E")
            pointStrictExcludedPaint.color = Color.parseColor("#38BDF8")
            pointInnerBgPaint.color = Color.parseColor("#0A0E17")
            haloPaint.color = Color.parseColor("#10B981")

            odzBandBgPaint.color = Color.parseColor("#F43F5E")
            odzBandBgPaint.alpha = 30
            odzBandHatchPaint.color = Color.parseColor("#F43F5E")
            odzBandHatchPaint.alpha = 120
            odzTextPaint.color = Color.parseColor("#F43F5E")

            signBadgeBgPaint.color = Color.parseColor("#1E293B")
            labelTextPaint.color = Color.parseColor("#F8FAFC")
            legendTextPaint.color = Color.parseColor("#94A3B8")
            emptyPlaceholderPaint.color = Color.parseColor("#64748B")

            tooltipBgPaint.color = Color.parseColor("#1E293B")
            tooltipStrokePaint.color = Color.parseColor("#334155")
            tooltipTextTitlePaint.color = Color.parseColor("#F8FAFC")
            tooltipTextDetailPaint.color = Color.parseColor("#94A3B8")
            leaderLinePaint.color = Color.parseColor("#38BDF8")
        } else {
            axisPaint.color = Color.parseColor("#475569")
            arrowPaint.color = Color.parseColor("#64748B")
            solutionAxisPaint.color = Color.parseColor("#059669")
            arcPaint.color = Color.parseColor("#94A3B8")
            arcFillPaint.color = Color.parseColor("#059669")
            arcFillPaint.alpha = 40
            hatchPaint.color = Color.parseColor("#059669")

            pointIncludedPaint.color = Color.parseColor("#059669")
            pointOdzExcludedPaint.color = Color.parseColor("#DC2626")
            pointStrictExcludedPaint.color = Color.parseColor("#0284C7")
            pointInnerBgPaint.color = Color.parseColor("#FFFFFF")
            haloPaint.color = Color.parseColor("#059669")

            odzBandBgPaint.color = Color.parseColor("#DC2626")
            odzBandBgPaint.alpha = 25
            odzBandHatchPaint.color = Color.parseColor("#DC2626")
            odzBandHatchPaint.alpha = 100
            odzTextPaint.color = Color.parseColor("#DC2626")

            signBadgeBgPaint.color = Color.parseColor("#FFFFFF")
            labelTextPaint.color = Color.parseColor("#0F172A")
            legendTextPaint.color = Color.parseColor("#475569")
            emptyPlaceholderPaint.color = Color.parseColor("#94A3B8")

            tooltipBgPaint.color = Color.parseColor("#FFFFFF")
            tooltipStrokePaint.color = Color.parseColor("#CBD5E1")
            tooltipTextTitlePaint.color = Color.parseColor("#0F172A")
            tooltipTextDetailPaint.color = Color.parseColor("#475569")
            leaderLinePaint.color = Color.parseColor("#0284C7")
        }
    }

    fun setResult(newResult: InequalityResult?) {
        this.result = newResult
        this.selectedPoint = null
        this.selectedInterval = null
        this.scrollXOffset = 0f
        invalidate()
    }

    fun setTheme(isDark: Boolean) {
        this.isDarkTheme = isDark
    }

    fun clear() {
        setResult(null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val dp = resources.displayMetrics.density
        val res = result

        if (res == null || (res.intervals.isEmpty() && res.criticalPoints.isEmpty())) {
            drawEmptyPlaceholder(canvas)
            return
        }

        try {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val axisY = viewHeight * 0.58f

            val numCp = res.criticalPoints.size
            val maxLabelWidth = if (numCp > 0) {
                res.criticalPoints.maxOf { labelTextPaint.measureText(it.label) }
            } else 0f
            val minSlotWidth = max(72f * dp, maxLabelWidth + 24f * dp)
            val sidePadding = 36f * dp

            val requiredWidth = sidePadding * 2 + (numCp + 1) * minSlotWidth
            val contentWidth = max(viewWidth, requiredWidth)
            val slotWidth = (contentWidth - sidePadding * 2) / (numCp + 1)
            maxScrollX = max(0f, contentWidth - viewWidth)
            scrollXOffset = scrollXOffset.coerceIn(0f, maxScrollX)

            pointXMap.clear()
            for (i in 0 until numCp) {
                val px = sidePadding + (i + 1) * slotWidth
                pointXMap[i] = px
            }

            intervalBoundsMap.clear()
            for (i in 0..numCp) {
                val xLeft = if (i == 0) sidePadding * 0.5f else pointXMap[i - 1]!!
                val xRight = if (i == numCp) contentWidth - sidePadding * 0.5f else pointXMap[i]!!
                if (i < res.intervals.size) {
                    intervalBoundsMap.add(Triple(res.intervals[i], xLeft, xRight))
                }
            }

            canvas.save()
            canvas.translate(-scrollXOffset, 0f)

            // Layer 0: Main Axis & Arrowhead
            val axisStart = 12f * dp
            val axisEnd = contentWidth - 12f * dp
            canvas.drawLine(axisStart, axisY, axisEnd, axisY, axisPaint)
            drawArrowHead(canvas, axisEnd, axisY, dp)

            // Layer 1: ODZ Exclusion Bands
            for ((seg, xLeft, xRight) in intervalBoundsMap) {
                if (!seg.isInDomain) {
                    drawOdzBand(canvas, xLeft, xRight, axisY, dp)
                }
            }

            // Layer 2: Method of Intervals Arcs, Signs & Solution Hatching
            for ((seg, xLeft, xRight) in intervalBoundsMap) {
                if (seg.isInDomain) {
                    drawIntervalArcAndSign(canvas, seg, xLeft, xRight, axisY, dp)
                }
            }

            // Layer 3: Critical Points & Labels
            for (i in 0 until numCp) {
                val cp = res.criticalPoints[i]
                val px = pointXMap[i]!!
                val leftSol = res.intervals.getOrNull(i)?.isSolution == true
                val rightSol = res.intervals.getOrNull(i + 1)?.isSolution == true
                val isIsolated = cp.isIncluded && !leftSol && !rightSol

                val isSelected = (selectedPoint == cp)
                drawCriticalPoint(canvas, cp, px, axisY, isIsolated, isSelected, dp)

                val labelY = if (i % 2 == 0) axisY + 22f * dp else axisY + 36f * dp
                drawPointLabel(canvas, cp.label, px, labelY, isSelected)
            }

            canvas.restore()

            // Un-scrolled overlays: Legend & Tooltip
            drawLegend(canvas, dp)
            drawTooltipCard(canvas, viewWidth, axisY, dp)
        } catch (t: Throwable) {
            drawEmptyPlaceholder(canvas)
        }
    }

    private fun drawEmptyPlaceholder(canvas: Canvas) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val axisY = viewHeight * 0.58f
        val dp = resources.displayMetrics.density

        val axisStart = 16f * dp
        val axisEnd = viewWidth - 16f * dp
        canvas.drawLine(axisStart, axisY, axisEnd, axisY, axisPaint)
        drawArrowHead(canvas, axisEnd, axisY, dp)

        val fontMetrics = emptyPlaceholderPaint.fontMetrics
        val textY = axisY - 24f * dp - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText("Введите неравенство для построения числовой прямой", viewWidth / 2f, textY, emptyPlaceholderPaint)
    }

    private fun drawArrowHead(canvas: Canvas, x: Float, y: Float, dp: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x - 10f * dp, y - 5f * dp)
            lineTo(x - 10f * dp, y + 5f * dp)
            close()
        }
        canvas.drawPath(path, arrowPaint)
        canvas.drawText("x", x - 2f * dp, y - 8f * dp, labelTextPaint)
    }

    private fun drawOdzBand(canvas: Canvas, xLeft: Float, xRight: Float, axisY: Float, dp: Float) {
        val topY = axisY + 2f * dp
        val botY = axisY + 18f * dp
        val rect = RectF(xLeft, topY, xRight, botY)
        canvas.drawRect(rect, odzBandBgPaint)

        canvas.save()
        canvas.clipRect(rect)
        var hx = xLeft - (botY - topY)
        while (hx < xRight + (botY - topY)) {
            canvas.drawLine(hx, botY, hx + (botY - topY), topY, odzBandHatchPaint)
            hx += 6f * dp
        }
        canvas.restore()

        if ((xRight - xLeft) > 50f * dp) {
            val fontMetrics = odzTextPaint.fontMetrics
            val textY = topY + (botY - topY) / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f
            canvas.drawText("вне ОДЗ", (xLeft + xRight) / 2f, textY, odzTextPaint)
        }
    }

    private fun drawIntervalArcAndSign(
        canvas: Canvas,
        seg: IntervalSegment,
        xLeft: Float,
        xRight: Float,
        axisY: Float,
        dp: Float
    ) {
        val arcHeight = 38f * dp

        val arcPath = Path().apply {
            moveTo(xLeft, axisY)
            quadTo((xLeft + xRight) / 2f, axisY - arcHeight * 1.8f, xRight, axisY)
        }

        if (seg.isSolution) {
            canvas.drawLine(xLeft, axisY, xRight, axisY, solutionAxisPaint)

            val fillPath = Path(arcPath).apply {
                lineTo(xRight, axisY)
                lineTo(xLeft, axisY)
                close()
            }
            canvas.drawPath(fillPath, arcFillPaint)

            canvas.save()
            canvas.clipPath(fillPath)
            var hx = xLeft - arcHeight
            while (hx < xRight + arcHeight) {
                canvas.drawLine(hx, axisY, hx + arcHeight, axisY - arcHeight * 2f, hatchPaint)
                hx += 8f * dp
            }
            canvas.restore()
        }

        canvas.drawPath(arcPath, arcPaint)

        val midX = (xLeft + xRight) / 2f
        val badgeY = axisY - arcHeight * 0.9f
        val signStr = if (seg.sign > 0) "+" else if (seg.sign < 0) "−" else "0"

        val signColor = when {
            seg.sign > 0 -> if (isDarkTheme) Color.parseColor("#10B981") else Color.parseColor("#059669")
            seg.sign < 0 -> if (isDarkTheme) Color.parseColor("#F43F5E") else Color.parseColor("#DC2626")
            else -> if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
        }

        val badgeRadius = 10f * dp
        canvas.drawCircle(midX, badgeY, badgeRadius, signBadgeBgPaint)
        signBadgeTextPaint.color = signColor
        val fontMetrics = signBadgeTextPaint.fontMetrics
        val textY = badgeY - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(signStr, midX, textY, signBadgeTextPaint)
    }

    private fun drawCriticalPoint(
        canvas: Canvas,
        cp: CriticalPoint,
        x: Float,
        y: Float,
        isIsolated: Boolean,
        isSelected: Boolean,
        dp: Float
    ) {
        val radius = 6.5f * dp

        if (isIsolated) {
            haloPaint.alpha = 50
            canvas.drawCircle(x, y, 14f * dp, haloPaint)
            haloPaint.alpha = 100
            canvas.drawCircle(x, y, 10f * dp, haloPaint)
        }

        if (isSelected) {
            val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * dp
                color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
            }
            canvas.drawCircle(x, y, 11f * dp, selPaint)
        }

        if (cp.isIncluded) {
            canvas.drawCircle(x, y, radius, pointIncludedPaint)
        } else if (cp.isOdzExcluded) {
            canvas.drawCircle(x, y, radius, pointInnerBgPaint)
            canvas.drawCircle(x, y, radius, pointOdzExcludedPaint)
        } else {
            canvas.drawCircle(x, y, radius, pointInnerBgPaint)
            canvas.drawCircle(x, y, radius, pointStrictExcludedPaint)
        }
    }

    private fun drawPointLabel(
        canvas: Canvas,
        label: String,
        x: Float,
        y: Float,
        isSelected: Boolean
    ) {
        val paint = if (isSelected) {
            Paint(labelTextPaint).apply {
                color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
                isFakeBoldText = true
            }
        } else {
            labelTextPaint
        }
        canvas.drawText(label, x, y, paint)
    }

    private fun drawLegend(canvas: Canvas, dp: Float) {
        val startX = 12f * dp
        val startY = 16f * dp
        val dotRadius = 4f * dp

        canvas.drawCircle(startX + dotRadius, startY - 3f * dp, dotRadius, pointIncludedPaint)
        canvas.drawText("входит", startX + dotRadius * 2 + 4f * dp, startY, legendTextPaint)

        val x2 = startX + 65f * dp
        canvas.drawCircle(x2 + dotRadius, startY - 3f * dp, dotRadius, pointInnerBgPaint)
        canvas.drawCircle(x2 + dotRadius, startY - 3f * dp, dotRadius, pointOdzExcludedPaint)
        canvas.drawText("выколота", x2 + dotRadius * 2 + 4f * dp, startY, legendTextPaint)

        val x3 = x2 + 80f * dp
        canvas.drawLine(x3, startY - 3f * dp, x3 + 14f * dp, startY - 3f * dp, solutionAxisPaint)
        canvas.drawText("ответ", x3 + 18f * dp, startY, legendTextPaint)

        val res = result
        if (res?.isPeriodic == true) {
            val legendRightX = width.toFloat() - 12f * dp
            val periodicText = "Период [0; 2π)  •  + 2πk, k ∈ ℤ"
            val periodicPaint = Paint(legendTextPaint).apply {
                color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(periodicText, legendRightX, startY, periodicPaint)
        }
    }

    private fun drawTooltipCard(
        canvas: Canvas,
        viewWidth: Float,
        axisY: Float,
        dp: Float
    ) {
        val selPt = selectedPoint
        val selInt = selectedInterval
        if (selPt == null && selInt == null) return

        val titleText: String
        val detailText: String
        val targetContentX: Float
        val targetContentY: Float

        if (selPt != null) {
            val statusStr = if (selPt.isIncluded) "Включена" else "Выколота"
            titleText = "x = ${selPt.label}   •   $statusStr"
            detailText = selPt.reason
            val idx = result?.criticalPoints?.indexOf(selPt) ?: -1
            targetContentX = if (idx >= 0 && pointXMap.containsKey(idx)) pointXMap[idx]!! else viewWidth / 2f
            targetContentY = axisY
        } else {
            val seg = selInt!!
            val signStr = if (seg.sign > 0) "+" else if (seg.sign < 0) "−" else "0"
            val statusStr = if (!seg.isInDomain) "Вне ОДЗ" else if (seg.isSolution) "Входит в ответ" else "Не входит"
            titleText = "(${seg.startLabel}; ${seg.endLabel})   •   $statusStr"

            val signCmp = if (seg.sign > 0) "> 0" else if (seg.sign < 0) "< 0" else "= 0"
            detailText = if (!seg.isInDomain) {
                "Выражение не определено на этом интервале"
            } else if (seg.sampleLabel.isNotEmpty()) {
                "Пробная точка: x = ${seg.sampleLabel} ⇒ f(${seg.sampleLabel}) = ${seg.sampleValueLabel} ($signCmp)"
            } else {
                "Знак функции f(x) на интервале: $signStr"
            }

            var midX = viewWidth / 2f
            for ((s, xl, xr) in intervalBoundsMap) {
                if (s == seg) {
                    midX = (xl + xr) / 2f
                    break
                }
            }
            targetContentX = midX
            targetContentY = axisY - 25f * dp
        }

        val screenTargetX = targetContentX - scrollXOffset
        val cardTop = 26f * dp
        val cardBottom = cardTop + 42f * dp
        canvas.drawLine(screenTargetX, cardBottom, screenTargetX, targetContentY, leaderLinePaint)

        val cardLeft = 12f * dp
        val cardRight = viewWidth - 12f * dp
        val rect = RectF(cardLeft, cardTop, cardRight, cardBottom)

        canvas.drawRoundRect(rect, 8f * dp, 8f * dp, tooltipBgPaint)
        canvas.drawRoundRect(rect, 8f * dp, 8f * dp, tooltipStrokePaint)

        canvas.drawText(titleText, cardLeft + 10f * dp, cardTop + 16f * dp, tooltipTextTitlePaint)

        val maxDetailWidth = cardRight - cardLeft - 20f * dp
        var textPaintToUse = tooltipTextDetailPaint
        if (tooltipTextDetailPaint.measureText(detailText) > maxDetailWidth) {
            textPaintToUse = Paint(tooltipTextDetailPaint).apply {
                textSize = 9.5f * resources.displayMetrics.scaledDensity
            }
        }
        canvas.drawText(detailText, cardLeft + 10f * dp, cardTop + 32f * dp, textPaintToUse)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val res = result ?: return super.onTouchEvent(event)
        if (res.criticalPoints.isEmpty() && res.intervals.isEmpty()) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                initialScrollX = scrollXOffset
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = touchStartX - event.x
                if (abs(dx) > touchSlop || isDragging) {
                    isDragging = true
                    scrollXOffset = (initialScrollX + dx).coerceIn(0f, maxScrollX)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    val contentX = event.x + scrollXOffset
                    val touchY = event.y

                    val dp = resources.displayMetrics.density
                    val axisY = height * 0.58f

                    var hitPoint: CriticalPoint? = null
                    for (i in 0 until res.criticalPoints.size) {
                        val px = pointXMap[i] ?: continue
                        val dist = hypot((contentX - px).toDouble(), (touchY - axisY).toDouble())
                        if (dist <= 22f * dp) {
                            hitPoint = res.criticalPoints[i]
                            break
                        }
                    }

                    if (hitPoint != null) {
                        selectedPoint = hitPoint
                        selectedInterval = null
                        val status = if (hitPoint.isIncluded) "включена" else "выколота"
                        onPointOrIntervalSelected?.invoke("x = ${hitPoint.label}: $status")
                        performClick()
                        invalidate()
                        return true
                    }

                    var hitInterval: IntervalSegment? = null
                    for ((seg, xl, xr) in intervalBoundsMap) {
                        if (contentX >= xl && contentX <= xr && touchY >= axisY - 50f * dp && touchY <= axisY + 30f * dp) {
                            hitInterval = seg
                            break
                        }
                    }

                    if (hitInterval != null) {
                        selectedInterval = hitInterval
                        selectedPoint = null
                        val signStr = if (hitInterval.sign > 0) "+" else if (hitInterval.sign < 0) "−" else "0"
                        val desc = if (!hitInterval.isInDomain) "вне ОДЗ" else "знак $signStr"
                        onPointOrIntervalSelected?.invoke("Интервал (${hitInterval.startLabel}; ${hitInterval.endLabel}): $desc")
                        performClick()
                        invalidate()
                        return true
                    }

                    selectedPoint = null
                    selectedInterval = null
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
