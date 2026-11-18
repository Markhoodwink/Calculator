package com.example.calculator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class UnitConverterManager(
    val rootLayout: View,
    private val onHaptic: (View) -> Unit,
    private val onSendToCalculator: (String) -> Unit
) {
    val engine = UnitConverterEngine()

    val layoutUnitConverter: View = rootLayout.findViewById(R.id.layoutUnitConverter)
    val btnConverterBack: TextView = rootLayout.findViewById(R.id.btnConverterBack)
    val tvConverterTitle: TextView = rootLayout.findViewById(R.id.tvConverterTitle)
    val btnConverterClear: TextView? = rootLayout.findViewById(R.id.btnConverterClear)
    val layoutCategorySelector: MaterialCardView = rootLayout.findViewById(R.id.layoutCategorySelector)
    val tvCategoryBadge: TextView = rootLayout.findViewById(R.id.tvCategoryBadge)
    val tvCategoryLabel: TextView? = rootLayout.findViewById(R.id.tvCategoryLabel)
    val tvSelectedCategory: TextView = rootLayout.findViewById(R.id.tvSelectedCategory)
    val tvSelectedCategoryUnits: TextView = rootLayout.findViewById(R.id.tvSelectedCategoryUnits)
    val tvCategoryDropdownArrow: TextView = rootLayout.findViewById(R.id.tvCategoryDropdownArrow)
    val layoutConverterPresets: LinearLayout = rootLayout.findViewById(R.id.layoutConverterPresets)
    val cardConverterMain: MaterialCardView = rootLayout.findViewById(R.id.cardConverterMain)
    val boxFromValue: View? = rootLayout.findViewById(R.id.boxFromValue)
    val btnFromUnit: MaterialButton = rootLayout.findViewById(R.id.btnFromUnit)
    val tvFromValue: TextView = rootLayout.findViewById(R.id.tvFromValue)
    val dividerCardMain: View? = rootLayout.findViewById(R.id.dividerCardMain)
    val btnSwapUnits: MaterialButton = rootLayout.findViewById(R.id.btnSwapUnits)
    val btnCopyConverted: MaterialButton = rootLayout.findViewById(R.id.btnCopyConverted)
    val btnSendToCalc: MaterialButton = rootLayout.findViewById(R.id.btnSendToCalc)
    val btnToUnit: MaterialButton = rootLayout.findViewById(R.id.btnToUnit)
    val tvToValue: TextView = rootLayout.findViewById(R.id.tvToValue)
    val containerAllConversions: LinearLayout = rootLayout.findViewById(R.id.containerAllConversions)
    val layoutConverterKeypad: LinearLayout = rootLayout.findViewById(R.id.layoutConverterKeypad)

    private var currentCategoryId: String = "length"
    private var fromUnitId: String = "m"
    private var toUnitId: String = "cm"
    private var inputValue: String = "1"

    init {
        setupListeners()
        selectCategory("length")
    }

    private fun setupListeners() {
        btnConverterClear?.setOnClickListener { v ->
            onHaptic(v)
            inputValue = "1"
            recalculate()
        }

        layoutCategorySelector.setOnClickListener { v ->
            onHaptic(v)
            showCategorySelectionBottomSheet()
        }

        btnFromUnit.setOnClickListener { v ->
            onHaptic(v)
            showUnitSelectionDialog(isFrom = true)
        }

        btnToUnit.setOnClickListener { v ->
            onHaptic(v)
            showUnitSelectionDialog(isFrom = false)
        }

        btnSwapUnits.setOnClickListener { v ->
            onHaptic(v)
            swapUnits()
        }

        btnCopyConverted.setOnClickListener { v ->
            onHaptic(v)
            copyToClipboard(tvToValue.text.toString())
            Toast.makeText(rootLayout.context, "Скопировано: ${tvToValue.text}", Toast.LENGTH_SHORT).show()
        }

        btnSendToCalc.setOnClickListener { v ->
            onHaptic(v)
            onSendToCalculator(tvToValue.text.toString())
        }

        // Numpad button bindings
        val numpadMap = mapOf(
            R.id.btnConv7 to "7", R.id.btnConv8 to "8", R.id.btnConv9 to "9", R.id.btnConvDel to "⌫",
            R.id.btnConv4 to "4", R.id.btnConv5 to "5", R.id.btnConv6 to "6", R.id.btnConvClear to "C",
            R.id.btnConv1 to "1", R.id.btnConv2 to "2", R.id.btnConv3 to "3", R.id.btnConvSign to "±",
            R.id.btnConv0 to "0", R.id.btnConvDot to ".", R.id.btnConv00 to "00", R.id.btnConvTripleZero to "000"
        )

        numpadMap.forEach { (id, key) ->
            rootLayout.findViewById<Button>(id)?.setOnClickListener { v ->
                onHaptic(v)
                onNumpadKey(key)
            }
        }
    }

    fun selectCategory(categoryId: String) {
        val cat = engine.getCategory(categoryId) ?: return
        currentCategoryId = categoryId

        // Default units for category
        if (cat.units.size >= 2) {
            fromUnitId = cat.units.find { it.id == "m" || it.id == "kg" || it.id == "liter" || it.id == "kmh" || it.id == "celsius" || it.id == "pa" || it.id == "j" || it.id == "min" || it.id == "mib" }?.id ?: cat.units[0].id
            toUnitId = cat.units.find { it.id != fromUnitId }?.id ?: cat.units[1].id
        } else if (cat.units.isNotEmpty()) {
            fromUnitId = cat.units[0].id
            toUnitId = cat.units[0].id
        }

        tvSelectedCategory.text = cat.name
        tvSelectedCategoryUnits.text = getCategoryUnitPreview(cat.id)
        tvCategoryBadge.text = getCategoryBadge(cat.id)

        buildPresetChips()
        recalculate()
    }

    private fun showCategorySelectionBottomSheet() {
        val context = rootLayout.context
        val dp = context.resources.displayMetrics.density
        val isDark = ThemeManager.isDarkTheme(context)
        val dialog = BottomSheetDialog(context)

        val rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    16f * dp, 16f * dp,
                    16f * dp, 16f * dp,
                    0f, 0f, 0f, 0f
                )
                setColor(if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL)
            }
        }

        val dragHandle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, (12 * dp).toInt())
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2f * dp
                setColor(if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)
            }
        }
        rootContainer.addView(dragHandle)

        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (12 * dp).toInt())
        }

        val tvTitle = TextView(context).apply {
            text = "Выберите величину"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY)
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * dp
                setColor(if (isDark) ThemeManager.COLOR_DARK_INPUT else ThemeManager.COLOR_LIGHT_INPUT)
            }
            setOnClickListener { dialog.dismiss() }
        }

        headerLayout.addView(tvTitle)
        headerLayout.addView(btnClose)
        rootContainer.addView(headerLayout)

        val scrollView = NestedScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        engine.categories.forEach { cat ->
            val isSelected = cat.id == currentCategoryId
            val badge = getCategoryBadge(cat.id)
            val preview = getCategoryUnitPreview(cat.id)

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (6 * dp).toInt())
                }

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * dp
                    if (isSelected) {
                        setColor(if (isDark) Color.parseColor("#0C4A6E") else Color.parseColor("#E0F2FE"))
                        setStroke((1.5f * dp).toInt(), if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
                    } else {
                        setColor(if (isDark) ThemeManager.COLOR_DARK_INPUT else Color.parseColor("#F8FAFC"))
                        setStroke((1f * dp).toInt(), if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)
                    }
                }

                setOnClickListener { v ->
                    onHaptic(v)
                    selectCategory(cat.id)
                    dialog.dismiss()
                }
            }

            val tvBadge = TextView(context).apply {
                text = badge
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt()).apply {
                    setMargins(0, 0, (10 * dp).toInt(), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * dp
                    if (isSelected) {
                        setColor(if (isDark) Color.parseColor("#0284C7") else Color.parseColor("#BAE6FD"))
                    } else {
                        setColor(if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL)
                    }
                }
                setTextColor(if (isSelected) (if (isDark) Color.WHITE else Color.parseColor("#0284C7")) else (if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")))
            }

            val textContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvName = TextView(context).apply {
                text = cat.name
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    if (isSelected) (if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
                    else (if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
                )
            }

            val tvUnits = TextView(context).apply {
                text = preview
                textSize = 12f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(
                    if (isSelected) (if (isDark) Color.parseColor("#7DD3FC") else Color.parseColor("#0369A1"))
                    else (if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY)
                )
            }

            textContainer.addView(tvName)
            textContainer.addView(tvUnits)

            row.addView(tvBadge)
            row.addView(textContainer)

            if (isSelected) {
                val tvCheck = TextView(context).apply {
                    text = "✓"
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
                    setPadding((6 * dp).toInt(), 0, 0, 0)
                }
                row.addView(tvCheck)
            }

            listContainer.addView(row)
        }

        scrollView.addView(listContainer)
        rootContainer.addView(scrollView)

        dialog.setContentView(rootContainer)

        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.background = null
        }

        dialog.show()
    }

    private fun getCategoryBadge(categoryId: String): String {
        return when (categoryId) {
            "length" -> "📏"
            "area" -> "⬜"
            "volume" -> "🧊"
            "mass" -> "⚖"
            "speed" -> "⚡"
            "temperature" -> "🌡"
            "pressure" -> "⏲"
            "energy" -> "🔋"
            "time" -> "⏱"
            "data" -> "💾"
            else -> "📏"
        }
    }

    private fun getCategoryUnitPreview(categoryId: String): String {
        return when (categoryId) {
            "length" -> "мм, см, дм, м, км, дюйм, фут, миля"
            "area" -> "мм², см², дм², м², сотка, га, км², фут²"
            "volume" -> "мл, литр, м³, стакан, галлон, баррель"
            "mass" -> "мг, г, кг, центнер, тонна, унция, фунт"
            "speed" -> "м/с, км/ч, миль/ч (mph), узел, Мах"
            "temperature" -> "Цельсий (°C), Кельвин (K), Фаренгейт (°F)"
            "pressure" -> "Па, кПа, МПа, бар, атм, мм рт. ст., psi"
            "energy" -> "Дж, кДж, МДж, кал, ккал, Вт·ч, кВт·ч, эВ"
            "time" -> "мс, сек, мин, час, сутки, неделя, год"
            "data" -> "бит, Байт, КБ, МБ, ГБ, ТБ, КиБ, МиБ, ГиБ"
            else -> ""
        }
    }

    private fun buildPresetChips() {
        layoutConverterPresets.removeAllViews()
        val cat = engine.getCategory(currentCategoryId) ?: return
        val dp = rootLayout.resources.displayMetrics.density
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)

        cat.presets.forEach { preset ->
            val tv = TextView(rootLayout.context).apply {
                text = preset.label
                textSize = 11f
                isSingleLine = true
                gravity = Gravity.CENTER
                setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (28 * dp).toInt()
                ).apply {
                    setMargins(0, 0, (6 * dp).toInt(), 0)
                }
                setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * dp
                    setColor(if (isDark) Color.parseColor("#0C4A6E") else Color.parseColor("#E0F2FE"))
                    setStroke((1 * dp).toInt(), if (isDark) Color.parseColor("#0284C7") else Color.parseColor("#38BDF8"))
                }

                setOnClickListener { v ->
                    onHaptic(v)
                    fromUnitId = preset.fromUnitId
                    toUnitId = preset.toUnitId
                    recalculate()
                }
            }
            layoutConverterPresets.addView(tv)
        }
    }

    private fun showUnitSelectionDialog(isFrom: Boolean) {
        val cat = engine.getCategory(currentCategoryId) ?: return
        val unitNames = cat.units.map { it.name }.toTypedArray()
        val currentId = if (isFrom) fromUnitId else toUnitId
        val currentIndex = cat.units.indexOfFirst { it.id == currentId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(rootLayout.context)
            .setTitle(if (isFrom) "Выберите исходную единицу" else "Выберите целевую единицу")
            .setSingleChoiceItems(unitNames, currentIndex) { dialog, which ->
                if (isFrom) {
                    fromUnitId = cat.units[which].id
                } else {
                    toUnitId = cat.units[which].id
                }
                dialog.dismiss()
                recalculate()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    fun swapUnits() {
        val tmp = fromUnitId
        fromUnitId = toUnitId
        toUnitId = tmp
        recalculate()
    }

    fun onNumpadKey(key: String) {
        when (key) {
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" -> {
                if (inputValue == "0") {
                    inputValue = key
                } else if (inputValue == "-0") {
                    inputValue = "-$key"
                } else if (inputValue.length < 15) {
                    inputValue += key
                }
            }
            "00" -> {
                if (inputValue != "0" && inputValue != "-0" && inputValue.length <= 13) {
                    inputValue += "00"
                }
            }
            "000" -> {
                if (inputValue != "0" && inputValue != "-0" && inputValue.length <= 12) {
                    inputValue += "000"
                }
            }
            "." -> {
                if (!inputValue.contains(".")) {
                    inputValue += "."
                }
            }
            "±" -> {
                inputValue = if (inputValue.startsWith("-")) {
                    inputValue.removePrefix("-")
                } else {
                    "-$inputValue"
                }
            }
            "⌫" -> {
                inputValue = inputValue.dropLast(1)
                if (inputValue.isEmpty() || inputValue == "-") {
                    inputValue = "0"
                }
            }
            "C" -> {
                inputValue = "0"
            }
        }
        recalculate()
    }

    fun recalculate() {
        val cat = engine.getCategory(currentCategoryId) ?: return
        val fromUnit = cat.units.find { it.id == fromUnitId } ?: cat.units.firstOrNull()
        val toUnit = cat.units.find { it.id == toUnitId } ?: cat.units.getOrNull(1) ?: fromUnit

        if (fromUnit != null) fromUnitId = fromUnit.id
        if (toUnit != null) toUnitId = toUnit.id

        btnFromUnit.text = "${fromUnit?.name ?: ""} ▾"
        btnToUnit.text = "${toUnit?.name ?: ""} ▾"
        tvFromValue.text = inputValue

        val valDouble = inputValue.toDoubleOrNull() ?: 0.0
        val result = engine.convert(valDouble, fromUnitId, toUnitId, currentCategoryId)
        tvToValue.text = engine.formatValue(result)

        rebuildAllConversionsList(valDouble)
    }

    private fun rebuildAllConversionsList(value: Double) {
        containerAllConversions.removeAllViews()
        val cat = engine.getCategory(currentCategoryId) ?: return
        val dp = rootLayout.resources.displayMetrics.density
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)

        val all = engine.convertAll(value, fromUnitId, currentCategoryId)

        val headerTv = TextView(rootLayout.context).apply {
            text = "Все единицы категории (${cat.name}):"
            textSize = 12f
            setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt())
            setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY)
        }
        containerAllConversions.addView(headerTv)

        all.forEach { item ->
            val isTarget = item.unit.id == toUnitId
            val isSource = item.unit.id == fromUnitId

            val row = LinearLayout(rootLayout.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (6 * dp).toInt())
                }

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f * dp
                    if (isTarget) {
                        setColor(if (isDark) Color.parseColor("#064E3B") else Color.parseColor("#ECFDF5"))
                        setStroke((1.5f * dp).toInt(), Color.parseColor("#10B981"))
                    } else {
                        setColor(if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL)
                        setStroke((1 * dp).toInt(), if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)
                    }
                }

                setOnClickListener { v ->
                    onHaptic(v)
                    toUnitId = item.unit.id
                    copyToClipboard(item.formattedValue)
                    Toast.makeText(rootLayout.context, "Скопировано: ${item.formattedValue} ${item.unit.name}", Toast.LENGTH_SHORT).show()
                    recalculate()
                }
            }

            val displayName = if (isSource) "${item.unit.name} (исх.)" else item.unit.name

            val tvName = TextView(rootLayout.context).apply {
                text = displayName
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(
                    if (isTarget) (if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669"))
                    else if (isSource) (if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
                    else if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY
                    else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY
                )
                if (isTarget || isSource) {
                    setTypeface(typeface, Typeface.BOLD)
                }
            }

            val tvVal = TextView(rootLayout.context).apply {
                text = item.formattedValue
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    if (isTarget) (if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669"))
                    else if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY
                    else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY
                )
            }

            row.addView(tvName)
            row.addView(tvVal)
            containerAllConversions.addView(row)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = rootLayout.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Converted Value", text)
        clipboard.setPrimaryClip(clip)
    }

    fun refreshTheme() {
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        val dp = rootLayout.resources.displayMetrics.density

        layoutUnitConverter.setBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_SCREEN else ThemeManager.COLOR_LIGHT_SCREEN)
        tvConverterTitle.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)

        btnConverterBack.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f * dp
            setColor(if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL)
            setStroke((1f * dp).toInt(), if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)
        }
        btnConverterBack.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))

        layoutCategorySelector.setCardBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL)
        layoutCategorySelector.strokeColor = if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER

        tvCategoryBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * dp
            setColor(if (isDark) ThemeManager.COLOR_DARK_INPUT else ThemeManager.COLOR_LIGHT_INPUT)
        }
        tvCategoryBadge.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))

        tvCategoryLabel?.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY)
        tvSelectedCategory.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
        tvSelectedCategoryUnits.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY)

        tvCategoryDropdownArrow.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * dp
            setColor(if (isDark) Color.parseColor("#0C4A6E") else Color.parseColor("#E0F2FE"))
        }
        tvCategoryDropdownArrow.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))

        cardConverterMain.setCardBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL)
        cardConverterMain.strokeColor = if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER

        boxFromValue?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * dp
            setColor(if (isDark) ThemeManager.COLOR_DARK_INPUT else Color.parseColor("#F8FAFC"))
            setStroke((1f * dp).toInt(), if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
        }

        tvFromValue.setTextColor(if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)

        val pillBgColor = if (isDark) ThemeManager.COLOR_DARK_INPUT else Color.parseColor("#F1F5F9")
        val pillStrokeColor = if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER
        val pillTextColor = if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY

        btnFromUnit.backgroundTintList = ColorStateList.valueOf(pillBgColor)
        btnFromUnit.strokeColor = ColorStateList.valueOf(pillStrokeColor)
        btnFromUnit.setTextColor(pillTextColor)

        btnToUnit.backgroundTintList = ColorStateList.valueOf(pillBgColor)
        btnToUnit.strokeColor = ColorStateList.valueOf(pillStrokeColor)
        btnToUnit.setTextColor(pillTextColor)

        dividerCardMain?.setBackgroundColor(if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER)

        btnSwapUnits.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0284C7"))
        btnSwapUnits.setTextColor(Color.WHITE)

        val actionBgColor = if (isDark) Color.parseColor("#0C4A6E") else Color.parseColor("#E0F2FE")
        val actionStrokeColor = if (isDark) Color.parseColor("#0284C7") else Color.parseColor("#38BDF8")
        val actionTextColor = if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")

        btnCopyConverted.backgroundTintList = ColorStateList.valueOf(actionBgColor)
        btnCopyConverted.strokeColor = ColorStateList.valueOf(actionStrokeColor)
        btnCopyConverted.setTextColor(actionTextColor)

        btnSendToCalc.backgroundTintList = ColorStateList.valueOf(actionBgColor)
        btnSendToCalc.strokeColor = ColorStateList.valueOf(actionStrokeColor)
        btnSendToCalc.setTextColor(actionTextColor)

        tvToValue.setTextColor(if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669"))

        layoutConverterKeypad.setBackgroundColor(Color.TRANSPARENT)

        val digitBg = if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#FFFFFF")
        val digitStroke = if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1")
        val digitText = if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")

        val primaryDigits = listOf(
            R.id.btnConv0, R.id.btnConv1, R.id.btnConv2, R.id.btnConv3, R.id.btnConv4,
            R.id.btnConv5, R.id.btnConv6, R.id.btnConv7, R.id.btnConv8, R.id.btnConv9,
            R.id.btnConvDot
        )
        primaryDigits.forEach { id ->
            (rootLayout.findViewById<View>(id) as? MaterialButton)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(digitBg)
                btn.strokeColor = ColorStateList.valueOf(digitStroke)
                btn.strokeWidth = (1 * dp).toInt()
                btn.setTextColor(digitText)
                btn.textSize = 19f
            }
        }

        val secBg = if (isDark) Color.parseColor("#162032") else Color.parseColor("#E2E8F0")
        val secStroke = if (isDark) Color.parseColor("#293548") else Color.parseColor("#CBD5E1")

        listOf(R.id.btnConv00, R.id.btnConvTripleZero).forEach { id ->
            (rootLayout.findViewById<View>(id) as? MaterialButton)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(secBg)
                btn.strokeColor = ColorStateList.valueOf(secStroke)
                btn.strokeWidth = (1 * dp).toInt()
                btn.setTextColor(if (isDark) Color.parseColor("#CBD5E1") else Color.parseColor("#334155"))
                btn.textSize = 15f
                btn.setTypeface(btn.typeface, Typeface.BOLD)
            }
        }

        (rootLayout.findViewById<View>(R.id.btnConvSign) as? MaterialButton)?.let { btn ->
            btn.backgroundTintList = ColorStateList.valueOf(secBg)
            btn.strokeColor = ColorStateList.valueOf(secStroke)
            btn.strokeWidth = (1 * dp).toInt()
            btn.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            btn.textSize = 18f
            btn.setTypeface(btn.typeface, Typeface.BOLD)
        }

        val delBg = if (isDark) Color.parseColor("#451A1A") else Color.parseColor("#FEE2E2")
        val delStroke = if (isDark) Color.parseColor("#7F1D1D") else Color.parseColor("#FECACA")
        val delText = if (isDark) Color.parseColor("#FCA5A5") else Color.parseColor("#DC2626")

        listOf(R.id.btnConvDel, R.id.btnConvClear).forEach { id ->
            (rootLayout.findViewById<View>(id) as? MaterialButton)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(delBg)
                btn.strokeColor = ColorStateList.valueOf(delStroke)
                btn.strokeWidth = (1 * dp).toInt()
                btn.setTextColor(delText)
            }
        }

        val cat = engine.getCategory(currentCategoryId)
        if (cat != null) {
            tvSelectedCategory.text = cat.name
            tvSelectedCategoryUnits.text = getCategoryUnitPreview(cat.id)
            tvCategoryBadge.text = getCategoryBadge(cat.id)
        }

        buildPresetChips()
        recalculate()
    }
}
