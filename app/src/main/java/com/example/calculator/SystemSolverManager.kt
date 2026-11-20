package com.example.calculator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

class SystemSolverManager(
    private val rootLayout: View,
    private val casEngine: CasEngine,
    private val onHaptic: (View) -> Unit = {}
) {

    val layoutSystemSolver: View = rootLayout.findViewById(R.id.layoutSystemSolver)
    val btnSystemBack: TextView = rootLayout.findViewById(R.id.btnSystemBack)
    val btnSystemClear: TextView = rootLayout.findViewById(R.id.btnSystemClear)
    val containerEquationsList: LinearLayout = rootLayout.findViewById(R.id.containerEquationsList)
    val btnAddEquation: Button = rootLayout.findViewById(R.id.btnAddEquation)
    val btnSolveSystemMain: Button = rootLayout.findViewById(R.id.btnSolveSystemMain)
    val cardSystemResult: View = rootLayout.findViewById(R.id.cardSystemResult)
    val tvSystemResult: TextView = rootLayout.findViewById(R.id.tvSystemResult)
    val btnCopySystemResult: Button = rootLayout.findViewById(R.id.btnCopySystemResult)
    val layoutSystemKeypad: LinearLayout = rootLayout.findViewById(R.id.layoutSystemKeypad)
    val fabToggleSystemKeypad: TextView = rootLayout.findViewById(R.id.fabToggleSystemKeypad)

    val viewSystemStepsDivider: View = rootLayout.findViewById(R.id.viewSystemStepsDivider)
    val btnToggleSystemSteps: MaterialButton = rootLayout.findViewById(R.id.btnToggleSystemSteps)
    val layoutSystemStepsContainer: LinearLayout = rootLayout.findViewById(R.id.layoutSystemStepsContainer)
    val scrollSystemSolver: ScrollView? = rootLayout.findViewById(R.id.scrollSystemSolver)

    private var isStepsExpanded: Boolean = false
    private var lastResult: LinearSystemResult? = null

    private val solver = EquationSystemSolver(casEngine)

    data class EquationRowData(
        val view: View,
        val tvNumber: TextView,
        val editText: EditText,
        val btnDelete: TextView
    )

    private val equationRows = mutableListOf<EquationRowData>()
    private var activeCell: EditText? = null

    private val defaultHints = listOf(
        "x - y = 2",
        "2y - x = 4",
        "3x + 2y - z = 7",
        "w + x + y + z = 10",
        "u + 2v = 5"
    )

    init {
        initKeypad()

        btnAddEquation.bindCrispKey(isHeavy = true) {
            addEquationRow()
        }

        btnSolveSystemMain.bindCrispKey(isHeavy = true) {
            solve()
        }

        btnSystemClear.bindCrispKey(isHeavy = true) {
            resetAll()
        }

        btnCopySystemResult.bindCrispKey(isHeavy = true) {
            val textToCopy = tvSystemResult.text.toString().trim()
            if (textToCopy.isNotEmpty() && textToCopy != "Результат появится здесь") {
                val clipboard = rootLayout.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Результат", textToCopy))
                Toast.makeText(rootLayout.context, "Ответ скопирован", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(rootLayout.context, "Нет результата для копирования", Toast.LENGTH_SHORT).show()
            }
        }

        fabToggleSystemKeypad.bindCrispKey(isHeavy = true) {
            val isVis = layoutSystemKeypad.visibility == View.VISIBLE
            setKeypadVisible(!isVis)
        }

        btnToggleSystemSteps.bindCrispKey(isHeavy = false) {
            toggleSteps()
        }

        // По умолчанию 2 строки уравнений
        resetAll()
    }

    fun resetAll() {
        containerEquationsList.removeAllViews()
        equationRows.clear()
        activeCell = null
        tvSystemResult.text = "Результат появится здесь"
        hideSteps()

        addEquationRow()
        addEquationRow()

        if (equationRows.isNotEmpty()) {
            setActiveCell(equationRows[0].editText)
        }
        setKeypadVisible(true)
    }

    private fun hideSteps() {
        viewSystemStepsDivider.visibility = View.GONE
        btnToggleSystemSteps.visibility = View.GONE
        layoutSystemStepsContainer.visibility = View.GONE
        isStepsExpanded = false
        layoutSystemStepsContainer.removeAllViews()
        lastResult = null
    }

    private fun toggleSteps() {
        isStepsExpanded = !isStepsExpanded
        layoutSystemStepsContainer.visibility = if (isStepsExpanded) View.VISIBLE else View.GONE
        btnToggleSystemSteps.text = if (isStepsExpanded) "▲ Скрыть ход решения" else "📖 Показать ход решения ▾"
        if (isStepsExpanded) {
            scrollSystemSolver?.post {
                val targetY = cardSystemResult.top + btnToggleSystemSteps.top
                scrollSystemSolver.smoothScrollTo(0, targetY)
            }
        }
    }

    fun addEquationRow(initialText: String = ""): EditText? {
        if (equationRows.size >= 5) {
            Toast.makeText(rootLayout.context, "Максимум 5 уравнений", Toast.LENGTH_SHORT).show()
            return null
        }

        val context = rootLayout.context
        val density = rootLayout.resources.displayMetrics.density
        val dp = { value: Float -> (value * density).toInt() }

        val rowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(4f), 0, dp(4f))
            }
            gravity = Gravity.CENTER_VERTICAL
        }

        val rowIndex = equationRows.size

        val isDark = ThemeManager.isDarkTheme(context)
        val tvNumber = TextView(context).apply {
            text = "${rowIndex + 1})"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#475569"))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(26f), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        rowLayout.addView(tvNumber)

        val cell = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1f).apply {
                setMargins(dp(4f), 0, dp(6f), 0)
            }
            background = getCellBackground(false)
            setTextColor(if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(12f), 0, dp(12f), 0)
            hint = defaultHints.getOrNull(rowIndex) ?: "a·x + b = 0"
            setHintTextColor(if (isDark) Color.parseColor("#475569") else Color.parseColor("#94A3B8"))
            isSingleLine = true
            maxLines = 1
            showSoftInputOnFocus = false
            isCursorVisible = true
            tag = "eq_input_${rowIndex}"
            contentDescription = "eq_input_${rowIndex}"
            if (initialText.isNotEmpty()) setText(initialText)

            setOnTouchListener { _, _ ->
                setActiveCell(this)
                false
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    setActiveCell(this)
                }
            }
            setOnClickListener {
                setActiveCell(this)
            }
        }
        rowLayout.addView(cell)

        val btnDelete = TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#EF4444"))
            gravity = Gravity.CENTER
            val delGd = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#FFFFFF"))
                cornerRadius = 6f * density
                setStroke((1f * density).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
            }
            background = delGd
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f))
        }
        rowLayout.addView(btnDelete)

        val rowData = EquationRowData(rowLayout, tvNumber, cell, btnDelete)
        equationRows.add(rowData)
        containerEquationsList.addView(rowLayout)

        btnDelete.bindCrispKey(isHeavy = true) {
            removeEquationRow(rowData)
        }

        updateRowsState()
        setActiveCell(cell)
        return cell
    }

    fun removeEquationRow(row: EquationRowData) {
        if (equationRows.size <= 2) {
            Toast.makeText(rootLayout.context, "Минимум 2 уравнения", Toast.LENGTH_SHORT).show()
            return
        }

        val index = equationRows.indexOf(row)
        if (index == -1) return

        containerEquationsList.removeView(row.view)
        equationRows.removeAt(index)

        updateRowsState()

        if (activeCell == row.editText) {
            val nextIndex = if (index < equationRows.size) index else equationRows.size - 1
            setActiveCell(equationRows[nextIndex].editText)
        }
    }

    private fun updateRowsState() {
        val showDelete = equationRows.size > 2
        for ((i, row) in equationRows.withIndex()) {
            row.tvNumber.text = "${i + 1})"
            row.editText.tag = "eq_input_$i"
            row.editText.contentDescription = "eq_input_$i"
            row.btnDelete.visibility = if (showDelete) View.VISIBLE else View.GONE
        }
        btnAddEquation.isEnabled = equationRows.size < 5
        btnAddEquation.alpha = if (equationRows.size < 5) 1.0f else 0.5f
    }

    fun setKeypadVisible(visible: Boolean) {
        layoutSystemKeypad.visibility = if (visible) View.VISIBLE else View.GONE
        fabToggleSystemKeypad.text = if (visible) "▼" else "▲"
    }

    fun setActiveCell(cell: EditText) {
        if (activeCell != cell) {
            activeCell?.background = getCellBackground(false)
            activeCell = cell
            activeCell?.background = getCellBackground(true)
        }
        setKeypadVisible(true)
        cell.requestFocus()
        cell.setSelection(cell.text?.length ?: 0)
    }

    fun refreshTheme() {
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        val density = rootLayout.resources.displayMetrics.density
        for (row in equationRows) {
            val isFocused = (activeCell == row.editText)
            row.editText.background = getCellBackground(isFocused)
            row.editText.setTextColor(if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
            row.editText.setHintTextColor(if (isDark) Color.parseColor("#475569") else Color.parseColor("#94A3B8"))
            row.tvNumber.setTextColor(if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#475569"))
            row.btnDelete.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#FFFFFF"))
                cornerRadius = 6f * density
                setStroke((1f * density).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
            }
        }

        btnToggleSystemSteps.backgroundTintList = ColorStateList.valueOf(
            if (isDark) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC")
        )
        btnToggleSystemSteps.strokeColor = ColorStateList.valueOf(
            if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER
        )
        btnToggleSystemSteps.setTextColor(
            if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
        )
        viewSystemStepsDivider.setBackgroundColor(
            if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER
        )

        fabToggleSystemKeypad.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#FFFFFF"))
            setStroke((1f * density).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
        }
        fabToggleSystemKeypad.setTextColor(
            if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669")
        )

        lastResult?.detailedSteps?.let { steps ->
            if (steps.isNotEmpty() && layoutSystemStepsContainer.childCount > 0) {
                populateSystemSteps(steps)
            }
        }
    }

    private fun getCellBackground(isFocused: Boolean): Drawable {
        val density = rootLayout.resources.displayMetrics.density
        val gd = GradientDrawable()
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        gd.shape = GradientDrawable.RECTANGLE
        gd.setColor(if (isDark) Color.parseColor("#0F172A") else Color.parseColor("#E2E8F0"))
        gd.cornerRadius = 8f * density
        if (isFocused) {
            gd.setStroke((2f * density).toInt(), Color.parseColor("#38BDF8"))
        } else {
            gd.setStroke((1f * density).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
        }
        return gd
    }

    private fun initKeypad() {
        val inputMap = mapOf(
            // Цифры
            R.id.btnSys0 to "0", R.id.btnSys1 to "1", R.id.btnSys2 to "2",
            R.id.btnSys3 to "3", R.id.btnSys4 to "4", R.id.btnSys5 to "5",
            R.id.btnSys6 to "6", R.id.btnSys7 to "7", R.id.btnSys8 to "8",
            R.id.btnSys9 to "9",
            // Переменные
            R.id.btnSysVarX to "x", R.id.btnSysVarY to "y", R.id.btnSysVarZ to "z",
            R.id.btnSysVarA to "a", R.id.btnSysVarB to "b", R.id.btnSysVarC to "c",
            // Операторы
            R.id.btnSysDot to ".", R.id.btnSysDiv to "/", R.id.btnSysMul to "·",
            R.id.btnSysPlus to "+", R.id.btnSysPow to "^", R.id.btnSysEq to "=",
            R.id.btnSysBracketOpen to "(", R.id.btnSysBracketClose to ")"
        )

        inputMap.forEach { (id, str) ->
            rootLayout.findViewById<Button>(id)?.bindCrispKey(isHeavy = false) {
                handleInput(str)
            }
        }

        rootLayout.findViewById<Button>(R.id.btnSysMinus)?.bindCrispKey(isHeavy = false) {
            handleInput("-")
        }

        rootLayout.findViewById<Button>(R.id.btnSysDel)?.bindCrispKey(isHeavy = true) {
            handleDelete()
        }

        rootLayout.findViewById<Button>(R.id.btnSysNext)?.bindCrispKey(isHeavy = true) {
            handleNext()
        }

        rootLayout.findViewById<Button>(R.id.btnSysSolve)?.bindCrispKey(isHeavy = true) {
            solve()
        }
    }

    private fun handleInput(str: String) {
        val cell = activeCell ?: equationRows.firstOrNull()?.editText ?: return
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
        val cell = activeCell ?: equationRows.firstOrNull()?.editText ?: return
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

    private fun handleNext() {
        if (equationRows.isEmpty()) return
        val currentIndex = equationRows.indexOfFirst { it.editText == activeCell }
        if (currentIndex == -1 || currentIndex == equationRows.lastIndex) {
            solve()
        } else {
            setActiveCell(equationRows[currentIndex + 1].editText)
        }
    }

    fun solve() {
        if (equationRows.isEmpty()) return
        val equations = equationRows.map { it.editText.text.toString().trim() }
        val result = solver.solve(equations)
        renderLinearResult(result)
    }

    private fun renderLinearResult(result: LinearSystemResult) {
        lastResult = result
        when (result) {
            is LinearSystemResult.UniqueSolution -> {
                tvSystemResult.text = result.formattedLines.joinToString("\n")
            }
            is LinearSystemResult.NoSolution -> {
                tvSystemResult.text = "Система несовместна (нет решений)"
            }
            is LinearSystemResult.InfiniteSolutions -> {
                tvSystemResult.text = "Бесконечно много решений (зависимые уравнения)"
            }
            is LinearSystemResult.Error -> {
                tvSystemResult.text = result.message
            }
        }

        val steps = result.detailedSteps
        if (result !is LinearSystemResult.Error && steps.isNotEmpty()) {
            viewSystemStepsDivider.visibility = View.VISIBLE
            btnToggleSystemSteps.visibility = View.VISIBLE
            isStepsExpanded = false
            layoutSystemStepsContainer.visibility = View.GONE
            btnToggleSystemSteps.text = "📖 Показать ход решения ▾"
            populateSystemSteps(steps)
        } else {
            viewSystemStepsDivider.visibility = View.GONE
            btnToggleSystemSteps.visibility = View.GONE
            layoutSystemStepsContainer.visibility = View.GONE
            isStepsExpanded = false
            layoutSystemStepsContainer.removeAllViews()
        }
    }

    private fun populateSystemSteps(steps: List<SystemStep>) {
        layoutSystemStepsContainer.removeAllViews()
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

            // Верхняя строка: Pill с рангом/тегом + Заголовок
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val pillBgColor: Int
            val pillTextColor: Int
            when {
                step.badge == "Анализ" -> {
                    pillBgColor = if (isDark) Color.parseColor("#7F1D1D") else Color.parseColor("#FEE2E2")
                    pillTextColor = if (isDark) Color.parseColor("#F87171") else Color.parseColor("#B91C1C")
                }
                step.badge == "Ответ" -> {
                    pillBgColor = if (isDark) Color.parseColor("#064E3B") else Color.parseColor("#D1FAE5")
                    pillTextColor = if (isDark) Color.parseColor("#34D399") else Color.parseColor("#059669")
                }
                else -> {
                    pillBgColor = if (isDark) Color.parseColor("#0C4A6E") else Color.parseColor("#E0F2FE")
                    pillTextColor = if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
                }
            }

            val tvPill = TextView(context).apply {
                text = step.badge
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
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
                typeface = Typeface.DEFAULT_BOLD
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

            // Формула
            if (step.formula.isNotEmpty()) {
                val tvFormula = TextView(context).apply {
                    text = step.formula
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
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
            }

            // Пояснение
            if (step.explanation.isNotEmpty()) {
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
            }

            layoutSystemStepsContainer.addView(stepCard)
        }
    }
}
