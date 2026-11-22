package com.example.calculator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private val casEngine = CasEngine()
    private lateinit var casioDisplay: CasioDisplayView
    private lateinit var tvAngleMode: TextView
    private lateinit var layoutVarChips: LinearLayout
    private lateinit var historyRepository: HistoryRepository
    private lateinit var systemSolverManager: SystemSolverManager
    private lateinit var graphModeManager: GraphModeManager
    private lateinit var matrixModeManager: MatrixModeManager
    private lateinit var trigSolverManager: TrigSolverManager
    private lateinit var section3DManager: Section3DManager
    private lateinit var unitConverterManager: UnitConverterManager
    private lateinit var inequalitySolverManager: InequalitySolverManager
    private lateinit var formulaScannerManager: FormulaScannerManager

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (::formulaScannerManager.isInitialized) {
            formulaScannerManager.onCameraPermissionResult(granted)
        }
    }

    private val galleryPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && ::formulaScannerManager.isInitialized) {
            formulaScannerManager.onGalleryImagePicked(uri)
        }
    }

    // ── SHIFT state ──────────────────────────────────────────────────────────
    private var isShiftActive: Boolean = false
    private lateinit var btnShift: Button
    private lateinit var btnCasSteps: MaterialButton
    private lateinit var tvShiftIndicator: TextView
    // ─────────────────────────────────────────────────────────────────────────

    private var lastAnswer: String? = null
    private var lastSolveResult: CasEngine.SolveResult? = null
    private var fractionModeState = 0 // 0: Обыкновенная дробь, 1: Смешанная, 2: Десятичная
    private var isApproxMode = false

    private var engBaseNumber: Double? = null
    private var engPrefix: String = ""
    private var engStep: Int = 0

    private var pendingPreviewRunnable: Runnable? = null
    private val previewExecutor = Executors.newSingleThreadExecutor()
    private val previewRequestId = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun cancelPendingPreview() {
        pendingPreviewRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingPreviewRunnable = null
        previewRequestId.incrementAndGet()
    }

    private var lastVarChipList: List<String> = emptyList()

    override fun onDestroy() {
        super.onDestroy()
        previewExecutor.shutdown()
        inequalitySolverManager.cleanup()
        if (::formulaScannerManager.isInitialized) {
            formulaScannerManager.cleanup()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyRepository = HistoryRepository(this)

        val rootLayout = findViewById<View>(R.id.mainRootLayout)
        rootLayout.clearFocus()
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft,
                statusBarInsets.top,
                view.paddingRight,
                navBarInsets.bottom
            )
            insets
        }

        window.statusBarColor = Color.parseColor("#0A0E17")
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        casioDisplay = findViewById(R.id.casioDisplay)
        tvAngleMode = findViewById(R.id.tvAngleMode)
        layoutVarChips = findViewById(R.id.layoutVarChips)
        btnShift = findViewById(R.id.btnShift)
        tvShiftIndicator = findViewById(R.id.tvShiftIndicator)
        btnCasSteps = findViewById(R.id.btnCasSteps)

        btnCasSteps.setOnClickListener { v ->
            triggerKeyHaptic(v, isHeavy = true)
            val steps = lastSolveResult?.detailedSteps ?: emptyList()
            if (steps.isNotEmpty()) {
                StepsBottomSheet(this, steps) { view -> triggerKeyHaptic(view) }.show()
            }
        }

        initKeypad()
        initNavigationPad()

        casioDisplay.onPreviewClick = {
            KeyFeedback.trigger(casioDisplay, isHeavy = true)
            val raw = casioDisplay.getRawExpression().trim()
            if (raw.contains("=")) {
                solveCurrentEquation()
            } else {
                executeCalculation()
            }
        }

        // Слушатель формулы для динамического появления кнопок "Найти: [x] [y]" и "живого" превью
        casioDisplay.onContentChanged = { rawExpr ->
            btnCasSteps.visibility = View.GONE
            updateVariableChips(rawExpr)
            engBaseNumber = null
            engPrefix = ""
            engStep = 0

            cancelPendingPreview()
            val requestId = previewRequestId.incrementAndGet()

            if (!rawExpr.contains("=")) {
                if (rawExpr.isBlank() || extractVariables(rawExpr).isNotEmpty()) {
                    casioDisplay.setLivePreview(null)
                } else {
                    val degreeMode = casEngine.isDegreeMode
                    val lastAns = casEngine.lastAnswer

                    previewExecutor.execute {
                        if (requestId != previewRequestId.get()) return@execute
                        var previewText: String? = null
                        try {
                            val previewEngine = CasEngine().apply {
                                isDegreeMode = degreeMode
                                lastAnswer = lastAns
                            }
                            val eval = previewEngine.evaluateFull(rawExpr, includeSteps = false)
                            val formula = eval.displayFormula.trim()
                            if (formula.isNotBlank() &&
                                formula.length <= 48 &&
                                !formula.contains("NaN") &&
                                !formula.contains("Infinity") &&
                                !formula.startsWith("Ошибка") &&
                                !formula.startsWith("Error") &&
                                !formula.startsWith("Не удалось") &&
                                !formula.startsWith("Переменная")
                            ) {
                                previewText = eval.displayFormula
                            }
                        } catch (_: Exception) {}

                        if (requestId == previewRequestId.get()) {
                            mainHandler.post {
                                if (requestId == previewRequestId.get() && !casioDisplay.hasResult) {
                                    casioDisplay.setLivePreview(previewText)
                                }
                            }
                        }
                    }
                }
            } else {
                val eqParts = rawExpr.split("=")
                val vars = extractVariables(rawExpr)
                if (eqParts.size != 2 || eqParts[0].trim().isBlank() || eqParts[1].trim().isBlank() || vars.isEmpty()) {
                    casioDisplay.setLivePreview(null)
                } else {
                    val targetVar = if ("x" in vars) "x" else vars.first()
                    val degreeMode = casEngine.isDegreeMode
                    val lastAns = casEngine.lastAnswer

                    val runnable = Runnable {
                        pendingPreviewRunnable = null
                        previewExecutor.execute {
                            if (requestId != previewRequestId.get()) return@execute
                            var previewText: String? = null
                            try {
                                val previewEngine = CasEngine().apply {
                                    isDegreeMode = degreeMode
                                    lastAnswer = lastAns
                                }
                                val eval = previewEngine.isolateVariableFull(rawExpr, targetVar)
                                val formula = eval.displayFormula.trim()
                                if (formula.isNotBlank() &&
                                    formula.length <= 48 &&
                                    !formula.contains("NaN") &&
                                    !formula.contains("Infinity") &&
                                    !formula.startsWith("Ошибка") &&
                                    !formula.startsWith("Error") &&
                                    !formula.startsWith("Не удалось") &&
                                    !formula.startsWith("Переменная") &&
                                    !formula.startsWith("Нет ")
                                ) {
                                    previewText = eval.displayFormula
                                }
                            } catch (_: Exception) {}

                            if (requestId == previewRequestId.get()) {
                                mainHandler.post {
                                    if (requestId == previewRequestId.get() && !casioDisplay.hasResult) {
                                        casioDisplay.setLivePreview(previewText)
                                    }
                                }
                            }
                        }
                    }
                    pendingPreviewRunnable = runnable
                    mainHandler.postDelayed(runnable, 120L)
                }
            }
        }

        // Переключение режимов DEG <-> RAD
        tvAngleMode.setOnClickListener {
            triggerKeyHaptic(it)
            casEngine.isDegreeMode = !casEngine.isDegreeMode
            tvAngleMode.text = if (casEngine.isDegreeMode) "DEG" else "RAD"
        }

        // Кнопка истории в статус-баре
        findViewById<View>(R.id.btnHistory)?.setOnClickListener {
            triggerKeyHaptic(it)
            showHistoryBottomSheet()
        }

        // Свайп вниз по CasioDisplayView открывает историю
        casioDisplay.onSwipeDown = {
            showHistoryBottomSheet()
        }

        // Долгий тап по CasioDisplayView: контекстное копирование результата
        casioDisplay.setOnLongClickListener {
            triggerKeyHaptic(it)
            showCopyResultDialog()
            true
        }

        systemSolverManager = SystemSolverManager(rootLayout, casEngine) { v ->
            triggerKeyHaptic(v)
        }

        graphModeManager = GraphModeManager(rootLayout, casEngine) { v ->
            triggerKeyHaptic(v)
        }

        matrixModeManager = MatrixModeManager(rootLayout, casEngine) { v ->
            triggerKeyHaptic(v)
        }

        trigSolverManager = TrigSolverManager(rootLayout, casEngine) { v ->
            triggerKeyHaptic(v)
        }

        section3DManager = Section3DManager(rootLayout) { v ->
            triggerKeyHaptic(v)
        }

        unitConverterManager = UnitConverterManager(rootLayout, { v -> triggerKeyHaptic(v) }) { resultValue ->
            casioDisplay.insertText(resultValue)
            findViewById<View>(R.id.layoutModeHub).visibility = View.GONE
            unitConverterManager.layoutUnitConverter.visibility = View.GONE
            findViewById<View>(R.id.layoutCalculator).visibility = View.VISIBLE
        }

        inequalitySolverManager = InequalitySolverManager(rootLayout, casEngine) { v ->
            triggerKeyHaptic(v)
        }

        formulaScannerManager = FormulaScannerManager(
            activity = this,
            rootLayout = rootLayout,
            onHaptic = { v -> triggerKeyHaptic(v) },
            onRequestCameraPermission = {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onRequestGalleryImage = {
                galleryPickerLauncher.launch("image/*")
            },
            onCloseScanner = {
                val layoutModeHub = findViewById<View>(R.id.layoutModeHub)
                val layoutCalculator = findViewById<View>(R.id.layoutCalculator)
                if (layoutCalculator.visibility != View.VISIBLE &&
                    systemSolverManager.layoutSystemSolver.visibility != View.VISIBLE &&
                    graphModeManager.layoutGraphMode.visibility != View.VISIBLE &&
                    matrixModeManager.layoutMatrixMode.visibility != View.VISIBLE &&
                    trigSolverManager.layoutTrigSolver.visibility != View.VISIBLE &&
                    section3DManager.layoutSection3D.visibility != View.VISIBLE &&
                    unitConverterManager.layoutUnitConverter.visibility != View.VISIBLE &&
                    inequalitySolverManager.layoutInequalitySolver.visibility != View.VISIBLE
                ) {
                    layoutModeHub.visibility = View.VISIBLE
                }
            },
            onSendFormula = { target, casExpr ->
                openTargetModeWithFormula(target, casExpr)
            }
        )

        initModeHub()
        initTheme()
    }

    private fun openTargetModeWithFormula(target: ScanTarget, casExpr: String) {
        val layoutModeHub = findViewById<View>(R.id.layoutModeHub)
        val layoutCalculator = findViewById<View>(R.id.layoutCalculator)
        val layoutSystemSolver = systemSolverManager.layoutSystemSolver
        val layoutGraphMode = graphModeManager.layoutGraphMode
        val layoutMatrixMode = matrixModeManager.layoutMatrixMode
        val layoutTrigSolver = trigSolverManager.layoutTrigSolver
        val layoutSection3D = section3DManager.layoutSection3D
        val layoutUnitConverter = unitConverterManager.layoutUnitConverter
        val layoutInequalitySolver = inequalitySolverManager.layoutInequalitySolver

        layoutModeHub.visibility = View.GONE
        layoutSystemSolver.visibility = View.GONE
        layoutGraphMode.visibility = View.GONE
        layoutMatrixMode.visibility = View.GONE
        layoutTrigSolver.visibility = View.GONE
        layoutSection3D.visibility = View.GONE
        layoutUnitConverter.visibility = View.GONE
        layoutInequalitySolver.visibility = View.GONE
        layoutCalculator.visibility = View.GONE

        when (target) {
            ScanTarget.CALCULATOR -> {
                layoutCalculator.visibility = View.VISIBLE
                casioDisplay.loadExpression(casExpr)
                if (casExpr.contains("=")) {
                    solveCurrentEquation()
                } else {
                    executeCalculation()
                }
            }
            ScanTarget.INEQUALITY -> {
                layoutInequalitySolver.visibility = View.VISIBLE
                inequalitySolverManager.loadFormulaAndSolve(casExpr)
            }
            ScanTarget.TRIG -> {
                layoutTrigSolver.visibility = View.VISIBLE
                trigSolverManager.loadFormulaAndSolve(casExpr)
            }
        }
    }

    private fun initTheme() {
        val btnToggleTheme = findViewById<TextView>(R.id.btnToggleTheme)
        val btnCalcThemeToggle = findViewById<TextView>(R.id.btnCalcThemeToggle)

        val isDark = ThemeManager.isDarkTheme(this)
        applyThemeState(isDark)

        val toggleAction = { v: View ->
            triggerKeyHaptic(v)
            val newDark = !ThemeManager.isDarkTheme(this)
            ThemeManager.setDarkTheme(this, newDark)
            applyThemeState(newDark)
            lastVarChipList = emptyList()
            updateVariableChips(casioDisplay.getRawExpression())
        }

        btnToggleTheme?.setOnClickListener(toggleAction)
        btnCalcThemeToggle?.setOnClickListener(toggleAction)
    }

    private fun applyThemeState(isDark: Boolean) {
        val rootLayout = findViewById<View>(R.id.mainRootLayout)
        val graphView = graphModeManager.graphView
        ThemeManager.applyTheme(rootLayout, graphView, isDark)
        setupDualFunctionButtons(isDark)
        systemSolverManager.refreshTheme()
        graphModeManager.refreshTheme()
        matrixModeManager.refreshTheme()
        trigSolverManager.refreshTheme()
        section3DManager.refreshTheme()
        unitConverterManager.refreshTheme()
        inequalitySolverManager.refreshTheme()
        formulaScannerManager.refreshTheme()
    }

    private fun initModeHub() {
        val layoutModeHub = findViewById<View>(R.id.layoutModeHub)
        val layoutCalculator = findViewById<View>(R.id.layoutCalculator)
        val layoutSystemSolver = systemSolverManager.layoutSystemSolver
        val layoutGraphMode = graphModeManager.layoutGraphMode
        val layoutMatrixMode = matrixModeManager.layoutMatrixMode
        val layoutTrigSolver = trigSolverManager.layoutTrigSolver
        val layoutSection3D = section3DManager.layoutSection3D
        val layoutUnitConverter = unitConverterManager.layoutUnitConverter
        val layoutInequalitySolver = inequalitySolverManager.layoutInequalitySolver
        val btnOpenMenu = findViewById<View>(R.id.btnOpenMenu)
        val btnOpenGuide = findViewById<View>(R.id.btnOpenGuide)
        val btnOpenScanner = findViewById<View>(R.id.btnOpenScanner)
        val cardModeCalc = findViewById<View>(R.id.cardModeCalc)
        val cardModeSystem = findViewById<View>(R.id.cardModeSystem)
        val cardModeGraph = findViewById<View>(R.id.cardModeGraph)
        val cardModeMatrix = findViewById<View>(R.id.cardModeMatrix)
        val cardModeTrig = findViewById<View>(R.id.cardModeTrig)
        val cardModeSection3D = findViewById<View>(R.id.cardModeSection3D)
        val cardModeConverter = findViewById<View>(R.id.cardModeConverter)
        val cardModeInequality = findViewById<View>(R.id.cardModeInequality)

        btnOpenScanner?.setOnClickListener {
            triggerKeyHaptic(it)
            layoutModeHub.visibility = View.GONE
            formulaScannerManager.openScanner()
        }

        btnOpenMenu?.setOnClickListener {
            triggerKeyHaptic(it)
            layoutModeHub.visibility = View.VISIBLE
        }

        btnOpenGuide?.setOnClickListener {
            triggerKeyHaptic(it)
            GuideBottomSheet().show(supportFragmentManager, "guide_sheet")
        }

        cardModeCalc?.setOnClickListener {
            triggerKeyHaptic(it)
            layoutModeHub.visibility = View.GONE
            layoutSystemSolver.visibility = View.GONE
            layoutGraphMode.visibility = View.GONE
            layoutMatrixMode.visibility = View.GONE
            layoutTrigSolver.visibility = View.GONE
            layoutSection3D.visibility = View.GONE
            layoutUnitConverter.visibility = View.GONE
            layoutInequalitySolver.visibility = View.GONE
            formulaScannerManager.layoutFormulaScanner.visibility = View.GONE
            layoutCalculator.visibility = View.VISIBLE
        }

        cardModeSystem?.setOnClickListener {
            triggerKeyHaptic(it)
            layoutModeHub.visibility = View.GONE
            layoutCalculator.visibility = View.GONE
            layoutGraphMode.visibility = View.GONE
            layoutMatrixMode.visibility = View.GONE
            layoutTrigSolver.visibility = View.GONE
            layoutSection3D.visibility = View.GONE
            layoutUnitConverter.visibility = View.GONE
            layoutInequalitySolver.visibility = View.GONE
            formulaScannerManager.layoutFormulaScanner.visibility = View.GONE
            layoutSystemSolver.visibility = View.VISIBLE
        }

        systemSolverManager.btnSystemBack.setOnClickListener {
            triggerKeyHaptic(it)
            layoutSystemSolver.visibility = View.GONE
            layoutModeHub.visibility = View.VISIBLE
        }

        cardModeGraph?.setOnClickListener {
            triggerKeyHaptic(it)
            layoutModeHub.visibility = View.GONE
            layoutCalculator.visibility = View.GONE
            layoutSystemSolver.visibility = View.GONE
            layoutMatrixMode.visibility = View.GONE
            layoutTrigSolver.visibility = View.GONE
            layoutSection3D.visibility = View.GONE
            layoutUnitConverter.visibility = View.GONE
            layoutInequalitySolver.visibility = View.GONE
            formulaScannerManager.layoutFormulaScanner.visibility = View.GONE
            layoutGraphMode.visibility = View.VISIBLE
        }

        graphModeManager.btnGraphBack.setOnClickListener {
            triggerKeyHaptic(it)
            layoutGraphMode.visibility = View.GONE
            layoutModeHub.visibility = View.VISIBLE
        }

        cardModeMatrix?.setOnClickListener {
            triggerKeyHaptic(it)
            layoutModeHub.visibility = View.GONE
            layoutCalculator.visibility = View.GONE
            layoutSystemSolver.visibility = View.GONE
            layoutGraphMode.visibility = View.GONE
            layoutTrigSolver.visibility = View.GONE
            layoutSection3D.visibility = View.GONE
            layoutUnitConverter.visibility = View.GONE
            layoutInequalitySolver.visibility = View.GONE
            formulaScannerManager.layoutFormulaScanner.visibility = View.GONE
            layoutMatrixMode.visibility = View.VISIBLE
        }

        matrixModeManager.btnMatrixBack.setOnClickListener {
            triggerKeyHaptic(it)
            layoutMatrixMode.visibility = View.GONE
            layoutModeHub.visibility = View.VISIBLE
        }

        cardModeTrig?.setOnClickListener {
            triggerKeyHaptic(it)
            layoutModeHub.visibility = View.GONE
            layoutCalculator.visibility = View.GONE
            layoutSystemSolver.visibility = View.GONE
            layoutGraphMode.visibility = View.GONE
            layoutMatrixMode.visibility = View.GONE
            layoutSection3D.visibility = View.GONE
            layoutUnitConverter.visibility = View.GONE
            layoutInequalitySolver.visibility = View.GONE
            formulaScannerManager.layoutFormulaScanner.visibility = View.GONE
            layoutTrigSolver.visibility = View.VISIBLE
        }

        trigSolverManager.btnTrigBack.setOnClickListener {
            triggerKeyHaptic(it)
            layoutTrigSolver.visibility = View.GONE
            layoutModeHub.visibility = View.VISIBLE
        }

        cardModeSection3D?.setOnClickListener {
            triggerKeyHaptic(it)
            layoutModeHub.visibility = View.GONE
            layoutCalculator.visibility = View.GONE
            layoutSystemSolver.visibility = View.GONE
            layoutGraphMode.visibility = View.GONE
            layoutMatrixMode.visibility = View.GONE
            layoutTrigSolver.visibility = View.GONE
            layoutUnitConverter.visibility = View.GONE
            layoutInequalitySolver.visibility = View.GONE
            formulaScannerManager.layoutFormulaScanner.visibility = View.GONE
            layoutSection3D.visibility = View.VISIBLE
        }

        section3DManager.btnSectionBack.setOnClickListener {
            triggerKeyHaptic(it)
            layoutSection3D.visibility = View.GONE
            layoutModeHub.visibility = View.VISIBLE
        }

        cardModeConverter?.setOnClickListener {
            triggerKeyHaptic(it)
            layoutModeHub.visibility = View.GONE
            layoutCalculator.visibility = View.GONE
            layoutSystemSolver.visibility = View.GONE
            layoutGraphMode.visibility = View.GONE
            layoutMatrixMode.visibility = View.GONE
            layoutTrigSolver.visibility = View.GONE
            layoutSection3D.visibility = View.GONE
            layoutInequalitySolver.visibility = View.GONE
            formulaScannerManager.layoutFormulaScanner.visibility = View.GONE
            layoutUnitConverter.visibility = View.VISIBLE
        }

        unitConverterManager.btnConverterBack.setOnClickListener {
            triggerKeyHaptic(it)
            layoutUnitConverter.visibility = View.GONE
            layoutModeHub.visibility = View.VISIBLE
        }

        cardModeInequality?.setOnClickListener {
            triggerKeyHaptic(it)
            layoutModeHub.visibility = View.GONE
            layoutCalculator.visibility = View.GONE
            layoutSystemSolver.visibility = View.GONE
            layoutGraphMode.visibility = View.GONE
            layoutMatrixMode.visibility = View.GONE
            layoutTrigSolver.visibility = View.GONE
            layoutSection3D.visibility = View.GONE
            layoutUnitConverter.visibility = View.GONE
            formulaScannerManager.layoutFormulaScanner.visibility = View.GONE
            layoutInequalitySolver.visibility = View.VISIBLE
        }

        inequalitySolverManager.btnIneqBack.setOnClickListener {
            triggerKeyHaptic(it)
            layoutInequalitySolver.visibility = View.GONE
            layoutModeHub.visibility = View.VISIBLE
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (formulaScannerManager.handleBackPress()) {
                    return
                }
                if (layoutSystemSolver.visibility == View.VISIBLE) {
                    layoutSystemSolver.visibility = View.GONE
                    layoutModeHub.visibility = View.VISIBLE
                } else if (layoutGraphMode.visibility == View.VISIBLE) {
                    layoutGraphMode.visibility = View.GONE
                    layoutModeHub.visibility = View.VISIBLE
                } else if (layoutMatrixMode.visibility == View.VISIBLE) {
                    layoutMatrixMode.visibility = View.GONE
                    layoutModeHub.visibility = View.VISIBLE
                } else if (layoutTrigSolver.visibility == View.VISIBLE) {
                    layoutTrigSolver.visibility = View.GONE
                    layoutModeHub.visibility = View.VISIBLE
                } else if (layoutSection3D.visibility == View.VISIBLE) {
                    layoutSection3D.visibility = View.GONE
                    layoutModeHub.visibility = View.VISIBLE
                } else if (layoutUnitConverter.visibility == View.VISIBLE) {
                    layoutUnitConverter.visibility = View.GONE
                    layoutModeHub.visibility = View.VISIBLE
                } else if (layoutInequalitySolver.visibility == View.VISIBLE) {
                    layoutInequalitySolver.visibility = View.GONE
                    layoutModeHub.visibility = View.VISIBLE
                } else if (layoutModeHub.visibility != View.VISIBLE) {
                    layoutModeHub.visibility = View.VISIBLE
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    // ── SHIFT helpers ─────────────────────────────────────────────────────────

    /** Переключить SHIFT; вернуть новое значение флага. */
    private fun toggleShift() {
        isShiftActive = !isShiftActive
        applyShiftVisual()
    }

    /** Визуально отразить состояние SHIFT. */
    private fun applyShiftVisual() {
        if (isShiftActive) {
            tvShiftIndicator.visibility = View.VISIBLE
            btnShift.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#78350F"))
            btnShift.setTextColor(Color.parseColor("#F59E0B"))
        } else {
            tvShiftIndicator.visibility = View.INVISIBLE
            btnShift.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#1A3A1A"))
            btnShift.setTextColor(Color.parseColor("#A3E635"))
        }
    }

    /** Сбросить SHIFT после выполнения вторичной функции. */
    private fun resetShift() {
        isShiftActive = false
        applyShiftVisual()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun triggerKeyHaptic(v: View, isHeavy: Boolean = false) {
        KeyFeedback.trigger(v, isHeavy)
    }

    private fun bindKey(buttonId: Int, isHeavy: Boolean = false, action: () -> Unit) {
        val btn = findViewById<View>(buttonId) ?: return
        btn.bindCrispKey(isHeavy) { action() }
    }

    private fun initKeypad() {
        setupDualFunctionButtons(ThemeManager.isDarkTheme(this))

        val digitMap = mapOf(
            R.id.btn0 to "0", R.id.btn1 to "1", R.id.btn2 to "2",
            R.id.btn3 to "3", R.id.btn4 to "4", R.id.btn5 to "5",
            R.id.btn6 to "6", R.id.btn7 to "7", R.id.btn8 to "8",
            R.id.btn9 to "9", R.id.btnDot to "."
        )
        digitMap.forEach { (id, text) ->
            bindKey(id) { casioDisplay.insertText(text) }
        }

        // Авто-подстановка Ans: если дисплей пуст, подставляем "Ans" перед оператором
        val ensureAnsIfEmpty = {
            if (casioDisplay.rootContainer.isEmpty()) {
                casioDisplay.insertText("Ans")
            }
        }

        bindKey(R.id.btnAdd) {
            ensureAnsIfEmpty()
            casioDisplay.insertText(" + ")
        }
        bindKey(R.id.btnSub) {
            ensureAnsIfEmpty()
            casioDisplay.insertText(" - ")
        }
        bindKey(R.id.btnMul) {
            ensureAnsIfEmpty()
            casioDisplay.insertText(" · ")
        }
        bindKey(R.id.btnDiv) {
            ensureAnsIfEmpty()
            casioDisplay.insertText(" ÷ ")
        }
        bindKey(R.id.btnEqualsSign) { casioDisplay.insertText(" = ") }

        // Кнопка переменной Ans
        bindKey(R.id.btnAns) {
            casioDisplay.insertText("Ans")
        }

        // Инженерная нотация
        bindKey(R.id.btnEng) {
            handleEngButton()
        }

        // ── SHIFT кнопка ─────────────────────────────────────────────────────
        bindKey(R.id.btnShift) {
            toggleShift()
        }

        // ── Тригонометрия и функции (с учётом SHIFT) ─────────────────────────
        bindKey(R.id.btnSin) {
            if (isShiftActive) {
                casioDisplay.insertText("asin(")
                resetShift()
            } else {
                casioDisplay.insertText("sin(")
            }
        }
        bindKey(R.id.btnCos) {
            if (isShiftActive) {
                casioDisplay.insertText("acos(")
                resetShift()
            } else {
                casioDisplay.insertText("cos(")
            }
        }
        bindKey(R.id.btnTan) {
            if (isShiftActive) {
                casioDisplay.insertText("atan(")
                resetShift()
            } else {
                casioDisplay.insertText("tan(")
            }
        }
        bindKey(R.id.btnLn) {
            if (isShiftActive) {
                casioDisplay.insertText("e^(")
                resetShift()
            } else {
                casioDisplay.insertText("ln(")
            }
        }
        bindKey(R.id.btnLog) {
            if (isShiftActive) {
                casioDisplay.insertText("10^(")
                resetShift()
            } else {
                casioDisplay.insertLogBase()
            }
        }
        bindKey(R.id.btnSqrt) {
            if (isShiftActive) {
                casioDisplay.insertCbrt()
                resetShift()
            } else {
                casioDisplay.insertSqrt()
            }
        }
        bindKey(R.id.btnSquare) {
            ensureAnsIfEmpty()
            if (isShiftActive) {
                casioDisplay.insertSuperscript("3")
                resetShift()
            } else {
                casioDisplay.insertSuperscript("2")
            }
        }
        // ─────────────────────────────────────────────────────────────────────

        bindKey(R.id.btnPow) {
            ensureAnsIfEmpty()
            if (isShiftActive) {
                casioDisplay.insertSuperscript("-1")
                resetShift()
            } else {
                casioDisplay.insertSuperscript()
            }
        }

        bindKey(R.id.btnPi) {
            if (isShiftActive) {
                resetShift()
                showConstantsBottomSheet()
            } else {
                casioDisplay.insertText("π")
            }
        }

        bindKey(R.id.btnOpenBracket) {
            if (isShiftActive) {
                ensureAnsIfEmpty()
                casioDisplay.insertText("!")
                resetShift()
            } else {
                casioDisplay.insertText("(")
            }
        }

        bindKey(R.id.btnCloseBracket) {
            if (isShiftActive) {
                ensureAnsIfEmpty()
                casioDisplay.insertText("%")
                resetShift()
            } else {
                casioDisplay.insertText(")")
            }
        }
        bindKey(R.id.btnFraction) {
            if (isShiftActive) {
                casioDisplay.insertMixedFraction()
                resetShift()
            } else {
                casioDisplay.insertFraction()
            }
        }

        // Переменные
        val varMap = mapOf(
            R.id.btnVarX to "x", R.id.btnVarY to "y",
            R.id.btnVarA to "a", R.id.btnVarM to "m",
            R.id.btnVarT to "t"
        )
        varMap.forEach { (id, v) ->
            bindKey(id) { casioDisplay.insertText(v) }
        }

        bindKey(R.id.btnVarV) {
            if (isShiftActive) {
                casioDisplay.insertCombinatorics("A")
                resetShift()
            } else {
                casioDisplay.insertCombinatorics("C")
            }
        }

        bindKey(R.id.btnAc, isHeavy = true) {
            cancelPendingPreview()
            casioDisplay.setLivePreview(null)
            casioDisplay.clearAll()
            lastSolveResult = null
            btnCasSteps.visibility = View.GONE
            fractionModeState = 0
            isApproxMode = false
            engBaseNumber = null
            engPrefix = ""
            engStep = 0
            // AC сбрасывает SHIFT
            if (isShiftActive) resetShift()
        }

        val btnDel = findViewById<View>(R.id.btnDel)
        btnDel?.bindRepeatingCrispKey(isHeavy = true) {
            val rawBefore = casioDisplay.getRawExpression()
            val containerBefore = casioDisplay.currentContainer
            val cursorBefore = casioDisplay.cursorIndex
            casioDisplay.deleteAtCursor()
            val rawAfter = casioDisplay.getRawExpression()
            val containerAfter = casioDisplay.currentContainer
            val cursorAfter = casioDisplay.cursorIndex
            (rawBefore != rawAfter) || (containerBefore !== containerAfter) || (cursorBefore != cursorAfter)
        }

        bindKey(R.id.btnSolveEquation, isHeavy = true) {
            solveCurrentEquation()
        }

        bindKey(R.id.btnExecute, isHeavy = true) {
            val raw = casioDisplay.getRawExpression().trim()
            if (raw.contains("=")) {
                solveCurrentEquation()
            } else {
                executeCalculation()
            }
        }

        bindKey(R.id.btnToggleFraction) {
            toggleFractionMode()
        }
    }

    private fun setupDualFunctionButtons(isDark: Boolean = ThemeManager.isDarkTheme(this)) {
        val hintColor = if (isDark) Color.parseColor("#F59E0B") else Color.parseColor("#B45309")
        val mainColor = if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
        val blueColor = if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0F172A")
        val goldColor = if (isDark) Color.parseColor("#F59E0B") else Color.parseColor("#0F172A")
        val whiteColor = mainColor
        val slateColor = mainColor

        // Ряд 0: sin, cos, tan, ln, log
        setDualKeyText(R.id.btnSin, "sin⁻¹", "sin", hintSizeSp = 9, mainSizeSp = 13, hintColor = hintColor, mainColor = mainColor)
        setDualKeyText(R.id.btnCos, "cos⁻¹", "cos", hintSizeSp = 9, mainSizeSp = 13, hintColor = hintColor, mainColor = mainColor)
        setDualKeyText(R.id.btnTan, "tan⁻¹", "tan", hintSizeSp = 9, mainSizeSp = 13, hintColor = hintColor, mainColor = mainColor)
        setDualKeyText(R.id.btnLn, "eˣ", "ln", hintSizeSp = 9, mainSizeSp = 13, hintColor = hintColor, mainColor = mainColor)
        setDualKeyText(R.id.btnLog, "10ˣ", "log□", hintSizeSp = 9, mainSizeSp = 13, hintColor = hintColor, mainColor = mainColor)

        // Ряд 1: дробь, корень, квадрат, степень, pi, скобки
        setDualKeyText(R.id.btnFraction, "■\u00A0■/□", "■/□", hintSizeSp = 8, mainSizeSp = 13, mainColor = hintColor)
        setDualKeyText(R.id.btnSqrt, "³√", "√", hintSizeSp = 9, mainSizeSp = 16, mainColor = whiteColor)
        setDualKeyText(R.id.btnSquare, "x³", "x²", hintSizeSp = 9, mainSizeSp = 14, mainColor = blueColor)
        setDualKeyText(R.id.btnPow, "x⁻¹", "x^□", hintSizeSp = 9, mainSizeSp = 13, mainColor = blueColor)
        setDualKeyText(R.id.btnPi, "CONST", "π", hintSizeSp = 8, mainSizeSp = 15, mainColor = whiteColor)
        setDualKeyText(R.id.btnOpenBracket, "x!", "(", hintSizeSp = 9, mainSizeSp = 15, mainColor = slateColor)
        setDualKeyText(R.id.btnCloseBracket, "%", ")", hintSizeSp = 9, mainSizeSp = 15, mainColor = slateColor)

        // Сочетания C / Размещения A (Dual function button)
        setDualKeyText(R.id.btnVarV, "A", "C", hintSizeSp = 9, mainSizeSp = 14, hintColor = hintColor, mainColor = blueColor)
    }

    private fun setDualKeyText(
        buttonId: Int,
        shiftHint: String,
        mainLabel: String,
        hintSizeSp: Int = 9,
        mainSizeSp: Int = 13,
        hintColor: Int = Color.parseColor("#F59E0B"),
        mainColor: Int = Color.parseColor("#E2E8F0")
    ) {
        val btn = findViewById<Button>(buttonId) ?: return
        val ssb = SpannableStringBuilder()

        // 1. SHIFT-подсказка (верхняя строка)
        val hintStart = 0
        ssb.append(shiftHint)
        val hintEnd = ssb.length
        ssb.setSpan(ForegroundColorSpan(hintColor), hintStart, hintEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(AbsoluteSizeSpan(hintSizeSp, true), hintStart, hintEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(StyleSpan(Typeface.BOLD), hintStart, hintEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        ssb.append("\n")

        // 2. Основная функция (нижняя строка)
        val mainStart = ssb.length
        ssb.append(mainLabel)
        val mainEnd = ssb.length
        ssb.setSpan(ForegroundColorSpan(mainColor), mainStart, mainEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(AbsoluteSizeSpan(mainSizeSp, true), mainStart, mainEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(StyleSpan(Typeface.BOLD), mainStart, mainEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Центрирование строк
        ssb.setSpan(
            AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
            0,
            ssb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        btn.text = ssb
        btn.isSingleLine = false
        btn.maxLines = 2
        btn.gravity = android.view.Gravity.CENTER
        btn.includeFontPadding = false
    }

    private fun initNavigationPad() {
        val btnLeft = findViewById<View>(R.id.btnArrowLeft)
        btnLeft?.bindRepeatingCrispKey(isHeavy = false) {
            val containerBefore = casioDisplay.currentContainer
            val cursorBefore = casioDisplay.cursorIndex
            casioDisplay.moveLeft()
            val containerAfter = casioDisplay.currentContainer
            val cursorAfter = casioDisplay.cursorIndex
            (containerBefore !== containerAfter) || (cursorBefore != cursorAfter)
        }

        val btnRight = findViewById<View>(R.id.btnArrowRight)
        btnRight?.bindRepeatingCrispKey(isHeavy = false) {
            val containerBefore = casioDisplay.currentContainer
            val cursorBefore = casioDisplay.cursorIndex
            casioDisplay.moveRight()
            val containerAfter = casioDisplay.currentContainer
            val cursorAfter = casioDisplay.cursorIndex
            (containerBefore !== containerAfter) || (cursorBefore != cursorAfter)
        }

        bindKey(R.id.btnArrowUp) { casioDisplay.jumpUp() }
        bindKey(R.id.btnArrowDown) { casioDisplay.jumpDown() }
    }

    private fun updateStepsButtonVisibility(res: CasEngine.SolveResult) {
        val formula = res.displayFormula
        val shouldShow = res.detailedSteps.isNotEmpty() &&
                !formula.startsWith("Ошибка") &&
                !formula.startsWith("Не удалось") &&
                !formula.startsWith("Переменная")
        btnCasSteps.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun executeCalculation() {
        cancelPendingPreview()
        casioDisplay.setLivePreview(null)
        casioDisplay.autoCloseUnclosedParens()
        val raw = casioDisplay.getRawExpression().trim()
        if (raw.isEmpty()) return

        val res = casEngine.evaluateFull(raw)
        lastSolveResult = res
        updateStepsButtonVisibility(res)
        fractionModeState = if (res.displayFormula.contains("/") && !res.displayFormula.contains("·")) 0 else 2
        isApproxMode = false
        engBaseNumber = null
        engPrefix = ""
        engStep = 0

        if (!res.displayFormula.startsWith("Ошибка")) {
            lastAnswer = res.displayFormula
            casEngine.lastAnswer = res.displayFormula
            historyRepository.addRecord(raw, res.displayFormula)
        }
        casioDisplay.setResult(res.displayFormula)
    }

    private fun solveCurrentEquation(specificVar: String? = null) {
        cancelPendingPreview()
        casioDisplay.setLivePreview(null)
        casioDisplay.autoCloseUnclosedParens()
        val raw = casioDisplay.getRawExpression().trim()
        if (!raw.contains("=")) {
            executeCalculation()
            return
        }

        val vars = extractVariables(raw)
        if (vars.isEmpty()) {
            casioDisplay.setResult("Нет переменных")
            btnCasSteps.visibility = View.GONE
            return
        }

        val target = specificVar ?: (if ("x" in vars) "x" else vars.first())
        val res = casEngine.isolateVariableFull(raw, target)
        lastSolveResult = res
        updateStepsButtonVisibility(res)
        fractionModeState = 0
        isApproxMode = false
        engBaseNumber = null
        engPrefix = ""
        engStep = 0

        if (!res.displayFormula.startsWith("Ошибка") &&
            !res.displayFormula.startsWith("Не удалось") &&
            !res.displayFormula.startsWith("Нет")) {
            val ansVal = if (res.exactFraction != null) {
                res.exactFraction.toString()
            } else if (res.displayFormula.contains("=")) {
                res.displayFormula.split(";")[0].substringAfter("=").trim()
            } else {
                res.displayFormula
            }
            lastAnswer = ansVal
            casEngine.lastAnswer = ansVal
            historyRepository.addRecord(raw, res.displayFormula)
        }
        casioDisplay.setResult(res.displayFormula)
    }

    private fun parseScientificOrDecimal(str: String): Double? {
        val trimmed = str.trim()
        trimmed.toDoubleOrNull()?.let { return it }

        val sciRegex = Regex("""^([+-]?\d+(?:\.\d+)?)\s*[·×*]\s*10([⁰¹²³⁴⁵⁶⁷⁸⁹⁻\d+-]+)$""")
        val m = sciRegex.matchEntire(trimmed)
        if (m != null) {
            val mantissa = m.groupValues[1]
            val supExp = m.groupValues[2]
            val supMap = mapOf(
                '⁰' to '0', '¹' to '1', '²' to '2', '3' to '3', '⁴' to '4',
                '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9',
                '⁻' to '-', '+' to '+'
            )
            val normalExp = supExp.map { supMap[it] ?: it }.joinToString("")
            return "${mantissa}e${normalExp}".toDoubleOrNull()
        }
        return null
    }

    private fun toggleFractionMode() {
        val res = lastSolveResult
        val resText = casioDisplay.getResultText().ifEmpty { res?.displayFormula ?: "" }.trim()
        if (resText.isEmpty() || resText.startsWith("Ошибка") || resText.startsWith("Нет") || resText.startsWith("Не удалось")) {
            return
        }

        engBaseNumber = null
        engPrefix = ""
        engStep = 0

        if (isApproxMode) {
            val originalFormula = lastSolveResult?.displayFormula
            if (!originalFormula.isNullOrEmpty()) {
                casioDisplay.setResult(originalFormula)
                isApproxMode = false
                return
            }
        }

        var frac = res?.exactFraction
        val prefix = if (resText.contains("=")) {
            resText.split(";")[0].substringBefore("=").trim() + " = "
        } else if (res != null && res.displayFormula.contains("=")) {
            res.displayFormula.split(";")[0].substringBefore("=").trim() + " = "
        } else ""

        if (frac == null) {
            val numPart = if (resText.contains("=")) {
                resText.split(";")[0].substringAfter("=").trim()
            } else {
                resText
            }
            if (numPart.contains("/")) {
                val parts = numPart.split("/")
                val n = parts[0].trim().toLongOrNull()
                val d = parts[1].trim().toLongOrNull()
                if (n != null && d != null && d != 0L) {
                    frac = CasEngine.Fraction(n, d).simplify()
                }
            } else {
                val f = CasEngine.Fraction.fromDecimalString(numPart)
                if (f.num != 0L || numPart == "0" || numPart == "0.0") {
                    frac = f
                } else {
                    val d = parseScientificOrDecimal(numPart)
                    if (d != null && !d.isNaN() && !d.isInfinite()) {
                        frac = CasEngine.Fraction.fromDouble(d)
                    }
                }
            }
        }

        if (frac == null) {
            if (res == null) {
                lastSolveResult = CasEngine.SolveResult(
                    displayFormula = resText,
                    primaryNode = null,
                    steps = emptyList()
                )
            }
            val baseFormula = lastSolveResult?.displayFormula ?: resText
            if (baseFormula.contains("√") || baseFormula.contains(";") || baseFormula.contains("±") || baseFormula.contains("π") || isApproxMode) {
                if (isApproxMode) {
                    casioDisplay.setResult(baseFormula)
                    isApproxMode = false
                } else {
                    val approx = computeDecimalApproximation(baseFormula)
                    if (approx != baseFormula) {
                        casioDisplay.setResult(approx)
                        isApproxMode = true
                    }
                }
                return
            }

            if (res != null) {
                casioDisplay.setResult(res.displayFormula)
            }
            return
        }

        if (res == null || res.exactFraction == null) {
            lastSolveResult = CasEngine.SolveResult(
                displayFormula = resText,
                primaryNode = null,
                steps = emptyList(),
                exactFraction = frac
            )
        }

        fractionModeState = (fractionModeState + 1) % 3
        val d = frac.toDouble()
        val absD = abs(d)

        if (absD > 0 && (absD < 1e-3 || absD >= 1e10)) {
            when (fractionModeState) {
                0 -> casioDisplay.setResult("$prefix$frac")
                1 -> casioDisplay.setResult("$prefix${CasEngine.Fraction.formatDoubleNorm1(d)}")
                2 -> casioDisplay.setResult("$prefix${CasEngine.Fraction.fractionToPlainDecimalString(frac)}")
            }
        } else {
            when (fractionModeState) {
                0 -> {
                    casioDisplay.setResult("$prefix$frac")
                }
                1 -> {
                    val mixed = casEngine.toMixedFraction(frac)
                    if (mixed != null) {
                        val (w, r, den) = mixed
                        val wStr = if (w < 0) "-${abs(w)}" else "$w"
                        casioDisplay.setResult("$prefix$wStr $r/$den")
                    } else {
                        casioDisplay.setResult("$prefix${CasEngine.Fraction.fractionToPlainDecimalString(frac)}")
                    }
                }
                2 -> {
                    casioDisplay.setResult("$prefix${CasEngine.Fraction.fractionToPlainDecimalString(frac)}")
                }
            }
        }
    }

    private fun computeDecimalApproximation(formula: String): String {
        val segments = formula.split(";")
        val approxSegments = segments.map { seg ->
            val trimmed = seg.trim()
            if (trimmed.isEmpty()) return@map trimmed
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx != -1) {
                val prefix = trimmed.substring(0, eqIdx).trim() + " = "
                var rhs = trimmed.substring(eqIdx + 1).trim()
                val pm = if (rhs.startsWith("±")) {
                    rhs = rhs.substring(1).trim()
                    "±"
                } else ""
                val d = evaluateExprToDouble(rhs)
                if (d != null && !d.isNaN()) {
                    "$prefix$pm${CasEngine.Fraction.formatDoubleNorm1(d)}"
                } else {
                    trimmed
                }
            } else {
                var rhs = trimmed
                val pm = if (rhs.startsWith("±")) {
                    rhs = rhs.substring(1).trim()
                    "±"
                } else ""
                val d = evaluateExprToDouble(rhs)
                if (d != null && !d.isNaN()) {
                    "$pm${CasEngine.Fraction.formatDoubleNorm1(d)}"
                } else {
                    trimmed
                }
            }
        }
        return approxSegments.joinToString(";  ")
    }

    private fun evaluateExprToDouble(expr: String): Double? {
        return try {
            val cleanExpr = expr.trim()
            val ast = casEngine.compileFunction(cleanExpr)
            if (ast != null) {
                val v = ast(0.0)
                if (!v.isNaN()) v else null
            } else {
                val eval = casEngine.evaluateFull(cleanExpr)
                val nodeVal = eval.primaryNode?.let { casEngine.evalNodeAtX(it, 0.0) }
                if (nodeVal != null && !nodeVal.isNaN()) nodeVal
                else eval.exactFraction?.toDouble()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun handleEngButton() {
        if (engBaseNumber == null) {
            val res = lastSolveResult
            val frac = res?.exactFraction
            val resultStr = casioDisplay.getResultText().ifEmpty { res?.displayFormula ?: "" }.trim()

            if (frac != null) {
                engBaseNumber = frac.toDouble()
                engPrefix = if (resultStr.contains("=")) resultStr.split("=")[0].trim() + " = " else ""
            } else if (resultStr.isNotEmpty() && !resultStr.startsWith("Ошибка") && !resultStr.startsWith("Нет") && !resultStr.startsWith("Не удалось")) {
                val numPart = if (resultStr.contains("=")) resultStr.split(";")[0].substringAfter("=").trim() else resultStr.trim()
                val d = if (numPart.contains("/")) {
                    val parts = numPart.split("/")
                    val n = parts[0].trim().toDoubleOrNull()
                    val den = parts[1].trim().toDoubleOrNull()
                    if (n != null && den != null && den != 0.0) n / den else null
                } else {
                    parseScientificOrDecimal(numPart)
                }
                if (d != null) {
                    engBaseNumber = d
                    engPrefix = if (resultStr.contains("=")) resultStr.split("=")[0].trim() + " = " else ""
                }
            }
        }

        val num = engBaseNumber ?: return
        val formatted = formatEng(num, engStep)
        engStep++
        casioDisplay.setResult("$engPrefix$formatted")
    }

    private fun formatEng(num: Double, step: Int): String {
        if (abs(num) < 1e-15) return "0 · 10⁰"
        val isNeg = num < 0
        val absNum = abs(num)

        var exp = (floor(log10(absNum) / 3.0) * 3).toInt()
        val offset = when (step % 3) {
            0 -> 0
            1 -> -3
            else -> 3
        }
        exp += offset

        val coeff = (if (isNeg) -absNum else absNum) / 10.0.pow(exp.toDouble())
        val bd = BigDecimal(coeff, MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros()
        val coeffStr = bd.toPlainString()
        val supExp = formatExponentSuperscript(exp)
        return "$coeffStr · 10$supExp"
    }

    private fun formatExponentSuperscript(exp: Int): String {
        val supDigits = mapOf(
            '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
            '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
            '-' to '⁻'
        )
        return exp.toString().map { supDigits[it] ?: it }.joinToString("")
    }

    private fun showHistoryBottomSheet() {
        val dialog = HistoryBottomSheet(
            this,
            historyRepository,
            onFormulaClick = { formula ->
                casioDisplay.loadExpression(formula)
                Toast.makeText(this, "Формула восстановлена", Toast.LENGTH_SHORT).show()
            },
            onResultClick = { resValue ->
                val toInsert = if (resValue.contains("=")) {
                    resValue.split(";")[0].substringAfter("=").trim()
                } else {
                    resValue
                }
                casioDisplay.insertText(toInsert)
                Toast.makeText(this, "Результат подставлен", Toast.LENGTH_SHORT).show()
            }
        )
        dialog.show()
    }

    private fun showConstantsBottomSheet() {
        val bottomSheet = ConstantsBottomSheet { constantValue ->
            casioDisplay.insertText(constantValue)
            Toast.makeText(this, "Константа вставлена: $constantValue", Toast.LENGTH_SHORT).show()
        }
        bottomSheet.show(supportFragmentManager, "ConstantsBottomSheet")
    }

    private fun showCopyResultDialog() {
        val resultText = casioDisplay.getResultText().ifEmpty { lastSolveResult?.displayFormula ?: "" }.trim()
        if (resultText.isEmpty() || !casioDisplay.hasResult) {
            Toast.makeText(this, "Нет результата для копирования", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf("Скопировать текст", "Скопировать как LaTeX")
        MaterialAlertDialogBuilder(this)
            .setTitle("Копировать результат")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyToClipboard("Результат", resultText)
                    1 -> {
                        val latex = convertToLatex(resultText)
                        copyToClipboard("LaTeX результат", latex)
                    }
                }
            }
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Скопировано: $text", Toast.LENGTH_SHORT).show()
    }

    private fun convertToLatex(raw: String): String {
        var s = raw.trim()

        s = s.replace("₁", "_{1}").replace("₂", "_{2}").replace("₃", "_{3}")
             .replace("₄", "_{4}").replace("₅", "_{5}")

        s = s.replace("π", "\\pi")
             .replace("±", "\\pm ")
             .replace("·", " \\cdot ")
             .replace("×", " \\cdot ")

        val supMap = mapOf(
            '0' to "0", '1' to "1", '2' to "2", '3' to "3", '4' to "4",
            '5' to "5", '6' to "6", '7' to "7", '8' to "8", '9' to "9",
            '-' to "-"
        )
        val supRegex = Regex("""[⁰¹²³⁴⁵⁶⁷⁸⁹⁻]+""")
        s = supRegex.replace(s) { match ->
            val converted = match.value.map { supMap[it] ?: it.toString() }.joinToString("")
            "^{$converted}"
        }

        s = s.replace(Regex("""cbrt\(([^)]+)\)"""), "\\sqrt[3]{$1}")
        s = s.replace(Regex("""³√\(([^)]+)\)"""), "\\sqrt[3]{$1}")
        s = s.replace(Regex("""³√([0-9a-zA-Z]+)"""), "\\sqrt[3]{$1}")
        s = s.replace(Regex("""√\(([^)]+)\)"""), "\\sqrt{$1}")
        s = s.replace(Regex("""√([0-9a-zA-Z]+)"""), "\\sqrt{$1}")

        val fracRegex = Regex("""(\([^()]+\)|\\sqrt\{[^}]+\}|[0-9a-zA-Z_.-]+)\s*/\s*(\([^()]+\)|\\sqrt\{[^}]+\}|[0-9a-zA-Z_.-]+)""")
        var prev = ""
        while (prev != s) {
            prev = s
            s = fracRegex.replace(s) { m ->
                var num = m.groupValues[1].trim()
                var den = m.groupValues[2].trim()
                if (num.startsWith("(") && num.endsWith(")")) num = num.substring(1, num.length - 1).trim()
                if (den.startsWith("(") && den.endsWith(")")) den = den.substring(1, den.length - 1).trim()
                "\\frac{$num}{$den}"
            }
        }

        return s
    }

    private fun updateVariableChips(rawText: String) {
        val vars = if (!rawText.contains("=")) emptyList() else extractVariables(rawText)
        if (vars == lastVarChipList) return
        lastVarChipList = vars

        layoutVarChips.removeAllViews()
        if (vars.isEmpty()) return

        val isDark = ThemeManager.isDarkTheme(this)
        val label = TextView(this).apply {
            this.text = "Найти: "
            setTextColor(if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#475569"))
            textSize = 11f
            setPadding(0, 0, 4, 0)
        }
        layoutVarChips.addView(label)

        for (v in vars) {
            val chip = MaterialButton(this).apply {
                this.text = v
                textSize = 12f
                setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#FFFFFF")
                )
                cornerRadius = 12
                insetTop = 0
                insetBottom = 0
                setPadding(16, 0, 16, 0)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(3, 0, 3, 0)
                }
                layoutParams = params
                stateListAnimator = null
                setOnTouchListener { vView, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            triggerKeyHaptic(vView)
                            vView.animate().scaleX(0.96f).scaleY(0.96f).setDuration(35).start()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            vView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(35).start()
                        }
                    }
                    false
                }
                setOnClickListener {
                    solveCurrentEquation(v)
                }
            }
            layoutVarChips.addView(chip)
        }
    }

    private fun extractVariables(text: String): List<String> {
        val reserved = setOf(
            "sqrt", "cbrt", "sin", "cos", "tan", "asin", "acos", "atan", "log", "ln", "fact", "Ans", "pi", "π",
            "Nₐ", "Na", "c", "h", "g", "G", "k", "k_B", "kB", "R", "ε₀", "e₀", "eps0", "μ₀", "mu0",
            "qₑ", "qe", "mₑ", "me", "mₚ", "mp", "e", "comb", "perm"
        )
        val reservedWords = listOf(
            "asin", "acos", "atan", "cbrt", "sqrt", "fact", "sin", "cos", "tan", "log", "ln", "pi", "Ans",
            "eps0", "mu0", "k_B", "kB", "Nₐ", "Na", "e₀", "ε₀", "μ₀", "mₑ", "me", "mₚ", "mp", "qₑ", "qe",
            "comb", "perm"
        )

        val resultVars = mutableListOf<String>()
        val regex = Regex("""[a-zA-Z\u03B1-\u03C9\u2090-\u209C\u2080-\u2089][a-zA-Z0-9_\u03B1-\u03C9\u2090-\u209C\u2080-\u2089]*""")

        val matches = regex.findAll(text).map { it.value }.toList()
        for (m in matches) {
            if (m in reserved) continue

            var k = 0
            while (k < m.length) {
                var matchedRw: String? = null
                for (rw in reservedWords) {
                    if (m.startsWith(rw, k)) {
                        matchedRw = rw
                        break
                    }
                }
                if (matchedRw != null) {
                    k += matchedRw.length
                } else {
                    val charVar = m[k].toString()
                    if (charVar !in reserved && charVar.any { it.isLetter() }) {
                        resultVars.add(charVar)
                    }
                    k++
                }
            }
        }

        return resultVars.distinct()
    }
}
