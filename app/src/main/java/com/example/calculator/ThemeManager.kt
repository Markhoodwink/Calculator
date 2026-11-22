package com.example.calculator

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

object ThemeManager {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_IS_DARK = "is_dark_theme"

    // Dark Theme Palette
    val COLOR_DARK_SCREEN = Color.parseColor("#0A0E17")
    val COLOR_DARK_PANEL = Color.parseColor("#1E293B")
    val COLOR_DARK_INPUT = Color.parseColor("#0F172A")
    val COLOR_DARK_BORDER = Color.parseColor("#334155")
    val COLOR_DARK_TEXT_PRIMARY = Color.parseColor("#F8FAFC")
    val COLOR_DARK_TEXT_SECONDARY = Color.parseColor("#94A3B8")
    val COLOR_DARK_KEYPAD_DIGIT = Color.parseColor("#1A222D")
    val COLOR_DARK_KEYPAD_FUNC = Color.parseColor("#243042")
    val COLOR_DARK_LCD_FRAME = Color.parseColor("#CBD5D0")

    // Light Theme Palette
    val COLOR_LIGHT_SCREEN = Color.parseColor("#F1F5F9")
    val COLOR_LIGHT_PANEL = Color.parseColor("#FFFFFF")
    val COLOR_LIGHT_INPUT = Color.parseColor("#E2E8F0")
    val COLOR_LIGHT_BORDER = Color.parseColor("#CBD5E1")
    val COLOR_LIGHT_TEXT_PRIMARY = Color.parseColor("#0F172A")
    val COLOR_LIGHT_TEXT_SECONDARY = Color.parseColor("#475569")
    val COLOR_LIGHT_KEYPAD_DIGIT = Color.parseColor("#FFFFFF")
    val COLOR_LIGHT_KEYPAD_FUNC = Color.parseColor("#E2E8F0")
    val COLOR_LIGHT_LCD_FRAME = Color.parseColor("#DCE5E0")

    fun isDarkTheme(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_DARK, true)
    }

    fun setDarkTheme(context: Context, isDark: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_DARK, isDark).apply()
    }

    fun applyTheme(rootLayout: View, graphView: GraphView?, isDark: Boolean) {
        val context = rootLayout.context
        val dp = rootLayout.resources.displayMetrics.density

        // 1. Status Bar
        (context as? Activity)?.let { activity ->
            activity.window.statusBarColor = if (isDark) COLOR_DARK_SCREEN else COLOR_LIGHT_SCREEN
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = !isDark
        }

        // 2. Root Layout
        rootLayout.setBackgroundColor(if (isDark) COLOR_DARK_SCREEN else COLOR_LIGHT_SCREEN)

        // 3. Toggle buttons & Guide button
        rootLayout.findViewById<TextView>(R.id.btnOpenGuide)?.apply {
            setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * dp
                setColor(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
                setStroke((1.5f * dp).toInt(), if (isDark) COLOR_DARK_BORDER else COLOR_LIGHT_BORDER)
            }
        }

        rootLayout.findViewById<TextView>(R.id.btnToggleTheme)?.apply {
            text = if (isDark) "☀ Светлая" else "🌙 Тёмная"
            setTextColor(if (isDark) COLOR_DARK_TEXT_PRIMARY else COLOR_LIGHT_TEXT_PRIMARY)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * dp
                setColor(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
                setStroke((1.5f * dp).toInt(), if (isDark) COLOR_DARK_BORDER else COLOR_LIGHT_BORDER)
            }
        }

        rootLayout.findViewById<TextView>(R.id.btnOpenScanner)?.apply {
            setTextColor(if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * dp
                setColor(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
                setStroke((1.5f * dp).toInt(), if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669"))
            }
        }

        rootLayout.findViewById<TextView>(R.id.btnCalcThemeToggle)?.apply {
            text = if (isDark) "☀" else "🌙"
            setTextColor(if (isDark) Color.parseColor("#F59E0B") else Color.parseColor("#0F172A"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4f * dp
                setColor(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
            }
        }

        // 4. layoutModeHub
        rootLayout.findViewById<View>(R.id.layoutModeHub)?.setBackgroundColor(if (isDark) COLOR_DARK_SCREEN else COLOR_LIGHT_SCREEN)
        rootLayout.findViewById<TextView>(R.id.tvHubTitle)?.setTextColor(if (isDark) COLOR_DARK_TEXT_PRIMARY else COLOR_LIGHT_TEXT_PRIMARY)
        rootLayout.findViewById<TextView>(R.id.tvHubSubtitle)?.setTextColor(if (isDark) COLOR_DARK_TEXT_SECONDARY else COLOR_LIGHT_TEXT_SECONDARY)
        rootLayout.findViewById<TextView>(R.id.tvHubFooter)?.setTextColor(if (isDark) Color.parseColor("#64748B") else Color.parseColor("#94A3B8"))

        listOf(R.id.cardModeCalc, R.id.cardModeSystem, R.id.cardModeGraph, R.id.cardModeMatrix, R.id.cardModeTrig, R.id.cardModeSection3D, R.id.cardModeConverter, R.id.cardModeInequality).forEach { cardId ->
            rootLayout.findViewById<MaterialCardView>(cardId)?.let { card ->
                card.setCardBackgroundColor(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
                card.strokeColor = if (isDark) COLOR_DARK_BORDER else COLOR_LIGHT_BORDER
            }
        }

        listOf(R.id.badgeModeCalc, R.id.badgeModeSystem, R.id.badgeModeGraph, R.id.badgeModeMatrix, R.id.badgeModeTrig, R.id.badgeModeSection3D, R.id.badgeModeConverter, R.id.badgeModeInequality).forEach { badgeId ->
            rootLayout.findViewById<TextView>(badgeId)?.let { badge ->
                badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * dp
                    setColor(if (isDark) COLOR_DARK_INPUT else COLOR_LIGHT_INPUT)
                    setStroke((1f * dp).toInt(), if (isDark) COLOR_DARK_BORDER else COLOR_LIGHT_BORDER)
                }
            }
        }

        listOf(R.id.tvModeCalcTitle, R.id.tvModeSystemTitle, R.id.tvModeGraphTitle, R.id.tvModeMatrixTitle, R.id.tvModeTrigTitle, R.id.tvModeSection3DTitle, R.id.tvModeConverterTitle, R.id.tvModeInequalityTitle).forEach {
            rootLayout.findViewById<TextView>(it)?.setTextColor(if (isDark) COLOR_DARK_TEXT_PRIMARY else COLOR_LIGHT_TEXT_PRIMARY)
        }
        listOf(R.id.tvModeCalcDesc, R.id.tvModeSystemDesc, R.id.tvModeGraphDesc, R.id.tvModeMatrixDesc, R.id.tvModeTrigDesc, R.id.tvModeSection3DDesc, R.id.tvModeConverterDesc).forEach {
            rootLayout.findViewById<TextView>(it)?.setTextColor(if (isDark) COLOR_DARK_TEXT_SECONDARY else COLOR_LIGHT_TEXT_SECONDARY)
        }
        rootLayout.findViewById<TextView>(R.id.chevronModeCalc)?.setTextColor(if (isDark) Color.parseColor("#64748B") else Color.parseColor("#94A3B8"))

        // 5. layoutCalculator
        rootLayout.findViewById<View>(R.id.layoutCalculator)?.setBackgroundColor(if (isDark) COLOR_DARK_SCREEN else COLOR_LIGHT_SCREEN)

        // Iconic Casio LCD display frame
        rootLayout.findViewById<View>(R.id.casioDisplay)?.parent?.let { parent ->
            (parent as? View)?.setBackgroundColor(if (isDark) COLOR_DARK_LCD_FRAME else COLOR_LIGHT_LCD_FRAME)
        }

        // Top status chips in LCD frame
        listOf(R.id.btnOpenMenu, R.id.btnHistory, R.id.tvAngleMode).forEach { id ->
            rootLayout.findViewById<TextView>(id)?.let { tv ->
                tv.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4f * dp
                    setColor(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
                }
            }
        }

        rootLayout.findViewById<TextView>(R.id.tvAngleMode)?.apply {
            setTextColor(if (isDark) COLOR_DARK_TEXT_PRIMARY else COLOR_LIGHT_TEXT_PRIMARY)
        }

        // D-Pad
        rootLayout.findViewById<View>(R.id.btnArrowLeft)?.parent?.let { parent ->
            (parent as? View)?.setBackgroundColor(if (isDark) COLOR_DARK_INPUT else COLOR_LIGHT_INPUT)
        }
        listOf(R.id.btnArrowLeft, R.id.btnArrowUp, R.id.btnArrowDown, R.id.btnArrowRight).forEach { id ->
            rootLayout.findViewById<Button>(id)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
            }
        }
        rootLayout.findViewById<Button>(R.id.btnArrowLeft)?.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
        rootLayout.findViewById<Button>(R.id.btnArrowRight)?.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
        rootLayout.findViewById<Button>(R.id.btnArrowUp)?.setTextColor(if (isDark) Color.parseColor("#F59E0B") else Color.parseColor("#D97706"))
        rootLayout.findViewById<Button>(R.id.btnArrowDown)?.setTextColor(if (isDark) Color.parseColor("#F59E0B") else Color.parseColor("#D97706"))

        // Calculator Keypad container
        rootLayout.findViewById<View>(R.id.btn0)?.parent?.parent?.let { parent ->
            (parent as? View)?.setBackgroundColor(if (isDark) Color.parseColor("#0B0F15") else Color.parseColor("#CBD5E1"))
        }

        // Calculator Digits
        listOf(R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4, R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9, R.id.btnDot).forEach { id ->
            rootLayout.findViewById<Button>(id)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_KEYPAD_DIGIT else COLOR_LIGHT_KEYPAD_DIGIT)
                btn.setTextColor(if (isDark) Color.WHITE else COLOR_LIGHT_TEXT_PRIMARY)
            }
        }

        // Calculator Function Buttons
        val calcFuncButtons = listOf(
            R.id.btnSin, R.id.btnCos, R.id.btnTan, R.id.btnLn, R.id.btnLog,
            R.id.btnFraction, R.id.btnSqrt, R.id.btnSquare, R.id.btnPow, R.id.btnPi,
            R.id.btnOpenBracket, R.id.btnCloseBracket,
            R.id.btnVarX, R.id.btnVarY, R.id.btnVarA, R.id.btnVarM, R.id.btnVarT, R.id.btnVarV, R.id.btnEng,
            R.id.btnDiv, R.id.btnMul, R.id.btnSub, R.id.btnAdd, R.id.btnEqualsSign, R.id.btnAns, R.id.btnToggleFraction
        )
        calcFuncButtons.forEach { id ->
            rootLayout.findViewById<Button>(id)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_KEYPAD_FUNC else COLOR_LIGHT_KEYPAD_FUNC)
            }
        }

        listOf(R.id.btnSin, R.id.btnCos, R.id.btnTan, R.id.btnLn, R.id.btnLog, R.id.btnSqrt, R.id.btnOpenBracket, R.id.btnCloseBracket, R.id.btnFraction, R.id.btnPi, R.id.btnSquare, R.id.btnPow).forEach {
            rootLayout.findViewById<Button>(it)?.setTextColor(if (isDark) COLOR_DARK_TEXT_PRIMARY else COLOR_LIGHT_TEXT_PRIMARY)
        }
        listOf(R.id.btnEqualsSign, R.id.btnToggleFraction).forEach {
            rootLayout.findViewById<Button>(it)?.setTextColor(if (isDark) Color.parseColor("#F59E0B") else Color.parseColor("#D97706"))
        }
        rootLayout.findViewById<View>(R.id.btnCasSteps)?.let { btn ->
            if (btn is MaterialButton) {
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) Color.parseColor("#064E3B") else Color.parseColor("#D1FAE5"))
                btn.setTextColor(if (isDark) Color.parseColor("#34D399") else Color.parseColor("#059669"))
            } else if (btn is Button) {
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) Color.parseColor("#064E3B") else Color.parseColor("#D1FAE5"))
                btn.setTextColor(if (isDark) Color.parseColor("#34D399") else Color.parseColor("#059669"))
            }
        }
        listOf(R.id.btnVarX, R.id.btnVarY, R.id.btnVarA, R.id.btnVarM, R.id.btnVarT, R.id.btnVarV, R.id.btnEng, R.id.btnAns).forEach {
            rootLayout.findViewById<Button>(it)?.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
        }
        listOf(R.id.btnDiv, R.id.btnMul, R.id.btnSub, R.id.btnAdd).forEach {
            rootLayout.findViewById<Button>(it)?.setTextColor(if (isDark) Color.parseColor("#93C5FD") else Color.parseColor("#0284C7"))
        }

        // 6. layoutSystemSolver
        rootLayout.findViewById<View>(R.id.layoutSystemSolver)?.setBackgroundColor(if (isDark) COLOR_DARK_SCREEN else COLOR_LIGHT_SCREEN)
        rootLayout.findViewById<TextView>(R.id.tvSystemTitle)?.setTextColor(if (isDark) COLOR_DARK_TEXT_PRIMARY else COLOR_LIGHT_TEXT_PRIMARY)
        rootLayout.findViewById<TextView>(R.id.tvEquationsSubtitle)?.setTextColor(if (isDark) COLOR_DARK_TEXT_SECONDARY else COLOR_LIGHT_TEXT_SECONDARY)
        rootLayout.findViewById<TextView>(R.id.tvSystemResult)?.setTextColor(if (isDark) COLOR_DARK_TEXT_PRIMARY else COLOR_LIGHT_TEXT_PRIMARY)

        listOf(R.id.btnSystemBack, R.id.btnSystemClear).forEach { id ->
            rootLayout.findViewById<TextView>(id)?.let { tv ->
                tv.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6f * dp
                    setColor(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
                    setStroke((1f * dp).toInt(), if (isDark) COLOR_DARK_BORDER else COLOR_LIGHT_BORDER)
                }
            }
        }

        rootLayout.findViewById<MaterialCardView>(R.id.cardSystemResult)?.let { card ->
            card.setCardBackgroundColor(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
            card.strokeColor = if (isDark) COLOR_DARK_BORDER else COLOR_LIGHT_BORDER
        }
        rootLayout.findViewById<View>(R.id.containerEquationsList)?.parent?.parent?.let { parent ->
            (parent as? MaterialCardView)?.let { card ->
                card.setCardBackgroundColor(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
                card.strokeColor = if (isDark) COLOR_DARK_BORDER else COLOR_LIGHT_BORDER
            }
        }

        listOf(R.id.btnCopySystemResult, R.id.btnAddEquation).forEach { id ->
            rootLayout.findViewById<Button>(id)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_KEYPAD_FUNC)
                btn.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            }
        }

        // System keypad
        rootLayout.findViewById<View>(R.id.layoutSystemKeypad)?.setBackgroundColor(if (isDark) Color.parseColor("#0B0F15") else Color.parseColor("#CBD5E1"))
        val sysDigits = listOf(R.id.btnSys0, R.id.btnSys1, R.id.btnSys2, R.id.btnSys3, R.id.btnSys4, R.id.btnSys5, R.id.btnSys6, R.id.btnSys7, R.id.btnSys8, R.id.btnSys9, R.id.btnSysDot)
        sysDigits.forEach { id ->
            rootLayout.findViewById<Button>(id)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_KEYPAD_DIGIT else COLOR_LIGHT_KEYPAD_DIGIT)
                btn.setTextColor(if (isDark) Color.WHITE else COLOR_LIGHT_TEXT_PRIMARY)
            }
        }
        val sysFuncs = listOf(
            R.id.btnSysVarX, R.id.btnSysVarY, R.id.btnSysVarZ, R.id.btnSysVarA, R.id.btnSysVarB, R.id.btnSysVarC,
            R.id.btnSysBracketOpen, R.id.btnSysBracketClose, R.id.btnSysPow, R.id.btnSysEq, R.id.btnSysDiv,
            R.id.btnSysMul, R.id.btnSysMinus, R.id.btnSysPlus, R.id.btnSysNext
        )
        sysFuncs.forEach { id ->
            rootLayout.findViewById<Button>(id)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_KEYPAD_FUNC else COLOR_LIGHT_KEYPAD_FUNC)
                btn.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            }
        }

        // 7. layoutGraphMode
        rootLayout.findViewById<View>(R.id.layoutGraphMode)?.setBackgroundColor(if (isDark) COLOR_DARK_SCREEN else COLOR_LIGHT_SCREEN)
        rootLayout.findViewById<TextView>(R.id.tvGraphTitle)?.setTextColor(if (isDark) COLOR_DARK_TEXT_PRIMARY else COLOR_LIGHT_TEXT_PRIMARY)
        listOf(R.id.btnGraphBack, R.id.btnGraphReset).forEach { id ->
            rootLayout.findViewById<TextView>(id)?.let { tv ->
                tv.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6f * dp
                    setColor(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
                    setStroke((1f * dp).toInt(), if (isDark) COLOR_DARK_BORDER else COLOR_LIGHT_BORDER)
                }
            }
        }
        rootLayout.findViewById<Button>(R.id.btnAddGraphSeries)?.let { btn ->
            btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_KEYPAD_FUNC)
            btn.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
        }
        listOf(R.id.btnGraphZoomIn, R.id.btnGraphZoomOut, R.id.btnGraphHome).forEach { id ->
            rootLayout.findViewById<MaterialButton>(id)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
                btn.strokeColor = ColorStateList.valueOf(if (isDark) COLOR_DARK_BORDER else COLOR_LIGHT_BORDER)
                btn.setTextColor(if (isDark) COLOR_DARK_TEXT_PRIMARY else COLOR_LIGHT_TEXT_PRIMARY)
            }
        }

        // Graph keypad
        rootLayout.findViewById<View>(R.id.layoutGraphKeypad)?.setBackgroundColor(if (isDark) Color.parseColor("#0B0F15") else Color.parseColor("#CBD5E1"))
        val graphDigits = listOf(
            R.id.btnGraphKey0, R.id.btnGraphKey1, R.id.btnGraphKey2, R.id.btnGraphKey3, R.id.btnGraphKey4,
            R.id.btnGraphKey5, R.id.btnGraphKey6, R.id.btnGraphKey7, R.id.btnGraphKey8, R.id.btnGraphKey9,
            R.id.btnGraphKeyDot
        )
        graphDigits.forEach { id ->
            rootLayout.findViewById<Button>(id)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_KEYPAD_DIGIT else COLOR_LIGHT_KEYPAD_DIGIT)
                btn.setTextColor(if (isDark) Color.WHITE else COLOR_LIGHT_TEXT_PRIMARY)
            }
        }
        val graphFuncs = listOf(
            R.id.btnGraphKeyX, R.id.btnGraphKeyY, R.id.btnGraphKeyA, R.id.btnGraphKeyB,
            R.id.btnGraphKeySin, R.id.btnGraphKeyCos, R.id.btnGraphKeyTan, R.id.btnGraphKeyLn,
            R.id.btnGraphKeyPi, R.id.btnGraphKeyAbs, R.id.btnGraphKeyLt, R.id.btnGraphKeyGt,
            R.id.btnGraphKeyOpenParen, R.id.btnGraphKeyCloseParen, R.id.btnGraphKeyPow,
            R.id.btnGraphKeySqrt, R.id.btnGraphKeyDiv, R.id.btnGraphKeyPlus, R.id.btnGraphKeyMinus,
            R.id.btnGraphKeyMul, R.id.btnGraphKeyEq
        )
        graphFuncs.forEach { id ->
            rootLayout.findViewById<Button>(id)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_KEYPAD_FUNC else COLOR_LIGHT_KEYPAD_FUNC)
                btn.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            }
        }

        // GraphView
        graphView?.isDarkTheme = isDark

        // 8. layoutMatrixMode
        rootLayout.findViewById<View>(R.id.layoutMatrixMode)?.setBackgroundColor(if (isDark) COLOR_DARK_SCREEN else COLOR_LIGHT_SCREEN)
        rootLayout.findViewById<TextView>(R.id.tvMatrixTitle)?.setTextColor(if (isDark) COLOR_DARK_TEXT_PRIMARY else COLOR_LIGHT_TEXT_PRIMARY)
        listOf(R.id.btnMatrixBack, R.id.btnMatrixClear).forEach { id ->
            rootLayout.findViewById<TextView>(id)?.let { tv ->
                tv.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6f * dp
                    setColor(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
                    setStroke((1f * dp).toInt(), if (isDark) COLOR_DARK_BORDER else COLOR_LIGHT_BORDER)
                }
            }
        }
        rootLayout.findViewById<MaterialCardView>(R.id.cardMatrixResult)?.let { card ->
            card.setCardBackgroundColor(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
            card.strokeColor = if (isDark) COLOR_DARK_BORDER else COLOR_LIGHT_BORDER
        }
        rootLayout.findViewById<View>(R.id.containerMatrixGrid)?.parent?.parent?.let { parent ->
            (parent as? MaterialCardView)?.let { card ->
                card.setCardBackgroundColor(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
                card.strokeColor = if (isDark) COLOR_DARK_BORDER else COLOR_LIGHT_BORDER
            }
        }
        rootLayout.findViewById<TextView>(R.id.tvMatrixInputTitle)?.setTextColor(if (isDark) COLOR_DARK_TEXT_PRIMARY else COLOR_LIGHT_TEXT_PRIMARY)
        rootLayout.findViewById<TextView>(R.id.tvMatrixResultTitle)?.setTextColor(if (isDark) COLOR_DARK_TEXT_PRIMARY else COLOR_LIGHT_TEXT_PRIMARY)

        listOf(R.id.btnMatDet, R.id.btnMatInv, R.id.btnMatTrans, R.id.btnMatAdd, R.id.btnMatSub, R.id.btnMatMul).forEach { id ->
            rootLayout.findViewById<MaterialButton>(id)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
            }
        }
        listOf(R.id.btnUseResultAsA, R.id.btnCopyMatrixResult).forEach { id ->
            rootLayout.findViewById<MaterialButton>(id)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_PANEL else COLOR_LIGHT_PANEL)
                btn.strokeColor = ColorStateList.valueOf(if (isDark) COLOR_DARK_BORDER else COLOR_LIGHT_BORDER)
                btn.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            }
        }

        // Matrix keypad
        rootLayout.findViewById<View>(R.id.layoutMatrixKeypad)?.setBackgroundColor(if (isDark) Color.parseColor("#0B0F15") else Color.parseColor("#CBD5E1"))
        val matDigits = listOf(R.id.btnMat0, R.id.btnMat1, R.id.btnMat2, R.id.btnMat3, R.id.btnMat4, R.id.btnMat5, R.id.btnMat6, R.id.btnMat7, R.id.btnMat8, R.id.btnMat9, R.id.btnMatDot)
        matDigits.forEach { id ->
            rootLayout.findViewById<Button>(id)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_KEYPAD_DIGIT else COLOR_LIGHT_KEYPAD_DIGIT)
                btn.setTextColor(if (isDark) Color.WHITE else COLOR_LIGHT_TEXT_PRIMARY)
            }
        }
        val matFuncs = listOf(
            R.id.btnMatDiv, R.id.btnMatMinus, R.id.btnMatNext, R.id.btnMatKeyDet
        )
        matFuncs.forEach { id ->
            rootLayout.findViewById<Button>(id)?.let { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(if (isDark) COLOR_DARK_KEYPAD_FUNC else COLOR_LIGHT_KEYPAD_FUNC)
                btn.setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            }
        }
    }
}
