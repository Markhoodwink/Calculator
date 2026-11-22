package com.example.calculator

import android.graphics.Bitmap

/**
 * Recommended application mode for a scanned math formula.
 */
enum class ScanTarget(val titleRu: String) {
    CALCULATOR("Уравнение / Расчёт"),
    INEQUALITY("Неравенства"),
    TRIG("Тригонометрия")
}

/**
 * Input mode hint for OCR engines (reserved for future handwriting grid-removal / segmentation).
 */
enum class ScanInputMode {
    PRINTED,
    HANDWRITTEN
}

/**
 * Debug metrics captured during formula OCR preprocessing and ONNX inference.
 */
data class OcrDebugInfo(
    val mode: ScanInputMode,
    val cropWidth: Int,
    val cropHeight: Int,
    val preprocessedWidth: Int,
    val preprocessedHeight: Int,
    val modelInputWidth: Int,
    val modelInputHeight: Int,
    val removedGridH: Int,
    val removedGridV: Int,
    val inkMinRatio: Float,
    val upperThreshold: Float,
    val inkPercent: Float,
    val preprocessMs: Long,
    val inferenceMs: Long,
    val tokenCount: Int,
    val stoppedByLoopGuard: Boolean,
    val wasInverted: Boolean = false,
    val usedRetry: Boolean = false
)

/**
 * Detailed recognition output containing the decoded LaTeX string, preprocessed bitmap, and telemetry.
 */
data class OcrRecognitionOutput(
    val rawLatex: String,
    val preprocessedBitmap: Bitmap,
    val debugInfo: OcrDebugInfo
)

/**
 * Result of LaTeX translation to native MB-CAL CAS/Display syntax.
 */
data class ConvertedFormula(
    val rawLatex: String,
    val casExpression: String,
    val recommendedTarget: ScanTarget,
    val warnings: List<String> = emptyList()
)

/**
 * Extensible OCR Engine interface for formula recognition.
 */
interface FormulaOcrEngine {
    val engineId: String
    fun isReady(): Boolean
    fun recognizeLatex(rgbFloatInput: FloatArray, width: Int, height: Int): String
    fun recognizeLatex(source: Bitmap): String = recognizeLatex(FloatArray(0), source.width, source.height)
}

/**
 * Extensible preprocessor interface for image normalization before OCR recognition.
 */
interface FormulaImagePreprocessor {
    fun preprocessForOcr(source: Bitmap, mode: ScanInputMode = ScanInputMode.PRINTED): Bitmap
}
