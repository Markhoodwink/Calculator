package com.example.calculator

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class Section3DManager(
    private val rootLayout: View,
    private val onHaptic: (View) -> Unit = {}
) {

    val layoutSection3D: View = rootLayout.findViewById(R.id.layoutSection3D)
    val btnSectionBack: TextView = rootLayout.findViewById(R.id.btnSectionBack)
    val tvSectionTitle: TextView = rootLayout.findViewById(R.id.tvSectionTitle)
    val btnSectionResetView: TextView = rootLayout.findViewById(R.id.btnSectionResetView)
    val btnSectionClear: TextView = rootLayout.findViewById(R.id.btnSectionClear)

    val section3DView: Section3DView = rootLayout.findViewById(R.id.section3DView)
    val tvSectionStatusOverlay: TextView = rootLayout.findViewById(R.id.tvSectionStatusOverlay)
    val layoutQuickRatioBar: View = rootLayout.findViewById(R.id.layoutQuickRatioBar)
    val tvRatioLabel: TextView = rootLayout.findViewById(R.id.tvRatioLabel)

    val btnRatioVertex: TextView = rootLayout.findViewById(R.id.btnRatioVertex)
    val btnRatioHalf: TextView = rootLayout.findViewById(R.id.btnRatioHalf)
    val btnRatioThird: TextView = rootLayout.findViewById(R.id.btnRatioThird)
    val btnRatioTwoThirds: TextView = rootLayout.findViewById(R.id.btnRatioTwoThirds)
    val btnRatioQuarter: TextView = rootLayout.findViewById(R.id.btnRatioQuarter)

    val cardSectionControls: MaterialCardView = rootLayout.findViewById(R.id.cardSectionControls)
    val tvPointsLabel: TextView = rootLayout.findViewById(R.id.tvPointsLabel)
    val chipPoint1: TextView = rootLayout.findViewById(R.id.chipPoint1)
    val chipPoint2: TextView = rootLayout.findViewById(R.id.chipPoint2)
    val chipPoint3: TextView = rootLayout.findViewById(R.id.chipPoint3)

    val layoutSectionPresets: LinearLayout = rootLayout.findViewById(R.id.layoutSectionPresets)
    val btnShowSectionPresets: TextView = rootLayout.findViewById(R.id.btnShowSectionPresets)

    val chipPolyCube: TextView = rootLayout.findViewById(R.id.chipPolyCube)
    val chipPolyBox: TextView = rootLayout.findViewById(R.id.chipPolyBox)
    val chipPolyTetra: TextView = rootLayout.findViewById(R.id.chipPolyTetra)
    val chipPolyPyramid4: TextView = rootLayout.findViewById(R.id.chipPolyPyramid4)
    val chipPolyPrism3: TextView = rootLayout.findViewById(R.id.chipPolyPrism3)
    val chipPolyPrism6: TextView = rootLayout.findViewById(R.id.chipPolyPrism6)

    private var activePolyId: String = "cube"

    init {
        initPolySelector()
        initHeaderControls()
        initRatioControls()
        initPointPills()

        section3DView.onSelectionChanged = { _, _, _ ->
            updateUIState()
        }

        updatePolySelection("cube")
    }

    private fun initHeaderControls() {
        btnSectionResetView.setOnClickListener {
            onHaptic(it)
            section3DView.cameraYaw = -35f
            section3DView.cameraPitch = 25f
            section3DView.zoomScale = 1.0f
            section3DView.invalidate()
        }

        btnSectionClear.setOnClickListener {
            onHaptic(it)
            section3DView.clearSelection()
        }
    }

    private fun initPolySelector() {
        val polyChips = listOf(
            chipPolyCube to "cube",
            chipPolyBox to "box",
            chipPolyTetra to "tetrahedron",
            chipPolyPyramid4 to "pyramid4",
            chipPolyPrism3 to "prism3",
            chipPolyPrism6 to "prism6"
        )

        polyChips.forEach { (chip, polyId) ->
            chip.setOnClickListener {
                onHaptic(it)
                if (activePolyId != polyId) {
                    updatePolySelection(polyId)
                }
            }
        }
    }

    private fun updatePolySelection(polyId: String) {
        activePolyId = polyId
        val poly = Section3DEngine.getPolyhedron(polyId) ?: return
        section3DView.polyhedron = poly

        val polyChips = listOf(
            chipPolyCube to "cube",
            chipPolyBox to "box",
            chipPolyTetra to "tetrahedron",
            chipPolyPyramid4 to "pyramid4",
            chipPolyPrism3 to "prism3",
            chipPolyPrism6 to "prism6"
        )

        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        val dp = rootLayout.resources.displayMetrics.density

        polyChips.forEach { (chip, id) ->
            val isSelected = (id == polyId)
            chip.setTextColor(if (isSelected) Color.WHITE else (if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#475569")))
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * dp
                if (isSelected) {
                    setColor(Color.parseColor("#0284C7"))
                } else {
                    setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
                }
            }
        }

        loadPresetsForActivePolyhedron()
        updateUIState()
    }

    private fun loadPresetsForActivePolyhedron() {
        val childCount = layoutSectionPresets.childCount
        if (childCount > 1) {
            layoutSectionPresets.removeViews(1, childCount - 1)
        }

        val presets = Section3DEngine.getPresetsForPolyhedron(activePolyId)
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        val dp = rootLayout.resources.displayMetrics.density

        presets.forEach { preset ->
            val chip = TextView(rootLayout.context).apply {
                text = preset.name
                textSize = 11f
                isSingleLine = true
                setPadding((8 * dp).toInt(), (5 * dp).toInt(), (8 * dp).toInt(), (5 * dp).toInt())
                setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6f * dp
                    setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
                    setStroke((1 * dp).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, (6 * dp).toInt(), 0)
                }
                layoutParams = params
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    onHaptic(it)
                    section3DView.setSelectedPoints(preset.points)
                }
            }
            layoutSectionPresets.addView(chip)
        }
    }

    private fun initRatioControls() {
        btnRatioVertex.setOnClickListener {
            onHaptic(it)
            val currentIdx = section3DView.activePointIndex
            if (currentIdx in 0 until section3DView.selectedPoints.size) {
                val pt = section3DView.selectedPoints[currentIdx]
                val targetRatio = if (pt.ratio >= 0.5) 0.0 else 1.0
                section3DView.updateActivePointRatio(targetRatio)
            }
        }
        btnRatioHalf.setOnClickListener {
            onHaptic(it)
            section3DView.updateActivePointRatio(0.5)
        }
        btnRatioThird.setOnClickListener {
            onHaptic(it)
            section3DView.updateActivePointRatio(1.0 / 3.0)
        }
        btnRatioTwoThirds.setOnClickListener {
            onHaptic(it)
            section3DView.updateActivePointRatio(2.0 / 3.0)
        }
        btnRatioQuarter.setOnClickListener {
            onHaptic(it)
            section3DView.updateActivePointRatio(0.25)
        }
    }

    private fun initPointPills() {
        val pills = listOf(chipPoint1 to 0, chipPoint2 to 1, chipPoint3 to 2)
        pills.forEach { (pill, index) ->
            pill.setOnClickListener {
                onHaptic(it)
                if (index < section3DView.selectedPoints.size) {
                    section3DView.setSelectedPoints(section3DView.selectedPoints, index)
                }
            }
            pill.setOnLongClickListener {
                onHaptic(it)
                if (index < section3DView.selectedPoints.size) {
                    section3DView.selectedPoints.removeAt(index)
                    val newActive = if (section3DView.selectedPoints.isNotEmpty()) {
                        (section3DView.selectedPoints.size - 1).coerceAtMost(index)
                    } else -1
                    section3DView.setSelectedPoints(section3DView.selectedPoints, newActive)
                }
                true
            }
        }
    }

    private fun updateUIState() {
        val selected = section3DView.selectedPoints
        val activeIdx = section3DView.activePointIndex
        val res = section3DView.crossSectionResult
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        val dp = rootLayout.resources.displayMetrics.density

        // 1. Status Overlay
        if (selected.size < 3) {
            tvSectionStatusOverlay.text = "Нажмите на 3 точки на рёбрах или вершинах (${selected.size}/3)"
            tvSectionStatusOverlay.setTextColor(if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
        } else {
            if (res == null || !res.isValid) {
                val err = res?.errorMessage ?: "Недопустимое сечение"
                tvSectionStatusOverlay.text = "Ошибка: $err"
                tvSectionStatusOverlay.setTextColor(Color.parseColor("#EF4444"))
            } else {
                val sFormatted = String.format(Locale.US, "%.3f", res.area)
                val pFormatted = String.format(Locale.US, "%.3f", res.perimeter)
                tvSectionStatusOverlay.text = "Сечение: ${res.shapeName} • S = $sFormatted • P = $pFormatted"
                tvSectionStatusOverlay.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            }
        }

        // 2. Point Pills
        val pills = listOf(chipPoint1, chipPoint2, chipPoint3)
        for (i in 0..2) {
            val pill = pills[i]
            if (i < selected.size) {
                val pt = selected[i]
                pill.text = "${i + 1}: ${pt.label}"
                val isActive = (i == activeIdx)
                if (isActive) {
                    pill.setTextColor(Color.WHITE)
                    pill.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6f * dp
                        setColor(Color.parseColor("#0284C7"))
                    }
                } else {
                    pill.setTextColor(if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
                    pill.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6f * dp
                        setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
                    }
                }
            } else {
                pill.text = "${i + 1}: —"
                pill.setTextColor(if (isDark) Color.parseColor("#64748B") else Color.parseColor("#94A3B8"))
                pill.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6f * dp
                    setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
                }
            }
        }

        // 3. Quick Ratio Bar visibility
        if (activeIdx in 0 until selected.size && selected[activeIdx].edgeIndex != null) {
            layoutQuickRatioBar.visibility = View.VISIBLE
        } else {
            layoutQuickRatioBar.visibility = View.GONE
        }
    }

    fun refreshTheme() {
        val isDark = ThemeManager.isDarkTheme(rootLayout.context)
        val dp = rootLayout.resources.displayMetrics.density

        section3DView.isDarkTheme = isDark

        layoutSection3D.setBackgroundColor(if (isDark) Color.parseColor("#0A0E17") else Color.parseColor("#F1F5F9"))
        tvSectionTitle.setTextColor(if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
        tvPointsLabel.setTextColor(if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#475569"))
        tvRatioLabel.setTextColor(if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#475569"))

        listOf(btnSectionBack, btnSectionResetView, btnSectionClear).forEach { tv ->
            tv.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * dp
                setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#FFFFFF"))
                setStroke((1f * dp).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
            }
        }
        btnSectionBack.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
        btnSectionResetView.setTextColor(if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
        btnSectionClear.setTextColor(Color.parseColor("#EF4444"))

        tvSectionStatusOverlay.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * dp
            setColor(if (isDark) Color.parseColor("#CC0F172A") else Color.parseColor("#CCFFFFFF"))
            setStroke((1f * dp).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
        }

        layoutQuickRatioBar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * dp
            setColor(if (isDark) Color.parseColor("#EE0F172A") else Color.parseColor("#EEFFFFFF"))
            setStroke((1f * dp).toInt(), if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"))
        }

        listOf(btnRatioVertex, btnRatioHalf, btnRatioThird, btnRatioTwoThirds, btnRatioQuarter).forEach { tv ->
            tv.setTextColor(if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A"))
            tv.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4f * dp
                setColor(if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
            }
        }

        cardSectionControls.setCardBackgroundColor(if (isDark) Color.parseColor("#0F172A") else Color.parseColor("#FFFFFF"))
        cardSectionControls.strokeColor = if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#CBD5E1")

        updatePolySelection(activePolyId)
    }
}
