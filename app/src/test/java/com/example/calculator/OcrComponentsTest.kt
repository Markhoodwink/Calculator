package com.example.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrComponentsTest {

    @Test
    fun testBpeTokenizerDecodingWithMockJson() {
        val mockTokenizerJson = """
            {
              "added_tokens": [
                { "id": 0, "content": "<s>", "special": true },
                { "id": 1, "content": "<pad>", "special": true },
                { "id": 2, "content": "</s>", "special": true }
              ],
              "model": {
                "vocab": {
                  "<s>": 0,
                  "<pad>": 1,
                  "</s>": 2,
                  "Ġ\\frac": 10,
                  "Ġ{": 11,
                  "x": 12,
                  "}{": 13,
                  "2": 14,
                  "}": 15,
                  "Ġ\\leqslant": 16,
                  "Ġy": 17,
                  "^{2}": 18
                }
              }
            }
        """.trimIndent()

        val tokenizer = BpeLatexTokenizer.fromJsonString(mockTokenizerJson)

        assertEquals("<s>", tokenizer.idToToken[0])
        assertEquals("</s>", tokenizer.idToToken[2])
        assertTrue(tokenizer.specialTokenIds.contains(0))
        assertTrue(tokenizer.specialTokenIds.contains(1))
        assertTrue(tokenizer.specialTokenIds.contains(2))

        val tokenIds = listOf(0, 10, 11, 12, 13, 14, 15, 16, 17, 18, 2)
        val decoded = tokenizer.decode(tokenIds, skipSpecialTokens = true)

        assertEquals("\\frac {x}{2} \\leqslant y^{2}", decoded)
    }

    @Test
    fun testOcrModelManagerUrlGeneration() {
        val fileName = "encoder_model.onnx"
        val primaryUrl = OcrModelManager.getPrimaryUrl(fileName)
        val fallbackUrl = OcrModelManager.getFallbackUrl(fileName)

        assertEquals("https://github.com/Markhoodwink/Calculator/releases/download/ocr-models-v1/encoder_model.onnx", primaryUrl)
        assertEquals("https://huggingface.co/breezedeus/pix2text-mfr-1.5/resolve/main/encoder_model.onnx?download=true", fallbackUrl)

        val labelRu = OcrModelManager.getFileLabelRu("encoder_model.onnx")
        assertTrue(labelRu.contains("энкодера"))
    }

    @Test
    fun testLocalOnnxOcrEngineConfigJsonParsing() {
        val mockConfigJson = """
            {
              "decoder_start_token_id": 2,
              "eos_token_id": 2,
              "bos_token_id": 0,
              "pad_token_id": 1,
              "max_length": 160
            }
        """.trimIndent()

        val config = LocalOnnxOcrEngine.parseConfigParams(mockConfigJson)

        assertEquals(2, config.decoderStartTokenId)
        assertEquals(2, config.eosTokenId)
        assertEquals(0, config.bosTokenId)
        assertEquals(1, config.padTokenId)
        assertEquals(160, config.maxNewTokens)

        val mockPrepConfigJson = """
            {
              "size": {
                "height": 384,
                "width": 384
              },
              "image_mean": [0.5, 0.5, 0.5],
              "image_std": [0.5, 0.5, 0.5]
            }
        """.trimIndent()

        val prepConfig = LocalOnnxOcrEngine.parsePreprocessorParams(mockPrepConfigJson)

        assertEquals(384, prepConfig.targetWidth)
        assertEquals(384, prepConfig.targetHeight)
        assertEquals(0.5f, prepConfig.mean[0], 0.001f)
        assertEquals(0.5f, prepConfig.std[0], 0.001f)
    }

    @Test
    fun testPureJsonParserComplexData() {
        val json = """
            {
              "number": 42,
              "float": 3.14159,
              "bool": true,
              "nullVal": null,
              "list": [1, 2, "three"],
              "nested": { "a": "b" }
            }
        """.trimIndent()

        val parsed = PureJsonParser.parse(json) as? Map<*, *>
        assertTrue(parsed != null)
        assertEquals(42L, (parsed!!["number"] as Number).toLong())
        assertEquals(3.14159, (parsed["float"] as Number).toDouble(), 0.00001)
        assertEquals(true, parsed["bool"])
        assertEquals(null, parsed["nullVal"])
        val list = parsed["list"] as? List<*>
        assertEquals(3, list?.size)
        assertEquals("three", list?.get(2))
    }

    @Test
    fun testDetectRepetitionLoop() {
        val loop1 = listOf(2, 5, 5, 5, 5)
        assertEquals(1, LocalOnnxOcrEngine.detectRepetitionLoop(loop1))

        val loop2 = listOf(2, 10, 11, 10, 11, 10, 11, 10, 11)
        assertEquals(1, LocalOnnxOcrEngine.detectRepetitionLoop(loop2))

        val loop3 = listOf(2, 1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 2, 3)
        assertEquals(1, LocalOnnxOcrEngine.detectRepetitionLoop(loop3))

        val valid = listOf(2, 10, 11, 12, 13)
        assertEquals(-1, LocalOnnxOcrEngine.detectRepetitionLoop(valid))
    }

    @Test
    fun testRemoveNotebookGrid() {
        val w = 120
        val h = 80
        val ratio = FloatArray(w * h) { 1.0f }

        // 2 edge-to-edge horizontal light grid lines (ratio = 0.84f) at y = 20 and y = 50
        for (x in 0 until w) {
            ratio[20 * w + x] = 0.84f
            ratio[50 * w + x] = 0.84f
        }

        // 3 vertical light grid lines (ratio = 0.84f) at x = 15, x = 60, x = 100
        for (y in 0 until h) {
            ratio[y * w + 15] = 0.84f
            ratio[y * w + 60] = 0.84f
            ratio[y * w + 100] = 0.84f
        }

        // Centered dark fraction bar (ratio = 0.25f) at y = 40, spanning x = 25..95
        for (x in 25..95) {
            ratio[40 * w + x] = 0.25f
        }

        // Crossing pencil stroke (ratio = 0.62f) at x = 60, spanning y = 35..45
        for (y in 35..45) {
            ratio[y * w + 60] = 0.62f
        }

        val (removedH, removedV) = PrintedFormulaPreprocessor.removeNotebookGrid(ratio, w, h)

        assertTrue(removedH >= 2)
        assertTrue(removedV >= 3)
        assertTrue(ratio[20 * w + 5] >= 0.99f)
        assertTrue(ratio[40 * w + 30] <= 0.65f)
        assertTrue(ratio[38 * w + 60] <= 0.65f)
    }

    @Test
    fun testComputeAdaptiveBounds() {
        val total = 1000
        val pencilArray = FloatArray(total) { 0.98f }
        for (i in 0 until 20) {
            pencilArray[i] = 0.66f
        }

        val (lowerPencil, upperPencil) = PrintedFormulaPreprocessor.computeAdaptiveBounds(pencilArray)
        assertTrue(upperPencil >= 0.90f)

        val gelArray = FloatArray(total) { 0.98f }
        for (i in 0 until 20) {
            gelArray[i] = 0.20f
        }

        val (lowerGel, upperGel) = PrintedFormulaPreprocessor.computeAdaptiveBounds(gelArray)
        assertTrue(upperGel < upperPencil)
        assertTrue(lowerGel < lowerPencil)
    }

    @Test
    fun testRemoveTiltedHorizontalNotebookGrid() {
        val w = 300
        val h = 120
        val ratio = FloatArray(w * h) { 1.0f }

        // 2 tilted edge-to-edge horizontal light grid lines (ratio = 0.84f) sloping down by 12px left to right
        for (x in 0 until w) {
            val y1 = (30 + (12 * x) / w).coerceIn(0, h - 1)
            val y2 = (80 + (12 * x) / w).coerceIn(0, h - 1)
            ratio[y1 * w + x] = 0.84f
            ratio[y2 * w + x] = 0.84f
        }

        // Centered dark fraction bar (ratio = 0.25f) at y = 60, spanning x = 80..220
        for (x in 80..220) {
            ratio[60 * w + x] = 0.25f
        }

        val (removedH, removedV) = PrintedFormulaPreprocessor.removeNotebookGrid(ratio, w, h)

        assertTrue("Expected removedH >= 2, got $removedH", removedH >= 2)

        // Sample pixels along the tilted grid line 1 (left, middle, right)
        val leftY1 = 30
        val midY1 = 30 + (12 * 150) / w
        val rightY1 = 30 + (12 * 290) / w

        assertTrue("Left grid pixel should be lifted, got ${ratio[leftY1 * w + 5]}", ratio[leftY1 * w + 5] >= 0.99f)
        assertTrue("Mid grid pixel should be lifted, got ${ratio[midY1 * w + 150]}", ratio[midY1 * w + 150] >= 0.99f)
        assertTrue("Right grid pixel should be lifted, got ${ratio[rightY1 * w + 290]}", ratio[rightY1 * w + 290] >= 0.99f)

        // Centered fraction bar at y = 60 is NOT erased
        assertTrue("Fraction bar should not be erased, got ${ratio[60 * w + 150]}", ratio[60 * w + 150] <= 0.30f)
    }

    @Test
    fun testDetectDarkThemeBackground() {
        val w = 100
        val h = 60
        val total = w * h

        // 1) Dark #111827 (luma ~22) background with bright #F8FAFC (luma ~248) text -> true
        val darkBgLuma = FloatArray(total) { 22f }
        for (y in 20..40) {
            for (x in 20..80) {
                darkBgLuma[y * w + x] = 248f
            }
        }
        assertTrue(PrintedFormulaPreprocessor.detectDarkThemeBackground(darkBgLuma, w, h))

        // 2) White #FFFFFF background with dark text -> false
        val whiteBgLuma = FloatArray(total) { 255f }
        for (y in 20..40) {
            for (x in 20..80) {
                whiteBgLuma[y * w + x] = 20f
            }
        }
        assertFalse(PrintedFormulaPreprocessor.detectDarkThemeBackground(whiteBgLuma, w, h))

        // 3) Dim gray #B8B8B8 (luma ~184) background with dark text -> false
        val grayBgLuma = FloatArray(total) { 184f }
        for (y in 20..40) {
            for (x in 20..80) {
                grayBgLuma[y * w + x] = 30f
            }
        }
        assertFalse(PrintedFormulaPreprocessor.detectDarkThemeBackground(grayBgLuma, w, h))
    }

    @Test
    fun testEraseDisconnectedSideSpecks() {
        val w = 200
        val h = 60
        val total = w * h

        // 200x60 array with a 4px left-edge artifact (x = 0..3), empty gap x = 4..34, and main formula ink x = 35..170.
        val outPixels1 = IntArray(total) { 0xFFFFFFFF.toInt() }
        for (y in 20..40) {
            for (x in 0..3) {
                outPixels1[y * w + x] = (0xFF shl 24) or 0x00000000
            }
        }
        for (y in 15..45) {
            for (x in 35..170) {
                outPixels1[y * w + x] = (0xFF shl 24) or 0x00000000
            }
        }

        PrintedFormulaPreprocessor.eraseDisconnectedSideSpecks(outPixels1, w, h)
        assertEquals(255, outPixels1[25 * w + 1] and 0xFF) // x = 1 wiped to white (255)
        assertEquals(0, outPixels1[25 * w + 40] and 0xFF)   // x = 40 remains dark (0)

        // Ink starting at x = 8 (not touching x <= 1) is NOT wiped!
        val outPixels2 = IntArray(total) { 0xFFFFFFFF.toInt() }
        for (y in 20..40) {
            for (x in 8..12) {
                outPixels2[y * w + x] = (0xFF shl 24) or 0x00000000
            }
        }
        for (y in 15..45) {
            for (x in 45..170) {
                outPixels2[y * w + x] = (0xFF shl 24) or 0x00000000
            }
        }

        PrintedFormulaPreprocessor.eraseDisconnectedSideSpecks(outPixels2, w, h)
        assertEquals(0, outPixels2[25 * w + 10] and 0xFF) // x = 10 remains dark (0)
    }
}
