package com.example.calculator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

class StepsBottomSheet(
    context: Context,
    private val steps: List<CasStep>,
    private val onHaptic: (View) -> Unit = {}
) : BottomSheetDialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val context = context
        val dp = context.resources.displayMetrics.density
        val isDark = ThemeManager.isDarkTheme(context)

        // Palette
        val panelColor = if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL
        val textColorPrimary = if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY
        val textColorSecondary = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
        val borderColor = if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER
        val cardBgColor = if (isDark) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC")

        // Root Container
        val rootContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    18f * dp, 18f * dp,
                    18f * dp, 18f * dp,
                    0f, 0f, 0f, 0f
                )
                setColor(panelColor)
            }
        }

        // Drag Handle
        val dragHandle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, (12 * dp).toInt())
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2f * dp
                setColor(if (isDark) Color.parseColor("#475569") else Color.parseColor("#CBD5E1"))
            }
        }
        rootContainer.addView(dragHandle)

        // Header Layout
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (12 * dp).toInt())
        }

        val tvTitle = TextView(context).apply {
            text = "📖 Ход решения"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColorPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(tvTitle)

        // Copy Button
        val btnCopy = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "Копировать"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (32 * dp).toInt()).apply {
                marginEnd = (6 * dp).toInt()
            }
            insetTop = 0
            insetBottom = 0
            cornerRadius = (8 * dp).toInt()
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (isDark) Color.parseColor("#334155") else Color.parseColor("#E2E8F0")
            )
            setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))

            setOnClickListener { v ->
                onHaptic(v)
                val textToCopy = steps.joinToString("\n\n") { step ->
                    "${step.badge}. ${step.title}\n${step.formula}\n${step.explanation}"
                }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Ход решения", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Ход решения скопирован", Toast.LENGTH_SHORT).show()
            }
        }
        headerLayout.addView(btnCopy)

        // Close Button
        val btnClose = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColorSecondary)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onHaptic(it)
                dismiss()
            }
        }
        headerLayout.addView(btnClose)

        rootContainer.addView(headerLayout)

        // ScrollView for Step Cards
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }

        val cardsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        for (step in steps) {
            val cardView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (6 * dp).toInt())
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * dp
                    setColor(cardBgColor)
                    setStroke((1 * dp).toInt(), borderColor)
                }
            }

            // Top Row (Badge Pill + Step Title)
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Colored Badge Pill
            val (badgeBgColor, badgeTextColor) = getBadgeColors(step.badge, isDark)
            val tvBadge = TextView(context).apply {
                text = step.badge
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(badgeTextColor)
                setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (6 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4f * dp
                    setColor(badgeBgColor)
                }
            }
            topRow.addView(tvBadge)

            // Step Title
            val tvStepTitle = TextView(context).apply {
                text = step.title
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColorPrimary)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            topRow.addView(tvStepTitle)

            cardView.addView(topRow)

            // Middle (Formula)
            val tvFormula = TextView(context).apply {
                text = formatFormulaForDisplay(step.formula)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColorPrimary)
                setTextIsSelectable(true)
                setLineSpacing(4f * dp, 1.15f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, (4 * dp).toInt(), 0, (2 * dp).toInt())
                }
            }
            cardView.addView(tvFormula)

            // Bottom (Explanation)
            if (step.explanation.isNotBlank()) {
                val tvExplanation = TextView(context).apply {
                    text = step.explanation
                    textSize = 12f
                    setTextColor(textColorSecondary)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                cardView.addView(tvExplanation)
            }

            cardsContainer.addView(cardView)
        }

        scrollView.addView(cardsContainer)
        rootContainer.addView(scrollView)

        setContentView(rootContainer)

        // Make bottom sheet container transparent so top rounded corners render cleanly
        window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background = null
    }

    private fun formatFormulaForDisplay(formula: String): String {
        return formula
            .replace(Regex("""√\(([a-zA-Z0-9]+)\)"""), "√$1")
            .replace(Regex("""\s*/\s*"""), " / ")
    }

    private fun getBadgeColors(badge: String, isDark: Boolean): Pair<Int, Int> {
        return when {
            badge == "ОДЗ" -> {
                if (isDark) Color.parseColor("#7F1D1D") to Color.parseColor("#FCA5A5")
                else Color.parseColor("#FEE2E2") to Color.parseColor("#991B1B")
            }
            badge == "Условие" -> {
                if (isDark) Color.parseColor("#312E81") to Color.parseColor("#A5B4FC")
                else Color.parseColor("#E0E7FF") to Color.parseColor("#3730A3")
            }
            badge == "Ответ" -> {
                if (isDark) Color.parseColor("#064E3B") to Color.parseColor("#34D399")
                else Color.parseColor("#D1FAE5") to Color.parseColor("#047857")
            }
            badge.startsWith("Шаг") -> {
                if (isDark) Color.parseColor("#075985") to Color.parseColor("#38BDF8")
                else Color.parseColor("#E0F2FE") to Color.parseColor("#0369A1")
            }
            else -> {
                if (isDark) Color.parseColor("#334155") to Color.parseColor("#94A3B8")
                else Color.parseColor("#F1F5F9") to Color.parseColor("#475569")
            }
        }
    }
}
