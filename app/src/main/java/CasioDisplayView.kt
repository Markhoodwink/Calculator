package com.example.calculator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max

class CasioDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- ЭЛЕМЕНТЫ НАТУРАЛЬНОГО ДИСПЛЕЯ CASIO ---
    sealed class MathItem {
        data class Symbol(var text: String) : MathItem()
        data class Fraction(val num: MathContainer, val den: MathContainer) : MathItem()
        data class MixedFraction(val whole: MathContainer = MathContainer(), val num: MathContainer = MathContainer(), val den: MathContainer = MathContainer()) : MathItem()
        data class Sqrt(val inner: MathContainer, val degree: String? = null) : MathItem()
        data class Superscript(val exp: MathContainer) : MathItem()
        data class Combinatorics(
            val symbol: String, // "C" or "A"
            val upper: MathContainer = MathContainer(),
            val lower: MathContainer = MathContainer()
        ) : MathItem()
        data class LogBase(
            val base: MathContainer = MathContainer(),
            val arg: MathContainer = MathContainer()
        ) : MathItem()
    }

    class MathContainer(var parent: MathContainer? = null) {
        val items = mutableListOf<MathItem>()

        fun toRawString(): String {
            val sb = StringBuilder()
            for (item in items) {
                when (item) {
                    is MathItem.Symbol -> {
                        val clean = item.text
                            .replace("×", "*")
                            .replace("·", "*")
                            .replace("⋅", "*")
                            .replace("∙", "*")
                            .replace("÷", "/")
                            .replace("−", "-")
                        sb.append(clean)
                    }
                    is MathItem.Fraction -> {
                        val n = if (item.num.isEmpty()) "0" else item.num.toRawString()
                        val d = if (item.den.isEmpty()) "1" else item.den.toRawString()
                        sb.append("(($n)/($d))")
                    }
                    is MathItem.MixedFraction -> {
                        val wholeRaw = if (item.whole.isEmpty() || item.whole.toRawString().isBlank()) "0" else item.whole.toRawString()
                        val numRaw = if (item.num.isEmpty() || item.num.toRawString().isBlank()) "0" else item.num.toRawString()
                        val denRaw = if (item.den.isEmpty() || item.den.toRawString().isBlank()) "1" else item.den.toRawString()
                        val trimmedW = wholeRaw.trim()
                        if (trimmedW.startsWith("-")) {
                            val posW = trimmedW.substring(1).ifBlank { "0" }
                            sb.append("-(( $posW )+($numRaw)/($denRaw))")
                        } else {
                            sb.append("(($trimmedW)+($numRaw)/($denRaw))")
                        }
                    }
                    is MathItem.Sqrt -> {
                        val inn = if (item.inner.isEmpty()) "0" else item.inner.toRawString()
                        sb.append(if (item.degree != null) "cbrt($inn)" else "√($inn)")
                    }
                    is MathItem.Superscript -> {
                        val e = if (item.exp.isEmpty()) "1" else item.exp.toRawString()
                        sb.append("^($e)")
                    }
                    is MathItem.Combinatorics -> {
                        val func = if (item.symbol == "A") "perm" else "comb"
                        val l = item.lower.toRawString()
                        val u = item.upper.toRawString()
                        sb.append("$func(($l),($u))")
                    }
                    is MathItem.LogBase -> {
                        val b = item.base.toRawString()
                        val a = item.arg.toRawString()
                        sb.append("log(($b),($a))")
                    }
                }
            }
            return sb.toString()
        }

        fun isEmpty(): Boolean = items.isEmpty()
    }

    val rootContainer = MathContainer()
    var currentContainer: MathContainer = rootContainer
    var cursorIndex: Int = 0

    // ---- МОДЕЛЬ РЕЗУЛЬТАТА (поддержка нескольких корней) ----
    data class ResultEntry(
        val prefix: String,          // «x₁ = » или «»
        val whole: String?,          // целая часть смешанной дроби (или null)
        val numerator: String?,      // текст числителя дроби (или null)
        val denominator: String?,    // текст знаменателя (или null)
        val plainText: String        // используется когда numerator == null
    )

    private var resultEntries: List<ResultEntry> = emptyList()
    private var resultWhole: String? = null
    private var resultNumerator: String? = null
    private var resultDenominator: String? = null
    private var _hasResult: Boolean = false
    val hasResult: Boolean get() = _hasResult
    private var currentResultText: String = ""

    private var previewEntries: List<ResultEntry> = emptyList()
    private var hasPreview: Boolean = false

    // Прокрутка для защиты от обрезания длинных формул и результатов
    private var inputScrollX: Float = 0f
    private var resultScrollX: Float = 0f
    private var maxResultScrollX: Float = 0f
    private var followCursorOnNextDraw: Boolean = true

    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private var lastTouchX: Float = 0f
    private var dragStartScrollX: Float = 0f
    private var dragStartResultScrollX: Float = 0f
    private var isHorizontalDragging: Boolean = false
    private var isVerticalSwiping: Boolean = false
    private var isTouchInResultArea: Boolean = false
    private val touchSlop: Float by lazy { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }

    fun getResultText(): String = currentResultText

    var onContentChanged: ((String) -> Unit)? = null
    var onPreviewClick: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null

    var isCompactInputMode: Boolean = false

    fun setEditorTheme(isDark: Boolean) {
        val textCol = Color.parseColor(if (isDark) "#F8FAFC" else "#0F172A")
        val placeholderCol = Color.parseColor(if (isDark) "#64748B" else "#94A3B8")
        val cursorCol = Color.parseColor(if (isDark) "#38BDF8" else "#0284C7")

        inputTextPaint.color = textCol
        smallTextPaint.color = textCol
        inputLinePaint.color = textCol
        placeholderPaint.color = placeholderCol
        cursorPaint.color = cursorCol
        invalidate()
    }

    private val density = resources.displayMetrics.density

    private val inputTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = 21f * density
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = 14f * density
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private val inputLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }

    private val resultTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = 22f * density
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private val resultLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        strokeWidth = 2.2f * density
        style = Paint.Style.STROKE
    }

    private val previewTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = 18f * density
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private val previewLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        strokeWidth = 1.8f * density
        style = Paint.Style.STROKE
    }

    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        strokeWidth = 1.4f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        strokeWidth = 2.4f * density
        style = Paint.Style.STROKE
    }

    private val matchedBracketBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#330284C7")
        style = Paint.Style.FILL
    }

    private val matchedBracketBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0284C7")
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }

    private val unmatchedBracketBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33DC2626")
        style = Paint.Style.FILL
    }

    private val unmatchedBracketBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DC2626")
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }

    private var isCursorVisible = true
    private val blinkRunnable = object : Runnable {
        override fun run() {
            isCursorVisible = !isCursorVisible
            invalidate()
            postDelayed(this, 500)
        }
    }

    init {
        isClickable = true
        isLongClickable = true
        post(blinkRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blinkRunnable)
    }

    private fun resetCursorBlink() {
        isCursorVisible = true
        removeCallbacks(blinkRunnable)
        postDelayed(blinkRunnable, 500)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        followCursorOnNextDraw = true
        ensureCursorVisible()
    }

    // --- ВВОД СИМВОЛОВ И ДРОБЕЙ ---
    private fun shouldAutoInsertCloseParen(): Boolean {
        if (cursorIndex == currentContainer.items.size) return true
        val itemRight = currentContainer.items[cursorIndex]
        if (itemRight is MathItem.Symbol) {
            val t = itemRight.text.trim()
            if (t.isEmpty() || t == ")" || t in listOf("+", "-", "−", "·", "×", "÷", "/", "=", "^")) {
                return true
            }
        }
        return false
    }

    fun insertText(str: String) {
        if (str == ")") {
            if (cursorIndex < currentContainer.items.size) {
                val itemRight = currentContainer.items[cursorIndex] as? MathItem.Symbol
                if (itemRight?.text == ")") {
                    var unclosedOpen = 0
                    for (i in 0 until cursorIndex) {
                        val sym = currentContainer.items[i] as? MathItem.Symbol
                        if (sym != null) {
                            if (sym.text.endsWith("(")) {
                                unclosedOpen++
                            } else if (sym.text == ")") {
                                if (unclosedOpen > 0) unclosedOpen--
                            }
                        }
                    }
                    if (unclosedOpen > 0) {
                        cursorIndex++
                        notifyChange()
                        return
                    }
                }
            }
            currentContainer.items.add(cursorIndex, MathItem.Symbol(str))
            cursorIndex++
            notifyChange()
            return
        }

        if (str.endsWith("(")) {
            val autoClose = shouldAutoInsertCloseParen()
            currentContainer.items.add(cursorIndex, MathItem.Symbol(str))
            cursorIndex++
            if (autoClose) {
                currentContainer.items.add(cursorIndex, MathItem.Symbol(")"))
            }
            notifyChange()
            return
        }

        currentContainer.items.add(cursorIndex, MathItem.Symbol(str))
        cursorIndex++
        notifyChange()
    }

    fun autoCloseUnclosedParens() {
        autoCloseContainer(rootContainer)
    }

    private fun autoCloseContainer(container: MathContainer) {
        for (item in container.items) {
            when (item) {
                is MathItem.Fraction -> {
                    autoCloseContainer(item.num)
                    autoCloseContainer(item.den)
                }
                is MathItem.MixedFraction -> {
                    autoCloseContainer(item.whole)
                    autoCloseContainer(item.num)
                    autoCloseContainer(item.den)
                }
                is MathItem.Sqrt -> {
                    autoCloseContainer(item.inner)
                }
                is MathItem.Superscript -> {
                    autoCloseContainer(item.exp)
                }
                is MathItem.Combinatorics -> {
                    autoCloseContainer(item.upper)
                    autoCloseContainer(item.lower)
                }
                is MathItem.LogBase -> {
                    autoCloseContainer(item.base)
                    autoCloseContainer(item.arg)
                }
                else -> {}
            }
        }
        var openCount = 0
        for (item in container.items) {
            if (item is MathItem.Symbol) {
                if (item.text.endsWith("(")) {
                    openCount++
                } else if (item.text == ")") {
                    if (openCount > 0) openCount--
                }
            }
        }
        if (openCount > 0) {
            for (k in 0 until openCount) {
                container.items.add(MathItem.Symbol(")"))
            }
            if (currentContainer === container && cursorIndex > container.items.size - openCount) {
                cursorIndex = container.items.size
            }
            notifyChange()
        }
    }

    fun insertFraction() {
        val num = MathContainer(parent = currentContainer)
        val den = MathContainer(parent = currentContainer)

        // 1. Если непосредственно перед курсором висит точка — удаляем её
        while (cursorIndex > 0 && currentContainer.items[cursorIndex - 1] is MathItem.Symbol) {
            val prevSym = currentContainer.items[cursorIndex - 1] as MathItem.Symbol
            if (prevSym.text == ".") {
                currentContainer.items.removeAt(cursorIndex - 1)
                cursorIndex--
            } else {
                break
            }
        }

        // 2. Забираем всё число целиком (цифры, точку внутри числа или переменную), предшествующее курсору
        val collectedSymbols = mutableListOf<MathItem.Symbol>()
        while (cursorIndex > 0 && currentContainer.items[cursorIndex - 1] is MathItem.Symbol) {
            val sym = currentContainer.items[cursorIndex - 1] as MathItem.Symbol
            val t = sym.text
            val isNumberPart = t.all { it.isDigit() || it == '.' } && t.isNotEmpty()
            val isSingleVar = t.length == 1 && t[0].isLetter()

            if ((isNumberPart || isSingleVar) && t.none { it in "+−×÷=()" }) {
                if (t == "." && collectedSymbols.any { it.text.contains('.') }) {
                    break
                }
                collectedSymbols.add(0, sym)
                currentContainer.items.removeAt(cursorIndex - 1)
                cursorIndex--
                if (isSingleVar) break
            } else {
                break
            }
        }

        // 3. Если после взятия числа перед курсором опять осталась висящая точка — удаляем её
        while (cursorIndex > 0 && currentContainer.items[cursorIndex - 1] is MathItem.Symbol) {
            val prevSym = currentContainer.items[cursorIndex - 1] as MathItem.Symbol
            if (prevSym.text == ".") {
                currentContainer.items.removeAt(cursorIndex - 1)
                cursorIndex--
            } else {
                break
            }
        }

        // 4. Добавляем собранные символы в числитель дроби
        if (collectedSymbols.isNotEmpty()) {
            num.items.addAll(collectedSymbols)
            val frac = MathItem.Fraction(num, den)
            currentContainer.items.add(cursorIndex, frac)
            currentContainer = den
            cursorIndex = 0
            notifyChange()
            return
        }

        val frac = MathItem.Fraction(num, den)
        currentContainer.items.add(cursorIndex, frac)
        currentContainer = num
        cursorIndex = 0
        notifyChange()
    }

    fun insertMixedFraction() {
        val whole = MathContainer(parent = currentContainer)
        val num = MathContainer(parent = currentContainer)
        val den = MathContainer(parent = currentContainer)

        // 1. Если непосредственно перед курсором висит точка — удаляем её
        while (cursorIndex > 0 && currentContainer.items[cursorIndex - 1] is MathItem.Symbol) {
            val prevSym = currentContainer.items[cursorIndex - 1] as MathItem.Symbol
            if (prevSym.text == ".") {
                currentContainer.items.removeAt(cursorIndex - 1)
                cursorIndex--
            } else {
                break
            }
        }

        // 2. Забираем все подряд идущие цифры / точку непосредственно перед курсором
        val collectedSymbols = mutableListOf<MathItem.Symbol>()
        while (cursorIndex > 0 && currentContainer.items[cursorIndex - 1] is MathItem.Symbol) {
            val sym = currentContainer.items[cursorIndex - 1] as MathItem.Symbol
            val t = sym.text
            val isDigitOrDot = t.isNotEmpty() && t.all { it.isDigit() || it == '.' }

            if (isDigitOrDot && t.none { it in "+−×÷=()" }) {
                if (t == "." && collectedSymbols.any { it.text.contains('.') }) {
                    break
                }
                collectedSymbols.add(0, sym)
                currentContainer.items.removeAt(cursorIndex - 1)
                cursorIndex--
            } else {
                break
            }
        }

        // 3. Если после взятия опять осталась висячая точка — удаляем её
        while (cursorIndex > 0 && currentContainer.items[cursorIndex - 1] is MathItem.Symbol) {
            val prevSym = currentContainer.items[cursorIndex - 1] as MathItem.Symbol
            if (prevSym.text == ".") {
                currentContainer.items.removeAt(cursorIndex - 1)
                cursorIndex--
            } else {
                break
            }
        }

        if (collectedSymbols.isNotEmpty()) {
            for (sym in collectedSymbols) {
                reparentItem(sym, whole)
                whole.items.add(sym)
            }
            val mf = MathItem.MixedFraction(whole, num, den)
            currentContainer.items.add(cursorIndex, mf)
            currentContainer = num
            cursorIndex = 0
        } else {
            val mf = MathItem.MixedFraction(whole, num, den)
            currentContainer.items.add(cursorIndex, mf)
            currentContainer = whole
            cursorIndex = 0
        }
        notifyChange()
    }

    fun insertSqrt() {
        val inner = MathContainer(parent = currentContainer)
        val sq = MathItem.Sqrt(inner)
        currentContainer.items.add(cursorIndex, sq)
        currentContainer = inner
        cursorIndex = 0
        notifyChange()
    }

    fun insertCbrt() {
        val inner = MathContainer(parent = currentContainer)
        val sq = MathItem.Sqrt(inner, degree = "3")
        currentContainer.items.add(cursorIndex, sq)
        currentContainer = inner
        cursorIndex = 0
        notifyChange()
    }

    fun insertSuperscript(defaultPower: String? = null) {
        val exp = MathContainer(parent = currentContainer)
        if (defaultPower != null) {
            exp.items.add(MathItem.Symbol(defaultPower))
        }
        val sup = MathItem.Superscript(exp)
        currentContainer.items.add(cursorIndex, sup)
        if (defaultPower == null) {
            currentContainer = exp
            cursorIndex = 0
        } else {
            cursorIndex++
        }
        notifyChange()
    }

    fun insertCombinatorics(symbol: String) {
        val upper = MathContainer(parent = currentContainer)
        val lower = MathContainer(parent = currentContainer)
        val item = MathItem.Combinatorics(symbol, upper, lower)
        currentContainer.items.add(cursorIndex, item)
        currentContainer = upper
        cursorIndex = 0
        notifyChange()
    }

    fun insertLogBase() {
        val base = MathContainer(parent = currentContainer)
        val arg = MathContainer(parent = currentContainer)
        val item = MathItem.LogBase(base, arg)
        currentContainer.items.add(cursorIndex, item)
        currentContainer = base
        cursorIndex = 0
        notifyChange()
    }

    private fun reparentItem(item: MathItem, newParent: MathContainer) {
        when (item) {
            is MathItem.Fraction -> {
                item.num.parent = newParent
                item.den.parent = newParent
            }
            is MathItem.MixedFraction -> {
                item.whole.parent = newParent
                item.num.parent = newParent
                item.den.parent = newParent
            }
            is MathItem.Sqrt -> {
                item.inner.parent = newParent
            }
            is MathItem.Superscript -> {
                item.exp.parent = newParent
            }
            is MathItem.Combinatorics -> {
                item.upper.parent = newParent
                item.lower.parent = newParent
            }
            is MathItem.LogBase -> {
                item.base.parent = newParent
                item.arg.parent = newParent
            }
            else -> {}
        }
    }

    fun deleteAtCursor() {
        // 1. Empty pair deletion: func(|) or (|)
        if (cursorIndex > 0 && cursorIndex < currentContainer.items.size) {
            val prev = currentContainer.items[cursorIndex - 1] as? MathItem.Symbol
            val next = currentContainer.items[cursorIndex] as? MathItem.Symbol
            if (prev != null && next != null && prev.text.endsWith("(") && next.text == ")") {
                currentContainer.items.removeAt(cursorIndex)     // remove ")"
                currentContainer.items.removeAt(cursorIndex - 1) // remove "func("
                cursorIndex--
                notifyChange()
                return
            }
        }

        if (cursorIndex > 0) {
            val prev = currentContainer.items[cursorIndex - 1]
            when (prev) {
                is MathItem.Fraction -> {
                    if (prev.num.isEmpty() && prev.den.isEmpty()) {
                        currentContainer.items.removeAt(cursorIndex - 1)
                        cursorIndex--
                    } else if (prev.den.isEmpty() && !prev.num.isEmpty()) {
                        val fracIdx = cursorIndex - 1
                        currentContainer.items.removeAt(fracIdx)
                        for (item in prev.num.items) {
                            reparentItem(item, currentContainer)
                        }
                        currentContainer.items.addAll(fracIdx, prev.num.items)
                        cursorIndex = fracIdx + prev.num.items.size
                    } else {
                        currentContainer = prev.den
                        cursorIndex = prev.den.items.size
                    }
                }
                is MathItem.MixedFraction -> {
                    if (prev.whole.isEmpty() && prev.num.isEmpty() && prev.den.isEmpty()) {
                        currentContainer.items.removeAt(cursorIndex - 1)
                        cursorIndex--
                    } else if (prev.whole.isEmpty()) {
                        val fracIdx = cursorIndex - 1
                        val frac = MathItem.Fraction(prev.num, prev.den)
                        prev.num.parent = currentContainer
                        prev.den.parent = currentContainer
                        currentContainer.items[fracIdx] = frac
                        currentContainer = frac.den
                        cursorIndex = frac.den.items.size
                    } else {
                        currentContainer = prev.den
                        cursorIndex = prev.den.items.size
                    }
                }
                is MathItem.Sqrt -> {
                    if (prev.inner.isEmpty()) {
                        currentContainer.items.removeAt(cursorIndex - 1)
                        cursorIndex--
                    } else {
                        currentContainer = prev.inner
                        cursorIndex = prev.inner.items.size
                    }
                }
                is MathItem.Superscript -> {
                    if (prev.exp.isEmpty()) {
                        currentContainer.items.removeAt(cursorIndex - 1)
                        cursorIndex--
                    } else {
                        currentContainer = prev.exp
                        cursorIndex = prev.exp.items.size
                    }
                }
                is MathItem.Combinatorics -> {
                    if (prev.upper.isEmpty() && prev.lower.isEmpty()) {
                        currentContainer.items.removeAt(cursorIndex - 1)
                        cursorIndex--
                    } else {
                        currentContainer = prev.lower
                        cursorIndex = prev.lower.items.size
                    }
                }
                is MathItem.LogBase -> {
                    if (prev.base.isEmpty() && prev.arg.isEmpty()) {
                        currentContainer.items.removeAt(cursorIndex - 1)
                        cursorIndex--
                    } else if (prev.arg.isEmpty()) {
                        currentContainer = prev.base
                        cursorIndex = prev.base.items.size
                    } else {
                        currentContainer = prev.arg
                        cursorIndex = prev.arg.items.size
                    }
                }
                else -> {
                    currentContainer.items.removeAt(cursorIndex - 1)
                    cursorIndex--
                }
            }
        } else {
            // cursorIndex == 0: check structural unwrapping / deletion in parent container
            val parent = currentContainer.parent
            if (parent != null) {
                var handled = false
                for (i in parent.items.indices) {
                    when (val item = parent.items[i]) {
                        is MathItem.Fraction -> {
                            if (item.den === currentContainer) {
                                if (item.den.isEmpty()) {
                                    if (item.num.isEmpty()) {
                                        parent.items.removeAt(i)
                                        currentContainer = parent
                                        cursorIndex = i
                                    } else {
                                        parent.items.removeAt(i)
                                        for (numItem in item.num.items) {
                                            reparentItem(numItem, parent)
                                        }
                                        parent.items.addAll(i, item.num.items)
                                        currentContainer = parent
                                        cursorIndex = i + item.num.items.size
                                    }
                                    handled = true
                                    break
                                } else {
                                    currentContainer = item.num
                                    cursorIndex = item.num.items.size
                                    handled = true
                                    break
                                }
                            } else if (item.num === currentContainer) {
                                if (item.num.isEmpty() && item.den.isEmpty()) {
                                    parent.items.removeAt(i)
                                    currentContainer = parent
                                    cursorIndex = i
                                    handled = true
                                    break
                                }
                            }
                        }
                        is MathItem.MixedFraction -> {
                            if (item.whole === currentContainer) {
                                if (item.whole.isEmpty()) {
                                    if (item.num.isEmpty() && item.den.isEmpty()) {
                                        parent.items.removeAt(i)
                                        currentContainer = parent
                                        cursorIndex = i
                                        handled = true
                                        break
                                    } else {
                                        val frac = MathItem.Fraction(item.num, item.den)
                                        item.num.parent = parent
                                        item.den.parent = parent
                                        parent.items[i] = frac
                                        currentContainer = parent
                                        cursorIndex = i
                                        handled = true
                                        break
                                    }
                                }
                            } else if (item.num === currentContainer) {
                                if (item.num.isEmpty()) {
                                    if (item.whole.isEmpty() && item.den.isEmpty()) {
                                        parent.items.removeAt(i)
                                        currentContainer = parent
                                        cursorIndex = i
                                        handled = true
                                        break
                                    } else {
                                        currentContainer = item.whole
                                        cursorIndex = item.whole.items.size
                                        handled = true
                                        break
                                    }
                                }
                            } else if (item.den === currentContainer) {
                                if (item.den.isEmpty()) {
                                    if (item.whole.isEmpty() && item.num.isEmpty()) {
                                        parent.items.removeAt(i)
                                        currentContainer = parent
                                        cursorIndex = i
                                        handled = true
                                        break
                                    } else {
                                        currentContainer = item.num
                                        cursorIndex = item.num.items.size
                                        handled = true
                                        break
                                    }
                                }
                            }
                        }
                        is MathItem.Sqrt -> {
                            if (item.inner === currentContainer) {
                                if (item.inner.isEmpty()) {
                                    parent.items.removeAt(i)
                                    currentContainer = parent
                                    cursorIndex = i
                                    handled = true
                                    break
                                } else if (cursorIndex == 0) {
                                    parent.items.removeAt(i)
                                    for (innerItem in item.inner.items) {
                                        reparentItem(innerItem, parent)
                                    }
                                    parent.items.addAll(i, item.inner.items)
                                    currentContainer = parent
                                    cursorIndex = i
                                    handled = true
                                    break
                                }
                            }
                        }
                        is MathItem.Superscript -> {
                            if (item.exp === currentContainer) {
                                if (item.exp.isEmpty()) {
                                    parent.items.removeAt(i)
                                    currentContainer = parent
                                    cursorIndex = i
                                    handled = true
                                    break
                                }
                            }
                        }
                        is MathItem.Combinatorics -> {
                            if (item.upper === currentContainer) {
                                if (item.upper.isEmpty()) {
                                    if (item.lower.isEmpty()) {
                                        parent.items.removeAt(i)
                                        currentContainer = parent
                                        cursorIndex = i
                                        handled = true
                                        break
                                    } else {
                                        currentContainer = item.lower
                                        cursorIndex = item.lower.items.size
                                        handled = true
                                        break
                                    }
                                }
                            } else if (item.lower === currentContainer) {
                                if (item.lower.isEmpty()) {
                                    if (item.upper.isEmpty()) {
                                        parent.items.removeAt(i)
                                        currentContainer = parent
                                        cursorIndex = i
                                        handled = true
                                        break
                                    } else {
                                        currentContainer = item.upper
                                        cursorIndex = item.upper.items.size
                                        handled = true
                                        break
                                    }
                                }
                            }
                        }
                        is MathItem.LogBase -> {
                            if (item.arg === currentContainer) {
                                if (item.arg.isEmpty()) {
                                    if (item.base.isEmpty()) {
                                        parent.items.removeAt(i)
                                        currentContainer = parent
                                        cursorIndex = i
                                        handled = true
                                        break
                                    } else {
                                        currentContainer = item.base
                                        cursorIndex = item.base.items.size
                                        handled = true
                                        break
                                    }
                                }
                            } else if (item.base === currentContainer) {
                                if (item.base.isEmpty()) {
                                    if (item.arg.isEmpty()) {
                                        parent.items.removeAt(i)
                                        currentContainer = parent
                                        cursorIndex = i
                                        handled = true
                                        break
                                    } else {
                                        val logIdx = i
                                        parent.items.removeAt(logIdx)
                                        for (argItem in item.arg.items) {
                                            reparentItem(argItem, parent)
                                        }
                                        parent.items.addAll(logIdx, item.arg.items)
                                        currentContainer = parent
                                        cursorIndex = logIdx
                                        handled = true
                                        break
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }
                if (!handled) {
                    exitToParentLeft()
                }
            }
        }
        notifyChange()
    }

    fun clearAll() {
        rootContainer.items.clear()
        currentContainer = rootContainer
        cursorIndex = 0
        inputScrollX = 0f
        resultScrollX = 0f
        clearResult()
        notifyChange()
    }

    private fun exitToParentLeft() {
        val parent = currentContainer.parent ?: return
        for (i in parent.items.indices) {
            when (val it = parent.items[i]) {
                is MathItem.Fraction -> {
                    if (it.den === currentContainer) {
                        currentContainer = it.num; cursorIndex = it.num.items.size; return
                    } else if (it.num === currentContainer) {
                        currentContainer = parent; cursorIndex = i; return
                    }
                }
                is MathItem.MixedFraction -> {
                    when {
                        it.den === currentContainer -> { currentContainer = it.num; cursorIndex = it.num.items.size; return }
                        it.num === currentContainer -> { currentContainer = it.whole; cursorIndex = it.whole.items.size; return }
                        it.whole === currentContainer -> { currentContainer = parent; cursorIndex = i; return }
                    }
                }
                is MathItem.Sqrt -> if (it.inner === currentContainer) { currentContainer = parent; cursorIndex = i; return }
                is MathItem.Superscript -> if (it.exp === currentContainer) { currentContainer = parent; cursorIndex = i; return }
                is MathItem.Combinatorics -> {
                    if (it.lower === currentContainer) { currentContainer = it.upper; cursorIndex = it.upper.items.size; return }
                    if (it.upper === currentContainer) { currentContainer = parent; cursorIndex = i; return }
                }
                is MathItem.LogBase -> {
                    if (it.arg === currentContainer) { currentContainer = it.base; cursorIndex = it.base.items.size; return }
                    if (it.base === currentContainer) { currentContainer = parent; cursorIndex = i; return }
                }
                else -> {}
            }
        }
    }

    fun moveLeft() {
        followCursorOnNextDraw = true
        if (cursorIndex > 0) {
            when (val prev = currentContainer.items[cursorIndex - 1]) {
                is MathItem.Fraction -> { currentContainer = prev.den; cursorIndex = prev.den.items.size }
                is MathItem.MixedFraction -> { currentContainer = prev.den; cursorIndex = prev.den.items.size }
                is MathItem.Sqrt -> { currentContainer = prev.inner; cursorIndex = prev.inner.items.size }
                is MathItem.Superscript -> { currentContainer = prev.exp; cursorIndex = prev.exp.items.size }
                is MathItem.Combinatorics -> { currentContainer = prev.lower; cursorIndex = prev.lower.items.size }
                is MathItem.LogBase -> { currentContainer = prev.arg; cursorIndex = prev.arg.items.size }
                else -> cursorIndex--
            }
        } else {
            exitToParentLeft()
        }
        resetCursorBlink()
    }

    fun moveRight() {
        followCursorOnNextDraw = true
        if (cursorIndex < currentContainer.items.size) {
            when (val next = currentContainer.items[cursorIndex]) {
                is MathItem.Fraction -> { currentContainer = next.num; cursorIndex = 0 }
                is MathItem.MixedFraction -> { currentContainer = next.whole; cursorIndex = 0 }
                is MathItem.Sqrt -> { currentContainer = next.inner; cursorIndex = 0 }
                is MathItem.Superscript -> { currentContainer = next.exp; cursorIndex = 0 }
                is MathItem.Combinatorics -> { currentContainer = next.upper; cursorIndex = 0 }
                is MathItem.LogBase -> { currentContainer = next.base; cursorIndex = 0 }
                else -> cursorIndex++
            }
        } else {
            val parent = currentContainer.parent ?: return
            for (i in parent.items.indices) {
                when (val it = parent.items[i]) {
                    is MathItem.Fraction -> {
                        if (it.num === currentContainer) { currentContainer = it.den; cursorIndex = 0; resetCursorBlink(); return }
                        if (it.den === currentContainer) { currentContainer = parent; cursorIndex = i + 1; resetCursorBlink(); return }
                    }
                    is MathItem.MixedFraction -> {
                        if (it.whole === currentContainer) { currentContainer = it.num; cursorIndex = 0; resetCursorBlink(); return }
                        if (it.num === currentContainer) { currentContainer = it.den; cursorIndex = 0; resetCursorBlink(); return }
                        if (it.den === currentContainer) { currentContainer = parent; cursorIndex = i + 1; resetCursorBlink(); return }
                    }
                    is MathItem.Sqrt -> if (it.inner === currentContainer) { currentContainer = parent; cursorIndex = i + 1; resetCursorBlink(); return }
                    is MathItem.Superscript -> if (it.exp === currentContainer) { currentContainer = parent; cursorIndex = i + 1; resetCursorBlink(); return }
                    is MathItem.Combinatorics -> {
                        if (it.upper === currentContainer) { currentContainer = it.lower; cursorIndex = 0; resetCursorBlink(); return }
                        if (it.lower === currentContainer) { currentContainer = parent; cursorIndex = i + 1; resetCursorBlink(); return }
                    }
                    is MathItem.LogBase -> {
                        if (it.base === currentContainer) { currentContainer = it.arg; cursorIndex = 0; resetCursorBlink(); return }
                        if (it.arg === currentContainer) { currentContainer = parent; cursorIndex = i + 1; resetCursorBlink(); return }
                    }
                    else -> {}
                }
            }
        }
        resetCursorBlink()
    }

    fun jumpUp() {
        followCursorOnNextDraw = true
        val parent = currentContainer.parent
        if (parent != null) {
            for (it in parent.items) {
                if (it is MathItem.Fraction && it.den === currentContainer) {
                    currentContainer = it.num; cursorIndex = it.num.items.size; resetCursorBlink(); return
                }
                if (it is MathItem.MixedFraction && (it.den === currentContainer || it.whole === currentContainer)) {
                    currentContainer = it.num; cursorIndex = it.num.items.size; resetCursorBlink(); return
                }
                if (it is MathItem.Combinatorics && it.lower === currentContainer) {
                    currentContainer = it.upper; cursorIndex = it.upper.items.size; resetCursorBlink(); return
                }
                if (it is MathItem.LogBase && it.base === currentContainer) {
                    currentContainer = it.arg; cursorIndex = it.arg.items.size; resetCursorBlink(); return
                }
            }
        }
        val frac = currentContainer.items.filterIsInstance<MathItem.Fraction>().firstOrNull()
        if (frac != null) {
            currentContainer = frac.num; cursorIndex = frac.num.items.size; resetCursorBlink(); return
        }
        val mf = currentContainer.items.filterIsInstance<MathItem.MixedFraction>().firstOrNull()
        if (mf != null) {
            currentContainer = mf.num; cursorIndex = mf.num.items.size; resetCursorBlink(); return
        }
        val comb = currentContainer.items.filterIsInstance<MathItem.Combinatorics>().firstOrNull()
        if (comb != null) {
            currentContainer = comb.upper; cursorIndex = comb.upper.items.size; resetCursorBlink(); return
        }
        val logBase = currentContainer.items.filterIsInstance<MathItem.LogBase>().firstOrNull()
        if (logBase != null) {
            currentContainer = logBase.arg; cursorIndex = logBase.arg.items.size; resetCursorBlink(); return
        }
    }

    fun jumpDown() {
        followCursorOnNextDraw = true
        val parent = currentContainer.parent
        if (parent != null) {
            for (it in parent.items) {
                if (it is MathItem.Fraction && it.num === currentContainer) {
                    currentContainer = it.den; cursorIndex = it.den.items.size; resetCursorBlink(); return
                }
                if (it is MathItem.MixedFraction && (it.num === currentContainer || it.whole === currentContainer)) {
                    currentContainer = it.den; cursorIndex = it.den.items.size; resetCursorBlink(); return
                }
                if (it is MathItem.Combinatorics && it.upper === currentContainer) {
                    currentContainer = it.lower; cursorIndex = it.lower.items.size; resetCursorBlink(); return
                }
                if (it is MathItem.LogBase && it.arg === currentContainer) {
                    currentContainer = it.base; cursorIndex = it.base.items.size; resetCursorBlink(); return
                }
            }
        }
        val frac = currentContainer.items.filterIsInstance<MathItem.Fraction>().firstOrNull()
        if (frac != null) {
            currentContainer = frac.den; cursorIndex = frac.den.items.size; resetCursorBlink(); return
        }
        val mf = currentContainer.items.filterIsInstance<MathItem.MixedFraction>().firstOrNull()
        if (mf != null) {
            currentContainer = mf.den; cursorIndex = mf.den.items.size; resetCursorBlink(); return
        }
        val comb = currentContainer.items.filterIsInstance<MathItem.Combinatorics>().firstOrNull()
        if (comb != null) {
            currentContainer = comb.lower; cursorIndex = comb.lower.items.size; resetCursorBlink(); return
        }
        val logBase = currentContainer.items.filterIsInstance<MathItem.LogBase>().firstOrNull()
        if (logBase != null) {
            currentContainer = logBase.base; cursorIndex = logBase.base.items.size; resetCursorBlink(); return
        }
    }

    fun getRawExpression(): String = rootContainer.toRawString()

    private fun notifyChange() {
        _hasResult = false
        currentResultText = ""
        resultEntries = emptyList()
        followCursorOnNextDraw = true
        resetCursorBlink()
        onContentChanged?.invoke(getRawExpression())
    }

    private fun normalizeSuperscripts(text: String): String {
        val supMap = mapOf(
            '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
            '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
            '-' to '⁻', '+' to '⁺', '/' to '⁄', '=' to '⁼',
            '(' to '⁽', ')' to '⁾',
            'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
            'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ', 'k' to 'ᵏ', 'l' to 'ˡ',
            'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ', 'p' to 'ᵖ', 'r' to 'ʳ',
            's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ', 'v' to 'ᵛ', 'w' to 'ʷ',
            'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ'
        )

        // 1. Handle ^(...)
        val parenRegex = Regex("""\^\(([^)]+)\)""")
        var result = parenRegex.replace(text) { match ->
            val inner = match.groupValues[1].trim()
            val converted = inner.mapNotNull { c ->
                if (c == ' ') null else supMap[c] ?: c
            }.joinToString("")
            converted
        }

        // 2. Handle ^{...} or ^[+-]?\d+
        val curlyOrDirectRegex = Regex("""\^\{?([+-]?\d+)\}?""")
        result = curlyOrDirectRegex.replace(result) { match ->
            match.groupValues[1].map { supMap[it] ?: it }.joinToString("")
        }

        return result
    }

    fun setLivePreview(previewText: String?) {
        if (previewText.isNullOrBlank() || isErrorMessage(previewText)) {
            hasPreview = false
            previewEntries = emptyList()
        } else {
            val normalized = normalizeSuperscripts(previewText)
            previewEntries = parseMultiResult(normalized.trim())
            hasPreview = previewEntries.isNotEmpty()
        }
        invalidate()
    }

    // --- УСТАНОВКА И ОТОБРАЖЕНИЕ РЕЗУЛЬТАТА ---
    fun setResult(resultText: String) {
        android.util.Log.d("CAS_TEST", "DISPLAY_RESULT: $resultText")
        _hasResult = true
        hasPreview = false
        val normalized = normalizeSuperscripts(resultText)
        currentResultText = normalized
        val trimmed = normalized.trim()
        resultEntries = parseMultiResult(trimmed)
        resultWhole = resultEntries.firstOrNull()?.whole
        resultNumerator = resultEntries.firstOrNull()?.numerator
        resultDenominator = resultEntries.firstOrNull()?.denominator
        resultScrollX = 0f
        followCursorOnNextDraw = true
        invalidate()
    }

    private fun parseMultiResult(text: String): List<ResultEntry> {
        val segments = splitTopLevel(text, ';')
        return segments.map { seg -> parseSingleResult(seg.trim()) }
    }

    private fun splitTopLevel(text: String, sep: Char): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in text.indices) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
                sep -> if (depth == 0) {
                    result.add(text.substring(start, i))
                    start = i + 1
                }
            }
        }
        result.add(text.substring(start))
        return result
    }

    fun parseResultFraction(expr: String): Boolean {
        var depth = 0
        var slashIdx = -1
        for (i in expr.indices) {
            when (expr[i]) {
                '(', '{' -> depth++
                ')', '}' -> depth--
                '/' -> if (depth == 0) { slashIdx = i; break }
            }
        }

        if (slashIdx != -1) {
            val numRaw = stripOuterParens(expr.substring(0, slashIdx)).trim()
            val den = stripOuterParens(expr.substring(slashIdx + 1)).trim()

            val mixedRegex = Regex("""^([+-]?\d+)\s+(\d+)$""")
            val match = mixedRegex.matchEntire(numRaw)
            if (match != null) {
                resultWhole = match.groupValues[1]
                resultNumerator = match.groupValues[2]
            } else {
                resultWhole = null
                resultNumerator = numRaw
            }
            resultDenominator = den
            return true
        } else {
            resultWhole = null
            resultNumerator = null
            resultDenominator = null
            return false
        }
    }

    private fun parseSingleResult(segment: String): ResultEntry {
        val trimmed = segment.trim()
        val eqIdx = trimmed.indexOf('=')
        val prefix: String
        var rhs: String
        if (eqIdx != -1) {
            prefix = trimmed.substring(0, eqIdx).trim() + " = "
            rhs = trimmed.substring(eqIdx + 1).trim()
        } else {
            prefix = ""
            rhs = trimmed
        }

        var effectivePrefix = prefix
        if (rhs.startsWith("±")) {
            effectivePrefix += "± "
            rhs = rhs.substring(1).trim()
        }

        rhs = stripOuterParens(rhs)

        val isFrac = parseResultFraction(rhs)
        return if (isFrac) {
            ResultEntry(effectivePrefix, resultWhole, resultNumerator, resultDenominator, "")
        } else {
            ResultEntry(effectivePrefix, null, null, null, rhs)
        }
    }

    private fun stripOuterParens(raw: String): String {
        var s = raw.trim()
        while (s.startsWith("(") && s.endsWith(")")) {
            var bal = 0
            var allEnclosed = true
            for (j in 0 until s.length - 1) {
                if (s[j] == '(') bal++
                else if (s[j] == ')') bal--
                if (bal == 0) { allEnclosed = false; break }
            }
            if (allEnclosed) {
                s = s.substring(1, s.length - 1).trim()
            } else {
                break
            }
        }
        if ((s.startsWith("-") || s.startsWith("−")) && s.length > 2) {
            val sign = s.substring(0, 1)
            var rest = s.substring(1).trim()
            if (rest.startsWith("(") && rest.endsWith(")")) {
                var bal = 0
                var allEnclosed = true
                for (j in 0 until rest.length - 1) {
                    if (rest[j] == '(') bal++
                    else if (rest[j] == ')') bal--
                    if (bal == 0) { allEnclosed = false; break }
                }
                if (allEnclosed) {
                    rest = rest.substring(1, rest.length - 1).trim()
                    s = sign + rest
                }
            }
        }
        return s
    }

    fun clearResult() {
        _hasResult = false
        currentResultText = ""
        resultEntries = emptyList()
        resultWhole = null
        resultNumerator = null
        resultDenominator = null
        resultScrollX = 0f
        maxResultScrollX = 0f
        invalidate()
    }

    fun loadExpression(expr: String) {
        rootContainer.items.clear()
        populateContainerFromString(expr, rootContainer)
        currentContainer = rootContainer
        cursorIndex = rootContainer.items.size
        inputScrollX = 0f
        clearResult()
        notifyChange()
    }

    private fun populateContainerFromString(raw: String, target: MathContainer) {
        var i = 0
        val s = raw.trim()
        while (i < s.length) {
            if (s.startsWith("Ans", i)) {
                target.items.add(MathItem.Symbol("Ans"))
                i += 3
                continue
            }
            if (s.startsWith("((", i)) {
                val fracMatch = findFractionMatch(s, i)
                if (fracMatch != null) {
                    val (numStr, denStr, nextIdx) = fracMatch
                    val frac = MathItem.Fraction(
                        MathContainer(parent = target),
                        MathContainer(parent = target)
                    )
                    populateContainerFromString(numStr, frac.num)
                    populateContainerFromString(denStr, frac.den)
                    target.items.add(frac)
                    i = nextIdx
                    continue
                }
            }
            if (s.startsWith("cbrt(", i) || s.startsWith("³√(", i)) {
                val openParenOffset = if (s.startsWith("cbrt(", i)) 4 else 2
                val closeIdx = findMatchingParen(s, i + openParenOffset)
                if (closeIdx != -1) {
                    val innerStr = s.substring(i + openParenOffset + 1, closeIdx)
                    val sq = MathItem.Sqrt(MathContainer(parent = target), degree = "3")
                    populateContainerFromString(innerStr, sq.inner)
                    target.items.add(sq)
                    i = closeIdx + 1
                    continue
                }
            }
            if (s.startsWith("√(", i)) {
                val closeIdx = findMatchingParen(s, i + 1)
                if (closeIdx != -1) {
                    val innerStr = s.substring(i + 2, closeIdx)
                    val sq = MathItem.Sqrt(MathContainer(parent = target))
                    populateContainerFromString(innerStr, sq.inner)
                    target.items.add(sq)
                    i = closeIdx + 1
                    continue
                }
            }
            if (s.startsWith("^(", i)) {
                val closeIdx = findMatchingParen(s, i + 1)
                if (closeIdx != -1) {
                    val expStr = s.substring(i + 2, closeIdx)
                    val sup = MathItem.Superscript(MathContainer(parent = target))
                    populateContainerFromString(expStr, sup.exp)
                    target.items.add(sup)
                    i = closeIdx + 1
                    continue
                }
            }
            if (s.startsWith("log((", i)) {
                val match = findLogBaseMatch(s, i)
                if (match != null) {
                    val item = MathItem.LogBase(
                        base = MathContainer(parent = target),
                        arg = MathContainer(parent = target)
                    )
                    populateContainerFromString(match.baseStr, item.base)
                    populateContainerFromString(match.argStr, item.arg)
                    target.items.add(item)
                    i = match.nextIdx
                    continue
                }
            }
            if (s.startsWith("comb((", i) || s.startsWith("perm((", i)) {
                val match = findCombinatoricsMatch(s, i)
                if (match != null) {
                    val item = MathItem.Combinatorics(
                        match.symbol,
                        upper = MathContainer(parent = target),
                        lower = MathContainer(parent = target)
                    )
                    populateContainerFromString(match.upperStr, item.upper)
                    populateContainerFromString(match.lowerStr, item.lower)
                    target.items.add(item)
                    i = match.nextIdx
                    continue
                }
            }
            val ch = s[i]
            val displayCh = when (ch) {
                '*' -> "·"
                '/' -> "÷"
                '-' -> "−"
                else -> ch.toString()
            }
            target.items.add(MathItem.Symbol(displayCh))
            i++
        }
    }

    private fun findMatchingParen(s: String, openIdx: Int): Int {
        if (openIdx >= s.length || s[openIdx] != '(') return -1
        var depth = 0
        for (k in openIdx until s.length) {
            if (s[k] == '(') depth++
            else if (s[k] == ')') {
                depth--
                if (depth == 0) return k
            }
        }
        return -1
    }

    private fun findFractionMatch(s: String, startIdx: Int): Triple<String, String, Int>? {
        if (!s.startsWith("((", startIdx)) return null
        val numOpenIdx = startIdx + 1
        val numCloseIdx = findMatchingParen(s, numOpenIdx)
        if (numCloseIdx == -1 || numCloseIdx + 2 >= s.length) return null
        if (s[numCloseIdx + 1] != '/' || s[numCloseIdx + 2] != '(') return null
        val denOpenIdx = numCloseIdx + 2
        val denCloseIdx = findMatchingParen(s, denOpenIdx)
        if (denCloseIdx == -1 || denCloseIdx + 1 >= s.length) return null
        if (s[denCloseIdx + 1] != ')') return null
        val numStr = s.substring(numOpenIdx + 1, numCloseIdx)
        val denStr = s.substring(denOpenIdx + 1, denCloseIdx)
        return Triple(numStr, denStr, denCloseIdx + 2)
    }

    private data class CombMatch(val symbol: String, val lowerStr: String, val upperStr: String, val nextIdx: Int)

    private fun findCombinatoricsMatch(s: String, startIdx: Int): CombMatch? {
        val isPerm = s.startsWith("perm((", startIdx)
        val isComb = s.startsWith("comb((", startIdx)
        if (!isPerm && !isComb) return null
        val symbol = if (isPerm) "A" else "C"
        val prefixLen = 4
        val lowerOpenIdx = startIdx + prefixLen + 1
        val lowerCloseIdx = findMatchingParen(s, lowerOpenIdx)
        if (lowerCloseIdx == -1 || lowerCloseIdx + 2 >= s.length) return null
        if (s[lowerCloseIdx + 1] != ',' || s[lowerCloseIdx + 2] != '(') return null
        val upperOpenIdx = lowerCloseIdx + 2
        val upperCloseIdx = findMatchingParen(s, upperOpenIdx)
        if (upperCloseIdx == -1 || upperCloseIdx + 1 >= s.length) return null
        if (s[upperCloseIdx + 1] != ')') return null
        val lowerStr = s.substring(lowerOpenIdx + 1, lowerCloseIdx)
        val upperStr = s.substring(upperOpenIdx + 1, upperCloseIdx)
        return CombMatch(symbol, lowerStr, upperStr, upperCloseIdx + 2)
    }

    private data class LogBaseMatch(val baseStr: String, val argStr: String, val nextIdx: Int)

    private fun findLogBaseMatch(s: String, startIdx: Int): LogBaseMatch? {
        if (!s.startsWith("log((", startIdx)) return null
        val baseOpenIdx = startIdx + 4
        val baseCloseIdx = findMatchingParen(s, baseOpenIdx)
        if (baseCloseIdx == -1 || baseCloseIdx + 2 >= s.length) return null
        if (s[baseCloseIdx + 1] != ',' || s[baseCloseIdx + 2] != '(') return null
        val argOpenIdx = baseCloseIdx + 2
        val argCloseIdx = findMatchingParen(s, argOpenIdx)
        if (argCloseIdx == -1 || argCloseIdx + 1 >= s.length) return null
        if (s[argCloseIdx + 1] != ')') return null
        val baseStr = s.substring(baseOpenIdx + 1, baseCloseIdx)
        val argStr = s.substring(argOpenIdx + 1, argCloseIdx)
        return LogBaseMatch(baseStr, argStr, argCloseIdx + 2)
    }

    // --- РАСЧЁТ РАЗМЕРОВ И ОТРИСОВКА ---
    data class MeasureResult(val width: Float, val ascent: Float, val descent: Float) {
        val totalHeight: Float get() = ascent + descent
    }

    private fun measureContainer(container: MathContainer, isSuperscript: Boolean = false): MeasureResult {
        val paint = if (isSuperscript) smallTextPaint else inputTextPaint
        if (container.isEmpty()) {
            val w = if (isSuperscript) 14f * density else 20f * density
            val a = if (isSuperscript) 12f * density else 18f * density
            val d = if (isSuperscript) 4f * density else 6f * density
            return MeasureResult(w, a, d)
        }
        var totalW = 0f
        var maxAscent = if (isSuperscript) 12f * density else 16f * density
        var maxDescent = if (isSuperscript) 4f * density else 6f * density

        for (item in container.items) {
            when (item) {
                is MathItem.Symbol -> {
                    totalW += paint.measureText(item.text)
                }
                is MathItem.Fraction -> {
                    val numM = measureContainer(item.num)
                    val denM = measureContainer(item.den)
                    val fracW = max(numM.width, denM.width) + 12f * density
                    totalW += fracW + 4f * density
                    val fracAscent = numM.totalHeight + 6f * density
                    val fracDescent = denM.totalHeight + 6f * density
                    if (fracAscent > maxAscent) maxAscent = fracAscent
                    if (fracDescent > maxDescent) maxDescent = fracDescent
                }
                is MathItem.MixedFraction -> {
                    val wM = measureContainer(item.whole)
                    val numM = measureContainer(item.num)
                    val denM = measureContainer(item.den)
                    val fracW = max(numM.width, denM.width) + 12f * density
                    totalW += wM.width + 4f * density + fracW + 4f * density
                    val fracAscent = numM.totalHeight + 6f * density
                    val fracDescent = denM.totalHeight + 6f * density
                    if (max(wM.ascent, fracAscent) > maxAscent) maxAscent = max(wM.ascent, fracAscent)
                    if (max(wM.descent, fracDescent) > maxDescent) maxDescent = max(wM.descent, fracDescent)
                }
                is MathItem.Sqrt -> {
                    val inM = measureContainer(item.inner)
                    val hookW = if (item.degree != null) 17f * density else 14f * density
                    totalW += hookW + inM.width + 4f * density
                    val degAscent = if (item.degree != null) inM.ascent + 8f * density else inM.ascent + 6f * density
                    val sqAscent = max(inM.ascent + 6f * density, degAscent)
                    if (sqAscent > maxAscent) maxAscent = sqAscent
                    if (inM.descent > maxDescent) maxDescent = inM.descent
                }
                is MathItem.Superscript -> {
                    val expM = measureContainer(item.exp, isSuperscript = true)
                    totalW += expM.width + 2f * density
                    val supAscent = 10f * density + expM.ascent
                    if (supAscent > maxAscent) maxAscent = supAscent
                }
                is MathItem.Combinatorics -> {
                    val symW = paint.measureText(item.symbol)
                    val upperM = measureContainer(item.upper, isSuperscript = true)
                    val lowerM = measureContainer(item.lower, isSuperscript = true)
                    val itemW = symW + max(upperM.width, lowerM.width) + 2f * density
                    totalW += itemW + 2f * density
                    val combAscent = max(maxAscent, 8f * density + upperM.ascent)
                    val combDescent = max(maxDescent, 6f * density + lowerM.descent)
                    if (combAscent > maxAscent) maxAscent = combAscent
                    if (combDescent > maxDescent) maxDescent = combDescent
                }
                is MathItem.LogBase -> {
                    val logW = paint.measureText("log")
                    val baseM = measureContainer(item.base, isSuperscript = true)
                    val openW = paint.measureText("(")
                    val argM = measureContainer(item.arg, isSuperscript = false)
                    val closeW = paint.measureText(")")
                    val itemW = logW + baseM.width + openW + argM.width + closeW
                    totalW += itemW

                    val baseAscent = baseM.ascent - 6f * density
                    val baseDescent = 6f * density + baseM.descent
                    if (baseAscent > maxAscent) maxAscent = baseAscent
                    if (baseDescent > maxDescent) maxDescent = baseDescent
                    if (argM.ascent > maxAscent) maxAscent = argM.ascent
                    if (argM.descent > maxDescent) maxDescent = argM.descent
                }
            }
        }
        return MeasureResult(totalW, maxAscent, maxDescent)
    }

    private fun getCursorXInContainer(container: MathContainer, startX: Float, isSuperscript: Boolean = false): Float? {
        var currentX = startX
        val paint = if (isSuperscript) smallTextPaint else inputTextPaint

        if (container.isEmpty()) {
            val boxW = if (isSuperscript) 14f * density else 20f * density
            if (currentContainer === container) {
                return currentX + boxW / 2f
            }
            return null
        }

        for (i in 0..container.items.size) {
            if (currentContainer === container && cursorIndex == i) {
                return currentX
            }
            if (i >= container.items.size) break

            when (val item = container.items[i]) {
                is MathItem.Symbol -> {
                    currentX += paint.measureText(item.text)
                }
                is MathItem.Fraction -> {
                    val numM = measureContainer(item.num)
                    val denM = measureContainer(item.den)
                    val fracW = max(numM.width, denM.width) + 12f * density
                    val numX = currentX + (fracW - numM.width) / 2f
                    val denX = currentX + (fracW - denM.width) / 2f
                    val inNum = getCursorXInContainer(item.num, numX)
                    if (inNum != null) return inNum
                    val inDen = getCursorXInContainer(item.den, denX)
                    if (inDen != null) return inDen
                    currentX += fracW + 4f * density
                }
                is MathItem.MixedFraction -> {
                    val wM = measureContainer(item.whole)
                    val inW = getCursorXInContainer(item.whole, currentX)
                    if (inW != null) return inW
                    currentX += wM.width + 4f * density

                    val numM = measureContainer(item.num)
                    val denM = measureContainer(item.den)
                    val fracW = max(numM.width, denM.width) + 12f * density
                    val numX = currentX + (fracW - numM.width) / 2f
                    val denX = currentX + (fracW - denM.width) / 2f
                    val inNum = getCursorXInContainer(item.num, numX)
                    if (inNum != null) return inNum
                    val inDen = getCursorXInContainer(item.den, denX)
                    if (inDen != null) return inDen
                    currentX += fracW + 4f * density
                }
                is MathItem.Sqrt -> {
                    val inM = measureContainer(item.inner)
                    val hookW = if (item.degree != null) 17f * density else 14f * density
                    val inInner = getCursorXInContainer(item.inner, currentX + hookW)
                    if (inInner != null) return inInner
                    currentX += hookW + inM.width + 8f * density
                }
                is MathItem.Superscript -> {
                    val expM = measureContainer(item.exp, isSuperscript = true)
                    val inExp = getCursorXInContainer(item.exp, currentX, isSuperscript = true)
                    if (inExp != null) return inExp
                    currentX += expM.width + 2f * density
                }
                is MathItem.Combinatorics -> {
                    val symW = paint.measureText(item.symbol)
                    val upperM = measureContainer(item.upper, isSuperscript = true)
                    val lowerM = measureContainer(item.lower, isSuperscript = true)
                    val itemW = symW + max(upperM.width, lowerM.width) + 2f * density
                    val inUpper = getCursorXInContainer(item.upper, currentX + symW, isSuperscript = true)
                    if (inUpper != null) return inUpper
                    val inLower = getCursorXInContainer(item.lower, currentX + symW, isSuperscript = true)
                    if (inLower != null) return inLower
                    currentX += itemW + 2f * density
                }
                is MathItem.LogBase -> {
                    val logW = paint.measureText("log")
                    val baseM = measureContainer(item.base, isSuperscript = true)
                    val openW = paint.measureText("(")
                    val argM = measureContainer(item.arg, isSuperscript = false)
                    val closeW = paint.measureText(")")

                    val inBase = getCursorXInContainer(item.base, currentX + logW, isSuperscript = true)
                    if (inBase != null) return inBase

                    val inArg = getCursorXInContainer(item.arg, currentX + logW + baseM.width + openW, isSuperscript = false)
                    if (inArg != null) return inArg

                    currentX += logW + baseM.width + openW + argM.width + closeW
                }
            }
        }
        return null
    }

    private fun ensureCursorVisible() {
        if (width <= 0) return
        val startX = 14f * density
        val cursorX = getCursorXInContainer(rootContainer, startX) ?: return
        val margin = 24f * density

        if (cursorX - inputScrollX > width - margin) {
            inputScrollX = cursorX - (width - margin)
        } else if (cursorX - inputScrollX < margin) {
            inputScrollX = max(0f, cursorX - margin)
        }

        val totalM = measureContainer(rootContainer)
        val maxScroll = max(0f, startX + totalM.width + margin - width)
        inputScrollX = inputScrollX.coerceIn(0f, maxScroll)
    }

    private fun getResultBoundaryY(): Float {
        val showPreview = !hasResult && hasPreview && previewEntries.isNotEmpty()
        if (!hasResult && !showPreview) return height.toFloat()
        if (hasResult && resultEntries.isEmpty()) return height.toFloat()
        val text = if (hasResult) currentResultText else ""
        if (isErrorMessage(text) || shouldWrapMultilineResult()) {
            val lineH = 22f * density
            val marginBottom = 12f * density
            return (height.toFloat() - marginBottom - 3 * lineH - 8f * density).coerceAtLeast(height * 0.4f)
        }
        val lineH = 46f * density
        val marginBottom = 14f * density
        return (height.toFloat() - marginBottom - lineH - 12f * density).coerceAtLeast(height * 0.5f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Отрисовка вводимого выражения (слева сверху)
        val totalM = measureContainer(rootContainer)
        val inputBaselineY = if (isCompactInputMode && !hasResult && !hasPreview) {
            val centeredY = (height.toFloat() + totalM.ascent - totalM.descent) / 2f
            centeredY.coerceAtLeast(totalM.ascent + 6f * density)
        } else {
            max(34f * density, totalM.ascent + 6f * density)
        }
        val startX = 14f * density
        val margin = 24f * density
        val maxInputScroll = max(0f, startX + totalM.width + margin - width)
        if (followCursorOnNextDraw) {
            ensureCursorVisible()
            followCursorOnNextDraw = false
        } else {
            inputScrollX = inputScrollX.coerceIn(0f, maxInputScroll)
        }

        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), getResultBoundaryY())
        canvas.translate(-inputScrollX, 0f)
        drawContainer(canvas, rootContainer, startX, inputBaselineY)
        canvas.restore()

        // 2. Отрисовка результата(ов) Casio (справа снизу)
        if (hasResult && resultEntries.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(0f, getResultBoundaryY(), width.toFloat(), height.toFloat())
            if (isErrorMessage(currentResultText) || shouldWrapMultilineResult()) {
                drawMultilineError(canvas)
            } else {
                drawResultEntries(canvas, resultEntries, resultTextPaint, resultLinePaint)
            }
            canvas.restore()
        } else if (!hasResult && hasPreview && previewEntries.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(0f, getResultBoundaryY(), width.toFloat(), height.toFloat())
            drawResultEntries(canvas, previewEntries, previewTextPaint, previewLinePaint)
            canvas.restore()
        }
    }

    data class BracketInfo(
        val openIndices: Map<Int, Int>,
        val unmatchedIndices: Set<Int>,
        val activeMatchedPair: Pair<Int, Int>?
    )

    private fun analyzeBrackets(container: MathContainer): BracketInfo {
        val stack = mutableListOf<Int>()
        val matches = mutableMapOf<Int, Int>()
        val unmatched = mutableSetOf<Int>()

        for (i in container.items.indices) {
            val item = container.items[i]
            if (item is MathItem.Symbol) {
                if (item.text.endsWith("(")) {
                    stack.add(i)
                } else if (item.text == ")") {
                    if (stack.isNotEmpty()) {
                        val openIdx = stack.removeAt(stack.size - 1)
                        matches[openIdx] = i
                        matches[i] = openIdx
                    } else {
                        unmatched.add(i)
                    }
                }
            }
        }
        unmatched.addAll(stack)

        var activePair: Pair<Int, Int>? = null
        if (currentContainer === container) {
            var adjacentBracketIdx: Int? = null
            if (cursorIndex > 0 && cursorIndex - 1 < container.items.size) {
                val prevSym = container.items[cursorIndex - 1] as? MathItem.Symbol
                if (prevSym != null && (prevSym.text.endsWith("(") || prevSym.text == ")")) {
                    adjacentBracketIdx = cursorIndex - 1
                }
            }
            if (adjacentBracketIdx == null && cursorIndex < container.items.size) {
                val nextSym = container.items[cursorIndex] as? MathItem.Symbol
                if (nextSym != null && (nextSym.text.endsWith("(") || nextSym.text == ")")) {
                    adjacentBracketIdx = cursorIndex
                }
            }

            if (adjacentBracketIdx != null) {
                val partner = matches[adjacentBracketIdx]
                if (partner != null) {
                    activePair = Pair(adjacentBracketIdx, partner)
                }
            }
        }

        return BracketInfo(matches, unmatched, activePair)
    }

    private fun drawContainer(
        canvas: Canvas,
        container: MathContainer,
        startX: Float,
        baselineY: Float,
        isSuperscript: Boolean = false
    ): Float {
        var currentX = startX
        val paint = if (isSuperscript) smallTextPaint else inputTextPaint

        if (container.isEmpty()) {
            val boxW = if (isSuperscript) 14f * density else 20f * density
            val boxH = if (isSuperscript) 16f * density else 22f * density
            val topY = baselineY - (boxH * 0.75f)
            canvas.drawRect(currentX, topY, currentX + boxW, topY + boxH, placeholderPaint)

            if (currentContainer === container && isCursorVisible) {
                val cursorX = currentX + boxW / 2f
                canvas.drawLine(cursorX, topY + 2f * density, cursorX, topY + boxH - 2f * density, cursorPaint)
            }
            return currentX + boxW
        }

        val bracketInfo = analyzeBrackets(container)

        for (i in 0..container.items.size) {
            if (currentContainer === container && cursorIndex == i && isCursorVisible) {
                val fm = paint.fontMetrics
                canvas.drawLine(
                    currentX,
                    baselineY + fm.ascent,
                    currentX,
                    baselineY + fm.descent,
                    cursorPaint
                )
            }

            if (i >= container.items.size) break

            when (val item = container.items[i]) {
                is MathItem.Symbol -> {
                    val isMatchedActive = bracketInfo.activeMatchedPair != null &&
                            (i == bracketInfo.activeMatchedPair.first || i == bracketInfo.activeMatchedPair.second)
                    val isUnmatched = bracketInfo.unmatchedIndices.contains(i)

                    val symW = paint.measureText(item.text)
                    if (isMatchedActive || isUnmatched) {
                        val fm = paint.fontMetrics
                        val rectLeft = currentX - 1.5f * density
                        val rectTop = baselineY + fm.ascent - 0.5f * density
                        val rectRight = currentX + symW + 1.5f * density
                        val rectBottom = baselineY + fm.descent + 0.5f * density
                        val radius = 3.5f * density

                        val bgPaint = if (isMatchedActive) matchedBracketBgPaint else unmatchedBracketBgPaint
                        val borderPaint = if (isMatchedActive) matchedBracketBorderPaint else unmatchedBracketBorderPaint

                        canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, radius, radius, bgPaint)
                        canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, radius, radius, borderPaint)
                    }

                    canvas.drawText(item.text, currentX, baselineY, paint)
                    currentX += symW
                }
                is MathItem.Fraction -> {
                    val numM = measureContainer(item.num)
                    val denM = measureContainer(item.den)
                    val fracW = max(numM.width, denM.width) + 12f * density

                    val barY = baselineY - 6f * density
                    canvas.drawLine(currentX, barY, currentX + fracW, barY, inputLinePaint)

                    val numX = currentX + (fracW - numM.width) / 2f
                    val numBaseY = barY - 4f * density - numM.descent
                    drawContainer(canvas, item.num, numX, numBaseY)

                    val denX = currentX + (fracW - denM.width) / 2f
                    val denBaseY = barY + 4f * density + denM.ascent
                    drawContainer(canvas, item.den, denX, denBaseY)

                    currentX += fracW + 4f * density
                }
                is MathItem.MixedFraction -> {
                    val wM = measureContainer(item.whole)
                    drawContainer(canvas, item.whole, currentX, baselineY)
                    currentX += wM.width + 4f * density

                    val numM = measureContainer(item.num)
                    val denM = measureContainer(item.den)
                    val fracW = max(numM.width, denM.width) + 12f * density

                    val barY = baselineY - 6f * density
                    canvas.drawLine(currentX, barY, currentX + fracW, barY, inputLinePaint)

                    val numX = currentX + (fracW - numM.width) / 2f
                    val numBaseY = barY - 4f * density - numM.descent
                    drawContainer(canvas, item.num, numX, numBaseY)

                    val denX = currentX + (fracW - denM.width) / 2f
                    val denBaseY = barY + 4f * density + denM.ascent
                    drawContainer(canvas, item.den, denX, denBaseY)

                    currentX += fracW + 4f * density
                }
                is MathItem.Sqrt -> {
                    val inM = measureContainer(item.inner)
                    val hookW = if (item.degree != null) 17f * density else 14f * density
                    val totalW = hookW + inM.width + 4f * density
                    val topY = baselineY - inM.ascent - 3f * density
                    val bottomY = baselineY + inM.descent + 2f * density

                    val path = Path().apply {
                        if (item.degree != null) {
                            moveTo(currentX, baselineY - 2f * density)
                            lineTo(currentX + 4f * density, baselineY + 1f * density)
                            lineTo(currentX + 8f * density, bottomY)
                            lineTo(currentX + 13f * density, topY)
                            lineTo(currentX + totalW, topY)
                        } else {
                            moveTo(currentX, baselineY - 4f * density)
                            lineTo(currentX + 3f * density, baselineY)
                            lineTo(currentX + 6f * density, bottomY)
                            lineTo(currentX + 11f * density, topY)
                            lineTo(currentX + totalW, topY)
                        }
                    }
                    canvas.drawPath(path, inputLinePaint)

                    if (item.degree != null) {
                        val degreePaint = Paint(smallTextPaint).apply {
                            textSize = 11f * density
                            color = smallTextPaint.color
                            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                        }
                        val degX = currentX + 1.5f * density
                        val degY = topY + 9f * density
                        canvas.drawText(item.degree, degX, degY, degreePaint)
                    }

                    drawContainer(canvas, item.inner, currentX + hookW, baselineY)
                    currentX += totalW + 4f * density
                }
                is MathItem.Superscript -> {
                    val expM = measureContainer(item.exp, isSuperscript = true)
                    val expBaseY = baselineY - 10f * density
                    drawContainer(canvas, item.exp, currentX, expBaseY, isSuperscript = true)
                    currentX += expM.width + 2f * density
                }
                is MathItem.Combinatorics -> {
                    val symW = paint.measureText(item.symbol)
                    val upperM = measureContainer(item.upper, isSuperscript = true)
                    val lowerM = measureContainer(item.lower, isSuperscript = true)
                    val itemW = symW + max(upperM.width, lowerM.width) + 2f * density

                    canvas.drawText(item.symbol, currentX, baselineY, paint)

                    val upperBaseY = baselineY - 10f * density
                    val lowerBaseY = baselineY + 6f * density

                    drawContainer(canvas, item.upper, currentX + symW, upperBaseY, isSuperscript = true)
                    drawContainer(canvas, item.lower, currentX + symW, lowerBaseY, isSuperscript = true)

                    currentX += itemW + 2f * density
                }
                is MathItem.LogBase -> {
                    val logW = paint.measureText("log")
                    canvas.drawText("log", currentX, baselineY, paint)

                    val baseStartX = currentX + logW
                    val baseBaseY = baselineY + 6f * density
                    drawContainer(canvas, item.base, baseStartX, baseBaseY, isSuperscript = true)
                    val baseM = measureContainer(item.base, isSuperscript = true)

                    val openStartX = baseStartX + baseM.width
                    canvas.drawText("(", openStartX, baselineY, paint)
                    val openW = paint.measureText("(")

                    val argStartX = openStartX + openW
                    drawContainer(canvas, item.arg, argStartX, baselineY, isSuperscript = false)
                    val argM = measureContainer(item.arg, isSuperscript = false)

                    val closeStartX = argStartX + argM.width
                    canvas.drawText(")", closeStartX, baselineY, paint)
                    val closeW = paint.measureText(")")

                    currentX = closeStartX + closeW
                }
            }
        }
        return currentX
    }

    private fun isErrorMessage(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("Ошибка") || t.startsWith("Не удалось") || t.startsWith("Нет ") ||
                t.startsWith("Нет вещественных") || t.startsWith("Нет решений") || t.startsWith("Нет корней")
    }

    private fun shouldWrapMultilineResult(): Boolean {
        if (resultEntries.size != 1) return false
        val entry = resultEntries[0]
        if (entry.numerator != null || entry.denominator != null) return false
        val fullText = entry.prefix + entry.plainText
        val availW = width - 28f * density
        return resultTextPaint.measureText(fullText) > availW
    }

    private fun drawMultilineError(canvas: Canvas) {
        val marginRight = 14f * density
        val marginBottom = 12f * density
        val availW = (width.toFloat() - 2 * marginRight).coerceAtLeast(10f)

        val fullText = if (resultEntries.isNotEmpty()) {
            val e = resultEntries[0]
            if (e.prefix.isNotEmpty()) e.prefix + e.plainText else e.plainText
        } else currentResultText

        val textSizes = listOf(18f, 16f, 15f, 14f)
        var chosenPaint = Paint(resultTextPaint)
        var chosenLines = listOf<String>()

        for (sp in textSizes) {
            val paint = Paint(resultTextPaint).apply {
                textSize = sp * density
            }
            val words = fullText.split(Regex("\\s+"))
            val lines = mutableListOf<String>()
            val curLine = StringBuilder()

            for (w in words) {
                val candidate = if (curLine.isEmpty()) w else "$curLine $w"
                if (paint.measureText(candidate) <= availW) {
                    curLine.clear()
                    curLine.append(candidate)
                } else {
                    if (curLine.isNotEmpty()) {
                        lines.add(curLine.toString())
                        curLine.clear()
                    }
                    curLine.append(w)
                }
            }
            if (curLine.isNotEmpty()) {
                lines.add(curLine.toString())
            }

            chosenPaint = paint
            chosenLines = lines
            if (lines.size <= 3 && lines.all { paint.measureText(it) <= availW }) {
                break
            }
        }

        val fontMetrics = chosenPaint.fontMetrics
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent) * 1.15f
        val lastBaselineY = height.toFloat() - marginBottom - fontMetrics.descent

        for (idx in chosenLines.indices) {
            val line = chosenLines[idx]
            val lineBaselineY = lastBaselineY - (chosenLines.size - 1 - idx) * lineHeight
            val x = width.toFloat() - marginRight - chosenPaint.measureText(line)
            canvas.drawText(line, x, lineBaselineY, chosenPaint)
        }
    }

    private fun drawResultEntries(
        canvas: Canvas,
        entries: List<ResultEntry> = if (!hasResult && hasPreview) previewEntries else resultEntries,
        textPaint: Paint = if (!hasResult && hasPreview) previewTextPaint else resultTextPaint,
        linePaint: Paint = if (!hasResult && hasPreview) previewLinePaint else resultLinePaint
    ) {
        val gap = 16f * density          // отступ между корнями в одну строку
        val lineH = 46f * density        // высота одной строки результата
        val marginRight = 14f * density
        val marginBottom = 14f * density
        val wholeGap = 9f * density

        data class EntryMetrics(
            val entry: ResultEntry,
            val totalW: Float,
            val isFrac: Boolean,
            val prefixW: Float,
            val wholeW: Float,
            val fracW: Float,
            val numW: Float,
            val denW: Float
        )

        val metrics = entries.map { e ->
            val pw = textPaint.measureText(e.prefix)
            if (e.numerator != null && e.denominator != null) {
                val ww = if (e.whole != null) textPaint.measureText(e.whole) else 0f
                val nw = textPaint.measureText(e.numerator)
                val dw = textPaint.measureText(e.denominator)
                val fw = max(nw, dw) + 14f * density
                val totalEntryW = pw + (if (e.whole != null) ww + wholeGap else 0f) + fw
                EntryMetrics(e, totalEntryW, true, pw, ww, fw, nw, dw)
            } else {
                val tw = textPaint.measureText(e.prefix + e.plainText)
                EntryMetrics(e, tw, false, pw, 0f, 0f, 0f, 0f)
            }
        }

        val totalWOneLine = metrics.sumOf { it.totalW.toDouble() }.toFloat() +
                gap * (metrics.size - 1).coerceAtLeast(0)
        val availW = width - 2 * marginRight
        if (hasResult) {
            maxResultScrollX = max(0f, totalWOneLine - availW)
            resultScrollX = resultScrollX.coerceIn(0f, maxResultScrollX)
        }
        val currentScroll = if (hasResult) resultScrollX else 0f

        val baseY = height.toFloat() - marginBottom - lineH / 2f
        var x = if (totalWOneLine <= availW) {
            width.toFloat() - marginRight - totalWOneLine
        } else {
            marginRight - currentScroll
        }
        for (m in metrics) {
            x = drawSingleEntry(canvas, m.entry, m.isFrac, m.prefixW, m.wholeW, m.fracW, m.numW, m.denW, x, baseY, textPaint, linePaint)
            x += gap
        }
    }

    private fun drawSingleEntry(
        canvas: Canvas,
        e: ResultEntry,
        isFrac: Boolean,
        prefixW: Float,
        wholeW: Float,
        fracW: Float,
        numW: Float,
        denW: Float,
        startX: Float,
        baselineY: Float,
        textPaint: Paint = resultTextPaint,
        linePaint: Paint = resultLinePaint
    ): Float {
        var x = startX
        if (e.prefix.isNotEmpty()) {
            canvas.drawText(e.prefix, x, baselineY, textPaint)
            x += prefixW
        }
        if (isFrac && e.numerator != null && e.denominator != null) {
            if (e.whole != null) {
                canvas.drawText(e.whole, x, baselineY, textPaint)
                x += wholeW + 9f * density
            }
            val barY = baselineY - 7f * density
            canvas.drawLine(x, barY, x + fracW, barY, linePaint)
            val nx = x + (fracW - numW) / 2f
            canvas.drawText(e.numerator, nx, barY - 5f * density, textPaint)
            val dx2 = x + (fracW - denW) / 2f
            canvas.drawText(e.denominator, dx2, barY + 18f * density, textPaint)
            x += fracW
        } else {
            canvas.drawText(e.plainText, x, baselineY, textPaint)
            x += textPaint.measureText(e.plainText)
        }
        return x
    }

    private fun hitTestContainer(
        container: MathContainer,
        startX: Float,
        baselineY: Float,
        tapX: Float,
        tapY: Float,
        isSuperscript: Boolean = false
    ): Pair<MathContainer, Int> {
        if (container.isEmpty()) {
            return Pair(container, 0)
        }

        val paint = if (isSuperscript) smallTextPaint else inputTextPaint
        var currentX = startX

        for (i in 0 until container.items.size) {
            when (val item = container.items[i]) {
                is MathItem.Symbol -> {
                    val symW = paint.measureText(item.text)
                    if (tapX < currentX + symW / 2f) {
                        return Pair(container, i)
                    }
                    currentX += symW
                }
                is MathItem.Fraction -> {
                    val numM = measureContainer(item.num)
                    val denM = measureContainer(item.den)
                    val fracW = max(numM.width, denM.width) + 12f * density
                    val barY = baselineY - 6f * density
                    val totalItemW = fracW + 4f * density

                    if (tapX < currentX) {
                        return Pair(container, i)
                    }
                    if (tapX <= currentX + totalItemW) {
                        return if (tapY < barY) {
                            val numX = currentX + (fracW - numM.width) / 2f
                            val numBaseY = barY - 4f * density - numM.descent
                            hitTestContainer(item.num, numX, numBaseY, tapX, tapY)
                        } else {
                            val denX = currentX + (fracW - denM.width) / 2f
                            val denBaseY = barY + 4f * density + denM.ascent
                            hitTestContainer(item.den, denX, denBaseY, tapX, tapY)
                        }
                    }
                    currentX += totalItemW
                }
                is MathItem.MixedFraction -> {
                    val wM = measureContainer(item.whole)
                    val numM = measureContainer(item.num)
                    val denM = measureContainer(item.den)
                    val fracW = max(numM.width, denM.width) + 12f * density
                    val barY = baselineY - 6f * density
                    val wholeW = wM.width + 3f * density
                    val fracStartX = currentX + wholeW
                    val totalItemW = wholeW + fracW + 4f * density

                    if (tapX < currentX) {
                        return Pair(container, i)
                    }
                    if (tapX < fracStartX) {
                        return hitTestContainer(item.whole, currentX, baselineY, tapX, tapY)
                    }
                    if (tapX <= currentX + totalItemW) {
                        return if (tapY < barY) {
                            val numX = fracStartX + (fracW - numM.width) / 2f
                            val numBaseY = barY - 4f * density - numM.descent
                            hitTestContainer(item.num, numX, numBaseY, tapX, tapY)
                        } else {
                            val denX = fracStartX + (fracW - denM.width) / 2f
                            val denBaseY = barY + 4f * density + denM.ascent
                            hitTestContainer(item.den, denX, denBaseY, tapX, tapY)
                        }
                    }
                    currentX += totalItemW
                }
                is MathItem.Sqrt -> {
                    val inM = measureContainer(item.inner)
                    val hookW = if (item.degree != null) 17f * density else 14f * density
                    val totalW = hookW + inM.width + 4f * density
                    val totalItemW = totalW + 4f * density

                    if (tapX < currentX + hookW * 0.4f) {
                        return Pair(container, i)
                    }
                    if (tapX <= currentX + totalItemW) {
                        return hitTestContainer(item.inner, currentX + hookW, baselineY, tapX, tapY)
                    }
                    currentX += totalItemW
                }
                is MathItem.Superscript -> {
                    val expM = measureContainer(item.exp, isSuperscript = true)
                    val expW = expM.width + 2f * density
                    val expBaseY = baselineY - 10f * density

                    if (tapX < currentX) {
                        return Pair(container, i)
                    }
                    if (tapX <= currentX + expW) {
                        return hitTestContainer(item.exp, currentX, expBaseY, tapX, tapY, isSuperscript = true)
                    }
                    currentX += expW
                }
                is MathItem.Combinatorics -> {
                    val symW = paint.measureText(item.symbol)
                    val upperM = measureContainer(item.upper, isSuperscript = true)
                    val lowerM = measureContainer(item.lower, isSuperscript = true)
                    val itemW = symW + max(upperM.width, lowerM.width) + 2f * density
                    val totalItemW = itemW + 2f * density

                    if (tapX < currentX) {
                        return Pair(container, i)
                    }
                    if (tapX <= currentX + totalItemW) {
                        return if (tapY < baselineY) {
                            hitTestContainer(item.upper, currentX + symW, baselineY - 10f * density, tapX, tapY, isSuperscript = true)
                        } else {
                            hitTestContainer(item.lower, currentX + symW, baselineY + 6f * density, tapX, tapY, isSuperscript = true)
                        }
                    }
                    currentX += totalItemW
                }
                is MathItem.LogBase -> {
                    val logW = paint.measureText("log")
                    val baseM = measureContainer(item.base, isSuperscript = true)
                    val openW = paint.measureText("(")
                    val argM = measureContainer(item.arg, isSuperscript = false)
                    val closeW = paint.measureText(")")
                    val totalItemW = logW + baseM.width + openW + argM.width + closeW

                    if (tapX < currentX) {
                        return Pair(container, i)
                    }
                    if (tapX <= currentX + totalItemW) {
                        if (tapX < currentX + logW) {
                            return Pair(container, i)
                        }
                        if (tapX < currentX + logW + baseM.width) {
                            return hitTestContainer(item.base, currentX + logW, baselineY + 6f * density, tapX, tapY, isSuperscript = true)
                        }
                        if (tapX < currentX + logW + baseM.width + openW) {
                            return Pair(item.arg, 0)
                        }
                        return hitTestContainer(item.arg, currentX + logW + baseM.width + openW, baselineY, tapX, tapY, isSuperscript = false)
                    }
                    currentX += totalItemW
                }
            }
        }
        return Pair(container, container.items.size)
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (!isHorizontalDragging && !isVerticalSwiping) {
                performLongClick()
            }
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                lastTouchX = event.x
                dragStartScrollX = inputScrollX
                dragStartResultScrollX = resultScrollX
                isHorizontalDragging = false
                isVerticalSwiping = false
                isTouchInResultArea = event.y >= getResultBoundaryY()
                resetCursorBlink()
            }
            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.x - touchDownX
                val totalDy = event.y - touchDownY

                if (!isHorizontalDragging && !isVerticalSwiping) {
                    if (abs(totalDx) > touchSlop && abs(totalDx) >= abs(totalDy)) {
                        isHorizontalDragging = true
                        followCursorOnNextDraw = false
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (abs(totalDy) > touchSlop && abs(totalDy) > abs(totalDx)) {
                        isVerticalSwiping = true
                    }
                }

                if (isHorizontalDragging) {
                    if (isTouchInResultArea && (hasResult || (!hasResult && hasPreview && previewEntries.isNotEmpty()))) {
                        resultScrollX = (dragStartResultScrollX - totalDx).coerceIn(0f, maxResultScrollX)
                    } else {
                        val startX = 14f * density
                        val margin = 24f * density
                        val totalM = measureContainer(rootContainer)
                        val maxInputScroll = max(0f, startX + totalM.width + margin - width)
                        inputScrollX = (dragStartScrollX - totalDx).coerceIn(0f, maxInputScroll)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!isHorizontalDragging && !isVerticalSwiping) {
                    if (event.y < getResultBoundaryY()) {
                        val totalM = measureContainer(rootContainer)
                        val inputBaselineY = if (isCompactInputMode && !hasResult && !hasPreview) {
                            val centeredY = (height.toFloat() + totalM.ascent - totalM.descent) / 2f
                            centeredY.coerceAtLeast(totalM.ascent + 6f * density)
                        } else {
                            max(34f * density, totalM.ascent + 6f * density)
                        }
                        val startX = 14f * density
                        val tapCanvasX = event.x + inputScrollX
                        val tapCanvasY = event.y
                        val hit = hitTestContainer(rootContainer, startX, inputBaselineY, tapCanvasX, tapCanvasY)
                        val prevContainer = currentContainer
                        val prevIndex = cursorIndex
                        currentContainer = hit.first
                        cursorIndex = hit.second
                        if (currentContainer !== prevContainer || cursorIndex != prevIndex) {
                            followCursorOnNextDraw = true
                        }
                        resetCursorBlink()
                        invalidate()
                    } else if (!hasResult && hasPreview && previewEntries.isNotEmpty()) {
                        onPreviewClick?.invoke()
                    }
                    performClick()
                } else if (isVerticalSwiping) {
                    val dy = event.y - touchDownY
                    if (dy > 60f * density) {
                        onSwipeDown?.invoke()
                    }
                }
                isHorizontalDragging = false
                isVerticalSwiping = false
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isHorizontalDragging = false
                isVerticalSwiping = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
