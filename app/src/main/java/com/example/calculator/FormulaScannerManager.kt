package com.example.calculator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class FormulaScannerManager(
    private val activity: AppCompatActivity,
    private val rootLayout: View,
    private val onHaptic: (View) -> Unit,
    private val onRequestCameraPermission: () -> Unit,
    private val onRequestGalleryImage: () -> Unit,
    private val onCloseScanner: () -> Unit,
    private val onSendFormula: (ScanTarget, String) -> Unit
) {

    val layoutFormulaScanner: View = rootLayout.findViewById(R.id.layoutFormulaScanner)

    // Stage A views
    private val layoutScannerCameraStage: View = rootLayout.findViewById(R.id.layoutScannerCameraStage)
    private val scannerPreviewView: PreviewView = rootLayout.findViewById(R.id.scannerPreviewView)
    private val scannerCropOverlay: CropOverlayView = rootLayout.findViewById(R.id.scannerCropOverlay)
    private val btnScannerBack: TextView = rootLayout.findViewById(R.id.btnScannerBack)
    private val tvScannerTitle: TextView = rootLayout.findViewById(R.id.tvScannerTitle)
    private val btnScannerZoom: TextView = rootLayout.findViewById(R.id.btnScannerZoom)
    private val btnScannerFlash: TextView = rootLayout.findViewById(R.id.btnScannerFlash)
    private val tvScannerHint: TextView = rootLayout.findViewById(R.id.tvScannerHint)
    private val btnScannerGallery: TextView = rootLayout.findViewById(R.id.btnScannerGallery)
    private val btnScannerCapture: TextView = rootLayout.findViewById(R.id.btnScannerCapture)
    private val btnScannerModelInfo: TextView = rootLayout.findViewById(R.id.btnScannerModelInfo)
    private val layoutScannerModeToggle: View = rootLayout.findViewById(R.id.layoutScannerModeToggle)
    private val btnScanModePrinted: TextView = rootLayout.findViewById(R.id.btnScanModePrinted)
    private val btnScanModeHandwritten: TextView = rootLayout.findViewById(R.id.btnScanModeHandwritten)

    // Stage B views
    private val layoutScannerDownloadStage: View = rootLayout.findViewById(R.id.layoutScannerDownloadStage)
    private val tvScannerDownloadTitle: TextView = rootLayout.findViewById(R.id.tvScannerDownloadTitle)
    private val tvScannerDownloadDesc: TextView = rootLayout.findViewById(R.id.tvScannerDownloadDesc)
    private val pbScannerDownload: ProgressBar = rootLayout.findViewById(R.id.pbScannerDownload)
    private val tvScannerDownloadStatus: TextView = rootLayout.findViewById(R.id.tvScannerDownloadStatus)
    private val btnScannerDownloadCancel: MaterialButton = rootLayout.findViewById(R.id.btnScannerDownloadCancel)
    private val btnScannerDownloadStart: MaterialButton = rootLayout.findViewById(R.id.btnScannerDownloadStart)

    // Stage C views
    private val layoutScannerReviewStage: View = rootLayout.findViewById(R.id.layoutScannerReviewStage)
    private val btnScannerRetake: TextView = rootLayout.findViewById(R.id.btnScannerRetake)
    private val tvScannerReviewTitle: TextView = rootLayout.findViewById(R.id.tvScannerReviewTitle)
    private val btnScannerReviewClose: TextView = rootLayout.findViewById(R.id.btnScannerReviewClose)
    private val ivScannedCrop: ImageView = rootLayout.findViewById(R.id.ivScannedCrop)
    private val tvScannedCropModeBadge: TextView = rootLayout.findViewById(R.id.tvScannedCropModeBadge)
    private val tvScannerRecognizing: TextView = rootLayout.findViewById(R.id.tvScannerRecognizing)
    private val btnScannerToggleKeypad: TextView = rootLayout.findViewById(R.id.btnScannerToggleKeypad)
    private val scannerCasioDisplay: CasioDisplayView = rootLayout.findViewById(R.id.scannerCasioDisplay)
    private val tvScannerRawLatex: TextView = rootLayout.findViewById(R.id.tvScannerRawLatex)
    private val tvScannerDebugStats: TextView = rootLayout.findViewById(R.id.tvScannerDebugStats)
    private val layoutScannerEditKeypad: LinearLayout = rootLayout.findViewById(R.id.layoutScannerEditKeypad)
    private val layoutScannerTargets: LinearLayout = rootLayout.findViewById(R.id.layoutScannerTargets)
    private val btnSendToCalculator: MaterialButton = rootLayout.findViewById(R.id.btnSendToCalculator)
    private val btnSendToInequality: MaterialButton = rootLayout.findViewById(R.id.btnSendToInequality)
    private val btnSendToTrig: MaterialButton = rootLayout.findViewById(R.id.btnSendToTrig)

    // Stage Gallery Crop views
    private val layoutScannerGalleryCropStage: View = rootLayout.findViewById(R.id.layoutScannerGalleryCropStage)
    private val layoutGalleryCropHeader: View = rootLayout.findViewById(R.id.layoutGalleryCropHeader)
    private val layoutGalleryCropFooter: View = rootLayout.findViewById(R.id.layoutGalleryCropFooter)
    private val btnGalleryCropBack: TextView = rootLayout.findViewById(R.id.btnGalleryCropBack)
    private val tvGalleryCropTitle: TextView = rootLayout.findViewById(R.id.tvGalleryCropTitle)
    private val btnGalleryCropRepick: TextView = rootLayout.findViewById(R.id.btnGalleryCropRepick)
    private val ivGalleryCropSource: ImageView = rootLayout.findViewById(R.id.ivGalleryCropSource)
    private val galleryCropOverlay: CropOverlayView = rootLayout.findViewById(R.id.galleryCropOverlay)
    private val tvGalleryCropHint: TextView = rootLayout.findViewById(R.id.tvGalleryCropHint)
    private val btnGalleryCropRotate: MaterialButton = rootLayout.findViewById(R.id.btnGalleryCropRotate)
    private val btnGalleryCropConfirm: MaterialButton = rootLayout.findViewById(R.id.btnGalleryCropConfirm)
    private val layoutGalleryModeToggle: View = rootLayout.findViewById(R.id.layoutGalleryModeToggle)
    private val btnGalleryModePrinted: TextView = rootLayout.findViewById(R.id.btnGalleryModePrinted)
    private val btnGalleryModeHandwritten: TextView = rootLayout.findViewById(R.id.btnGalleryModeHandwritten)

    private val modelManager = OcrModelManager(activity)
    private val preprocessor = PrintedFormulaPreprocessor()
    private val ocrEngine = LocalOnnxOcrEngine(modelManager, preprocessor)

    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val downloadExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognitionRequestId = AtomicLong(0L)

    private val prefs = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private var currentScanMode: ScanInputMode = try {
        ScanInputMode.valueOf(prefs.getString("scanner_input_mode", "PRINTED") ?: "PRINTED")
    } catch (_: Exception) {
        ScanInputMode.PRINTED
    }

    private var camera: Camera? = null
    private var isFlashOn = false
    private var isZoom2x = false
    private var pendingGalleryBitmap: Bitmap? = null
    private var lastSourceWasGallery: Boolean = false

    private var lastOriginalCropBitmap: Bitmap? = null
    private var lastPreprocessedBitmap: Bitmap? = null
    private var showingPreprocessedPreview: Boolean = false

    init {
        scannerCasioDisplay.isCompactInputMode = true

        initStageAControls()
        initStageBControls()
        initGalleryCropControls()
        initStageCControls()
        initEditKeypad()

        refreshTheme()
    }

    private fun setScanMode(mode: ScanInputMode) {
        currentScanMode = mode
        prefs.edit().putString("scanner_input_mode", mode.name).apply()
        updateModeButtonsUI()
        updateScannerHint()
    }

    private fun updateScannerHint() {
        tvScannerHint.text = when (currentScanMode) {
            ScanInputMode.PRINTED -> "Наведите рамку на печатную формулу и нажмите Скан"
            ScanInputMode.HANDWRITTEN -> "Тетрадь: убираем клетку (гелевая, синяя ручка, карандаш)"
        }
    }

    private fun updateModeButtonsUI() {
        val isDark = ThemeManager.isDarkTheme(activity)
        val inactiveTextColor = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
        val activeTextColor = Color.WHITE
        val activeBgColor = Color.parseColor("#10B981") // Emerald

        val dp = activity.resources.displayMetrics.density

        fun stylePill(btn: TextView, isActive: Boolean) {
            if (isActive) {
                btn.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6f * dp
                    setColor(activeBgColor)
                }
                btn.setTextColor(activeTextColor)
            } else {
                btn.background = null
                btn.setTextColor(inactiveTextColor)
            }
        }

        val isPrinted = currentScanMode == ScanInputMode.PRINTED
        stylePill(btnScanModePrinted, isPrinted)
        stylePill(btnScanModeHandwritten, !isPrinted)
        stylePill(btnGalleryModePrinted, isPrinted)
        stylePill(btnGalleryModeHandwritten, !isPrinted)

        val containerBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * dp
            setColor(if (isDark) Color.parseColor("#CC1E293B") else Color.parseColor("#CCE2E8F0"))
        }
        layoutScannerModeToggle.background = containerBg
        layoutGalleryModeToggle.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * dp
            setColor(if (isDark) Color.parseColor("#CC1E293B") else Color.parseColor("#CCE2E8F0"))
        }
    }

    private fun initStageAControls() {
        btnScanModePrinted.bindCrispKey(isHeavy = false) {
            setScanMode(ScanInputMode.PRINTED)
        }

        btnScanModeHandwritten.bindCrispKey(isHeavy = false) {
            setScanMode(ScanInputMode.HANDWRITTEN)
        }

        btnScannerBack.bindCrispKey(isHeavy = false) {
            closeScanner()
        }

        btnScannerZoom.bindCrispKey(isHeavy = false) {
            val cam = camera ?: return@bindCrispKey
            isZoom2x = !isZoom2x
            val zoomState = cam.cameraInfo.zoomState.value
            val minZoom = zoomState?.minZoomRatio ?: 1.0f
            val maxZoom = zoomState?.maxZoomRatio ?: 2.0f
            val targetRatio = (if (isZoom2x) 2.0f else 1.0f).coerceIn(minZoom, maxZoom)
            cam.cameraControl.setZoomRatio(targetRatio)
            updateZoomButtonState()
        }

        scannerCropOverlay.onTapToFocus = { x, y ->
            triggerTapToFocus(x, y)
        }

        btnScannerFlash.bindCrispKey(isHeavy = false) {
            val cam = camera ?: return@bindCrispKey
            isFlashOn = !isFlashOn
            cam.cameraControl.enableTorch(isFlashOn)
            updateFlashButtonState()
        }

        btnScannerCapture.bindCrispKey(isHeavy = true) {
            lastSourceWasGallery = false
            pendingGalleryBitmap = null
            val frameBitmap = scannerPreviewView.bitmap
            if (frameBitmap != null) {
                val cropRect = scannerCropOverlay.getNormalizedCropRect()
                val croppedBitmap = preprocessor.cropByNormalizedRect(frameBitmap, cropRect)
                startRecognition(croppedBitmap)
            } else {
                Toast.makeText(activity, "Камера ещё не готова, попробуйте снова", Toast.LENGTH_SHORT).show()
            }
        }

        btnScannerGallery.bindCrispKey(isHeavy = false) {
            onRequestGalleryImage()
        }

        btnScannerModelInfo.bindCrispKey(isHeavy = false) {
            val sizeMb = modelManager.getInstalledSizeMb()
            AlertDialog.Builder(activity)
                .setTitle("Офлайн-сканер формул")
                .setMessage("Офлайн-модель Pix2Text-MFR 1.5\nРазмер на устройстве: $sizeMb МБ")
                .setPositiveButton("Удалить модель") { _, _ ->
                    ocrEngine.close()
                    modelManager.deleteModel()
                    Toast.makeText(activity, "Модель сканера удалена", Toast.LENGTH_SHORT).show()
                    closeScanner()
                }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    private fun initStageBControls() {
        btnScannerDownloadCancel.bindCrispKey(isHeavy = false) {
            modelManager.cancelDownload()
            closeScanner()
        }

        btnScannerDownloadStart.bindCrispKey(isHeavy = true) {
            btnScannerDownloadStart.isEnabled = false
            btnScannerDownloadCancel.isEnabled = true
            pbScannerDownload.visibility = View.VISIBLE
            tvScannerDownloadStatus.visibility = View.VISIBLE
            pbScannerDownload.progress = 0
            tvScannerDownloadStatus.text = "Подготовка загрузки..."

            downloadExecutor.execute {
                val res = modelManager.downloadModelBlocking { p ->
                    mainHandler.post {
                        pbScannerDownload.progress = p.percent
                        tvScannerDownloadStatus.text = "${p.currentFileLabel} (${p.percent}%)"
                    }
                }

                mainHandler.post {
                    if (res.isSuccess) {
                        layoutScannerDownloadStage.visibility = View.GONE
                        Toast.makeText(activity, "Офлайн-сканер установлен!", Toast.LENGTH_SHORT).show()
                        checkAndStartCamera()
                    } else {
                        val err = res.exceptionOrNull()
                        btnScannerDownloadStart.isEnabled = true
                        if (err !is CancellationException) {
                            tvScannerDownloadStatus.text = "Ошибка загрузки: ${err?.localizedMessage ?: "проверьте интернет"}"
                            tvScannerDownloadStatus.setTextColor(Color.parseColor("#EF4444"))
                        }
                    }
                }
            }
        }
    }

    private fun initGalleryCropControls() {
        btnGalleryModePrinted.bindCrispKey(isHeavy = false) {
            setScanMode(ScanInputMode.PRINTED)
        }

        btnGalleryModeHandwritten.bindCrispKey(isHeavy = false) {
            setScanMode(ScanInputMode.HANDWRITTEN)
        }

        btnGalleryCropRotate.bindCrispKey(isHeavy = false) {
            rotatePendingGalleryBitmap()
        }

        btnGalleryCropConfirm.bindCrispKey(isHeavy = true) {
            val bmp = pendingGalleryBitmap ?: return@bindCrispKey
            val croppedBitmap = preprocessor.cropByNormalizedRect(
                bmp,
                galleryCropOverlay.getNormalizedCropRectInImage()
            )
            layoutScannerGalleryCropStage.visibility = View.GONE
            startRecognition(croppedBitmap)
        }

        btnGalleryCropBack.bindCrispKey(isHeavy = false) {
            pendingGalleryBitmap = null
            lastSourceWasGallery = false
            layoutScannerGalleryCropStage.visibility = View.GONE
            layoutScannerCameraStage.visibility = View.VISIBLE
            checkAndStartCamera()
        }

        btnGalleryCropRepick.bindCrispKey(isHeavy = false) {
            onRequestGalleryImage()
        }
    }

    private fun initStageCControls() {
        ivScannedCrop.setOnClickListener {
            val preprocessed = lastPreprocessedBitmap ?: return@setOnClickListener
            val original = lastOriginalCropBitmap ?: return@setOnClickListener
            showingPreprocessedPreview = !showingPreprocessedPreview
            onHaptic(it)
            if (showingPreprocessedPreview) {
                ivScannedCrop.setImageBitmap(preprocessed)
                tvScannedCropModeBadge.text = "Вид ИИ · тап: оригинал"
            } else {
                ivScannedCrop.setImageBitmap(original)
                tvScannedCropModeBadge.text = "Оригинал · тап: вид ИИ"
            }
        }

        btnScannerRetake.bindCrispKey(isHeavy = false) {
            if (lastSourceWasGallery && pendingGalleryBitmap != null) {
                layoutScannerReviewStage.visibility = View.GONE
                layoutScannerGalleryCropStage.visibility = View.VISIBLE
            } else {
                layoutScannerReviewStage.visibility = View.GONE
                layoutScannerCameraStage.visibility = View.VISIBLE
                checkAndStartCamera()
            }
        }

        btnScannerReviewClose.bindCrispKey(isHeavy = false) {
            closeScanner()
        }

        btnScannerToggleKeypad.bindCrispKey(isHeavy = false) {
            if (layoutScannerEditKeypad.visibility == View.VISIBLE) {
                layoutScannerEditKeypad.visibility = View.GONE
                btnScannerToggleKeypad.text = "⌨"
            } else {
                layoutScannerEditKeypad.visibility = View.VISIBLE
                btnScannerToggleKeypad.text = "▼"
            }
        }

        tvScannerRawLatex.isClickable = true
        tvScannerRawLatex.isFocusable = true
        tvScannerRawLatex.setOnClickListener {
            if (tvScannerDebugStats.text.isNotBlank()) {
                onHaptic(it)
                tvScannerDebugStats.visibility = if (tvScannerDebugStats.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        btnSendToCalculator.bindCrispKey(isHeavy = true) {
            sendFormulaToTarget(ScanTarget.CALCULATOR)
        }

        btnSendToInequality.bindCrispKey(isHeavy = true) {
            sendFormulaToTarget(ScanTarget.INEQUALITY)
        }

        btnSendToTrig.bindCrispKey(isHeavy = true) {
            sendFormulaToTarget(ScanTarget.TRIG)
        }
    }

    private fun initEditKeypad() {
        rootLayout.findViewById<View>(R.id.btnScannerKeyLeft)?.bindRepeatingCrispKey(isHeavy = false) {
            val oldContainer = scannerCasioDisplay.currentContainer
            val oldCursor = scannerCasioDisplay.cursorIndex
            scannerCasioDisplay.moveLeft()
            scannerCasioDisplay.currentContainer !== oldContainer || scannerCasioDisplay.cursorIndex != oldCursor
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyRight)?.bindRepeatingCrispKey(isHeavy = false) {
            val oldContainer = scannerCasioDisplay.currentContainer
            val oldCursor = scannerCasioDisplay.cursorIndex
            scannerCasioDisplay.moveRight()
            scannerCasioDisplay.currentContainer !== oldContainer || scannerCasioDisplay.cursorIndex != oldCursor
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyDel)?.bindRepeatingCrispKey(isHeavy = true) {
            val oldExpr = scannerCasioDisplay.getRawExpression()
            val oldContainer = scannerCasioDisplay.currentContainer
            val oldCursor = scannerCasioDisplay.cursorIndex
            scannerCasioDisplay.deleteAtCursor()
            scannerCasioDisplay.getRawExpression() != oldExpr || scannerCasioDisplay.currentContainer !== oldContainer || scannerCasioDisplay.cursorIndex != oldCursor
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyAc)?.bindCrispKey(isHeavy = true) {
            scannerCasioDisplay.clearAll()
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyUp)?.bindCrispKey(isHeavy = false) {
            scannerCasioDisplay.jumpUp()
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyDown)?.bindCrispKey(isHeavy = false) {
            scannerCasioDisplay.jumpDown()
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyFrac)?.bindCrispKey(isHeavy = false) {
            scannerCasioDisplay.insertFraction()
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeySqrt)?.bindCrispKey(isHeavy = false) {
            scannerCasioDisplay.insertSqrt()
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyPow)?.bindCrispKey(isHeavy = false) {
            scannerCasioDisplay.insertSuperscript()
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeySq)?.bindCrispKey(isHeavy = false) {
            scannerCasioDisplay.insertSuperscript("2")
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyLog)?.bindCrispKey(isHeavy = false) {
            scannerCasioDisplay.insertLogBase()
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyLn)?.bindCrispKey(isHeavy = false) {
            scannerCasioDisplay.insertText("ln(")
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyCtg)?.bindCrispKey(isHeavy = false) {
            scannerCasioDisplay.insertText("ctg(")
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyLt)?.bindCrispKey(isHeavy = false) {
            scannerCasioDisplay.insertText("<")
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyGt)?.bindCrispKey(isHeavy = false) {
            scannerCasioDisplay.insertText(">")
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyPi)?.bindCrispKey(isHeavy = false) {
            scannerCasioDisplay.insertText("π")
        }

        rootLayout.findViewById<View>(R.id.btnScannerKeyAbs)?.bindCrispKey(isHeavy = false) {
            scannerCasioDisplay.insertText("|")
            scannerCasioDisplay.insertText("|")
            scannerCasioDisplay.moveLeft()
        }

        val textKeys = mapOf(
            R.id.btnScannerKeyLe to "≤",
            R.id.btnScannerKeyGe to "≥",
            R.id.btnScannerKeyEq to "=",
            R.id.btnScannerKeyX to "x",
            R.id.btnScannerKeyParenOpen to "(",
            R.id.btnScannerKeyParenClose to ")",
            R.id.btnScannerKey1 to "1",
            R.id.btnScannerKey2 to "2",
            R.id.btnScannerKey3 to "3",
            R.id.btnScannerKey4 to "4",
            R.id.btnScannerKey5 to "5",
            R.id.btnScannerKeyPlus to "+",
            R.id.btnScannerKeySub to "-",
            R.id.btnScannerKeyMul to "*",
            R.id.btnScannerKeyDiv to "/",
            R.id.btnScannerKey6 to "6",
            R.id.btnScannerKey7 to "7",
            R.id.btnScannerKey8 to "8",
            R.id.btnScannerKey9 to "9",
            R.id.btnScannerKey0 to "0",
            R.id.btnScannerKeyDot to ".",
            R.id.btnScannerKeySin to "sin(",
            R.id.btnScannerKeyCos to "cos(",
            R.id.btnScannerKeyTan to "tan("
        )

        for ((id, text) in textKeys) {
            rootLayout.findViewById<View>(id)?.bindCrispKey(isHeavy = false) {
                scannerCasioDisplay.insertText(text)
            }
        }
    }

    fun openScanner() {
        layoutFormulaScanner.visibility = View.VISIBLE
        layoutFormulaScanner.bringToFront()
        layoutScannerGalleryCropStage.visibility = View.GONE
        pendingGalleryBitmap = null
        lastSourceWasGallery = false

        updateModeButtonsUI()
        updateScannerHint()

        if (!modelManager.isModelInstalled()) {
            layoutScannerDownloadStage.visibility = View.VISIBLE
            layoutScannerReviewStage.visibility = View.GONE
            layoutScannerCameraStage.visibility = View.VISIBLE
            pbScannerDownload.visibility = View.GONE
            tvScannerDownloadStatus.visibility = View.GONE
            btnScannerDownloadStart.isEnabled = true
            btnScannerDownloadCancel.isEnabled = true
        } else {
            layoutScannerDownloadStage.visibility = View.GONE
            layoutScannerReviewStage.visibility = View.GONE
            layoutScannerCameraStage.visibility = View.VISIBLE
            checkAndStartCamera()
        }
    }

    private fun checkAndStartCamera() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            onRequestCameraPermission()
            Toast.makeText(activity, "Разрешите доступ к камере или выберите фото из галереи", Toast.LENGTH_LONG).show()
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(activity, "Разрешите доступ к камере или выберите фото из галереи", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateZoomButtonState() {
        if (isZoom2x) {
            btnScannerZoom.text = "2x"
            btnScannerZoom.setTextColor(Color.parseColor("#10B981"))
        } else {
            btnScannerZoom.text = "1x"
            btnScannerZoom.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun triggerTapToFocus(x: Float, y: Float) {
        val cam = camera ?: return
        onHaptic(scannerCropOverlay)
        val point = scannerPreviewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(scannerPreviewView.surfaceProvider)
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(activity, cameraSelector, preview)
                isFlashOn = false
                isZoom2x = false
                updateFlashButtonState()
                updateZoomButtonState()
            } catch (e: Exception) {
                Toast.makeText(activity, "Не удалось запустить камеру: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun stopCamera() {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(activity).get()
            cameraProvider.unbindAll()
        } catch (_: Exception) {}
        camera = null
        isFlashOn = false
        isZoom2x = false
        updateFlashButtonState()
        updateZoomButtonState()
    }

    private fun updateFlashButtonState() {
        if (isFlashOn) {
            btnScannerFlash.setTextColor(Color.parseColor("#F59E0B"))
        } else {
            btnScannerFlash.setTextColor(Color.parseColor("#64748B"))
        }
    }

    private fun updateGalleryImageBounds(bitmap: Bitmap) {
        val vw = ivGalleryCropSource.width.toFloat()
        val vh = ivGalleryCropSource.height.toFloat()
        if (vw > 0f && vh > 0f && bitmap.width > 0 && bitmap.height > 0) {
            val scale = minOf(vw / bitmap.width.toFloat(), vh / bitmap.height.toFloat())
            val drawnW = bitmap.width * scale
            val drawnH = bitmap.height * scale
            val left = (vw - drawnW) / 2f
            val top = (vh - drawnH) / 2f
            galleryCropOverlay.setImageBounds(RectF(left, top, left + drawnW, top + drawnH))
        }
    }

    private fun rotatePendingGalleryBitmap() {
        val oldBmp = pendingGalleryBitmap ?: return
        val matrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(oldBmp, 0, 0, oldBmp.width, oldBmp.height, matrix, true)
        pendingGalleryBitmap = rotated
        ivGalleryCropSource.setImageBitmap(rotated)
        ivGalleryCropSource.post {
            updateGalleryImageBounds(rotated)
        }
        if (rotated !== oldBmp) {
            oldBmp.recycle()
        }
    }

    fun onGalleryImagePicked(uri: Uri) {
        val bitmap = try {
            decodeSampledBitmapFromUri(uri, maxDimension = 1600)
        } catch (_: Exception) {
            Toast.makeText(activity, "Не удалось загрузить изображение", Toast.LENGTH_SHORT).show()
            return
        }
        if (bitmap != null) {
            mainHandler.post {
                stopCamera()
                pendingGalleryBitmap = bitmap
                lastSourceWasGallery = true
                layoutScannerCameraStage.visibility = View.GONE
                layoutScannerDownloadStage.visibility = View.GONE
                layoutScannerReviewStage.visibility = View.GONE
                layoutScannerGalleryCropStage.visibility = View.VISIBLE

                ivGalleryCropSource.setImageBitmap(bitmap)
                ivGalleryCropSource.post {
                    updateGalleryImageBounds(bitmap)
                }
            }
        }
    }

    private fun decodeSampledBitmapFromUri(uri: Uri, maxDimension: Int): Bitmap? {
        val resolver = activity.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val maxSide = maxOf(w, h)
                if (maxSide > maxDimension) {
                    val sample = (maxSide.toFloat() / maxDimension.toFloat()).roundToInt().coerceAtLeast(1)
                    decoder.setTargetSampleSize(sample)
                }
            }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            val w = options.outWidth
            val h = options.outHeight
            val maxSide = maxOf(w, h)
            var sample = 1
            if (maxSide > maxDimension) {
                sample = (maxSide.toFloat() / maxDimension.toFloat()).roundToInt().coerceAtLeast(1)
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        }
    }

    private fun startRecognition(croppedBitmap: Bitmap) {
        stopCamera()

        lastOriginalCropBitmap = croppedBitmap
        lastPreprocessedBitmap = null
        showingPreprocessedPreview = false
        tvScannedCropModeBadge.text = "Оригинал · тап: вид ИИ"
        tvScannerDebugStats.visibility = View.GONE
        tvScannerDebugStats.text = ""

        layoutScannerCameraStage.visibility = View.GONE
        layoutScannerReviewStage.visibility = View.VISIBLE
        btnScannerToggleKeypad.text = if (layoutScannerEditKeypad.visibility == View.VISIBLE) "▼" else "⌨"
        ivScannedCrop.setImageBitmap(croppedBitmap)
        scannerCasioDisplay.clearAll()
        tvScannerRecognizing.visibility = View.VISIBLE
        tvScannerRawLatex.text = ""
        setTargetButtonsEnabled(false)

        val requestId = recognitionRequestId.incrementAndGet()

        ocrExecutor.execute {
            val result = try {
                val ocrOutput = ocrEngine.recognizeWithDebug(croppedBitmap, currentScanMode)
                val converted = LatexToCasConverter.convert(ocrOutput.rawLatex)
                Result.success(Pair(ocrOutput, converted))
            } catch (e: Exception) {
                Result.failure(e)
            }

            mainHandler.post {
                if (requestId == recognitionRequestId.get()) {
                    tvScannerRecognizing.visibility = View.GONE
                    setTargetButtonsEnabled(true)
                    if (result.isSuccess) {
                        val (ocrOutput, converted) = result.getOrNull()!!
                        lastPreprocessedBitmap = ocrOutput.preprocessedBitmap

                        val d = ocrOutput.debugInfo
                        val modeRu = if (d.mode == ScanInputMode.PRINTED) "Печать" else "Тетрадь"
                        val invSuffix = if (d.wasInverted) " [инв]" else ""
                        val retrySuffix = if (d.usedRetry) " [повт]" else ""
                        val loopSuffix = if (d.stoppedByLoopGuard) " (петля)" else ""
                        val warnSuffix = if (converted.warnings.isNotEmpty()) " | !${converted.warnings.size}" else ""
                        fun fmt(v: Float) = String.format(Locale.US, "%.2f", v)
                        fun fmt1(v: Float) = String.format(Locale.US, "%.1f", v)
                        tvScannerDebugStats.text = "Режим: $modeRu$invSuffix$retrySuffix | Кадр: ${d.cropWidth}×${d.cropHeight} → ${d.preprocessedWidth}×${d.preprocessedHeight}\nСетка: ${d.removedGridH}Г/${d.removedGridV}В | Порог: ${fmt(d.inkMinRatio)}..${fmt(d.upperThreshold)} | Чернила: ${fmt1(d.inkPercent)}%\nВремя: ${d.preprocessMs + d.inferenceMs} мс (препр ${d.preprocessMs}, ИИ ${d.inferenceMs}) | Токенов: ${d.tokenCount}${loopSuffix}${warnSuffix}"
                        tvScannerDebugStats.visibility = View.GONE

                        if (converted.casExpression.isNotBlank()) {
                            scannerCasioDisplay.loadExpression(converted.casExpression)
                            tvScannerRawLatex.text = "LaTeX: ${converted.rawLatex}"
                            highlightTargetButtons(converted.recommendedTarget)
                        } else {
                            tvScannerRawLatex.text = "Не удалось распознать формулу — поправьте вручную или переснимите"
                            highlightTargetButtons(ScanTarget.CALCULATOR)
                        }
                    } else {
                        tvScannerDebugStats.visibility = View.GONE
                        tvScannerRawLatex.text = "Не удалось распознать формулу — поправьте вручную или переснимите"
                        highlightTargetButtons(ScanTarget.CALCULATOR)
                    }
                }
            }
        }
    }

    private fun setTargetButtonsEnabled(enabled: Boolean) {
        btnSendToCalculator.isEnabled = enabled
        btnSendToInequality.isEnabled = enabled
        btnSendToTrig.isEnabled = enabled
    }

    private fun highlightTargetButtons(recommended: ScanTarget) {
        val isDark = ThemeManager.isDarkTheme(activity)

        val highlightColor = Color.parseColor("#10B981") // Emerald
        val normalColorDark = Color.parseColor("#243042")
        val normalColorLight = Color.parseColor("#334155")
        val normalBg = if (isDark) normalColorDark else normalColorLight

        fun updateBtn(btn: MaterialButton, target: ScanTarget, label: String) {
            val isRec = target == recommended
            btn.text = if (isRec) "★ $label" else label
            btn.backgroundTintList = ColorStateList.valueOf(if (isRec) highlightColor else normalBg)
        }

        updateBtn(btnSendToCalculator, ScanTarget.CALCULATOR, "Уравнение")
        updateBtn(btnSendToInequality, ScanTarget.INEQUALITY, "Неравенства")
        updateBtn(btnSendToTrig, ScanTarget.TRIG, "Тригонометрия")
    }

    private fun sendFormulaToTarget(target: ScanTarget) {
        scannerCasioDisplay.autoCloseUnclosedParens()
        val expr = scannerCasioDisplay.getRawExpression().trim()
        if (expr.isEmpty()) {
            Toast.makeText(activity, "Формула пуста", Toast.LENGTH_SHORT).show()
            return
        }
        closeScanner()
        onSendFormula(target, expr)
    }

    fun handleBackPress(): Boolean {
        if (layoutFormulaScanner.visibility == View.VISIBLE) {
            if (layoutScannerReviewStage.visibility == View.VISIBLE) {
                if (lastSourceWasGallery && pendingGalleryBitmap != null) {
                    layoutScannerReviewStage.visibility = View.GONE
                    layoutScannerGalleryCropStage.visibility = View.VISIBLE
                } else {
                    layoutScannerReviewStage.visibility = View.GONE
                    layoutScannerCameraStage.visibility = View.VISIBLE
                    checkAndStartCamera()
                }
            } else if (layoutScannerGalleryCropStage.visibility == View.VISIBLE) {
                pendingGalleryBitmap = null
                lastSourceWasGallery = false
                layoutScannerGalleryCropStage.visibility = View.GONE
                layoutScannerCameraStage.visibility = View.VISIBLE
                checkAndStartCamera()
            } else {
                closeScanner()
            }
            return true
        }
        return false
    }

    fun closeScanner() {
        stopCamera()
        modelManager.cancelDownload()
        layoutScannerGalleryCropStage.visibility = View.GONE
        pendingGalleryBitmap = null
        lastSourceWasGallery = false
        layoutFormulaScanner.visibility = View.GONE
        onCloseScanner()
    }

    fun refreshTheme() {
        val isDark = ThemeManager.isDarkTheme(activity)
        val dp = activity.resources.displayMetrics.density

        val screenBg = if (isDark) ThemeManager.COLOR_DARK_SCREEN else ThemeManager.COLOR_LIGHT_SCREEN
        val panelBg = if (isDark) ThemeManager.COLOR_DARK_PANEL else ThemeManager.COLOR_LIGHT_PANEL
        val borderCol = if (isDark) ThemeManager.COLOR_DARK_BORDER else ThemeManager.COLOR_LIGHT_BORDER
        val textPrimary = if (isDark) ThemeManager.COLOR_DARK_TEXT_PRIMARY else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY
        val textSecondary = if (isDark) ThemeManager.COLOR_DARK_TEXT_SECONDARY else ThemeManager.COLOR_LIGHT_TEXT_SECONDARY

        layoutFormulaScanner.setBackgroundColor(screenBg)
        layoutScannerReviewStage.setBackgroundColor(screenBg)
        layoutScannerGalleryCropStage.setBackgroundColor(screenBg)
        layoutGalleryCropHeader.setBackgroundColor(panelBg)
        layoutGalleryCropFooter.setBackgroundColor(panelBg)

        tvScannerTitle.setTextColor(textPrimary)
        tvScannerReviewTitle.setTextColor(textPrimary)
        tvGalleryCropTitle.setTextColor(textPrimary)
        tvGalleryCropHint.setTextColor(textSecondary)

        val accentLink = if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
        btnScannerBack.setTextColor(accentLink)
        btnScannerRetake.setTextColor(accentLink)
        btnGalleryCropBack.setTextColor(accentLink)
        btnGalleryCropRepick.setTextColor(accentLink)
        btnScannerReviewClose.setTextColor(textSecondary)

        btnGalleryCropRotate.backgroundTintList = ColorStateList.valueOf(
            if (isDark) Color.parseColor("#243042") else Color.parseColor("#E2E8F0")
        )
        btnGalleryCropRotate.setTextColor(
            if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
        )

        // Style toggle keyboard button
        btnScannerToggleKeypad.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * dp
                setColor(if (isDark) ThemeManager.COLOR_DARK_INPUT else ThemeManager.COLOR_LIGHT_INPUT)
            }
            setTextColor(if (isDark) Color.parseColor("#10B981") else Color.parseColor("#059669"))
        }

        // Theme Stage B and Stage C cards
        rootLayout.findViewById<MaterialCardView>(R.id.cardScannerDownload)?.apply {
            setCardBackgroundColor(panelBg)
            strokeColor = borderCol
        }
        rootLayout.findViewById<MaterialCardView>(R.id.cardScannedCrop)?.apply {
            setCardBackgroundColor(panelBg)
            strokeColor = borderCol
        }
        rootLayout.findViewById<MaterialCardView>(R.id.cardScannedFormula)?.apply {
            setCardBackgroundColor(panelBg)
            strokeColor = borderCol
        }

        // Theme text labels across Stage B and Stage C
        tvScannerDownloadTitle.setTextColor(textPrimary)
        tvScannerDownloadDesc.setTextColor(textSecondary)
        rootLayout.findViewById<TextView>(R.id.tvScannerFormulaLabel)?.setTextColor(textSecondary)
        tvScannerRawLatex.setTextColor(textSecondary)
        tvScannerDebugStats.setTextColor(if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#475569"))
        rootLayout.findViewById<TextView>(R.id.tvScannerTargetsLabel)?.setTextColor(textPrimary)

        // Cards inside Review Stage
        val cardReviewHeader = layoutScannerReviewStage.findViewById<View>(R.id.btnScannerRetake)?.parent as? View
        cardReviewHeader?.setBackgroundColor(panelBg)
        layoutScannerTargets.setBackgroundColor(panelBg)

        // CasioDisplayView inside Stage C
        scannerCasioDisplay.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * dp
            setColor(if (isDark) ThemeManager.COLOR_DARK_INPUT else ThemeManager.COLOR_LIGHT_INPUT)
            setStroke((1f * dp).toInt(), borderCol)
        }
        scannerCasioDisplay.setEditorTheme(isDark)

        // Keypad container in Stage C
        layoutScannerEditKeypad.setBackgroundColor(screenBg)

        // Keypad buttons theme
        val digitIds = listOf(
            R.id.btnScannerKey1, R.id.btnScannerKey2, R.id.btnScannerKey3, R.id.btnScannerKey4, R.id.btnScannerKey5,
            R.id.btnScannerKey6, R.id.btnScannerKey7, R.id.btnScannerKey8, R.id.btnScannerKey9, R.id.btnScannerKey0,
            R.id.btnScannerKeyDot
        )
        digitIds.forEach { id ->
            rootLayout.findViewById<Button>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(if (isDark) ThemeManager.COLOR_DARK_KEYPAD_DIGIT else ThemeManager.COLOR_LIGHT_KEYPAD_DIGIT)
                setTextColor(if (isDark) Color.WHITE else ThemeManager.COLOR_LIGHT_TEXT_PRIMARY)
            }
        }

        val funcIds = listOf(
            R.id.btnScannerKeyLe, R.id.btnScannerKeyGe, R.id.btnScannerKeyAbs, R.id.btnScannerKeySin,
            R.id.btnScannerKeyCos, R.id.btnScannerKeyTan, R.id.btnScannerKeyLt, R.id.btnScannerKeyGt, R.id.btnScannerKeyCtg,
            R.id.btnScannerKeyLeft, R.id.btnScannerKeyRight, R.id.btnScannerKeyUp, R.id.btnScannerKeyDown,
            R.id.btnScannerKeyParenOpen, R.id.btnScannerKeyParenClose,
            R.id.btnScannerKeyFrac, R.id.btnScannerKeySqrt, R.id.btnScannerKeyPow, R.id.btnScannerKeySq,
            R.id.btnScannerKeyLog, R.id.btnScannerKeyLn,
            R.id.btnScannerKeyPlus, R.id.btnScannerKeySub, R.id.btnScannerKeyMul, R.id.btnScannerKeyDiv,
            R.id.btnScannerKeyPi
        )
        funcIds.forEach { id ->
            rootLayout.findViewById<Button>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(if (isDark) ThemeManager.COLOR_DARK_KEYPAD_FUNC else ThemeManager.COLOR_LIGHT_KEYPAD_FUNC)
                setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            }
        }

        listOf(R.id.btnScannerKeyX, R.id.btnScannerKeyEq).forEach { id ->
            rootLayout.findViewById<Button>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(if (isDark) ThemeManager.COLOR_DARK_KEYPAD_FUNC else ThemeManager.COLOR_LIGHT_KEYPAD_FUNC)
                setTextColor(if (isDark) Color.parseColor("#F59E0B") else Color.parseColor("#D97706"))
            }
        }

        listOf(R.id.btnScannerKeyDel, R.id.btnScannerKeyAc).forEach { id ->
            rootLayout.findViewById<Button>(id)?.apply {
                backgroundTintList = ColorStateList.valueOf(if (isDark) Color.parseColor("#7F1D1D") else Color.parseColor("#FEE2E2"))
                setTextColor(if (isDark) Color.parseColor("#FCA5A5") else Color.parseColor("#B91C1C"))
            }
        }

        updateModeButtonsUI()

        // Highlight recommended target button if display has text
        val raw = scannerCasioDisplay.getRawExpression()
        if (raw.isNotBlank()) {
            val converted = LatexToCasConverter.convert(raw)
            highlightTargetButtons(converted.recommendedTarget)
        } else {
            highlightTargetButtons(ScanTarget.CALCULATOR)
        }
    }

    fun cleanup() {
        stopCamera()
        modelManager.cancelDownload()
        ocrExecutor.shutdownNow()
        downloadExecutor.shutdownNow()
        try {
            ocrEngine.close()
        } catch (_: Exception) {}
    }
}
