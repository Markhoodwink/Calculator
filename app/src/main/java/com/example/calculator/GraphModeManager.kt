package com.example.calculator

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.*

data class ParamConfig(
    var value: Double = 1.0,
    var min: Double = -10.0,
    var max: Double = 10.0,
    var step: Double = 0.1 // 0.0 means continuous / smooth sliding
)

object GraphParamHelper {

    fun parseParamNumber(raw: String): Double? {
        var s = raw.trim()
            .replace(',', '.')
            .replace('−', '-')
            .replace("pi", "π", ignoreCase = true)
            .replace("п", "π", ignoreCase = true)
            .replace("П", "π")
            .replace("\\s+".toRegex(), "")
        if (s.isEmpty()) return null

        return try {
            if (s.contains('/')) {
                val parts = s.split('/')
                if (parts.size != 2) return null
                val num = parsePiOrDouble(parts[0]) ?: return null
                val den = parsePiOrDouble(parts[1]) ?: return null
                if (den == 0.0) return null
                val res = num / den
                if (res.isNaN() || res.isInfinite()) null else res
            } else {
                val res = parsePiOrDouble(s) ?: return null
                if (res.isNaN() || res.isInfinite()) null else res
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePiOrDouble(part: String): Double? {
        if (part.isEmpty()) return null
        if (part.contains('π')) {
            var p = part.replace("*", "")
            if (p == "π") return Math.PI
            if (p == "-π") return -Math.PI
            if (p == "+π") return Math.PI
            p = p.replace("π", "")
            val coeff = p.toDoubleOrNull() ?: return null
            return coeff * Math.PI
        }
        return part.toDoubleOrNull()
    }

    fun cleanDust(res: Double): Double {
        if (abs(res) < 1e-11) return 0.0
        return try {
            BigDecimal(res, MathContext(10, RoundingMode.HALF_UP))
                .stripTrailingZeros()
                .toDouble()
        } catch (_: Exception) {
            res
        }
    }

    fun snapParamValue(raw: Double, min: Double, max: Double, step: Double): Double {
        val clamped = raw.coerceIn(min, max)
        val res = if (step > 0.0) {
            val grid = round(clamped / step) * step
            if (grid < min || grid > max) {
                (min + round((clamped - min) / step) * step).coerceIn(min, max)
            } else {
                grid.coerceIn(min, max)
            }
        } else {
            clamped
        }
        return cleanDust(res)
    }

    fun stepParamValue(current: Double, deltaSteps: Int, min: Double, max: Double, step: Double): Double {
        val effectiveStep = if (step > 0.0) step else (max - min) / 100.0
        return snapParamValue(current + deltaSteps * effectiveStep, min, max, step)
    }

    fun formatParamNumber(value: Double): String {
        val v = if (abs(value) < 1e-11) 0.0 else value
        if (v == 0.0) return "0"
        val cleaned = cleanDust(v)
        val formatted = String.format(Locale.US, "%.6f", cleaned)
            .trimEnd('0')
            .trimEnd('.')
        return if (formatted == "-0" || formatted == "-0.0") "0" else formatted
    }

    fun computeSeekBarMax(min: Double, max: Double, step: Double): Int {
        return if (step > 0.0) {
            val steps = round((max - min) / step).toInt()
            if (steps in 1..2000) steps else 1000
        } else {
            1000
        }
    }

    fun progressToParamValue(progress: Int, seekBarMax: Int, min: Double, max: Double, step: Double): Double {
        if (seekBarMax <= 0) return min
        val frac = progress.toDouble() / seekBarMax.toDouble()
        val raw = min + frac * (max - min)
        return snapParamValue(raw, min, max, step)
    }

    fun paramValueToProgress(value: Double, seekBarMax: Int, min: Double, max: Double): Int {
        if (max <= min) return 0
        val clamped = value.coerceIn(min, max)
        val frac = (clamped - min) / (max - min)
        return round(frac * seekBarMax).toInt().coerceIn(0, seekBarMax)
    }
}

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

    // Конфигурации и значения параметров (a, b, k...)
    val paramConfigs: MutableMap<String, ParamConfig> = mutableMapOf()
    val paramValues: MutableMap<String, Double> = mutableMapOf()
    private val paramSliderViews: MutableMap<String, View> = mutableMapOf()

    // Анимация параметров
    private var activeAnimParam: String? = null
    private var animDirection: Int = 1
    private val animHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var animRunnable: Runnable? = null

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

        // Обновляем параметры в соответствии с текущей темой
        if (paramConfigs.isNotEmpty()) {
            containerParameterSliders.removeAllViews()
            paramSliderViews.clear()
            val sortedParams = paramConfigs.keys.sorted()
            for (param in sortedParams) {
                val cfg = paramConfigs[param]!!
                val row = createParamRowView(param, cfg)
                containerParameterSliders.addView(row)
                paramSliderViews[param] = row
            }
        }
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

        // Инициализируем конфиги по умолчанию для новых параметров
        for (p in sortedParams) {
            if (paramConfigs[p] == null) {
                paramConfigs[p] = ParamConfig(value = 1.0, min = -10.0, max = 10.0, step = 0.1)
            }
            paramValues[p] = paramConfigs[p]!!.value
        }

        // Удаляем параметры, которых больше нет
        val removedKeys = paramConfigs.keys.filter { it !in foundParams }
        for (k in removedKeys) {
            paramConfigs.remove(k)
            paramValues.remove(k)
            stopAnimation(k)
        }

        // Если набор параметров изменился, пересоздаем ползунки
        if (paramSliderViews.keys != foundParams) {
            containerParameterSliders.removeAllViews()
            paramSliderViews.clear()

            for (param in sortedParams) {
                val cfg = paramConfigs[param] ?: ParamConfig().also { paramConfigs[param] = it }
                val row = createParamRowView(param, cfg)
                containerParameterSliders.addView(row)
                paramSliderViews[param] = row
            }
        }

        containerParameterSliders.visibility = if (sortedParams.isEmpty()) View.GONE else View.VISIBLE

        plotAll(silent = true)
    }

    private fun startAnimation(param: String) {
        if (activeAnimParam != null && activeAnimParam != param) {
            stopAnimation(activeAnimParam!!)
        }
        activeAnimParam = param
        animDirection = 1
        updatePlayButtonState(param)
        scheduleAnimFrame()
    }

    private fun stopAnimation(param: String) {
        if (activeAnimParam == param) {
            activeAnimParam = null
            animRunnable?.let { animHandler.removeCallbacks(it) }
            animRunnable = null
            updatePlayButtonState(param)
        }
    }

    private fun stopAllAnimations() {
        activeAnimParam?.let { stopAnimation(it) }
    }

    private fun updatePlayButtonState(param: String) {
        val rowView = paramSliderViews[param] ?: return
        val btnPlay = rowView.findViewWithTag<TextView>("btnPlay") ?: return
        btnPlay.text = if (activeAnimParam == param) "⏸" else "▶"
    }

    private fun scheduleAnimFrame() {
        animRunnable?.let { animHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                val param = activeAnimParam ?: return
                val cfg = paramConfigs[param] ?: return

                val span = cfg.max - cfg.min
                if (span <= 0) return

                val isDiscreteStep = cfg.step > 0.0 && (span / cfg.step) <= 400.0
                val delta = if (isDiscreteStep) {
                    cfg.step * animDirection
                } else {
                    (span / 200.0) * animDirection
                }

                var nextVal = cfg.value + delta
                if (nextVal >= cfg.max) {
                    nextVal = cfg.max
                    animDirection = -1
                } else if (nextVal <= cfg.min) {
                    nextVal = cfg.min
                    animDirection = 1
                }

                cfg.value = GraphParamHelper.snapParamValue(nextVal, cfg.min, cfg.max, cfg.step)
                paramValues[param] = cfg.value

                updateParamRowUI(param)
                plotAll(silent = true)

                val delayMs = if (isDiscreteStep) 60L else 30L
                if (activeAnimParam == param) {
                    animHandler.postDelayed(this, delayMs)
                }
            }
        }
        animRunnable = runnable
        animHandler.postDelayed(runnable, 30L)
    }

    private fun updateParamRowUI(param: String) {
        val cfg = paramConfigs[param] ?: return
        val rowView = paramSliderViews[param] ?: return

        val tvLabel = rowView.findViewWithTag<TextView>("tvLabel")
        val tvRange = rowView.findViewWithTag<TextView>("tvRange")
        val chipStep = rowView.findViewWithTag<TextView>("chipStep")
        val seekBar = rowView.findViewWithTag<SeekBar>("seekBar")

        tvLabel?.text = "$param = ${GraphParamHelper.formatParamNumber(cfg.value)}"
        tvRange?.text = "[${GraphParamHelper.formatParamNumber(cfg.min)}, ${GraphParamHelper.formatParamNumber(cfg.max)}]"
        chipStep?.text = if (cfg.step == 0.0) "плавно" else "шаг ${GraphParamHelper.formatParamNumber(cfg.step)}"

        if (seekBar != null) {
            val newMax = GraphParamHelper.computeSeekBarMax(cfg.min, cfg.max, cfg.step)
            seekBar.max = newMax
            seekBar.progress = GraphParamHelper.paramValueToProgress(cfg.value, newMax, cfg.min, cfg.max)
        }
    }

    private fun createParamRowView(param: String, cfg: ParamConfig): View {
        val context = rootLayout.context
        val dp = context.resources.displayMetrics.density
        val isDark = ThemeManager.isDarkTheme(context)

        val cardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            }
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setColor(if (isDark) Color.parseColor("#131B2A") else Color.parseColor("#F8FAFC"))
                setStroke((1f * dp).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
            }
            background = bg
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        }

        // Row 1: Header (Play, Label, Spacer, Range, Step Chip)
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnPlay = TextView(context).apply {
            tag = "btnPlay"
            layoutParams = LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt())
            gravity = Gravity.CENTER
            text = if (activeAnimParam == param) "⏸" else "▶"
            setTextColor(if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669"))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * dp
                setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
            }
            setOnClickListener {
                onHaptic(it)
                if (activeAnimParam == param) {
                    stopAnimation(param)
                } else {
                    startAnimation(param)
                }
            }
        }

        val tvLabel = TextView(context).apply {
            tag = "tvLabel"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((6 * dp).toInt(), 0, 0, 0)
            }
            text = "$param = ${GraphParamHelper.formatParamNumber(cfg.value)}"
            setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val tvRange = TextView(context).apply {
            tag = "tvRange"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, (6 * dp).toInt(), 0)
            }
            text = "[${GraphParamHelper.formatParamNumber(cfg.min)}, ${GraphParamHelper.formatParamNumber(cfg.max)}]"
            setTextColor(if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B"))
            textSize = 11f
            setOnClickListener {
                onHaptic(it)
                stopAnimation(param)
                showParamSettingsDialog(param)
            }
        }

        val chipStep = TextView(context).apply {
            tag = "chipStep"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = if (cfg.step == 0.0) "плавно" else "шаг ${GraphParamHelper.formatParamNumber(cfg.step)}"
            setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * dp
                setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
                setStroke((1f * dp).toInt(), if (isDark) Color.parseColor("#0284C7") else Color.parseColor("#0284C7"))
            }
            setOnClickListener {
                onHaptic(it)
                stopAnimation(param)
                showParamSettingsDialog(param)
            }
        }

        headerRow.addView(btnPlay)
        headerRow.addView(tvLabel)
        headerRow.addView(spacer)
        headerRow.addView(tvRange)
        headerRow.addView(chipStep)

        // Row 2: Slider row (Minus, SeekBar, Plus)
        val sliderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (4 * dp).toInt(), 0, 0)
            }
        }

        val btnMinus = TextView(context).apply {
            tag = "btnMinus"
            layoutParams = LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt())
            gravity = Gravity.CENTER
            text = "−"
            setTextColor(if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * dp
                setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
                setStroke((1f * dp).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
            }
            setOnClickListener {
                onHaptic(it)
                stopAnimation(param)
                val newVal = GraphParamHelper.stepParamValue(cfg.value, -1, cfg.min, cfg.max, cfg.step)
                cfg.value = newVal
                paramValues[param] = newVal
                updateParamRowUI(param)
                plotAll(silent = true)
            }
        }

        val seekBarMax = GraphParamHelper.computeSeekBarMax(cfg.min, cfg.max, cfg.step)
        val curProg = GraphParamHelper.paramValueToProgress(cfg.value, seekBarMax, cfg.min, cfg.max)

        val seekBar = SeekBar(context).apply {
            tag = "seekBar"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            }
            max = seekBarMax
            progress = curProg
            progressTintList = ColorStateList.valueOf(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            thumbTintList = ColorStateList.valueOf(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                if (fromUser) {
                    stopAnimation(param)
                    val sbMax = sb?.max ?: 1000
                    val newVal = GraphParamHelper.progressToParamValue(prog, sbMax, cfg.min, cfg.max, cfg.step)
                    cfg.value = newVal
                    paramValues[param] = newVal
                    tvLabel.text = "$param = ${GraphParamHelper.formatParamNumber(newVal)}"
                    plotAll(silent = true)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                stopAnimation(param)
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val btnPlus = TextView(context).apply {
            tag = "btnPlus"
            layoutParams = LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt())
            gravity = Gravity.CENTER
            text = "+"
            setTextColor(if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * dp
                setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
                setStroke((1f * dp).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
            }
            setOnClickListener {
                onHaptic(it)
                stopAnimation(param)
                val newVal = GraphParamHelper.stepParamValue(cfg.value, 1, cfg.min, cfg.max, cfg.step)
                cfg.value = newVal
                paramValues[param] = newVal
                updateParamRowUI(param)
                plotAll(silent = true)
            }
        }

        sliderRow.addView(btnMinus)
        sliderRow.addView(seekBar)
        sliderRow.addView(btnPlus)

        cardView.addView(headerRow)
        cardView.addView(sliderRow)

        return cardView
    }

    fun showParamSettingsDialog(paramName: String) {
        val cfg = paramConfigs[paramName] ?: return
        val context = rootLayout.context
        val isDark = ThemeManager.isDarkTheme(context)
        val dp = context.resources.displayMetrics.density

        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * dp
                setColor(if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL)
                setStroke((1.5f * dp).toInt(), if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)
            }
        }

        val tvTitle = TextView(context).apply {
            text = "Настройки параметра $paramName"
            setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (14 * dp).toInt())
        }
        cardLayout.addView(tvTitle)

        val tvMinLabel = TextView(context).apply {
            text = "От (мин)"
            setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (4 * dp).toInt(), 0, (2 * dp).toInt())
        }
        val etMin = EditText(context).apply {
            setText(GraphParamHelper.formatParamNumber(cfg.min))
            setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
            textSize = 14f
            setSingleLine(true)
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setColor(if (isDark) ThemeManager.COLOR_DARK_INPUT else ThemeManager.COLOR_LIGHT_INPUT)
                setStroke((1f * dp).toInt(), if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)
            }
        }
        cardLayout.addView(tvMinLabel)
        cardLayout.addView(etMin)

        val tvMaxLabel = TextView(context).apply {
            text = "До (макс)"
            setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (8 * dp).toInt(), 0, (2 * dp).toInt())
        }
        val etMax = EditText(context).apply {
            setText(GraphParamHelper.formatParamNumber(cfg.max))
            setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
            textSize = 14f
            setSingleLine(true)
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setColor(if (isDark) ThemeManager.COLOR_DARK_INPUT else ThemeManager.COLOR_LIGHT_INPUT)
                setStroke((1f * dp).toInt(), if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)
            }
        }
        cardLayout.addView(tvMaxLabel)
        cardLayout.addView(etMax)

        val tvStepLabel = TextView(context).apply {
            text = "Шаг (0 — плавно)"
            setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (8 * dp).toInt(), 0, (2 * dp).toInt())
        }
        val etStep = EditText(context).apply {
            setText(GraphParamHelper.formatParamNumber(cfg.step))
            setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
            textSize = 14f
            setSingleLine(true)
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setColor(if (isDark) ThemeManager.COLOR_DARK_INPUT else ThemeManager.COLOR_LIGHT_INPUT)
                setStroke((1f * dp).toInt(), if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)
            }
        }
        cardLayout.addView(tvStepLabel)
        cardLayout.addView(etStep)

        val presetScroll = HorizontalScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        }
        val presetContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val presets = listOf("0.01", "0.05", "0.1", "0.25", "0.5", "1", "π/4", "Плавно")
        presets.forEach { preset ->
            val chip = TextView(context).apply {
                text = preset
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * dp
                    setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
                    setStroke((1f * dp).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, (6 * dp).toInt(), 0)
                }

                setOnClickListener {
                    onHaptic(it)
                    val strVal = if (preset == "Плавно") "0" else preset
                    etStep.setText(strVal)
                    etStep.setSelection(etStep.text.length)
                }
            }
            presetContainer.addView(chip)
        }
        presetScroll.addView(presetContainer)
        cardLayout.addView(presetScroll)

        val tvError = TextView(context).apply {
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            visibility = View.GONE
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        }
        cardLayout.addView(tvError)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        val btnCancel = Button(context).apply {
            text = "Отмена"
            isAllCaps = false
            setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY)
            background = null
        }
        val btnApply = Button(context).apply {
            text = "Применить"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setColor(if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669"))
            }
        }
        btnRow.addView(btnCancel)
        btnRow.addView(btnApply)
        cardLayout.addView(btnRow)

        val dialog = android.app.AlertDialog.Builder(context)
            .setView(cardLayout)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCancel.setOnClickListener {
            onHaptic(it)
            dialog.dismiss()
        }

        btnApply.setOnClickListener {
            onHaptic(it)
            val pMin = GraphParamHelper.parseParamNumber(etMin.text.toString())
            if (pMin == null) {
                tvError.text = "Неверный формат мин. значения"
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val pMax = GraphParamHelper.parseParamNumber(etMax.text.toString())
            if (pMax == null) {
                tvError.text = "Неверный формат макс. значения"
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val pStep = GraphParamHelper.parseParamNumber(etStep.text.toString())
            if (pStep == null || pStep < 0.0) {
                tvError.text = "Шаг должен быть неотрицательным числом"
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            if (pMin >= pMax) {
                tvError.text = "Мин. значение должно быть меньше макс."
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            tvError.visibility = View.GONE
            cfg.min = pMin
            cfg.max = pMax
            cfg.step = pStep
            cfg.value = GraphParamHelper.snapParamValue(cfg.value, cfg.min, cfg.max, cfg.step)
            paramValues[paramName] = cfg.value

            updateParamRowUI(paramName)
            plotAll(silent = true)
            dialog.dismiss()
        }

        dialog.show()
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
        stopAllAnimations()
        containerGraphSeriesList.removeAllViews()
        seriesList.clear()
        nextSeriesId = 1
        nextColorIndex = 0
        activeEditText = null
        activeSeriesItem = null
        paramConfigs.clear()
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
