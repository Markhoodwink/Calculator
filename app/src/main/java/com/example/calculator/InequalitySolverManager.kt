package com.example.calculator

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class InequalitySolverManager(
    private val rootLayout: View,
    private val casEngine: CasEngine,
    private val onHaptic: (View) -> Unit = {}
) {

    val layoutInequalitySolver: View = rootLayout.findViewById(R.id.layoutInequalitySolver)
    val btnIneqBack: TextView = rootLayout.findViewById(R.id.btnIneqBack)
    val tvIneqTitle: TextView = rootLayout.findViewById(R.id.tvIneqTitle)
    val btnIneqClear: TextView = rootLayout.findViewById(R.id.btnIneqClear)

    val cardIneqInput: MaterialCardView = rootLayout.findViewById(R.id.cardIneqInput)
    val ineqCasioDisplay: CasioDisplayView = rootLayout.findViewById(R.id.ineqCasioDisplay)
    val btnIneqToggleKeyboard: TextView = rootLayout.findViewById(R.id.btnIneqToggleKeyboard)
    val btnIneqSolve: Button = rootLayout.findViewById(R.id.btnIneqSolve)

    val cardIneqNumberLine: MaterialCardView = rootLayout.findViewById(R.id.cardIneqNumberLine)
    val ineqNumberLineView: NumberLineView = rootLayout.findViewById(R.id.ineqNumberLineView)
    val tvIneqNumberLineHint: TextView = rootLayout.findViewById(R.id.tvIneqNumberLineHint)

    val cardIneqResult: MaterialCardView = rootLayout.findViewById(R.id.cardIneqResult)
    val tvIneqResultHeader: TextView = rootLayout.findViewById(R.id.tvIneqResultHeader)
    val tvIneqAnswer: TextView = rootLayout.findViewById(R.id.tvIneqAnswer)
    val tvIneqOdz: TextView = rootLayout.findViewById(R.id.tvIneqOdz)
    val btnIneqSteps: MaterialButton = rootLayout.findViewById(R.id.btnIneqSteps)

    val layoutIneqKeypad: LinearLayout = rootLayout.findViewById(R.id.layoutIneqKeypad)

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestCounter = AtomicLong(0L)

    private var lastResult: InequalityResult? = null

    init {
        ineqCasioDisplay.isCompactInputMode = true

        ineqNumberLineView.onPointOrIntervalSelected = {
            KeyFeedback.trigger(ineqNumberLineView, isHeavy = false)
        }

        btnIneqSolve.bindCrispKey(isHeavy = true) {
            solve()
        }

        btnIneqClear.bindCrispKey(isHeavy = true) {
            clearAll()
        }

        btnIneqToggleKeyboard.bindCrispKey(isHeavy = true) {
            toggleKeypad()
        }

        btnIneqSteps.bindCrispKey(isHeavy = true) {
            val steps = lastResult?.detailedSteps
            if (!steps.isNullOrEmpty()) {
                StepsBottomSheet(rootLayout.context, steps) { v -> onHaptic(v) }.show()
            }
        }

        initPresets()
        initKeypad()

        refreshTheme()

        // Default initial formula
        ineqCasioDisplay.loadExpression("3^(x) + 10*3^(3 - x) ≥ 37")
        solve()
    }

    private fun initPresets() {
        val preset1 = rootLayout.findViewById<Button>(R.id.btnIneqPreset1)
        val preset2 = rootLayout.findViewById<Button>(R.id.btnIneqPreset2)
        val preset3 = rootLayout.findViewById<Button>(R.id.btnIneqPreset3)
        val preset4 = rootLayout.findViewById<Button>(R.id.btnIneqPreset4)
        val preset5 = rootLayout.findViewById<Button>(R.id.btnIneqPreset5)
        val preset6 = rootLayout.findViewById<Button>(R.id.btnIneqPreset6)
        val preset7 = rootLayout.findViewById<Button>(R.id.btnIneqPreset7)

        preset1?.bindCrispKey(isHeavy = false) { applyPreset("((x - 2)/(x + 1)) ≤ 0") }
        preset2?.bindCrispKey(isHeavy = false) { applyPreset("3^(x) + 10*3^(3 - x) ≥ 37") }
        preset3?.bindCrispKey(isHeavy = false) { applyPreset("((2^(x))/(2^(x) - 8)) + ((2^(x) + 8)/(2^(x) - 4)) + ((66)/(4^(x) - 12*2^(x) + 32)) ≤ 0") }
        preset4?.bindCrispKey(isHeavy = false) { applyPreset("log((x),(x^(3) + 2x - 13)) ≤ log((x),(x^(3) - 8))") }
        preset5?.bindCrispKey(isHeavy = false) { applyPreset("((|x^(2) + 2x - 3| - |x^(2) + 3x + 5|)/(2x + 1)) ≥ 0") }
        preset6?.bindCrispKey(isHeavy = false) { applyPreset("√(x + ((2)/(3)))*log((2),(log((0.5),(|1 + x|)))) ≤ 0") }
        preset7?.bindCrispKey(isHeavy = false) { applyPreset("|1 - log((2x),(x^(2) - 5x + 6))| ≤ 1 - log((2x),(x^(2) - 5x + 6))") }
    }

    private fun applyPreset(formula: String) {
        ineqCasioDisplay.loadExpression(formula)
        solve()
    }

    fun loadFormulaAndSolve(casExpr: String) {
        ineqCasioDisplay.loadExpression(casExpr)
        solve()
    }

    private fun initKeypad() {
        // 2D CAS Templates & special keys
        rootLayout.findViewById<View>(R.id.btnIneqFrac)?.bindCrispKey(isHeavy = false) {
            ineqCasioDisplay.insertFraction()
        }
        rootLayout.findViewById<View>(R.id.btnIneqLog)?.bindCrispKey(isHeavy = false) {
            ineqCasioDisplay.insertLogBase()
        }
        rootLayout.findViewById<View>(R.id.btnIneqSqrt)?.bindCrispKey(isHeavy = false) {
            ineqCasioDisplay.insertSqrt()
        }
        rootLayout.findViewById<View>(R.id.btnIneqPower)?.bindCrispKey(isHeavy = false) {
            ineqCasioDisplay.insertSuperscript()
        }
        rootLayout.findViewById<View>(R.id.btnIneqSq)?.bindCrispKey(isHeavy = false) {
            ineqCasioDisplay.insertSuperscript("2")
        }
        rootLayout.findViewById<View>(R.id.btnIneqAbs)?.bindCrispKey(isHeavy = false) {
            ineqCasioDisplay.insertText("|")
            ineqCasioDisplay.insertText("|")
            ineqCasioDisplay.moveLeft()
        }
        rootLayout.findViewById<View>(R.id.btnIneqLn)?.bindCrispKey(isHeavy = false) {
            ineqCasioDisplay.insertText("ln(")
        }
        rootLayout.findViewById<View>(R.id.btnIneqUp)?.bindCrispKey(isHeavy = false) {
            ineqCasioDisplay.jumpUp()
        }
        rootLayout.findViewById<View>(R.id.btnIneqDown)?.bindCrispKey(isHeavy = false) {
            ineqCasioDisplay.jumpDown()
        }

        // Standard text insert keys
        val keyTextMap = mapOf(
            R.id.btnIneqLt to "<",
            R.id.btnIneqGt to ">",
            R.id.btnIneqLe to "≤",
            R.id.btnIneqGe to "≥",
            R.id.btnIneqLParen to "(",
            R.id.btnIneqRParen to ")",
            R.id.btnIneqX to "x",
            R.id.btnIneq7 to "7",
            R.id.btnIneq8 to "8",
            R.id.btnIneq9 to "9",
            R.id.btnIneqDiv to "/",
            R.id.btnIneq4 to "4",
            R.id.btnIneq5 to "5",
            R.id.btnIneq6 to "6",
            R.id.btnIneqMul to "*",
            R.id.btnIneq1 to "1",
            R.id.btnIneq2 to "2",
            R.id.btnIneq3 to "3",
            R.id.btnIneq0 to "0",
            R.id.btnIneqDot to ".",
            R.id.btnIneqMinus to "-",
            R.id.btnIneqPlus to "+"
        )

        for ((id, text) in keyTextMap) {
            rootLayout.findViewById<View>(id)?.bindCrispKey(isHeavy = false) {
                ineqCasioDisplay.insertText(text)
            }
        }

        // Action & Repeating keys
        rootLayout.findViewById<View>(R.id.btnIneqAc)?.bindCrispKey(isHeavy = true) {
            ineqCasioDisplay.clearAll()
        }

        rootLayout.findViewById<View>(R.id.btnIneqKeypadSolve)?.bindCrispKey(isHeavy = true) {
            solve()
        }

        rootLayout.findViewById<View>(R.id.btnIneqDel)?.bindRepeatingCrispKey(isHeavy = true) {
            val oldExpr = ineqCasioDisplay.getRawExpression()
            val oldContainer = ineqCasioDisplay.currentContainer
            val oldCursor = ineqCasioDisplay.cursorIndex
            ineqCasioDisplay.deleteAtCursor()
            ineqCasioDisplay.getRawExpression() != oldExpr || ineqCasioDisplay.currentContainer !== oldContainer || ineqCasioDisplay.cursorIndex != oldCursor
        }

        rootLayout.findViewById<View>(R.id.btnIneqLeft)?.bindRepeatingCrispKey(isHeavy = false) {
            val oldContainer = ineqCasioDisplay.currentContainer
            val oldCursor = ineqCasioDisplay.cursorIndex
            ineqCasioDisplay.moveLeft()
            ineqCasioDisplay.currentContainer !== oldContainer || ineqCasioDisplay.cursorIndex != oldCursor
        }

        rootLayout.findViewById<View>(R.id.btnIneqRight)?.bindRepeatingCrispKey(isHeavy = false) {
            val oldContainer = ineqCasioDisplay.currentContainer
            val oldCursor = ineqCasioDisplay.cursorIndex
            ineqCasioDisplay.moveRight()
            ineqCasioDisplay.currentContainer !== oldContainer || ineqCasioDisplay.cursorIndex != oldCursor
        }
    }

    fun clearAll() {
        ineqCasioDisplay.clearAll()
        ineqNumberLineView.clear()
        tvIneqAnswer.text = ""
        tvIneqOdz.text = ""
        btnIneqSteps.visibility = View.GONE
        lastResult = null
    }

    private fun toggleKeypad() {
        if (layoutIneqKeypad.visibility == View.VISIBLE) {
            layoutIneqKeypad.visibility = View.GONE
            btnIneqToggleKeyboard.text = "⌨"
        } else {
            layoutIneqKeypad.visibility = View.VISIBLE
            btnIneqToggleKeyboard.text = "▼"
        }
    }

    fun solve() {
        ineqCasioDisplay.autoCloseUnclosedParens()
        val expr = ineqCasioDisplay.getRawExpression().trim()
        if (expr.isEmpty()) {
            ineqNumberLineView.clear()
            tvIneqAnswer.text = "Введите неравенство"
            tvIneqOdz.text = ""
            btnIneqSteps.visibility = View.GONE
            lastResult = null
            return
        }

        tvIneqAnswer.text = "Вычисляем..."
        tvIneqOdz.text = ""
        btnIneqSteps.visibility = View.GONE

        val requestId = requestCounter.incrementAndGet()

        executor.execute {
            val engineSnapshot = CasEngine().apply {
                isDegreeMode = false
                lastAnswer = casEngine.lastAnswer
            }
            val result = try {
                InequalitySolverEngine.solve(expr, engineSnapshot)
            } catch (e: Exception) {
                InequalityResult(
                    answerFormula = "Ошибка: ${e.localizedMessage ?: "Не удалось решить"}",
                    intervals = emptyList(),
                    criticalPoints = emptyList(),
                    odzConstraints = emptyList(),
                    detailedSteps = emptyList(),
                    errorMessage = e.localizedMessage,
                    isPeriodic = false
                )
            }

            mainHandler.post {
                if (requestId == requestCounter.get()) {
                    displayResult(result)
                }
            }
        }
    }

    private fun displayResult(res: InequalityResult) {
        lastResult = res
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)

        if (res.errorMessage != null) {
            tvIneqAnswer.text = res.errorMessage
            tvIneqAnswer.setTextColor(Color.parseColor("#EF4444"))
            tvIneqOdz.visibility = View.GONE
            ineqNumberLineView.clear()
            btnIneqSteps.visibility = View.GONE
        } else {
            tvIneqAnswer.text = res.answerFormula
            tvIneqAnswer.setTextColor(if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669"))
            ineqNumberLineView.setResult(res)

            if (res.odzConstraints.isNotEmpty()) {
                tvIneqOdz.text = "ОДЗ: " + res.odzConstraints.joinToString("; ")
                tvIneqOdz.visibility = View.VISIBLE
            } else {
                tvIneqOdz.text = "ОДЗ: x ∈ ℝ"
                tvIneqOdz.visibility = View.VISIBLE
            }

            if (res.detailedSteps.isNotEmpty()) {
                btnIneqSteps.visibility = View.VISIBLE
            } else {
                btnIneqSteps.visibility = View.GONE
            }
        }
    }

    fun refreshTheme() {
        val context = rootLayout.context
        val isDark = ThemeManager.isDarkTheme(context)
        val dp = rootLayout.resources.displayMetrics.density

        val textPrimary = if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY
        val textSecondary = if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY
        val panelBg = if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL
        val borderCol = if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER
        val screenBg = if (isDark) ThemeManager.COLOR_DARK_SCREEN else ThemeManager.COLOR_LIGHT_SCREEN

        layoutInequalitySolver.setBackgroundColor(screenBg)
        tvIneqTitle.setTextColor(textPrimary)

        listOf(btnIneqBack, btnIneqClear).forEach { btn ->
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * dp
                setColor(panelBg)
                setStroke((1f * dp).toInt(), borderCol)
            }
        }

        btnIneqToggleKeyboard.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * dp
            setColor(panelBg)
            setStroke((1f * dp).toInt(), borderCol)
        }
        btnIneqToggleKeyboard.setTextColor(if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669"))

        listOf(cardIneqInput, cardIneqNumberLine, cardIneqResult).forEach { card ->
            card.setCardBackgroundColor(panelBg)
            card.strokeColor = borderCol
        }

        ineqCasioDisplay.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * dp
            setColor(if (isDark) ThemeManager.COLOR_DARK_INPUT else ThemeManager.COLOR_LIGHT_INPUT)
            setStroke((1f * dp).toInt(), borderCol)
        }
        ineqCasioDisplay.setEditorTheme(isDark)

        tvIneqResultHeader.setTextColor(textSecondary)
        tvIneqNumberLineHint.setTextColor(if (isDark) Color.parseColor("#64748B") else Color.parseColor("#94A3B8"))

        ineqNumberLineView.setTheme(isDark)

        btnIneqSteps.backgroundTintList = ColorStateList.valueOf(if (isDark) ThemeManager.COLOR_DARK_INPUT else ThemeManager.COLOR_LIGHT_INPUT)
        btnIneqSteps.strokeColor = ColorStateList.valueOf(borderCol)
        btnIneqSteps.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))

        // Preset chips theme
        val presetIds = listOf(
            R.id.btnIneqPreset1, R.id.btnIneqPreset2, R.id.btnIneqPreset3,
            R.id.btnIneqPreset4, R.id.btnIneqPreset5, R.id.btnIneqPreset6, R.id.btnIneqPreset7
        )
        presetIds.forEach { id ->
            rootLayout.findViewById<Button>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(if (isDark) ThemeManager.COLOR_DARK_KEYPAD_FUNC else ThemeManager.COLOR_LIGHT_KEYPAD_FUNC)
                setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            }
        }

        // Keypad theme
        layoutIneqKeypad.setBackgroundColor(screenBg)

        val digitIds = listOf(
            R.id.btnIneq0, R.id.btnIneq1, R.id.btnIneq2, R.id.btnIneq3, R.id.btnIneq4,
            R.id.btnIneq5, R.id.btnIneq6, R.id.btnIneq7, R.id.btnIneq8, R.id.btnIneq9,
            R.id.btnIneqDot
        )
        digitIds.forEach { id ->
            rootLayout.findViewById<Button>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(if (isDark) ThemeManager.COLOR_DARK_KEYPAD_DIGIT else ThemeManager.COLOR_LIGHT_KEYPAD_DIGIT)
                setTextColor(if (isDark) Color.WHITE else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
            }
        }

        val funcIds = listOf(
            R.id.btnIneqLeft, R.id.btnIneqUp, R.id.btnIneqDown, R.id.btnIneqRight,
            R.id.btnIneqLt, R.id.btnIneqGt, R.id.btnIneqLe, R.id.btnIneqGe,
            R.id.btnIneqLParen, R.id.btnIneqRParen, R.id.btnIneqFrac, R.id.btnIneqLog,
            R.id.btnIneqSqrt, R.id.btnIneqPower, R.id.btnIneqAbs, R.id.btnIneqX,
            R.id.btnIneqDiv, R.id.btnIneqMul, R.id.btnIneqMinus, R.id.btnIneqPlus,
            R.id.btnIneqSq, R.id.btnIneqLn, R.id.btnIneqDel, R.id.btnIneqAc
        )
        funcIds.forEach { id ->
            rootLayout.findViewById<Button>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(if (isDark) ThemeManager.COLOR_DARK_KEYPAD_FUNC else ThemeManager.COLOR_LIGHT_KEYPAD_FUNC)
                if (id == R.id.btnIneqX) {
                    setTextColor(if (isDark) Color.parseColor("#F59E0B") else Color.parseColor("#D97706"))
                } else if (id == R.id.btnIneqAc || id == R.id.btnIneqDel) {
                    setTextColor(Color.parseColor("#EF4444"))
                } else {
                    setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
                }
            }
        }

        rootLayout.findViewById<Button>(R.id.btnIneqKeypadSolve)?.apply {
            backgroundTintList = ColorStateList.valueOf(if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
        }

        btnIneqSolve.backgroundTintList = ColorStateList.valueOf(if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669"))
        btnIneqSolve.setTextColor(Color.WHITE)
    }

    fun cleanup() {
        executor.shutdownNow()
    }
}
