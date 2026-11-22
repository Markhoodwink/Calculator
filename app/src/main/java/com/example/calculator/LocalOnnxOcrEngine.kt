package com.example.calculator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer

data class OcrModelConfig(
    val decoderStartTokenId: Int = 2,
    val eosTokenId: Int = 2,
    val bosTokenId: Int = 0,
    val padTokenId: Int = 1,
    val maxNewTokens: Int = 160
)

data class OcrPreprocessorConfig(
    val targetWidth: Int = 384,
    val targetHeight: Int = 384,
    val mean: FloatArray = floatArrayOf(0.5f, 0.5f, 0.5f),
    val std: FloatArray = floatArrayOf(0.5f, 0.5f, 0.5f)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OcrPreprocessorConfig) return false
        return targetWidth == other.targetWidth &&
                targetHeight == other.targetHeight &&
                mean.contentEquals(other.mean) &&
                std.contentEquals(other.std)
    }

    override fun hashCode(): Int {
        var result = targetWidth
        result = 31 * result + targetHeight
        result = 31 * result + mean.contentHashCode()
        result = 31 * result + std.contentHashCode()
        return result
    }
}

class LocalOnnxOcrEngine(
    private val modelManager: OcrModelManager,
    private val preprocessor: PrintedFormulaPreprocessor = PrintedFormulaPreprocessor()
) : FormulaOcrEngine, AutoCloseable {

    override val engineId: String = "pix2text-mfr-1.5-onnx"

    private val lock = Any()

    private var ortEnv: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: BpeLatexTokenizer? = null

    private var modelConfig: OcrModelConfig = OcrModelConfig()
    private var preprocessorConfig: OcrPreprocessorConfig = OcrPreprocessorConfig()

    private var isInitialized = false

    override fun isReady(): Boolean {
        return modelManager.isModelInstalled()
    }

    fun ensureInitialized() {
        if (isInitialized) return
        synchronized(lock) {
            if (isInitialized) return
            if (!modelManager.isModelInstalled()) {
                throw IllegalStateException("Модели OCR не установлены")
            }

            val modelDir = modelManager.getModelDir()
            val encoderFile = File(modelDir, "encoder_model.onnx")
            val decoderFile = File(modelDir, "decoder_model.onnx")
            val tokenizerFile = File(modelDir, "tokenizer.json")
            val configFile = File(modelDir, "config.json")
            val preprocessorConfigFile = File(modelDir, "preprocessor_config.json")

            if (configFile.exists()) {
                modelConfig = parseConfigParams(configFile.readText(Charsets.UTF_8))
            }
            if (preprocessorConfigFile.exists()) {
                preprocessorConfig = parsePreprocessorParams(preprocessorConfigFile.readText(Charsets.UTF_8))
            }
            if (tokenizerFile.exists()) {
                tokenizer = BpeLatexTokenizer.fromJsonString(tokenizerFile.readText(Charsets.UTF_8))
            }

            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            val encSession: OrtSession
            val decSession: OrtSession

            OrtSession.SessionOptions().use { sessionOptions ->
                val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                sessionOptions.setIntraOpNumThreads(threadCount)
                sessionOptions.setInterOpNumThreads(1)

                try {
                    sessionOptions.addXnnpack(mapOf("intra_op_num_threads" to threadCount.toString()))
                } catch (_: Throwable) {
                    // Fallback cleanly to default CPU EP if XNNPACK is unavailable on this ABI/device
                }

                encSession = env.createSession(encoderFile.absolutePath, sessionOptions)
                decSession = env.createSession(decoderFile.absolutePath, sessionOptions)
            }

            encoderSession = encSession
            decoderSession = decSession

            // Check if encoder session has fixed input dimensions
            try {
                val inputInfo = encSession.inputInfo
                if (inputInfo.isNotEmpty()) {
                    val nodeInfo = inputInfo.values.iterator().next()
                    val info = nodeInfo.info
                    if (info is ai.onnxruntime.TensorInfo) {
                        val shape = info.shape
                        if (shape.size >= 4 && shape[2] > 0 && shape[3] > 0) {
                            preprocessorConfig = preprocessorConfig.copy(
                                targetHeight = shape[2].toInt(),
                                targetWidth = shape[3].toInt()
                            )
                        }
                    }
                }
            } catch (_: Exception) {}

            isInitialized = true
        }
    }

    override fun recognizeLatex(source: Bitmap): String {
        return recognizeWithDebug(source, ScanInputMode.PRINTED).rawLatex
    }

    fun recognizeWithDebug(source: Bitmap, mode: ScanInputMode = ScanInputMode.PRINTED): OcrRecognitionOutput {
        ensureInitialized()

        val pass1 = performSinglePass(source, mode, softAntiMoirePass = false)
        val score1 = scoreRecognitionCandidate(pass1.rawLatex, pass1.debugInfo.stoppedByLoopGuard)

        if (mode == ScanInputMode.PRINTED && (pass1.debugInfo.stoppedByLoopGuard || score1 <= 50)) {
            val pass2 = performSinglePass(source, mode, softAntiMoirePass = true)
            val score2 = scoreRecognitionCandidate(pass2.rawLatex, pass2.debugInfo.stoppedByLoopGuard)
            if (score2 > score1) {
                val combinedDebugInfo = pass2.debugInfo.copy(
                    preprocessMs = pass1.debugInfo.preprocessMs + pass2.debugInfo.preprocessMs,
                    inferenceMs = pass1.debugInfo.inferenceMs + pass2.debugInfo.inferenceMs,
                    usedRetry = true
                )
                return pass2.copy(debugInfo = combinedDebugInfo)
            }
        }

        return pass1
    }

    private fun performSinglePass(
        source: Bitmap,
        mode: ScanInputMode,
        softAntiMoirePass: Boolean
    ): OcrRecognitionOutput {
        val prepResult = preprocessor.preprocessWithStats(source, mode, softAntiMoirePass)
        val processedBitmap = prepResult.bitmap
        val chwTensor = preprocessor.bitmapToChwFloatTensor(
            processedBitmap,
            preprocessorConfig.targetWidth,
            preprocessorConfig.targetHeight,
            preprocessorConfig.mean,
            preprocessorConfig.std
        )

        val env = ortEnv ?: throw IOException("OrtEnvironment не инициализирован")
        val encSession = encoderSession ?: throw IOException("Encoder session не инициализирована")
        val decSession = decoderSession ?: throw IOException("Decoder session не инициализирована")
        val tok = tokenizer ?: throw IOException("Токенизатор не инициализирован")

        val inferenceStart = System.nanoTime() / 1_000_000L

        val encoderInputName = encSession.inputNames.iterator().next()
        val pixelValuesTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(chwTensor),
            longArrayOf(1L, 3L, preprocessorConfig.targetHeight.toLong(), preprocessorConfig.targetWidth.toLong())
        )

        val generatedIds = mutableListOf(modelConfig.decoderStartTokenId)
        val maxTokens = modelConfig.maxNewTokens
        var stoppedByLoopGuard = false
        var rawLatex = ""

        try {
            val encResult = encSession.run(mapOf(encoderInputName to pixelValuesTensor))
            encResult.use { result ->
                var encSeqLen = 1
                var encoderHiddenStatesTensor: OnnxTensor? = null

                if (result.count() > 0) {
                    val tensorObj = try {
                        result.get("last_hidden_state").orElse(null) as? OnnxTensor
                    } catch (_: Exception) {
                        null
                    } ?: (result.get(0) as? OnnxTensor)

                    if (tensorObj != null) {
                        encoderHiddenStatesTensor = tensorObj
                        encSeqLen = (tensorObj.info.shape.getOrNull(1) ?: 1L).toInt()
                    }
                }

                var encAttnTensor: OnnxTensor? = null
                if ("encoder_attention_mask" in decSession.inputNames) {
                    val encAttnArray = LongArray(encSeqLen) { 1L }
                    encAttnTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(encAttnArray),
                        longArrayOf(1L, encSeqLen.toLong())
                    )
                }

                try {
                    for (step in 0 until maxTokens) {
                        val seqLen = generatedIds.size
                        val inputIdsArray = LongArray(seqLen) { i -> generatedIds[i].toLong() }
                        val inputIdsTensor = OnnxTensor.createTensor(
                            env,
                            LongBuffer.wrap(inputIdsArray),
                            longArrayOf(1L, seqLen.toLong())
                        )

                        val decoderInputMap = mutableMapOf<String, OnnxTensor>()
                        decoderInputMap["input_ids"] = inputIdsTensor

                        val encStates = encoderHiddenStatesTensor
                        if ("encoder_hidden_states" in decSession.inputNames && encStates != null) {
                            decoderInputMap["encoder_hidden_states"] = encStates
                        }

                        if (encAttnTensor != null) {
                            decoderInputMap["encoder_attention_mask"] = encAttnTensor
                        }

                        var attnMaskTensor: OnnxTensor? = null
                        if ("attention_mask" in decSession.inputNames) {
                            val attnArray = LongArray(seqLen) { 1L }
                            attnMaskTensor = OnnxTensor.createTensor(
                                env,
                                LongBuffer.wrap(attnArray),
                                longArrayOf(1L, seqLen.toLong())
                            )
                            decoderInputMap["attention_mask"] = attnMaskTensor
                        }

                        var nextId = modelConfig.eosTokenId

                        try {
                            val decResult = decSession.run(decoderInputMap)
                            decResult.use { decRes ->
                                if (decRes.count() > 0) {
                                    val outObj = decRes.get(0)
                                    nextId = extractArgmax(outObj, seqLen)
                                }
                            }
                        } finally {
                            inputIdsTensor.close()
                            attnMaskTensor?.close()
                        }

                        if (nextId == modelConfig.eosTokenId) {
                            break
                        }

                        generatedIds.add(nextId)

                        val loopStart = detectRepetitionLoop(generatedIds)
                        if (loopStart >= 0) {
                            stoppedByLoopGuard = true
                            if (loopStart < 3) {
                                rawLatex = ""
                            } else {
                                val validIds = generatedIds.subList(0, loopStart)
                                rawLatex = tok.decode(validIds, skipSpecialTokens = true)
                            }
                            break
                        }
                    }
                } finally {
                    encAttnTensor?.close()
                }

                if (!stoppedByLoopGuard) {
                    rawLatex = tok.decode(generatedIds, skipSpecialTokens = true)
                }
            }
        } finally {
            pixelValuesTensor.close()
        }

        val inferenceMs = (System.nanoTime() / 1_000_000L) - inferenceStart
        val tokenCount = (generatedIds.size - 1).coerceAtLeast(0)

        val debugInfo = OcrDebugInfo(
            mode = mode,
            cropWidth = source.width,
            cropHeight = source.height,
            preprocessedWidth = processedBitmap.width,
            preprocessedHeight = processedBitmap.height,
            modelInputWidth = preprocessorConfig.targetWidth,
            modelInputHeight = preprocessorConfig.targetHeight,
            removedGridH = prepResult.removedGridH,
            removedGridV = prepResult.removedGridV,
            inkMinRatio = prepResult.inkMinRatio,
            upperThreshold = prepResult.upperThreshold,
            inkPercent = prepResult.inkPercent,
            preprocessMs = prepResult.durationMs,
            inferenceMs = inferenceMs,
            tokenCount = tokenCount,
            stoppedByLoopGuard = stoppedByLoopGuard,
            wasInverted = prepResult.wasInverted,
            usedRetry = false
        )

        return OcrRecognitionOutput(
            rawLatex = rawLatex,
            preprocessedBitmap = processedBitmap,
            debugInfo = debugInfo
        )
    }

    override fun recognizeLatex(rgbFloatInput: FloatArray, width: Int, height: Int): String {
        ensureInitialized()

        val env = ortEnv ?: throw IOException("OrtEnvironment не инициализирован")
        val encSession = encoderSession ?: throw IOException("Encoder session не инициализирована")
        val decSession = decoderSession ?: throw IOException("Decoder session не инициализирована")
        val tok = tokenizer ?: throw IOException("Токенизатор не инициализирован")

        val encoderInputName = encSession.inputNames.iterator().next()
        val pixelValuesTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(rgbFloatInput),
            longArrayOf(1L, 3L, height.toLong(), width.toLong())
        )

        val generatedIds = mutableListOf(modelConfig.decoderStartTokenId)
        val maxTokens = modelConfig.maxNewTokens

        try {
            val encResult = encSession.run(mapOf(encoderInputName to pixelValuesTensor))
            encResult.use { result ->
                var encSeqLen = 1
                var encoderHiddenStatesTensor: OnnxTensor? = null

                if (result.count() > 0) {
                    val tensorObj = try {
                        result.get("last_hidden_state").orElse(null) as? OnnxTensor
                    } catch (_: Exception) {
                        null
                    } ?: (result.get(0) as? OnnxTensor)

                    if (tensorObj != null) {
                        encoderHiddenStatesTensor = tensorObj
                        encSeqLen = (tensorObj.info.shape.getOrNull(1) ?: 1L).toInt()
                    }
                }

                var encAttnTensor: OnnxTensor? = null
                if ("encoder_attention_mask" in decSession.inputNames) {
                    val encAttnArray = LongArray(encSeqLen) { 1L }
                    encAttnTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(encAttnArray),
                        longArrayOf(1L, encSeqLen.toLong())
                    )
                }

                try {
                    for (step in 0 until maxTokens) {
                        val seqLen = generatedIds.size
                        val inputIdsArray = LongArray(seqLen) { i -> generatedIds[i].toLong() }
                        val inputIdsTensor = OnnxTensor.createTensor(
                            env,
                            LongBuffer.wrap(inputIdsArray),
                            longArrayOf(1L, seqLen.toLong())
                        )

                        val decoderInputMap = mutableMapOf<String, OnnxTensor>()
                        decoderInputMap["input_ids"] = inputIdsTensor

                        val encStates = encoderHiddenStatesTensor
                        if ("encoder_hidden_states" in decSession.inputNames && encStates != null) {
                            decoderInputMap["encoder_hidden_states"] = encStates
                        }

                        if (encAttnTensor != null) {
                            decoderInputMap["encoder_attention_mask"] = encAttnTensor
                        }

                        var attnMaskTensor: OnnxTensor? = null
                        if ("attention_mask" in decSession.inputNames) {
                            val attnArray = LongArray(seqLen) { 1L }
                            attnMaskTensor = OnnxTensor.createTensor(
                                env,
                                LongBuffer.wrap(attnArray),
                                longArrayOf(1L, seqLen.toLong())
                            )
                            decoderInputMap["attention_mask"] = attnMaskTensor
                        }

                        var nextId = modelConfig.eosTokenId

                        try {
                            val decResult = decSession.run(decoderInputMap)
                            decResult.use { decRes ->
                                if (decRes.count() > 0) {
                                    val outObj = decRes.get(0)
                                    nextId = extractArgmax(outObj, seqLen)
                                }
                            }
                        } finally {
                            inputIdsTensor.close()
                            attnMaskTensor?.close()
                        }

                        if (nextId == modelConfig.eosTokenId) {
                            break
                        }

                        generatedIds.add(nextId)

                        val loopStart = detectRepetitionLoop(generatedIds)
                        if (loopStart >= 0) {
                            if (loopStart < 3) {
                                return ""
                            }
                            val validIds = generatedIds.subList(0, loopStart)
                            return tok.decode(validIds, skipSpecialTokens = true)
                        }
                    }
                } finally {
                    encAttnTensor?.close()
                }
            }
        } finally {
            pixelValuesTensor.close()
        }

        return tok.decode(generatedIds, skipSpecialTokens = true)
    }

    private fun extractArgmax(logitsObj: Any?, seqLen: Int): Int {
        if (logitsObj is OnnxTensor) {
            try {
                val fb = logitsObj.floatBuffer
                if (fb != null) {
                    val total = fb.capacity()
                    val vocabSize = (total / seqLen).coerceAtLeast(1)
                    val offset = (seqLen - 1) * vocabSize
                    var maxIdx = 0
                    var maxVal = Float.NEGATIVE_INFINITY
                    for (i in 0 until vocabSize) {
                        val idx = offset + i
                        if (idx < total) {
                            val v = fb.get(idx)
                            if (v > maxVal) {
                                maxVal = v
                                maxIdx = i
                            }
                        }
                    }
                    return maxIdx
                }
            } catch (_: Exception) {}
            return extractArgmax(logitsObj.value, seqLen)
        }
        if (logitsObj is FloatBuffer) {
            val total = logitsObj.capacity()
            val vocabSize = (total / seqLen).coerceAtLeast(1)
            val offset = (seqLen - 1) * vocabSize
            var maxIdx = 0
            var maxVal = Float.NEGATIVE_INFINITY
            for (i in 0 until vocabSize) {
                val idx = offset + i
                if (idx < total) {
                    val v = logitsObj.get(idx)
                    if (v > maxVal) {
                        maxVal = v
                        maxIdx = i
                    }
                }
            }
            return maxIdx
        }
        if (logitsObj is Array<*>) {
            if (logitsObj.isNotEmpty() && logitsObj[0] is Array<*>) {
                val seqArray = logitsObj[0] as Array<*>
                val lastIdx = (seqLen - 1).coerceIn(0, seqArray.size - 1)
                val floatArr = seqArray[lastIdx] as? FloatArray
                if (floatArr != null) {
                    return argmax(floatArr)
                }
            } else if (logitsObj.isNotEmpty() && logitsObj[0] is FloatArray) {
                val lastIdx = (seqLen - 1).coerceIn(0, logitsObj.size - 1)
                val floatArr = logitsObj[lastIdx] as FloatArray
                return argmax(floatArr)
            }
        } else if (logitsObj is FloatArray) {
            return argmax(logitsObj)
        }
        return modelConfig.eosTokenId
    }

    private fun argmax(arr: FloatArray): Int {
        var maxIdx = 0
        var maxVal = Float.NEGATIVE_INFINITY
        for (i in arr.indices) {
            if (arr[i] > maxVal) {
                maxVal = arr[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    override fun close() {
        synchronized(lock) {
            try {
                decoderSession?.close()
            } catch (_: Exception) {}
            try {
                encoderSession?.close()
            } catch (_: Exception) {}
            decoderSession = null
            encoderSession = null
            ortEnv = null
            isInitialized = false
        }
    }

    companion object {
        internal fun detectRepetitionLoop(ids: List<Int>): Int {
            val totalAvailable = ids.size - 1
            if (totalAvailable < 4) return -1

            for (p in 1..8) {
                val needed = 4 * p
                if (totalAvailable < needed) continue
                val startIndex = ids.size - needed

                var isLoop = true
                for (step in 1..3) {
                    for (k in 0 until p) {
                        if (ids[startIndex + k] != ids[startIndex + step * p + k]) {
                            isLoop = false
                            break
                        }
                    }
                    if (!isLoop) break
                }
                if (isLoop) {
                    return startIndex
                }
            }
            return -1
        }

        internal fun scoreRecognitionCandidate(rawLatex: String, stoppedByLoopGuard: Boolean): Int {
            if (rawLatex.isBlank()) return -1000
            val converted = LatexToCasConverter.convert(rawLatex)
            if (converted.casExpression.isBlank()) return -900
            var score = 100
            if (stoppedByLoopGuard) score -= 80
            score -= 25 * converted.warnings.size
            return score
        }

        fun parseConfigParams(jsonStr: String): OcrModelConfig {
            val root = PureJsonParser.parse(jsonStr) as? Map<*, *> ?: return OcrModelConfig()
            val decoder = root["decoder"] as? Map<*, *>

            val decoderStart = (root["decoder_start_token_id"] as? Number)?.toInt()
                ?: (decoder?.get("decoder_start_token_id") as? Number)?.toInt()
                ?: (decoder?.get("bos_token_id") as? Number)?.toInt()
                ?: 2
            val eos = (root["eos_token_id"] as? Number)?.toInt()
                ?: (decoder?.get("eos_token_id") as? Number)?.toInt()
                ?: 2
            val bos = (root["bos_token_id"] as? Number)?.toInt()
                ?: (decoder?.get("bos_token_id") as? Number)?.toInt()
                ?: 0
            val pad = (root["pad_token_id"] as? Number)?.toInt()
                ?: (decoder?.get("pad_token_id") as? Number)?.toInt()
                ?: 1
            val maxLen = (root["max_length"] as? Number)?.toInt()
                ?: (root["max_new_tokens"] as? Number)?.toInt()
                ?: (decoder?.get("max_length") as? Number)?.toInt()
                ?: (decoder?.get("max_new_tokens") as? Number)?.toInt()
                ?: 160

            return OcrModelConfig(
                decoderStartTokenId = decoderStart,
                eosTokenId = eos,
                bosTokenId = bos,
                padTokenId = pad,
                maxNewTokens = maxLen
            )
        }

        fun parsePreprocessorParams(jsonStr: String): OcrPreprocessorConfig {
            val root = PureJsonParser.parse(jsonStr) as? Map<*, *> ?: return OcrPreprocessorConfig()

            var h = (root["height"] as? Number)?.toInt()
            var w = (root["width"] as? Number)?.toInt()

            val sizeObj = root["size"]
            if (sizeObj is Map<*, *>) {
                h = h ?: (sizeObj["height"] as? Number)?.toInt()
                w = w ?: (sizeObj["width"] as? Number)?.toInt()
            } else if (sizeObj is Number) {
                h = h ?: sizeObj.toInt()
                w = w ?: sizeObj.toInt()
            }

            val cropSizeObj = root["crop_size"]
            if (cropSizeObj is Map<*, *>) {
                h = h ?: (cropSizeObj["height"] as? Number)?.toInt()
                w = w ?: (cropSizeObj["width"] as? Number)?.toInt()
            } else if (cropSizeObj is Number) {
                h = h ?: cropSizeObj.toInt()
                w = w ?: cropSizeObj.toInt()
            }

            val finalH = h ?: 384
            val finalW = w ?: 384

            fun parseFloatArray(obj: Any?): FloatArray? {
                val list = obj as? List<*> ?: return null
                val arr = FloatArray(list.size)
                for (i in list.indices) {
                    arr[i] = (list[i] as? Number)?.toFloat() ?: 0.5f
                }
                return arr
            }

            val meanArr = parseFloatArray(root["image_mean"])
                ?: parseFloatArray(root["mean"])
                ?: floatArrayOf(0.5f, 0.5f, 0.5f)

            val stdArr = parseFloatArray(root["image_std"])
                ?: parseFloatArray(root["std"])
                ?: floatArrayOf(0.5f, 0.5f, 0.5f)

            return OcrPreprocessorConfig(
                targetWidth = finalW,
                targetHeight = finalH,
                mean = meanArr,
                std = stdArr
            )
        }
    }
}
