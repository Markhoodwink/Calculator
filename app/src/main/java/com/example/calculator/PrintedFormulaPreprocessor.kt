package com.example.calculator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.roundToInt

data class PreprocessResult(
    val bitmap: Bitmap,
    val removedGridH: Int,
    val removedGridV: Int,
    val inkMinRatio: Float,
    val upperThreshold: Float,
    val inkPercent: Float,
    val durationMs: Long,
    val wasInverted: Boolean = false
)

class PrintedFormulaPreprocessor : FormulaImagePreprocessor {

    fun cropByNormalizedRect(source: Bitmap, normalizedRect: RectF): Bitmap {
        val srcW = source.width
        val srcH = source.height

        var leftPx = (normalizedRect.left * srcW).toInt().coerceIn(0, srcW - 1)
        var topPx = (normalizedRect.top * srcH).toInt().coerceIn(0, srcH - 1)
        var rightPx = (normalizedRect.right * srcW).toInt().coerceIn(leftPx + 1, srcW)
        var bottomPx = (normalizedRect.bottom * srcH).toInt().coerceIn(topPx + 1, srcH)

        var cropW = rightPx - leftPx
        var cropH = bottomPx - topPx

        if (cropW < 16) {
            val center = (leftPx + rightPx) / 2
            leftPx = (center - 8).coerceIn(0, max(0, srcW - 16))
            rightPx = (leftPx + 16).coerceAtMost(srcW)
            cropW = rightPx - leftPx
        }
        if (cropH < 16) {
            val center = (topPx + bottomPx) / 2
            topPx = (center - 8).coerceIn(0, max(0, srcH - 16))
            bottomPx = (topPx + 16).coerceAtMost(srcH)
            cropH = bottomPx - topPx
        }

        return Bitmap.createBitmap(source, leftPx, topPx, cropW, cropH)
    }

    override fun preprocessForOcr(source: Bitmap, mode: ScanInputMode): Bitmap {
        return preprocessWithStats(source, mode).bitmap
    }

    fun preprocessWithStats(
        source: Bitmap,
        mode: ScanInputMode,
        softAntiMoirePass: Boolean = false
    ): PreprocessResult {
        return when (mode) {
            ScanInputMode.PRINTED -> preprocessPrintedWithStats(source, softAntiMoirePass)
            ScanInputMode.HANDWRITTEN -> preprocessHandwrittenWithStats(source)
        }
    }

    private data class VerticalBand(val startY: Int, val endY: Int, val inkMass: Int)

    private fun preprocessPrintedWithStats(source: Bitmap, softAntiMoirePass: Boolean = false): PreprocessResult {
        val startMs = System.nanoTime() / 1_000_000L
        var workingBitmap = if (source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }

        var scaledBitmap: Bitmap? = null
        if (workingBitmap.width > 960) {
            val targetW = 960
            val targetH = (workingBitmap.height * 960f / workingBitmap.width).toInt().coerceAtLeast(16)
            scaledBitmap = Bitmap.createScaledBitmap(workingBitmap, targetW, targetH, true)
            if (workingBitmap != source && workingBitmap != scaledBitmap) {
                workingBitmap.recycle()
            }
            workingBitmap = scaledBitmap
        }

        val w = workingBitmap.width
        val h = workingBitmap.height
        val totalPixels = w * h
        val pixels = IntArray(totalPixels)
        workingBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val rawLuma = FloatArray(totalPixels)
        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            rawLuma[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val isDarkBg = detectDarkThemeBackground(rawLuma, w, h)

        val luma = FloatArray(totalPixels)
        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val sat = maxC - minC

            if (!isDarkBg) {
                if (sat > 55 && maxC > 125) {
                    luma[i] = maxC.toFloat().coerceAtLeast(220f)
                } else {
                    luma[i] = rawLuma[i]
                }
            } else {
                if (sat > 70 && minC < 80) {
                    luma[i] = 255f
                } else {
                    luma[i] = (255f - rawLuma[i]).coerceIn(0f, 255f)
                }
            }
        }

        if (softAntiMoirePass) {
            val gaussianLuma = FloatArray(totalPixels)
            for (y in 0 until h) {
                val rOff = y * w
                val topOff = (y - 1).coerceAtLeast(0) * w
                val botOff = (y + 1).coerceAtMost(h - 1) * w
                for (x in 0 until w) {
                    val leftX = (x - 1).coerceAtLeast(0)
                    val rightX = (x + 1).coerceAtMost(w - 1)

                    val center = luma[rOff + x]
                    val up = luma[topOff + x]
                    val down = luma[botOff + x]
                    val left = luma[rOff + leftX]
                    val right = luma[rOff + rightX]
                    val topLeft = luma[topOff + leftX]
                    val topRight = luma[topOff + rightX]
                    val botLeft = luma[botOff + leftX]
                    val botRight = luma[botOff + rightX]

                    gaussianLuma[rOff + x] = (center * 4f + (up + down + left + right) * 2f + (topLeft + topRight + botLeft + botRight)) / 16f
                }
            }
            System.arraycopy(gaussianLuma, 0, luma, 0, totalPixels)
        }

        val radius = (minOf(w, h) / 6).coerceIn(10, 28)
        val bg = separableBoxBlur(luma, w, h, radius)

        val upper = if (softAntiMoirePass) 0.84f else 0.87f
        val lower = 0.52f
        val outPixels = IntArray(totalPixels)
        val rowInkCount = IntArray(h)

        for (y in 0 until h) {
            val rowOff = y * w
            var rowInk = 0
            for (x in 0 until w) {
                val idx = rowOff + x
                val ratio = luma[idx] / maxOf(bg[idx], 32f)
                val outVal = when {
                    ratio >= upper -> 255
                    ratio <= lower -> 0
                    else -> (((ratio - lower) / (upper - lower)) * 255f).toInt().coerceIn(0, 255)
                }
                outPixels[idx] = (0xFF shl 24) or (outVal shl 16) or (outVal shl 8) or outVal
                if (outVal < 200) {
                    rowInk++
                }
            }
            rowInkCount[y] = rowInk
        }

        // Horizontal Band Filtering
        val minInkRow = maxOf(2, (w * 0.005f).toInt())
        val maxGap = maxOf(4, (h * 0.035f).toInt())

        val rawBands = mutableListOf<Pair<Int, Int>>()
        var inBand = false
        var bStart = 0
        for (y in 0 until h) {
            if (rowInkCount[y] >= minInkRow) {
                if (!inBand) {
                    inBand = true
                    bStart = y
                }
            } else {
                if (inBand) {
                    inBand = false
                    rawBands.add(Pair(bStart, y - 1))
                }
            }
        }
        if (inBand) {
            rawBands.add(Pair(bStart, h - 1))
        }

        val mergedBands = mutableListOf<VerticalBand>()
        if (rawBands.isNotEmpty()) {
            var currStart = rawBands[0].first
            var currEnd = rawBands[0].second
            for (k in 1 until rawBands.size) {
                val nextStart = rawBands[k].first
                val nextEnd = rawBands[k].second
                if (nextStart - currEnd - 1 <= maxGap) {
                    currEnd = nextEnd
                } else {
                    var mass = 0
                    for (y in currStart..currEnd) mass += rowInkCount[y]
                    mergedBands.add(VerticalBand(currStart, currEnd, mass))
                    currStart = nextStart
                    currEnd = nextEnd
                }
            }
            var mass = 0
            for (y in currStart..currEnd) mass += rowInkCount[y]
            mergedBands.add(VerticalBand(currStart, currEnd, mass))
        }

        var primaryStart = 0
        var primaryEnd = h - 1

        if (mergedBands.size >= 2) {
            val centerY = h / 2f
            var primaryBand = mergedBands[0]
            var bestScore = Float.MAX_VALUE

            for (band in mergedBands) {
                val touches = (band.startY <= 2) || (band.endY >= h - 3)
                val dist = Math.abs(((band.startY + band.endY) / 2f) - centerY)
                val score = (if (touches) 10000.0f else 0.0f) + dist - (band.inkMass * 0.001f)
                if (score < bestScore) {
                    bestScore = score
                    primaryBand = band
                }
            }

            primaryStart = primaryBand.startY
            primaryEnd = primaryBand.endY
            val minSepGap = maxOf(6, (h * 0.05f).toInt())

            for (band in mergedBands) {
                if (band == primaryBand) continue
                val touches = (band.startY <= 2) || (band.endY >= h - 3)
                val isSeparated = (band.endY < primaryBand.startY && (primaryBand.startY - band.endY - 1) >= minSepGap) ||
                        (band.startY > primaryBand.endY && (band.startY - primaryBand.endY - 1) >= minSepGap)

                if (touches || isSeparated) {
                    for (y in band.startY..band.endY) {
                        val rOff = y * w
                        for (x in 0 until w) {
                            outPixels[rOff + x] = (0xFF shl 24) or 0x00FFFFFF
                        }
                    }
                }
            }
        } else if (mergedBands.size == 1) {
            primaryStart = mergedBands[0].startY
            primaryEnd = mergedBands[0].endY
        }

        // Wipe 2px border if disconnected from primary band
        for (y in 0..1) {
            if (y < primaryStart) {
                val rOff = y * w
                for (x in 0 until w) outPixels[rOff + x] = (0xFF shl 24) or 0x00FFFFFF
            }
        }
        for (y in maxOf(0, h - 2) until h) {
            if (y > primaryEnd) {
                val rOff = y * w
                for (x in 0 until w) outPixels[rOff + x] = (0xFF shl 24) or 0x00FFFFFF
            }
        }

        // Erase disconnected side specks
        eraseDisconnectedSideSpecks(outPixels, w, h)

        // Bounding Box Crop
        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1

        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                val outVal = outPixels[rowOff + x] and 0xFF
                if (outVal < 210) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val paddedBitmap: Bitmap
        val finalInkCount: Int
        val finalTotalPixels: Int

        if (maxX > minX && maxY > minY) {
            val inkW = maxX - minX + 1
            val inkH = maxY - minY + 1
            val pad = (inkH * 0.14f).toInt().coerceIn(10, 28)

            val inkBitmap = Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
            val croppedInk = Bitmap.createBitmap(inkBitmap, minX, minY, inkW, inkH)

            paddedBitmap = Bitmap.createBitmap(inkW + 2 * pad, inkH + 2 * pad, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(paddedBitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(croppedInk, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))

            var darkCount = 0
            val pPix = IntArray(paddedBitmap.width * paddedBitmap.height)
            paddedBitmap.getPixels(pPix, 0, paddedBitmap.width, 0, 0, paddedBitmap.width, paddedBitmap.height)
            for (p in pPix) {
                if ((p and 0xFF) < 200) darkCount++
            }
            finalInkCount = darkCount
            finalTotalPixels = paddedBitmap.width * paddedBitmap.height

            if (croppedInk != inkBitmap) croppedInk.recycle()
            inkBitmap.recycle()
        } else {
            val pad = (h * 0.14f).toInt().coerceIn(10, 28)
            val outBitmap = Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)

            paddedBitmap = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(paddedBitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(outBitmap, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))

            var darkCount = 0
            val pPix = IntArray(paddedBitmap.width * paddedBitmap.height)
            paddedBitmap.getPixels(pPix, 0, paddedBitmap.width, 0, 0, paddedBitmap.width, paddedBitmap.height)
            for (p in pPix) {
                if ((p and 0xFF) < 200) darkCount++
            }
            finalInkCount = darkCount
            finalTotalPixels = paddedBitmap.width * paddedBitmap.height

            outBitmap.recycle()
        }

        val durationMs = (System.nanoTime() / 1_000_000L) - startMs
        val inkPercent = if (finalTotalPixels > 0) (finalInkCount.toFloat() / finalTotalPixels) * 100f else 0f

        if (scaledBitmap != null && scaledBitmap != source) {
            scaledBitmap.recycle()
        }

        return PreprocessResult(
            bitmap = paddedBitmap,
            removedGridH = 0,
            removedGridV = 0,
            inkMinRatio = lower,
            upperThreshold = upper,
            inkPercent = inkPercent,
            durationMs = durationMs,
            wasInverted = isDarkBg
        )
    }

    private fun preprocessHandwrittenWithStats(source: Bitmap): PreprocessResult {
        val startMs = System.nanoTime() / 1_000_000L
        val argbBitmap = if (source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }

        val w = argbBitmap.width
        val h = argbBitmap.height
        val totalPixels = w * h
        val pixels = IntArray(totalPixels)
        argbBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. Pen-Friendly Grayscale (NO colored highlight deletion!)
        val luma = FloatArray(totalPixels)
        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val stdLuma = 0.299f * r + 0.587f * g + 0.114f * b
            val minC = minOf(r, g, b).toFloat()
            luma[i] = 0.65f * stdLuma + 0.35f * minC
        }

        // 2. Local Illumination Normalization
        val radius = (minOf(w, h) / 5).coerceIn(14, 36)
        val bg = separableBoxBlur(luma, w, h, radius)

        val ratio = FloatArray(totalPixels)
        for (i in 0 until totalPixels) {
            ratio[i] = (luma[i] / maxOf(bg[i], 32f)).coerceIn(0f, 1.2f)
        }

        // 3. Pure-Kotlin Edge-to-Edge Notebook Grid Removal
        val (removedH, removedV) = removeNotebookGrid(ratio, w, h)

        // 4. Adaptive Contrast Stretch
        val (lower, upper) = computeAdaptiveBounds(ratio)
        val outPixels = IntArray(totalPixels)
        for (i in 0 until totalPixels) {
            val rVal = ratio[i]
            val outVal = when {
                rVal >= upper -> 255
                rVal <= lower -> 0
                else -> {
                    val t = ((rVal - lower) / (upper - lower)).toDouble()
                    val norm = Math.pow(t, 1.15)
                    (norm * 255.0).toInt().coerceIn(0, 255)
                }
            }
            outPixels[i] = outVal
        }

        // 5. Gentle Thin-Stroke Reinforcement
        val initialInkCount = outPixels.count { it < 185 }
        val inkFraction = initialInkCount.toFloat() / totalPixels

        if (inkFraction < 0.055f) {
            val reinforced = outPixels.clone()
            for (y in 1 until h - 1) {
                val rOff = y * w
                for (x in 1 until w - 1) {
                    val idx = rOff + x
                    val center = outPixels[idx]
                    val up = outPixels[(y - 1) * w + x]
                    val down = outPixels[(y + 1) * w + x]
                    val left = outPixels[rOff + x - 1]
                    val right = outPixels[rOff + x + 1]
                    val minCross = minOf(up, down, left, right)
                    val outNew = minOf(center, minCross + 40).coerceIn(0, 255)
                    reinforced[idx] = outNew
                }
            }
            System.arraycopy(reinforced, 0, outPixels, 0, totalPixels)
        }

        // 6. Softer Band Filtering & Generous Padding
        val rowInkCount = IntArray(h)
        val argbOutPixels = IntArray(totalPixels)
        for (y in 0 until h) {
            val rowOff = y * w
            var rowInk = 0
            for (x in 0 until w) {
                val idx = rowOff + x
                val v = outPixels[idx]
                argbOutPixels[idx] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                if (v < 200) rowInk++
            }
            rowInkCount[y] = rowInk
        }

        val minInkRow = maxOf(2, (w * 0.005f).toInt())
        val maxGap = maxOf(6, (h * 0.06f).toInt())

        val rawBands = mutableListOf<Pair<Int, Int>>()
        var inBand = false
        var bStart = 0
        for (y in 0 until h) {
            if (rowInkCount[y] >= minInkRow) {
                if (!inBand) {
                    inBand = true
                    bStart = y
                }
            } else {
                if (inBand) {
                    inBand = false
                    rawBands.add(Pair(bStart, y - 1))
                }
            }
        }
        if (inBand) {
            rawBands.add(Pair(bStart, h - 1))
        }

        val mergedBands = mutableListOf<VerticalBand>()
        if (rawBands.isNotEmpty()) {
            var currStart = rawBands[0].first
            var currEnd = rawBands[0].second
            for (k in 1 until rawBands.size) {
                val nextStart = rawBands[k].first
                val nextEnd = rawBands[k].second
                if (nextStart - currEnd - 1 <= maxGap) {
                    currEnd = nextEnd
                } else {
                    var mass = 0
                    for (y in currStart..currEnd) mass += rowInkCount[y]
                    mergedBands.add(VerticalBand(currStart, currEnd, mass))
                    currStart = nextStart
                    currEnd = nextEnd
                }
            }
            var mass = 0
            for (y in currStart..currEnd) mass += rowInkCount[y]
            mergedBands.add(VerticalBand(currStart, currEnd, mass))
        }

        var primaryStart = 0
        var primaryEnd = h - 1

        if (mergedBands.size >= 2) {
            val centerY = h / 2f
            var primaryBand = mergedBands[0]
            var bestScore = Float.MAX_VALUE

            for (band in mergedBands) {
                val touches = (band.startY <= 2) || (band.endY >= h - 3)
                val dist = Math.abs(((band.startY + band.endY) / 2f) - centerY)
                val score = (if (touches) 10000.0f else 0.0f) + dist - (band.inkMass * 0.001f)
                if (score < bestScore) {
                    bestScore = score
                    primaryBand = band
                }
            }

            primaryStart = primaryBand.startY
            primaryEnd = primaryBand.endY
            val minSepGap = maxOf(8, (h * 0.07f).toInt())

            for (band in mergedBands) {
                if (band == primaryBand) continue
                val touches = (band.startY <= 2) || (band.endY >= h - 3)
                val isSeparated = (band.endY < primaryBand.startY && (primaryBand.startY - band.endY - 1) >= minSepGap) ||
                        (band.startY > primaryBand.endY && (band.startY - primaryBand.endY - 1) >= minSepGap)

                if (touches || isSeparated) {
                    for (y in band.startY..band.endY) {
                        val rOff = y * w
                        for (x in 0 until w) {
                            argbOutPixels[rOff + x] = (0xFF shl 24) or 0x00FFFFFF
                        }
                    }
                }
            }
        } else if (mergedBands.size == 1) {
            primaryStart = mergedBands[0].startY
            primaryEnd = mergedBands[0].endY
        }

        for (y in 0..1) {
            if (y < primaryStart) {
                val rOff = y * w
                for (x in 0 until w) argbOutPixels[rOff + x] = (0xFF shl 24) or 0x00FFFFFF
            }
        }
        for (y in maxOf(0, h - 2) until h) {
            if (y > primaryEnd) {
                val rOff = y * w
                for (x in 0 until w) argbOutPixels[rOff + x] = (0xFF shl 24) or 0x00FFFFFF
            }
        }

        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1

        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                val outVal = argbOutPixels[rowOff + x] and 0xFF
                if (outVal < 210) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val paddedBitmap: Bitmap
        val finalInkCount: Int
        val finalTotalPixels: Int

        if (maxX > minX && maxY > minY) {
            val inkW = maxX - minX + 1
            val inkH = maxY - minY + 1
            val pad = (inkH * 0.16f).toInt().coerceIn(12, 32)

            val inkBitmap = Bitmap.createBitmap(argbOutPixels, w, h, Bitmap.Config.ARGB_8888)
            val croppedInk = Bitmap.createBitmap(inkBitmap, minX, minY, inkW, inkH)

            paddedBitmap = Bitmap.createBitmap(inkW + 2 * pad, inkH + 2 * pad, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(paddedBitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(croppedInk, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))

            var darkCount = 0
            val pPix = IntArray(paddedBitmap.width * paddedBitmap.height)
            paddedBitmap.getPixels(pPix, 0, paddedBitmap.width, 0, 0, paddedBitmap.width, paddedBitmap.height)
            for (p in pPix) {
                if ((p and 0xFF) < 200) darkCount++
            }
            finalInkCount = darkCount
            finalTotalPixels = paddedBitmap.width * paddedBitmap.height

            if (croppedInk != inkBitmap) croppedInk.recycle()
            inkBitmap.recycle()
        } else {
            val pad = (h * 0.16f).toInt().coerceIn(12, 32)
            val outBitmap = Bitmap.createBitmap(argbOutPixels, w, h, Bitmap.Config.ARGB_8888)

            paddedBitmap = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(paddedBitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(outBitmap, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))

            var darkCount = 0
            val pPix = IntArray(paddedBitmap.width * paddedBitmap.height)
            paddedBitmap.getPixels(pPix, 0, paddedBitmap.width, 0, 0, paddedBitmap.width, paddedBitmap.height)
            for (p in pPix) {
                if ((p and 0xFF) < 200) darkCount++
            }
            finalInkCount = darkCount
            finalTotalPixels = paddedBitmap.width * paddedBitmap.height

            outBitmap.recycle()
        }

        val durationMs = (System.nanoTime() / 1_000_000L) - startMs
        val inkPercent = if (finalTotalPixels > 0) (finalInkCount.toFloat() / finalTotalPixels) * 100f else 0f

        return PreprocessResult(
            bitmap = paddedBitmap,
            removedGridH = removedH,
            removedGridV = removedV,
            inkMinRatio = lower,
            upperThreshold = upper,
            inkPercent = inkPercent,
            durationMs = durationMs
        )
    }

    private fun separableBoxBlur(luma: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val horiz = FloatArray(w * h)
        val bg = FloatArray(w * h)

        for (y in 0 until h) {
            val rowOffset = y * w
            var windowSum = 0.0f
            var windowCount = 0

            for (x in 0..minOf(radius, w - 1)) {
                windowSum += luma[rowOffset + x]
                windowCount++
            }
            horiz[rowOffset] = windowSum / windowCount

            for (x in 1 until w) {
                val addIdx = x + radius
                if (addIdx < w) {
                    windowSum += luma[rowOffset + addIdx]
                    windowCount++
                }
                val remIdx = x - radius - 1
                if (remIdx >= 0) {
                    windowSum -= luma[rowOffset + remIdx]
                    windowCount--
                }
                horiz[rowOffset + x] = windowSum / windowCount
            }
        }

        for (x in 0 until w) {
            var windowSum = 0.0f
            var windowCount = 0

            for (y in 0..minOf(radius, h - 1)) {
                windowSum += horiz[y * w + x]
                windowCount++
            }
            bg[x] = windowSum / windowCount

            for (y in 1 until h) {
                val addIdx = y + radius
                if (addIdx < h) {
                    windowSum += horiz[addIdx * w + x]
                    windowCount++
                }
                val remIdx = y - radius - 1
                if (remIdx >= 0) {
                    windowSum -= horiz[remIdx * w + x]
                    windowCount--
                }
                bg[y * w + x] = windowSum / windowCount
            }
        }

        return bg
    }

    fun bitmapToChwFloatTensor(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        mean: FloatArray,
        std: FloatArray
    ): FloatArray {
        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val processed = if (aspect > 5.0f) {
            val requiredHeight = (bitmap.width / 5.0f).toInt().coerceAtLeast(bitmap.height + 1)
            val output = Bitmap.createBitmap(bitmap.width, requiredHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            val top = (requiredHeight - bitmap.height) / 2f
            canvas.drawBitmap(bitmap, 0f, top, Paint(Paint.FILTER_BITMAP_FLAG))
            output
        } else {
            bitmap
        }

        val scaled = if (processed.width == targetWidth && processed.height == targetHeight) {
            processed
        } else {
            Bitmap.createScaledBitmap(processed, targetWidth, targetHeight, true)
        }

        if (processed != bitmap && processed != scaled) {
            processed.recycle()
        }

        val totalPixels = targetWidth * targetHeight
        val pixels = IntArray(totalPixels)
        scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        if (scaled != bitmap && scaled != processed) {
            scaled.recycle()
        }

        val chwTensor = FloatArray(3 * totalPixels)
        val meanR = if (mean.isNotEmpty()) mean[0] else 0.5f
        val meanG = if (mean.size > 1) mean[1] else meanR
        val meanB = if (mean.size > 2) mean[2] else meanR

        val stdR = if (std.isNotEmpty()) std[0] else 0.5f
        val stdG = if (std.size > 1) std[1] else stdR
        val stdB = if (std.size > 2) std[2] else stdR

        val rOffset = 0
        val gOffset = totalPixels
        val bOffset = 2 * totalPixels

        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            chwTensor[rOffset + i] = (r / 255.0f - meanR) / stdR
            chwTensor[gOffset + i] = (g / 255.0f - meanG) / stdG
            chwTensor[bOffset + i] = (b / 255.0f - meanB) / stdB
        }

        return chwTensor
    }

    companion object {
        private data class HorizStripSegment(
            val stripIndex: Int,
            val minY: Int,
            val maxY: Int,
            val centerY: Float
        )

        private data class VertBandSegment(
            val bandIndex: Int,
            val minX: Int,
            val maxX: Int,
            val centerX: Float
        )

        fun removeNotebookGrid(ratio: FloatArray, w: Int, h: Int): Pair<Int, Int> {
            if (w < 6 || h < 6) return Pair(0, 0)

            // --- 1. Horizontal Grid Line Detection (S = 6 Vertical Strips) ---
            val S = 6
            val horizSegmentsByStrip = Array(S) { mutableListOf<HorizStripSegment>() }

            for (s in 0 until S) {
                val xStart = s * w / S
                val xEnd = if (s == S - 1) w else (s + 1) * w / S
                val stripW = xEnd - xStart
                if (stripW <= 0) continue

                val candidateRows = BooleanArray(h)
                for (y in 0 until h) {
                    val rowOff = y * w
                    var darkCount = 0
                    val hist = IntArray(64)
                    for (x in xStart until xEnd) {
                        val rVal = ratio[rowOff + x]
                        if (rVal < 0.965f) {
                            darkCount++
                        }
                        val bin = ((rVal / 1.1f) * 64).toInt().coerceIn(0, 63)
                        hist[bin]++
                    }
                    val darkFrac = darkCount.toFloat() / stripW

                    var cum = 0
                    var medianBin = 0
                    val half = stripW / 2
                    for (b in 0 until 64) {
                        cum += hist[b]
                        if (cum >= half) {
                            medianBin = b
                            break
                        }
                    }
                    val medianRatio = (medianBin + 0.5f) / 64f * 1.1f

                    if (darkFrac >= 0.45f && medianRatio >= 0.68f) {
                        candidateRows[y] = true
                    }
                }

                // Collapse contiguous y runs in strip s into segments
                var runStart = -1
                for (y in 0 until h) {
                    if (candidateRows[y]) {
                        if (runStart < 0) runStart = y
                    } else {
                        if (runStart >= 0) {
                            val minY = runStart
                            val maxY = y - 1
                            val centerY = (minY + maxY) / 2.0f
                            horizSegmentsByStrip[s].add(HorizStripSegment(s, minY, maxY, centerY))
                            runStart = -1
                        }
                    }
                }
                if (runStart >= 0) {
                    val minY = runStart
                    val maxY = h - 1
                    val centerY = (minY + maxY) / 2.0f
                    horizSegmentsByStrip[s].add(HorizStripSegment(s, minY, maxY, centerY))
                }
            }

            // Chain segments across strips (left -> right)
            val hChains = mutableListOf<MutableList<HorizStripSegment>>()
            for (s in 0 until S) {
                for (seg in horizSegmentsByStrip[s]) {
                    var bestChain: MutableList<HorizStripSegment>? = null
                    var minErr = Float.MAX_VALUE
                    for (chain in hChains) {
                        val lastSeg = chain.last()
                        val step = s - lastSeg.stripIndex
                        if (step in 1..2) {
                            val maxDist = step * maxOf(10, (h * 0.08f).toInt()).toFloat()
                            val diffY = kotlin.math.abs(seg.centerY - lastSeg.centerY)
                            if (diffY <= maxDist) {
                                val expectedY = if (chain.size >= 2) {
                                    val avgDy = (lastSeg.centerY - chain.first().centerY) / (lastSeg.stripIndex - chain.first().stripIndex)
                                    lastSeg.centerY + avgDy * step
                                } else {
                                    lastSeg.centerY
                                }
                                val tol = if (step == 1) 6.0f else 12.0f
                                val err = kotlin.math.abs(seg.centerY - expectedY)
                                if (err <= tol && err < minErr) {
                                    minErr = err
                                    bestChain = chain
                                }
                            }
                        }
                    }
                    if (bestChain != null) {
                        bestChain.add(seg)
                    } else {
                        hChains.add(mutableListOf(seg))
                    }
                }
            }

            val maxHThickness = maxOf(8, (h * 0.045f).toInt())
            val confirmedHChains = hChains.filter { chain ->
                val size = chain.size
                val hasLeftMargin = chain.any { it.stripIndex == 0 }
                val hasRightMargin = chain.any { it.stripIndex == S - 1 }
                val spansValid = (size >= 5) || (size >= 4 && hasLeftMargin && hasRightMargin)
                val thinValid = chain.all { (it.maxY - it.minY + 1) <= maxHThickness }
                spansValid && thinValid
            }

            var removedH = 0
            val halfBandH = maxOf(2, (h * 0.012f).toInt())

            for (chain in confirmedHChains) {
                removedH++
                val anchors = chain.map { seg ->
                    val xAnchor = (seg.stripIndex + 0.5f) * w / S
                    Pair(xAnchor, seg.centerY)
                }

                for (x in 0 until w) {
                    val yLine: Float = if (anchors.size == 1) {
                        anchors[0].second
                    } else if (x <= anchors.first().first) {
                        val slope = (anchors[1].second - anchors[0].second) / (anchors[1].first - anchors[0].first)
                        anchors[0].second + slope * (x - anchors[0].first)
                    } else if (x >= anchors.last().first) {
                        val slope = (anchors.last().second - anchors[anchors.size - 2].second) / (anchors.last().first - anchors[anchors.size - 2].first)
                        anchors.last().second + slope * (x - anchors.last().first)
                    } else {
                        var k = 0
                        while (k < anchors.size - 1 && anchors[k + 1].first < x) {
                            k++
                        }
                        val t = (x - anchors[k].first) / (anchors[k + 1].first - anchors[k].first)
                        anchors[k].second + t * (anchors[k + 1].second - anchors[k].second)
                    }

                    val yCenter = yLine.roundToInt().coerceIn(0, h - 1)
                    val yMin = (yCenter - halfBandH).coerceAtLeast(0)
                    val yMax = (yCenter + halfBandH).coerceAtMost(h - 1)

                    for (y in yMin..yMax) {
                        val idx = y * w + x
                        if (ratio[idx] > 0.66f) {
                            ratio[idx] = 1.0f
                        }
                    }
                }
            }

            // --- 2. Vertical Grid Line Detection (B = if (h >= 60) 3 else 1 Horizontal Bands) ---
            val B = if (h >= 60) 3 else 1
            val vertSegmentsByBand = Array(B) { mutableListOf<VertBandSegment>() }

            for (b in 0 until B) {
                val yStart = b * h / B
                val yEnd = if (b == B - 1) h else (b + 1) * h / B
                val bandH = yEnd - yStart
                if (bandH <= 0) continue

                val candidateCols = BooleanArray(w)
                for (x in 0 until w) {
                    var darkCount = 0
                    val hist = IntArray(64)
                    for (y in yStart until yEnd) {
                        val rVal = ratio[y * w + x]
                        if (rVal < 0.965f) {
                            darkCount++
                        }
                        val bin = ((rVal / 1.1f) * 64).toInt().coerceIn(0, 63)
                        hist[bin]++
                    }
                    val darkFrac = darkCount.toFloat() / bandH

                    var cum = 0
                    var medianBin = 0
                    val half = bandH / 2
                    for (k in 0 until 64) {
                        cum += hist[k]
                        if (cum >= half) {
                            medianBin = k
                            break
                        }
                    }
                    val medianRatio = (medianBin + 0.5f) / 64f * 1.1f

                    if (darkFrac >= 0.50f && medianRatio >= 0.66f) {
                        candidateCols[x] = true
                    }
                }

                // Collapse contiguous x runs in band b into segments
                var runStart = -1
                for (x in 0 until w) {
                    if (candidateCols[x]) {
                        if (runStart < 0) runStart = x
                    } else {
                        if (runStart >= 0) {
                            val minX = runStart
                            val maxX = x - 1
                            val centerX = (minX + maxX) / 2.0f
                            vertSegmentsByBand[b].add(VertBandSegment(b, minX, maxX, centerX))
                            runStart = -1
                        }
                    }
                }
                if (runStart >= 0) {
                    val minX = runStart
                    val maxX = w - 1
                    val centerX = (minX + maxX) / 2.0f
                    vertSegmentsByBand[b].add(VertBandSegment(b, minX, maxX, centerX))
                }
            }

            // Chain vertical segments across bands (top -> bottom)
            val vChains = mutableListOf<MutableList<VertBandSegment>>()
            for (b in 0 until B) {
                for (seg in vertSegmentsByBand[b]) {
                    var bestChain: MutableList<VertBandSegment>? = null
                    var minErr = Float.MAX_VALUE
                    for (chain in vChains) {
                        val lastSeg = chain.last()
                        val step = b - lastSeg.bandIndex
                        if (step in 1..2) {
                            val maxDist = step * maxOf(10, (w * 0.025f).toInt()).toFloat()
                            val diffX = kotlin.math.abs(seg.centerX - lastSeg.centerX)
                            if (diffX <= maxDist) {
                                val expectedX = if (chain.size >= 2) {
                                    val avgDx = (lastSeg.centerX - chain.first().centerX) / (lastSeg.bandIndex - chain.first().bandIndex)
                                    lastSeg.centerX + avgDx * step
                                } else {
                                    lastSeg.centerX
                                }
                                val tol = if (step == 1) 6.0f else 12.0f
                                val err = kotlin.math.abs(seg.centerX - expectedX)
                                if (err <= tol && err < minErr) {
                                    minErr = err
                                    bestChain = chain
                                }
                            }
                        }
                    }
                    if (bestChain != null) {
                        bestChain.add(seg)
                    } else {
                        vChains.add(mutableListOf(seg))
                    }
                }
            }

            val maxVThickness = maxOf(8, (w * 0.045f).toInt())
            val confirmedVChains = vChains.filter { chain ->
                val spansValid = chain.size >= (if (B >= 3) 2 else 1)
                val thinValid = chain.all { (it.maxX - it.minX + 1) <= maxVThickness }
                spansValid && thinValid
            }

            var removedV = 0
            for (chain in confirmedVChains) {
                removedV++
                val anchors = chain.map { seg ->
                    val yAnchor = (seg.bandIndex + 0.5f) * h / B
                    Pair(yAnchor, seg.centerX)
                }

                for (y in 0 until h) {
                    val xLine: Float = if (anchors.size == 1) {
                        anchors[0].second
                    } else if (y <= anchors.first().first) {
                        val slope = (anchors[1].second - anchors[0].second) / (anchors[1].first - anchors[0].first)
                        anchors[0].second + slope * (y - anchors[0].first)
                    } else if (y >= anchors.last().first) {
                        val slope = (anchors.last().second - anchors[anchors.size - 2].second) / (anchors.last().first - anchors[anchors.size - 2].first)
                        anchors.last().second + slope * (y - anchors.last().first)
                    } else {
                        var k = 0
                        while (k < anchors.size - 1 && anchors[k + 1].first < y) {
                            k++
                        }
                        val t = (y - anchors[k].first) / (anchors[k + 1].first - anchors[k].first)
                        anchors[k].second + t * (anchors[k + 1].second - anchors[k].second)
                    }

                    val xCenter = xLine.roundToInt().coerceIn(0, w - 1)
                    val xMin = (xCenter - 2).coerceAtLeast(0)
                    val xMax = (xCenter + 2).coerceAtMost(w - 1)

                    val rowOff = y * w
                    for (x in xMin..xMax) {
                        val idx = rowOff + x
                        if (ratio[idx] > 0.66f) {
                            ratio[idx] = 1.0f
                        }
                    }
                }
            }

            return Pair(removedH, removedV)
        }

        fun computeAdaptiveBounds(ratio: FloatArray): Pair<Float, Float> {
            val hist = IntArray(100)
            val total = ratio.size
            for (i in 0 until total) {
                val v = ratio[i].coerceIn(0f, 1f)
                val bin = (v * 99.99f).toInt().coerceIn(0, 99)
                hist[bin]++
            }

            val targetCount = (total * 0.01f).toInt().coerceAtLeast(1)
            var cum = 0
            var p1Bin = 0
            for (b in 0 until 100) {
                cum += hist[b]
                if (cum >= targetCount) {
                    p1Bin = b
                    break
                }
            }
            val p1 = (p1Bin + 0.5f) / 100f

            val (lower, upper) = if (p1 > 0.56f) {
                val up = 0.90f
                val low = (p1 + 0.02f).coerceIn(0.45f, 0.78f)
                Pair(low, up)
            } else {
                val up = 0.87f
                val low = (p1 + 0.06f).coerceIn(0.36f, 0.70f)
                Pair(low, up)
            }
            return Pair(lower, upper)
        }

        fun detectDarkThemeBackground(luma: FloatArray, w: Int, h: Int): Boolean {
            val totalPixels = w * h
            if (totalPixels == 0 || w <= 4 || h <= 4) return false

            var borderCount = 0
            val borderHist = IntArray(64)
            val allHist = IntArray(64)

            for (y in 0 until h) {
                val rOff = y * w
                val isBorderY = (y < 2 || y >= h - 2)
                for (x in 0 until w) {
                    val l = luma[rOff + x]
                    val bin = (l / 4.0f).toInt().coerceIn(0, 63)
                    allHist[bin]++
                    if (isBorderY || x < 2 || x >= w - 2) {
                        borderHist[bin]++
                        borderCount++
                    }
                }
            }

            if (borderCount == 0) return false

            var cumBorder = 0
            var borderMedianBin = 0
            val halfBorder = borderCount / 2
            for (b in 0 until 64) {
                cumBorder += borderHist[b]
                if (cumBorder >= halfBorder) {
                    borderMedianBin = b
                    break
                }
            }
            val borderMedian = (borderMedianBin + 0.5f) * 4.0f

            var cumAll = 0
            var p95Bin = 0
            val target95 = (totalPixels * 0.95f).toInt()
            for (b in 0 until 64) {
                cumAll += allHist[b]
                if (cumAll >= target95) {
                    p95Bin = b
                    break
                }
            }
            val p95 = (p95Bin + 0.5f) * 4.0f

            return borderMedian < 95f && p95 > borderMedian + 45f
        }

        fun eraseDisconnectedSideSpecks(outPixels: IntArray, w: Int, h: Int) {
            if (w < 10 || h < 10) return

            val colInkCount = IntArray(w)
            for (y in 0 until h) {
                val rOff = y * w
                for (x in 0 until w) {
                    if ((outPixels[rOff + x] and 0xFF) < 200) {
                        colInkCount[x]++
                    }
                }
            }

            if (colInkCount[0] > 0 || colInkCount[1] > 0) {
                var leftEnd = -1
                var x = 0
                while (x < w) {
                    if (colInkCount[x] > 0) {
                        leftEnd = x
                        x++
                    } else if (x + 1 < w && colInkCount[x + 1] > 0) {
                        x++
                    } else {
                        break
                    }
                }

                val maxAllowedLeftWidth = maxOf(14, (w * 0.04f).toInt())
                if (leftEnd >= 0 && leftEnd <= maxAllowedLeftWidth) {
                    var emptyGap = 0
                    var gx = leftEnd + 1
                    while (gx < w && colInkCount[gx] == 0) {
                        emptyGap++
                        gx++
                    }
                    val minGap = maxOf(18, (h * 0.35f).toInt())
                    var hasSubstantialInkRight = false
                    for (rx in (leftEnd + 1 + emptyGap) until w) {
                        if (colInkCount[rx] > 0) {
                            hasSubstantialInkRight = true
                            break
                        }
                    }

                    if (emptyGap >= minGap && hasSubstantialInkRight) {
                        for (y in 0 until h) {
                            val rOff = y * w
                            for (c in 0..leftEnd) {
                                outPixels[rOff + c] = 0xFFFFFFFF.toInt()
                            }
                        }
                    }
                }
            }

            if (colInkCount[w - 1] > 0 || colInkCount[w - 2] > 0) {
                var rightStart = w
                var x = w - 1
                while (x >= 0) {
                    if (colInkCount[x] > 0) {
                        rightStart = x
                        x--
                    } else if (x - 1 >= 0 && colInkCount[x - 1] > 0) {
                        x--
                    } else {
                        break
                    }
                }

                val maxAllowedRightWidth = maxOf(14, (w * 0.04f).toInt())
                if (rightStart < w && (w - 1 - rightStart) <= maxAllowedRightWidth) {
                    var emptyGap = 0
                    var gx = rightStart - 1
                    while (gx >= 0 && colInkCount[gx] == 0) {
                        emptyGap++
                        gx--
                    }
                    val minGap = maxOf(18, (h * 0.35f).toInt())
                    var hasSubstantialInkLeft = false
                    for (lx in 0 until (rightStart - emptyGap)) {
                        if (colInkCount[lx] > 0) {
                            hasSubstantialInkLeft = true
                            break
                        }
                    }

                    if (emptyGap >= minGap && hasSubstantialInkLeft) {
                        for (y in 0 until h) {
                            val rOff = y * w
                            for (c in rightStart until w) {
                                outPixels[rOff + c] = 0xFFFFFFFF.toInt()
                            }
                        }
                    }
                }
            }
        }
    }
}
