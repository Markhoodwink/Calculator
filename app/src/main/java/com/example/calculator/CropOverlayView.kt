package com.example.calculator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    val cropRect = RectF()
    private var imageBounds: RectF? = null

    private enum class DragMode {
        NONE, TL, TR, BL, BR, FRAME
    }

    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    var onTapToFocus: ((Float, Float) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var hasMoved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var focusPointX = -1f
    private var focusPointY = -1f
    private var focusStartTimeMs = 0L

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clearXfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x80FFFFFF.toInt()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF10B981.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val focusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF10B981.toInt()
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && cropRect.isEmpty) {
            resetCropRect()
        }
    }

    fun setImageBounds(bounds: RectF?) {
        imageBounds = bounds?.let { RectF(it) }
        resetCropRect()
    }

    fun resetCropRect() {
        val w = width.toFloat()
        val h = height.toFloat()
        val eff = imageBounds ?: if (w > 0f && h > 0f) {
            RectF(paddingLeft.toFloat(), paddingTop.toFloat(), w - paddingRight.toFloat(), h - paddingBottom.toFloat())
        } else null

        if (eff != null && eff.width() > 0f && eff.height() > 0f) {
            val dp = resources.displayMetrics.density
            val left = eff.left + eff.width() * 0.08f
            val right = eff.left + eff.width() * 0.92f

            val effH = eff.height()
            val targetBoxH = 110f * dp
            val minBoxH = (56f * dp).coerceAtMost(effH)
            val boxHeight = targetBoxH.coerceAtMost(effH * 0.45f).coerceAtLeast(minBoxH)

            val centerY = if (imageBounds != null) eff.centerY() else eff.top + effH * 0.42f
            val top = (centerY - boxHeight / 2f).coerceAtLeast(eff.top)
            val bottom = (centerY + boxHeight / 2f).coerceAtMost(eff.bottom)

            cropRect.set(left, top, right, bottom)
            invalidate()
        }
    }

    fun showFocusIndicator(x: Float, y: Float) {
        focusPointX = x
        focusPointY = y
        focusStartTimeMs = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (cropRect.isEmpty) {
            resetCropRect()
        }

        val dp = resources.displayMetrics.density

        // 1. Dark translucent scrim outside rounded cropRect
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        scrimPaint.style = Paint.Style.FILL
        scrimPaint.color = 0xAA000000.toInt()
        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        scrimPaint.xfermode = clearXfermode
        val cornerRadius = 12f * dp
        canvas.drawRoundRect(cropRect, cornerRadius, cornerRadius, scrimPaint)
        scrimPaint.xfermode = null
        canvas.restoreToCount(layer)

        // 2. Crisp white border
        borderPaint.strokeWidth = 1.5f * dp
        canvas.drawRoundRect(cropRect, cornerRadius, cornerRadius, borderPaint)

        // 3. Crisp emerald L-shaped corner handles
        handlePaint.strokeWidth = 3.5f * dp
        val armLen = 22f * dp
        val l = cropRect.left
        val t = cropRect.top
        val r = cropRect.right
        val b = cropRect.bottom

        // Top-Left
        canvas.drawLine(l, t + armLen, l, t, handlePaint)
        canvas.drawLine(l, t, l + armLen, t, handlePaint)

        // Top-Right
        canvas.drawLine(r - armLen, t, r, t, handlePaint)
        canvas.drawLine(r, t, r, t + armLen, handlePaint)

        // Bottom-Left
        canvas.drawLine(l, b - armLen, l, b, handlePaint)
        canvas.drawLine(l, b, l + armLen, b, handlePaint)

        // Bottom-Right
        canvas.drawLine(r - armLen, b, r, b, handlePaint)
        canvas.drawLine(r, b, r, b - armLen, handlePaint)

        // 4. Tap-to-focus emerald ring animation
        val elapsed = SystemClock.uptimeMillis() - focusStartTimeMs
        if (elapsed in 0L..650L) {
            val progress = elapsed / 650f
            val startRadius = 34f * dp
            val endRadius = 22f * dp
            val radius = startRadius - (startRadius - endRadius) * progress

            val alpha = if (elapsed < 400L) {
                255
            } else {
                val fadeProgress = (elapsed - 400L) / 250f
                ((1f - fadeProgress) * 255f).toInt().coerceIn(0, 255)
            }

            focusRingPaint.strokeWidth = 2f * dp
            focusRingPaint.alpha = alpha
            canvas.drawCircle(focusPointX, focusPointY, radius, focusRingPaint)
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val dp = resources.displayMetrics.density
        val hitRadius = 28f * dp

        val b = imageBounds
        val leftLimit = b?.left ?: paddingLeft.toFloat()
        val topLimit = b?.top ?: paddingTop.toFloat()
        val rightLimit = b?.right ?: (width - paddingRight).toFloat()
        val bottomLimit = b?.bottom ?: (height - paddingBottom).toFloat()

        val effW = (rightLimit - leftLimit).coerceAtLeast(1f)
        val effH = (bottomLimit - topLimit).coerceAtLeast(1f)

        val minWidthPx = (100f * dp).coerceAtMost(effW)
        val minHeightPx = (56f * dp).coerceAtMost(effH)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                lastTouchX = x
                lastTouchY = y
                downX = x
                downY = y
                hasMoved = false

                val distTL = hypot(x - cropRect.left, y - cropRect.top)
                val distTR = hypot(x - cropRect.right, y - cropRect.top)
                val distBL = hypot(x - cropRect.left, y - cropRect.bottom)
                val distBR = hypot(x - cropRect.right, y - cropRect.bottom)

                dragMode = when {
                    distTL <= hitRadius -> DragMode.TL
                    distTR <= hitRadius -> DragMode.TR
                    distBL <= hitRadius -> DragMode.BL
                    distBR <= hitRadius -> DragMode.BR
                    cropRect.contains(x, y) -> DragMode.FRAME
                    else -> DragMode.NONE
                }

                return dragMode != DragMode.NONE || onTapToFocus != null
            }

            MotionEvent.ACTION_MOVE -> {
                val x = event.x
                val y = event.y

                if (hypot(x - downX, y - downY) > touchSlop) {
                    hasMoved = true
                }

                if (dragMode == DragMode.NONE) {
                    return onTapToFocus != null
                }

                when (dragMode) {
                    DragMode.TL -> {
                        cropRect.left = x.coerceIn(leftLimit, cropRect.right - minWidthPx)
                        cropRect.top = y.coerceIn(topLimit, cropRect.bottom - minHeightPx)
                    }

                    DragMode.TR -> {
                        cropRect.right = x.coerceIn(cropRect.left + minWidthPx, rightLimit)
                        cropRect.top = y.coerceIn(topLimit, cropRect.bottom - minHeightPx)
                    }

                    DragMode.BL -> {
                        cropRect.left = x.coerceIn(leftLimit, cropRect.right - minWidthPx)
                        cropRect.bottom = y.coerceIn(cropRect.top + minHeightPx, bottomLimit)
                    }

                    DragMode.BR -> {
                        cropRect.right = x.coerceIn(cropRect.left + minWidthPx, rightLimit)
                        cropRect.bottom = y.coerceIn(cropRect.top + minHeightPx, bottomLimit)
                    }

                    DragMode.FRAME -> {
                        val dx = x - lastTouchX
                        val dy = y - lastTouchY
                        val rectWidth = cropRect.width()
                        val rectHeight = cropRect.height()

                        val newLeft = (cropRect.left + dx).coerceIn(leftLimit, rightLimit - rectWidth)
                        val newTop = (cropRect.top + dy).coerceIn(topLimit, bottomLimit - rectHeight)
                        cropRect.set(newLeft, newTop, newLeft + rectWidth, newTop + rectHeight)

                        lastTouchX = x
                        lastTouchY = y
                    }

                    DragMode.NONE -> {}
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                if (event.action == MotionEvent.ACTION_UP && !hasMoved && onTapToFocus != null) {
                    onTapToFocus?.invoke(event.x, event.y)
                    showFocusIndicator(event.x, event.y)
                }
                dragMode = DragMode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun getNormalizedCropRect(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return RectF(0.08f, 0.35f, 0.92f, 0.49f)
        return RectF(
            (cropRect.left / w).coerceIn(0f, 1f),
            (cropRect.top / h).coerceIn(0f, 1f),
            (cropRect.right / w).coerceIn(0f, 1f),
            (cropRect.bottom / h).coerceIn(0f, 1f)
        )
    }

    fun getNormalizedCropRectInImage(): RectF {
        val b = imageBounds
        if (b != null && b.width() > 0f && b.height() > 0f) {
            return RectF(
                ((cropRect.left - b.left) / b.width()).coerceIn(0f, 1f),
                ((cropRect.top - b.top) / b.height()).coerceIn(0f, 1f),
                ((cropRect.right - b.left) / b.width()).coerceIn(0f, 1f),
                ((cropRect.bottom - b.top) / b.height()).coerceIn(0f, 1f)
            )
        }
        return getNormalizedCropRect()
    }
}
