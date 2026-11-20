package com.example.calculator

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.*

class GraphModeManager(
    private val rootLayout: View,
    private val casEngine: CasEngine,
    private val onHaptic: (View) -> Unit = {}
) {

    companion object {
        val PALETTE: List<Int> = listOf(
            "#38BDF8", // Голубой (Desmos cyan)
            "#EF4444", // Красный
            "#10B981", // Зеленый
            "#A855F7", // Фиолетовый
            "#F59E0B", // Янтарный
            "#06B6D4"  // Бирюзовый
        ).map { Color.parseColor(it) }
    }

    data class SeriesItem(
        val id: Int,
        var formula: String,
        var color: Int,
        var visible: Boolean = true,
        var compiled: CompiledPlot? = null,
        val rowView: View,
        val visibilityView: View,
        val editText: EditText,
        val removeBtn: View
    )

    val layoutGraphMode: View = rootLayout.findViewById(R.id.layoutGraphMode)
    val btnGraphBack: TextView = rootLayout.findViewById(R.id.btnGraphBack)
    val btnGraphReset: TextView = rootLayout.findViewById(R.id.btnGraphReset)
    val fabToggleGraphKeypad: TextView = rootLayout.findViewById(R.id.fabToggleGraphKeypad)

    val btnAddGraphSeries: Button = rootLayout.findViewById(R.id.btnAddGraphSeries)
    val btnGraphHome: Button = rootLayout.findViewById(R.id.btnGraphHome)
    val btnGraphPlot: Button = rootLayout.findViewById(R.id.btnGraphPlot)
    val scrollGraphSeries: ScrollView = rootLayout.findViewById(R.id.scrollGraphSeries)
    val containerGraphSeriesList: LinearLayout = rootLayout.findViewById(R.id.containerGraphSeriesList)
    val containerParameterSliders: LinearLayout = rootLayout.findViewById(R.id.containerParameterSliders)

    val tabGraphPlot: Button = rootLayout.findViewById(R.id.tabGraphPlot)
    val tabGraphTable: Button = rootLayout.findViewById(R.id.tabGraphTable)

    val containerGraphPlot: View = rootLayout.findViewById(R.id.containerGraphPlot)
    val containerGraphTable: View = rootLayout.findViewById(R.id.containerGraphTable)

    val graphView: GraphView = rootLayout.findViewById(R.id.graphView)
    val btnGraphZoomIn: Button = rootLayout.findViewById(R.id.btnGraphZoomIn)
    val btnGraphZoomOut: Button = rootLayout.findViewById(R.id.btnGraphZoomOut)

    val etTableStart: EditText = rootLayout.findViewById(R.id.etTableStart)
    val etTableEnd: EditText = rootLayout.findViewById(R.id.etTableEnd)
    val etTableStep: EditText = rootLayout.findViewById(R.id.etTableStep)
    val btnTableBuild: Button = rootLayout.findViewById(R.id.btnTableBuild)
    val tvTableHeaderY: TextView = rootLayout.findViewById(R.id.tvTableHeaderY)
    val containerTableRows: LinearLayout = rootLayout.findViewById(R.id.containerTableRows)

    val layoutGraphKeypad: View = rootLayout.findViewById(R.id.layoutGraphKeypad)

    private val seriesList: MutableList<SeriesItem> = mutableListOf()
    private var nextSeriesId = 1
    private var nextColorIndex = 0
    private var activeEditText: EditText? = null
    private var activeSeriesItem: SeriesItem? = null

    // Значения параметров (a, b, k...)
    val paramValues: MutableMap<String, Double> = mutableMapOf()
    private val paramSliderViews: MutableMap<String, View> = mutableMapOf()

    init {
        setupTabs()
        setupZoomControls()
        setupKeypad()

        fabToggleGraphKeypad.bindCrispKey(isHeavy = true) {
            val isVis = layoutGraphKeypad.visibility == View.VISIBLE
            setKeypadVisible(!isVis)
        }

        btnAddGraphSeries.bindCrispKey(isHeavy = true) {
            if (seriesList.size >= 6) {
                Toast.makeText(rootLayout.context, "Максимум 6 графиков", Toast.LENGTH_SHORT).show()
                return@bindCrispKey
            }
            addNewSeries()
        }

        btnGraphHome.bindCrispKey(isHeavy = false) {
            graphView.resetViewport()
        }

        btnGraphPlot.bindCrispKey(isHeavy = true) {
            plotAll(silent = false)
        }

        btnTableBuild.bindCrispKey(isHeavy = true) {
            rebuildTable()
        }

        btnGraphReset.bindCrispKey(isHeavy = true) {
            resetAll()
        }

        setupTableInputs()

        // По умолчанию 1 строка: y = x^2 - 4 (цвет #38BDF8)
        addNewSeries("y = x^2 - 4")
        setKeypadVisible(true)
        updateSlidersAndPlot()
    }

    fun setKeypadVisible(visible: Boolean) {
        layoutGraphKeypad.visibility = if (visible) View.VISIBLE else View.GONE
        fabToggleGraphKeypad.text = if (visible) "▼" else "▲"
    }

    private fun setupTableInputs() {
        listOf(etTableStart, etTableEnd, etTableStep).forEach { et ->
            et.showSoftInputOnFocus = false
            et.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    setActiveField(v as EditText)
                }
            }
            et.setOnClickListener { v ->
                onHaptic(v)
                setActiveField(v as EditText)
                setKeypadVisible(true)
            }
        }
    }

    fun addNewSeries(initialFormula: String = "", initialColor: Int? = null): SeriesItem {
        val id = nextSeriesId++
        val color = initialColor ?: PALETTE[(nextColorIndex++) % PALETTE.size]

        val context = rootLayout.context
        val inflater = LayoutInflater.from(context)
        val rowView = inflater.inflate(R.layout.item_graph_series, containerGraphSeriesList, false)

        val visibilityView: View = rowView.findViewById(R.id.btnSeriesVisibility)
        val editText: EditText = rowView.findViewById(R.id.etSeriesFormula)
        val removeBtn: View = rowView.findViewById(R.id.btnRemoveSeries)

        editText.setText(initialFormula)
        editText.showSoftInputOnFocus = false

        val item = SeriesItem(
            id = id,
            formula = initialFormula,
            color = color,
            visible = true,
            compiled = null,
            rowView = rowView,
            visibilityView = visibilityView,
            editText = editText,
            removeBtn = removeBtn
        )

        updateVisibilityCircle(visibilityView, color, item.visible)

        visibilityView.bindCrispKey(isHeavy = false) {
            item.visible = !item.visible
            updateVisibilityCircle(visibilityView, item.color, item.visible)
            plotAll(silent = true)
        }

        visibilityView.setOnLongClickListener {
            KeyFeedback.trigger(it, isHeavy = false)
            val curIdx = PALETTE.indexOf(item.color)
            val nextIdx = if (curIdx >= 0) (curIdx + 1) % PALETTE.size else 0
            item.color = PALETTE[nextIdx]
            updateVisibilityCircle(visibilityView, item.color, item.visible)
            setActiveField(item.editText)
            plotAll(silent = true)
            true
        }

        editText.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                activeSeriesItem = item
                setActiveField(v as EditText)
                setKeypadVisible(true)
            }
        }

        editText.setOnClickListener { v ->
            onHaptic(v)
            activeSeriesItem = item
            setActiveField(v as EditText)
            setKeypadVisible(true)
        }

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                item.formula = s?.toString() ?: ""
                updateSlidersAndPlot()
            }
        })

        removeBtn.bindCrispKey(isHeavy = true) {
            removeSeries(item)
        }

        seriesList.add(item)
        containerGraphSeriesList.addView(rowView)

        updateRemoveButtonsVisibility()
        updateSeriesScrollHeight()
        updateAddButtonState()

        activeSeriesItem = item
        setActiveField(editText)

        return item
    }

    private fun removeSeries(item: SeriesItem) {
        if (seriesList.size <= 1) return
        seriesList.remove(item)
        containerGraphSeriesList.removeView(item.rowView)

        updateRemoveButtonsVisibility()
        updateSeriesScrollHeight()
        updateAddButtonState()

        if (activeSeriesItem == item) {
            val fallback = seriesList.lastOrNull()
            activeSeriesItem = fallback
            if (fallback != null) {
                setActiveField(fallback.editText)
            }
        }

        updateSlidersAndPlot()
    }

    private fun updateRemoveButtonsVisibility() {
        val showRemove = seriesList.size > 1
        seriesList.forEach { s ->
            s.removeBtn.visibility = if (showRemove) View.VISIBLE else View.GONE
        }
    }

    private fun updateAddButtonState() {
        val canAdd = seriesList.size < 6
        btnAddGraphSeries.isEnabled = canAdd
        btnAddGraphSeries.alpha = if (canAdd) 1.0f else 0.5f
    }

    private fun updateSeriesScrollHeight() {
        val dp = rootLayout.resources.displayMetrics.density
        val maxH = (135 * dp).toInt()
        val lp = scrollGraphSeries.layoutParams
        if (seriesList.size <= 2) {
            if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                scrollGraphSeries.layoutParams = lp
            }
        } else {
            if (lp.height != maxH) {
                lp.height = maxH
                scrollGraphSeries.layoutParams = lp
            }
        }
    }

    private fun updateVisibilityCircle(v: View, color: Int, visible: Boolean) {
        val dp = rootLayout.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (visible) {
                setColor(color)
                setStroke((1.5f * dp).toInt(), Color.parseColor("#475569"))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke((2f * dp).toInt(), color)
            }
        }
        v.background = bg
    }

    private fun setActiveField(et: EditText) {
        activeEditText = et
        val dp = rootLayout.resources.displayMetrics.density

        val allFields = seriesList.map { it.editText } + listOf(etTableStart, etTableEnd, etTableStep)

        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        allFields.forEach { field ->
            val isActive = (field == et)
            val bg = GradientDrawable().apply {
                setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#FFFFFF"))
                cornerRadius = 6f * dp
                if (isActive) {
                    val activeColor = activeSeriesItem?.color ?: Color.parseColor("#38BDF8")
                    setStroke((1.8f * dp).toInt(), activeColor)
                } else {
                    setStroke((1f * dp).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
                }
            }
            field.background = bg
            field.setTextColor(if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
            field.setHintTextColor(if (isDark) Color.parseColor("#64748B") else Color.parseColor("#94A3B8"))
        }
    }

    fun refreshTheme() {
        val isPlot = containerGraphPlot.visibility == View.VISIBLE
        selectTab(isPlot)
        activeEditText?.let { setActiveField(it) } ?: run {
            seriesList.firstOrNull()?.let { setActiveField(it.editText) }
        }

        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        val dp = rootLayout.resources.displayMetrics.density
        fabToggleGraphKeypad.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * dp
            setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#FFFFFF"))
            setStroke((1f * dp).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
        }
        fabToggleGraphKeypad.setTextColor(
            if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669")
        )
    }

    private fun setupTabs() {
        tabGraphPlot.bindCrispKey(isHeavy = false) {
            selectTab(isPlotTab = true)
        }

        tabGraphTable.bindCrispKey(isHeavy = false) {
            selectTab(isPlotTab = false)
        }
    }

    private fun selectTab(isPlotTab: Boolean) {
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        val activeBg = ColorStateList.valueOf(Color.parseColor("#0284C7"))
        val inactiveBg = ColorStateList.valueOf(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
        val activeText = Color.parseColor("#FFFFFF")
        val inactiveText = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#475569")

        if (isPlotTab) {
            containerGraphPlot.visibility = View.VISIBLE
            containerGraphTable.visibility = View.GONE
            tabGraphPlot.backgroundTintList = activeBg
            tabGraphPlot.setTextColor(activeText)
            tabGraphTable.backgroundTintList = inactiveBg
            tabGraphTable.setTextColor(inactiveText)
        } else {
            containerGraphPlot.visibility = View.GONE
            containerGraphTable.visibility = View.VISIBLE
            tabGraphTable.backgroundTintList = activeBg
            tabGraphTable.setTextColor(activeText)
            tabGraphPlot.backgroundTintList = inactiveBg
            tabGraphPlot.setTextColor(inactiveText)
            rebuildTable()
        }
    }

    private fun setupZoomControls() {
        btnGraphZoomIn.bindCrispKey(isHeavy = false) {
            graphView.zoomIn()
        }

        btnGraphZoomOut.bindCrispKey(isHeavy = false) {
            graphView.zoomOut()
        }
    }

    private fun setupKeypad() {
        val keyMap = mapOf(
            R.id.btnGraphKeyX to "x",
            R.id.btnGraphKeyY to "y",
            R.id.btnGraphKeyA to "a",
            R.id.btnGraphKeyB to "b",
            R.id.btnGraphKeySin to "sin(",
            R.id.btnGraphKeyCos to "cos(",
            R.id.btnGraphKeyTan to "tan(",
            R.id.btnGraphKeyLn to "ln(",
            R.id.btnGraphKeyPi to "π",
            R.id.btnGraphKeyAbs to "|",
            R.id.btnGraphKeyLt to "<",
            R.id.btnGraphKeyGt to ">",
            R.id.btnGraphKey7 to "7",
            R.id.btnGraphKey8 to "8",
            R.id.btnGraphKey9 to "9",
            R.id.btnGraphKeyOpenParen to "(",
            R.id.btnGraphKeyCloseParen to ")",
            R.id.btnGraphKey4 to "4",
            R.id.btnGraphKey5 to "5",
            R.id.btnGraphKey6 to "6",
            R.id.btnGraphKeyPow to "^",
            R.id.btnGraphKeySqrt to "√(",
            R.id.btnGraphKeyDiv to "/",
            R.id.btnGraphKey1 to "1",
            R.id.btnGraphKey2 to "2",
            R.id.btnGraphKey3 to "3",
            R.id.btnGraphKeyPlus to "+",
            R.id.btnGraphKeyMinus to "-",
            R.id.btnGraphKeyMul to "·",
            R.id.btnGraphKey0 to "0",
            R.id.btnGraphKeyDot to ".",
            R.id.btnGraphKeyEq to "="
        )

        keyMap.forEach { (resId, text) ->
            rootLayout.findViewById<View>(resId)?.bindCrispKey(isHeavy = false) {
                insertText(text)
            }
        }

        rootLayout.findViewById<View>(R.id.btnGraphKeyDel)?.bindCrispKey(isHeavy = true) {
            deleteChar()
        }

        rootLayout.findViewById<View>(R.id.btnGraphKeyAC)?.bindCrispKey(isHeavy = true) {
            clearActiveField()
        }

        rootLayout.findViewById<View>(R.id.btnGraphKeypadPlot)?.bindCrispKey(isHeavy = true) {
            if (containerGraphTable.visibility == View.VISIBLE) {
                rebuildTable()
            } else {
                plotAll(silent = false)
            }
        }
    }

    private fun insertText(str: String) {
        val target = activeEditText ?: seriesList.firstOrNull()?.editText ?: return
        val start = target.selectionStart.coerceAtLeast(0)
        val end = target.selectionEnd.coerceAtLeast(0)
        val current = target.text?.toString() ?: ""
        val low = min(start, end)
        val high = max(start, end)
        val newText = current.substring(0, low) + str + current.substring(high)
        target.setText(newText)
        val newCursor = (low + str.length).coerceIn(0, newText.length)
        target.setSelection(newCursor)
    }

    private fun deleteChar() {
        val target = activeEditText ?: seriesList.firstOrNull()?.editText ?: return
        val start = target.selectionStart.coerceAtLeast(0)
        val end = target.selectionEnd.coerceAtLeast(0)
        val current = target.text?.toString() ?: ""
        if (start != end) {
            val low = min(start, end)
            val high = max(start, end)
            val newText = current.substring(0, low) + current.substring(high)
            target.setText(newText)
            target.setSelection(low.coerceIn(0, newText.length))
        } else if (start > 0) {
            val newText = current.substring(0, start - 1) + current.substring(start)
            target.setText(newText)
            target.setSelection((start - 1).coerceIn(0, newText.length))
        }
    }

    private fun clearActiveField() {
        val target = activeEditText ?: seriesList.firstOrNull()?.editText ?: return
        target.setText("")
        target.setSelection(0)
    }

    fun updateSlidersAndPlot() {
        val foundParams = mutableSetOf<String>()
        for (item in seriesList) {
            val expr = item.editText.text.toString().trim()
            if (expr.isNotEmpty()) {
                foundParams.addAll(DesmosPlotCompiler.extractParameters(expr))
            }
        }

        val sortedParams = foundParams.sorted()

        // Инициализируем значения по умолчанию (1.0) для новых параметров
        for (p in sortedParams) {
            if (paramValues[p] == null) {
                paramValues[p] = 1.0
            }
        }

        // Удаляем параметры, которых больше нет
        val removedKeys = paramValues.keys.filter { it !in foundParams }
        for (k in removedKeys) {
            paramValues.remove(k)
        }

        // Если набор параметров изменился, пересоздаем ползунки
        if (paramSliderViews.keys != foundParams) {
            containerParameterSliders.removeAllViews()
            paramSliderViews.clear()

            val context = rootLayout.context
            val dp = context.resources.displayMetrics.density

            for (param in sortedParams) {
                val curVal = paramValues[param] ?: 1.0

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (34 * dp).toInt()
                    ).apply {
                        setMargins(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
                    }
                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6f * dp
                        setColor(Color.parseColor("#131B2A"))
                        setStroke((1f * dp).toInt(), Color.parseColor("#334155"))
                    }
                    background = bg
                    setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
                }

                val tvLabel = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams((72 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                    text = "$param = ${String.format(Locale.US, "%.1f", curVal)}"
                    setTextColor(Color.parseColor("#38BDF8"))
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                    setTypeface(typeface, Typeface.BOLD)
                }

                val seekBar = SeekBar(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    max = 200 // от -10.0 до +10.0 с шагом 0.1
                    progress = (((curVal - (-10.0)) / 0.1).roundToInt()).coerceIn(0, 200)
                    progressTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
                    thumbTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
                }

                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val v = -10.0 + prog * 0.1
                            val rounded = round(v * 10.0) / 10.0
                            paramValues[param] = rounded
                            tvLabel.text = "$param = ${String.format(Locale.US, "%.1f", rounded)}"
                            plotAll(silent = true)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })

                row.addView(tvLabel)
                row.addView(seekBar)

                containerParameterSliders.addView(row)
                paramSliderViews[param] = row
            }
        }

        containerParameterSliders.visibility = if (sortedParams.isEmpty()) View.GONE else View.VISIBLE

        plotAll(silent = true)
    }

    fun plotAll(silent: Boolean = true) {
        var hasError = false
        val plots = mutableListOf<PlotSeries>()

        for (item in seriesList) {
            val expr = item.editText.text.toString().trim()
            item.formula = expr
            val compiled = DesmosPlotCompiler.compile(expr, paramValues)
            item.compiled = compiled

            if (compiled == null && expr.isNotEmpty()) {
                hasError = true
            }

            plots.add(PlotSeries(item.id, item.formula, item.color, item.visible, compiled))
        }

        graphView.setSeriesList(plots)

        if (containerGraphTable.visibility == View.VISIBLE) {
            rebuildTable()
        }

        if (!silent && hasError && seriesList.isNotEmpty()) {
            Toast.makeText(rootLayout.context, "Ошибка в выражении", Toast.LENGTH_SHORT).show()
        }
    }

    fun rebuildTable() {
        containerTableRows.removeAllViews()

        // Ищем активную функцию или первую с ExplicitY
        val activeExplicit = (activeSeriesItem?.compiled as? CompiledPlot.ExplicitY)
        val activeItem = if (activeExplicit != null) activeSeriesItem else seriesList.firstOrNull { it.compiled is CompiledPlot.ExplicitY }
        val compiledY = activeItem?.compiled as? CompiledPlot.ExplicitY

        if (compiledY == null) {
            val context = rootLayout.context
            val tv = TextView(context).apply {
                text = "Таблица значений доступна для функций вида y = f(x)"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 30, 0, 0)
            }
            containerTableRows.addView(tv)
            tvTableHeaderY.text = "y"
            tvTableHeaderY.setTextColor(Color.parseColor("#94A3B8"))
            return
        }

        val fn = compiledY.fn
        val color = activeItem.color
        tvTableHeaderY.text = "y"
        tvTableHeaderY.setTextColor(color)

        val start = etTableStart.text.toString().trim().toDoubleOrNull() ?: -5.0
        val end = etTableEnd.text.toString().trim().toDoubleOrNull() ?: 5.0
        var step = etTableStep.text.toString().trim().toDoubleOrNull() ?: 1.0
        if (step <= 0.0) step = 1.0

        val maxRows = 100
        val totalDelta = abs(end - start)
        val stepsCount = min(maxRows, floor(totalDelta / step).toInt() + 1)
        val sign = if (start <= end) 1.0 else -1.0
        val actualStep = step * sign

        val context = rootLayout.context
        val dp = context.resources.displayMetrics.density

        for (i in 0 until stepsCount) {
            val x = start + i * actualStep
            val y = try {
                fn(x)
            } catch (_: Exception) {
                Double.NaN
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (34 * dp).toInt()
                )
                val isEven = (i % 2 == 0)
                setBackgroundColor(if (isEven) Color.parseColor("#0F172A") else Color.parseColor("#162032"))
                setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
            }

            val tvX = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = formatTableVal(x)
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 13f
                typeface = Typeface.MONOSPACE
            }

            val divider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams((1 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.parseColor("#334155"))
            }

            val tvY = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
                gravity = Gravity.END
                text = formatTableVal(y)
                setTextColor(if (y.isNaN()) Color.parseColor("#EF4444") else color)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
            }

            row.addView(tvX)
            row.addView(divider)
            row.addView(tvY)
            containerTableRows.addView(row)
        }
    }

    private fun formatTableVal(v: Double): String {
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "+∞" else "-∞"
        if (abs(v) < 1e-12) return "0"
        val r = round(v * 10000.0) / 10000.0
        return if (r == floor(r)) {
            r.toLong().toString()
        } else {
            String.format(Locale.US, "%.4f", r).trimEnd('0').trimEnd('.')
        }
    }

    fun resetAll() {
        containerGraphSeriesList.removeAllViews()
        seriesList.clear()
        nextSeriesId = 1
        nextColorIndex = 0
        activeEditText = null
        activeSeriesItem = null
        paramValues.clear()
        containerParameterSliders.removeAllViews()
        paramSliderViews.clear()

        etTableStart.setText("-5")
        etTableEnd.setText("5")
        etTableStep.setText("1")
        graphView.resetViewport()

        addNewSeries("y = x^2 - 4")
        setKeypadVisible(true)
        updateSlidersAndPlot()
    }
}
