package com.example.calculator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class TrigSolverManager(
    private val rootLayout: View,
    private val casEngine: CasEngine,
    private val onHaptic: (View) -> Unit = {}
) {

    val layoutTrigSolver: View = rootLayout.findViewById(R.id.layoutTrigSolver)
    val scrollTrigSolver: ScrollView = rootLayout.findViewById(R.id.scrollTrigSolver)
    val btnTrigBack: TextView = rootLayout.findViewById(R.id.btnTrigBack)
    val tvTrigTitle: TextView = rootLayout.findViewById(R.id.tvTrigTitle)
    val fabToggleTrigKeypad: TextView = rootLayout.findViewById(R.id.fabToggleTrigKeypad)
    val btnTrigClear: TextView = rootLayout.findViewById(R.id.btnTrigClear)

    val cardTrigEquation: MaterialCardView = rootLayout.findViewById(R.id.cardTrigEquation)
    val tvTrigEqLabel: TextView = rootLayout.findViewById(R.id.tvTrigEqLabel)
    val etTrigEquation: EditText = rootLayout.findViewById(R.id.etTrigEquation)

    val cardTrigInterval: MaterialCardView = rootLayout.findViewById(R.id.cardTrigInterval)
    val tvTrigIntervalLabel: TextView = rootLayout.findViewById(R.id.tvTrigIntervalLabel)
    val etTrigIntervalStart: EditText = rootLayout.findViewById(R.id.etTrigIntervalStart)
    val etTrigIntervalEnd: EditText = rootLayout.findViewById(R.id.etTrigIntervalEnd)
    val btnTrigSolve: Button = rootLayout.findViewById(R.id.btnTrigSolve)

    val cardUnitCircle: MaterialCardView = rootLayout.findViewById(R.id.cardUnitCircle)
    val tvUnitCircleTitle: TextView = rootLayout.findViewById(R.id.tvUnitCircleTitle)
    val unitCircleView: UnitCircleView = rootLayout.findViewById(R.id.unitCircleView)

    val cardTrigResult: MaterialCardView = rootLayout.findViewById(R.id.cardTrigResult)
    val tvTrigResultHeader: TextView = rootLayout.findViewById(R.id.tvTrigResultHeader)
    val btnCopyTrigResult: Button = rootLayout.findViewById(R.id.btnCopyTrigResult)
    val tvTrigResultTitleA: TextView = rootLayout.findViewById(R.id.tvTrigResultTitleA)
    val tvTrigResultA: TextView = rootLayout.findViewById(R.id.tvTrigResultA)
    val tvTrigResultTitleB: TextView = rootLayout.findViewById(R.id.tvTrigResultTitleB)
    val tvTrigResultB: TextView = rootLayout.findViewById(R.id.tvTrigResultB)

    val viewStepsDivider: View = rootLayout.findViewById(R.id.viewStepsDivider)
    val btnToggleTrigSteps: MaterialButton = rootLayout.findViewById(R.id.btnToggleTrigSteps)
    val layoutTrigStepsContainer: LinearLayout = rootLayout.findViewById(R.id.layoutTrigStepsContainer)

    val layoutTrigKeypad: LinearLayout = rootLayout.findViewById(R.id.layoutTrigKeypad)

    private var activeCell: EditText = etTrigEquation
    private var lastResult: TrigSolverEngine.TrigSolveResult? = null
    private var isStepsExpanded: Boolean = false

    init {
        initInputs()
        initKeypad()

        btnTrigSolve.bindCrispKey(isHeavy = true) {
            solve()
        }

        btnTrigClear.bindCrispKey(isHeavy = true) {
            clearAll()
        }

        fabToggleTrigKeypad.bindCrispKey(isHeavy = true) {
            toggleKeypad()
        }

        btnCopyTrigResult.bindCrispKey(isHeavy = true) {
            copyResult()
        }

        btnToggleTrigSteps.bindCrispKey(isHeavy = false) {
            toggleSteps()
        }

        etTrigEquation.setText("sin(x)^2 - 2sin(x) = 0")
        etTrigIntervalStart.setText("π/2")
        etTrigIntervalEnd.setText("π")

        refreshTheme()
        solve()
    }

    private fun initInputs() {
        val cells = listOf(etTrigEquation, etTrigIntervalStart, etTrigIntervalEnd)
        for (cell in cells) {
            cell.showSoftInputOnFocus = false
            cell.setOnClickListener {
                onHaptic(it)
                setActiveCell(cell)
            }
            cell.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    setActiveCell(cell)
                }
            }
        }
        setActiveCell(etTrigEquation)
    }

    fun setActiveCell(cell: EditText) {
        if (activeCell != cell) {
            activeCell.background = getCellBackground(false)
            activeCell = cell
            activeCell.background = getCellBackground(true)
        } else {
            cell.background = getCellBackground(true)
        }
        cell.requestFocus()
    }

    private fun getCellBackground(isFocused: Boolean): Drawable {
        val dp = rootLayout.resources.displayMetrics.density
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * dp
            setColor(if (isDark) ThemeManager.COLOR_DARK_INPUT else ThemeManager.COLOR_LIGHT_INPUT)
            if (isFocused) {
                setStroke((1.8f * dp).toInt(), Color.parseColor("#38BDF8"))
            } else {
                setStroke((1f * dp).toInt(), if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)
            }
        }
    }

    private fun initKeypad() {
        val keyMap = mapOf(
            R.id.btnTrigSin to "sin(",
            R.id.btnTrigCos to "cos(",
            R.id.btnTrigTan to "tan(",
            R.id.btnTrigCtg to "ctg(",
            R.id.btnTrigVarX to "x",
            R.id.btnTrigPi to "π",
            R.id.btnTrigSqrt to "√(",
            R.id.btnTrigSq to "^2",
            R.id.btnTrigEq to "=",
            R.id.btnTrigDiv to "/",
            R.id.btnTrigPlus to "+",
            R.id.btnTrigMinus to "−",
            R.id.btnTrigMul to "*",
            R.id.btnTrigOpen to "(",
            R.id.btnTrigClose to ")",
            R.id.btnTrigDot to ".",
            R.id.btnTrig0 to "0",
            R.id.btnTrig1 to "1",
            R.id.btnTrig2 to "2",
            R.id.btnTrig3 to "3",
            R.id.btnTrig4 to "4",
            R.id.btnTrig5 to "5",
            R.id.btnTrig6 to "6",
            R.id.btnTrig7 to "7",
            R.id.btnTrig8 to "8",
            R.id.btnTrig9 to "9"
        )

        for ((id, text) in keyMap) {
            rootLayout.findViewById<Button>(id)?.bindCrispKey(isHeavy = false) {
                handleInput(text)
            }
        }

        rootLayout.findViewById<Button>(R.id.btnTrigDel)?.bindCrispKey(isHeavy = true) {
            handleDelete()
        }
        rootLayout.findViewById<Button>(R.id.btnTrigDel)?.setOnLongClickListener {
            KeyFeedback.trigger(it, isHeavy = true)
            activeCell.setText("")
            true
        }

        rootLayout.findViewById<Button>(R.id.btnTrigKeypadSolve)?.bindCrispKey(isHeavy = true) {
            solve()
        }
    }

    private fun handleInput(str: String) {
        val cell = activeCell
        val start = maxOf(0, cell.selectionStart)
        val end = maxOf(0, cell.selectionEnd)
        val minPos = minOf(start, end)
        val maxPos = maxOf(start, end)

        cell.text.replace(minPos, maxPos, str)
        val newPos = minPos + str.length
        if (newPos <= cell.text.length) {
            cell.setSelection(newPos)
        }
    }

    private fun handleDelete() {
        val cell = activeCell
        val start = maxOf(0, cell.selectionStart)
        val end = maxOf(0, cell.selectionEnd)
        val minPos = minOf(start, end)
        val maxPos = maxOf(start, end)

        if (minPos != maxPos) {
            cell.text.delete(minPos, maxPos)
        } else if (minPos > 0) {
            cell.text.delete(minPos - 1, minPos)
        }
    }

    fun solve() {
        val eq = etTrigEquation.text.toString().trim()
        val startStr = etTrigIntervalStart.text.toString().trim()
        val endStr = etTrigIntervalEnd.text.toString().trim()

        if (eq.isEmpty()) {
            Toast.makeText(rootLayout.context, "Введите уравнение", Toast.LENGTH_SHORT).show()
            return
        }

        val hasInterval = startStr.isNotEmpty() && endStr.isNotEmpty()
        val result = TrigSolverEngine.solve(
            equation = eq,
            intervalStartStr = if (hasInterval) startStr else null,
            intervalEndStr = if (hasInterval) endStr else null,
            casEngine = casEngine
        )
        lastResult = result

        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        val textPrimary = if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY
        val textSecondary = if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY
        val textError = Color.parseColor("#EF4444")

        // Пункт а) Общее решение
        if (result.generalSolutions.isNotEmpty()) {
            tvTrigResultA.text = result.generalSolutions.joinToString(";  ") { it.displayFormula }
            tvTrigResultA.setTextColor(textPrimary)
        } else if (result.errorMessage != null) {
            tvTrigResultA.text = result.errorMessage
            tvTrigResultA.setTextColor(textError)
        } else {
            tvTrigResultA.text = "Нет действительных корней"
            tvTrigResultA.setTextColor(textError)
        }

        // Пункт б) Корни на отрезке
        if (hasInterval) {
            if (result.rootsOnInterval.isNotEmpty()) {
                val subs = listOf("₁", "₂", "₃", "₄", "₅", "₆", "₇", "₈", "₉")
                val rootsStr = result.rootsOnInterval.mapIndexed { idx, r ->
                    val sub = subs.getOrElse(idx) { "${idx + 1}" }
                    "x$sub = ${r.symbolic}"
                }.joinToString(";  ")
                tvTrigResultB.text = rootsStr
                tvTrigResultB.setTextColor(textPrimary)
            } else {
                tvTrigResultB.text = "На отрезке корней нет"
                tvTrigResultB.setTextColor(textSecondary)
            }
        } else {
            tvTrigResultB.text = "Отрезок не задан"
            tvTrigResultB.setTextColor(textSecondary)
        }

        // Окружность
        val startVal = if (hasInterval) TrigSolverEngine.parseIntervalBound(startStr, casEngine)?.first else null
        val endVal = if (hasInterval) TrigSolverEngine.parseIntervalBound(endStr, casEngine)?.first else null
        unitCircleView.setSolution(result, startVal, endVal)

        // Ход решения (подробные шаги)
        if (result.errorMessage == null && result.detailedSteps.isNotEmpty()) {
            viewStepsDivider.visibility = View.VISIBLE
            btnToggleTrigSteps.visibility = View.VISIBLE
            isStepsExpanded = false
            layoutTrigStepsContainer.visibility = View.GONE
            btnToggleTrigSteps.text = "📖 Показать ход решения ▾"
            populateTrigSteps(result.detailedSteps)
        } else {
            viewStepsDivider.visibility = View.GONE
            btnToggleTrigSteps.visibility = View.GONE
            layoutTrigStepsContainer.visibility = View.GONE
            isStepsExpanded = false
            layoutTrigStepsContainer.removeAllViews()
        }
    }

    fun clearAll() {
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        val textSecondary = if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY

        etTrigEquation.setText("")
        etTrigIntervalStart.setText("")
        etTrigIntervalEnd.setText("")
        tvTrigResultA.text = "Введите уравнение"
        tvTrigResultA.setTextColor(textSecondary)
        tvTrigResultB.text = "Отрезок не задан"
        tvTrigResultB.setTextColor(textSecondary)
        unitCircleView.clear()

        viewStepsDivider.visibility = View.GONE
        btnToggleTrigSteps.visibility = View.GONE
        layoutTrigStepsContainer.visibility = View.GONE
        isStepsExpanded = false
        layoutTrigStepsContainer.removeAllViews()

        setActiveCell(etTrigEquation)
    }

    private fun toggleSteps() {
        isStepsExpanded = !isStepsExpanded
        layoutTrigStepsContainer.visibility = if (isStepsExpanded) View.VISIBLE else View.GONE
        btnToggleTrigSteps.text = if (isStepsExpanded) "▲ Скрыть ход решения" else "📖 Показать ход решения ▾"
        if (isStepsExpanded) {
            scrollTrigSolver.post {
                val targetY = cardTrigResult.top + btnToggleTrigSteps.top
                scrollTrigSolver.smoothScrollTo(0, targetY)
            }
        }
    }

    private fun populateTrigSteps(steps: List<TrigSolverEngine.TrigStep>) {
        layoutTrigStepsContainer.removeAllViews()
        val context = rootLayout.context
        val isDark = ThemeManager.isDarkTheme(context)
        val dp = rootLayout.resources.displayMetrics.density

        val textPrimary = if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY
        val textSecondary = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#475569")
        val cardBg = if (isDark) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC")
        val cardBorder = if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1")

        for (step in steps) {
            val stepCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (10 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * dp
                    setColor(cardBg)
                    setStroke((1 * dp).toInt(), cardBorder)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * dp).toInt()
                }
            }

            // Top Row: Section Pill + Title
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val pillBgColor: Int
            val pillTextColor: Int
            when (step.section) {
                "ОДЗ" -> {
                    pillBgColor = if (isDark) Color.parseColor("#7F1D1D") else Color.parseColor("#FEE2E2")
                    pillTextColor = if (isDark) Color.parseColor("#F87171") else Color.parseColor("#B91C1C")
                }
                "Пункт а" -> {
                    pillBgColor = if (isDark) Color.parseColor("#0C4A6E") else Color.parseColor("#E0F2FE")
                    pillTextColor = if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
                }
                else -> { // Пункт б
                    pillBgColor = if (isDark) Color.parseColor("#064E3B") else Color.parseColor("#D1FAE5")
                    pillTextColor = if (isDark) Color.parseColor("#34D399") else Color.parseColor("#059669")
                }
            }

            val tvPill = TextView(context).apply {
                text = step.section
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(pillTextColor)
                val hPad = (6 * dp).toInt()
                val vPad = (2 * dp).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4f * dp
                    setColor(pillBgColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (6 * dp).toInt()
                }
            }

            val tvTitle = TextView(context).apply {
                text = step.title
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            topRow.addView(tvPill)
            topRow.addView(tvTitle)
            stepCard.addView(topRow)

            // Middle: Formula
            val tvFormula = TextView(context).apply {
                text = step.formula
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(textPrimary)
                setTextIsSelectable(true)
                setLineSpacing(2f * dp, 1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * dp).toInt()
                }
            }
            stepCard.addView(tvFormula)

            // Bottom: Explanation
            val tvExplanation = TextView(context).apply {
                text = step.explanation
                textSize = 12f
                setTextColor(textSecondary)
                setLineSpacing(1f * dp, 1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * dp).toInt()
                }
            }
            stepCard.addView(tvExplanation)

            layoutTrigStepsContainer.addView(stepCard)
        }
    }

    private fun toggleKeypad() {
        val isVisible = layoutTrigKeypad.visibility == View.VISIBLE
        val newVisible = !isVisible
        layoutTrigKeypad.visibility = if (newVisible) View.VISIBLE else View.GONE
        fabToggleTrigKeypad.text = if (newVisible) "▼" else "▲"
    }

    private fun copyResult() {
        val textA = tvTrigResultA.text.toString()
        val textB = tvTrigResultB.text.toString()
        val fullText = "а) $textA\nб) $textB"

        val clipboard = rootLayout.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Trig Solution", fullText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(rootLayout.context, "Решение скопировано", Toast.LENGTH_SHORT).show()
    }

    fun refreshTheme() {
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        val dp = rootLayout.resources.displayMetrics.density
        val textPrimary = if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY
        val textSecondary = if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY
        val textError = Color.parseColor("#EF4444")

        layoutTrigSolver.setBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_SCREEN else ThemeManager.COLOR_LIGHT_SCREEN)
        tvTrigTitle.setTextColor(textPrimary)

        listOf(btnTrigBack, btnTrigClear).forEach { btn ->
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * dp
                setColor(if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL)
                setStroke((1f * dp).toInt(), if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)
            }
        }

        fabToggleTrigKeypad.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * dp
            setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#FFFFFF"))
            setStroke((1f * dp).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
        }
        fabToggleTrigKeypad.setTextColor(
            if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669")
        )

        listOf(cardTrigEquation, cardTrigInterval, cardUnitCircle, cardTrigResult).forEach { card ->
            card.setCardBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL)
            card.strokeColor = if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER
        }

        listOf(
            tvTrigEqLabel, tvTrigIntervalLabel, tvUnitCircleTitle,
            tvTrigResultHeader
        ).forEach { tv ->
            tv.setTextColor(textSecondary)
        }

        tvTrigResultTitleA.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
        tvTrigResultTitleB.setTextColor(if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669"))

        val res = lastResult
        if (res != null) {
            if (res.generalSolutions.isNotEmpty()) {
                tvTrigResultA.setTextColor(textPrimary)
            } else {
                tvTrigResultA.setTextColor(textError)
            }

            if (etTrigIntervalStart.text.isNotBlank() && etTrigIntervalEnd.text.isNotBlank()) {
                if (res.rootsOnInterval.isNotEmpty()) {
                    tvTrigResultB.setTextColor(textPrimary)
                } else {
                    tvTrigResultB.setTextColor(textSecondary)
                }
            } else {
                tvTrigResultB.setTextColor(textSecondary)
            }
        } else {
            tvTrigResultA.setTextColor(textPrimary)
            tvTrigResultB.setTextColor(textSecondary)
        }

        etTrigEquation.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
        etTrigIntervalStart.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
        etTrigIntervalEnd.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)

        etTrigEquation.background = getCellBackground(activeCell == etTrigEquation)
        etTrigIntervalStart.background = getCellBackground(activeCell == etTrigIntervalStart)
        etTrigIntervalEnd.background = getCellBackground(activeCell == etTrigIntervalEnd)

        btnCopyTrigResult.backgroundTintList = ColorStateList.valueOf(if (isDark) ThemeManager.COLOR_DARK_INPUT else ThemeManager.COLOR_LIGHT_INPUT)
        (btnCopyTrigResult as? MaterialButton)?.strokeColor = ColorStateList.valueOf(if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)

        btnToggleTrigSteps.backgroundTintList = ColorStateList.valueOf(if (isDark) ThemeManager.COLOR_DARK_INPUT else ThemeManager.COLOR_LIGHT_INPUT)
        btnToggleTrigSteps.strokeColor = ColorStateList.valueOf(if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)
        btnToggleTrigSteps.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))

        viewStepsDivider.setBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)

        lastResult?.detailedSteps?.let { steps ->
            if (steps.isNotEmpty() && layoutTrigStepsContainer.childCount > 0) {
                populateTrigSteps(steps)
            }
        }

        layoutTrigKeypad.setBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_SCREEN else ThemeManager.COLOR_LIGHT_SCREEN)

        // Цифровые кнопки
        val digitIds = listOf(
            R.id.btnTrig0, R.id.btnTrig1, R.id.btnTrig2, R.id.btnTrig3, R.id.btnTrig4,
            R.id.btnTrig5, R.id.btnTrig6, R.id.btnTrig7, R.id.btnTrig8, R.id.btnTrig9, R.id.btnTrigDot
        )
        for (id in digitIds) {
            rootLayout.findViewById<Button>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(if (isDark) ThemeManager.COLOR_DARK_KEYPAD_DIGIT else ThemeManager.COLOR_LIGHT_KEYPAD_DIGIT)
                setTextColor(if (isDark) Color.WHITE else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
            }
        }

        // Функциональные кнопки
        val funcIds = listOf(
            R.id.btnTrigSin, R.id.btnTrigCos, R.id.btnTrigTan, R.id.btnTrigCtg, R.id.btnTrigVarX, R.id.btnTrigPi,
            R.id.btnTrigSqrt, R.id.btnTrigSq, R.id.btnTrigEq, R.id.btnTrigDiv, R.id.btnTrigPlus,
            R.id.btnTrigMinus, R.id.btnTrigMul, R.id.btnTrigOpen, R.id.btnTrigClose
        )
        for (id in funcIds) {
            rootLayout.findViewById<Button>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(if (isDark) ThemeManager.COLOR_DARK_KEYPAD_FUNC else ThemeManager.COLOR_LIGHT_KEYPAD_FUNC)
                if (id == R.id.btnTrigVarX) {
                    setTextColor(if (isDark) Color.parseColor("#F59E0B") else Color.parseColor("#D97706"))
                } else {
                    setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
                }
            }
        }

        unitCircleView.isDarkTheme = isDark
    }
}
