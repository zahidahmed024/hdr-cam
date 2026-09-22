package com.example.hdrcamera.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.hdrcamera.model.FilterParameters
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class UltraHdrProcessor(private val context: Context) {

    companion object {
        private const val TAG = "UltraHdrProcessor"
    }

    private data class FrameOffset(val dx: Int, val dy: Int)

    private val selfieSegmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        Segmentation.getClient(options)
    }

    suspend fun processAndSaveImage(
        jpegBytes: ByteArray,
        params: FilterParameters,
        rotationDegrees: Int = 90,
        isFrontCamera: Boolean = false,
        isPortraitMode: Boolean = false,
        bokehAperture: Float = 2.8f,
        onComplete: (Uri?, Bitmap?) -> Unit
    ) {
        processAndSaveBurst(
            jpegFrames = listOf(jpegBytes),
            params = params,
            rotationDegrees = rotationDegrees,
            isFrontCamera = isFrontCamera,
            isPortraitMode = isPortraitMode,
            bokehAperture = bokehAperture,
            onComplete = onComplete
        )
    }

    suspend fun processAndSaveBurst(
        jpegFrames: List<ByteArray>,
        params: FilterParameters,
        rotationDegrees: Int = 90,
        isFrontCamera: Boolean = false,
        isPortraitMode: Boolean = false,
        bokehAperture: Float = 2.8f,
        onComplete: (Uri?, Bitmap?) -> Unit
    ) = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        try {
            if (jpegFrames.isEmpty()) {
                Log.e(TAG, "processAndSaveBurst received empty frames list")
                withContext(Dispatchers.Main) { onComplete(null, null) }
                return@withContext
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f) // mirror selfie
                }
            }

            // 1. Decode reference frame (Frame 0)
            val refRaw = BitmapFactory.decodeByteArray(jpegFrames[0], 0, jpegFrames[0].size, decodeOptions)
                ?: run {
                    Log.e(TAG, "Failed to decode reference frame JPEG bytes")
                    withContext(Dispatchers.Main) { onComplete(null, null) }
                    return@withContext
                }

            val refOriented = if (rotationDegrees != 0 || isFrontCamera) {
                Bitmap.createBitmap(refRaw, 0, 0, refRaw.width, refRaw.height, matrix, true).also {
                    if (it != refRaw) refRaw.recycle()
                }
            } else {
                refRaw
            }

            val width = refOriented.width
            val height = refOriented.height
            val totalPixels = width * height

            val fusedBitmap: Bitmap
            if (jpegFrames.size == 1) {
                fusedBitmap = refOriented
            } else {
                val fusionStart = System.currentTimeMillis()
                val refPixels = IntArray(totalPixels)
                refOriented.getPixels(refPixels, 0, width, 0, 0, width, height)

                // Decode remaining frames in parallel
                val otherFramesData = coroutineScope {
                    (1 until jpegFrames.size).map { frameIdx ->
                        async(Dispatchers.Default) {
                            try {
                                val raw = BitmapFactory.decodeByteArray(jpegFrames[frameIdx], 0, jpegFrames[frameIdx].size, decodeOptions)
                                    ?: return@async null
                                val oriented = if (rotationDegrees != 0 || isFrontCamera) {
                                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
                                        if (it != raw) raw.recycle()
                                    }
                                } else {
                                    raw
                                }
                                val pixels = IntArray(totalPixels)
                                oriented.getPixels(pixels, 0, width, 0, 0, width, height)
                                oriented.recycle()
                                pixels
                            } catch (e: Exception) {
                                Log.w(TAG, "Error decoding burst frame $frameIdx: ${e.message}")
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                if (otherFramesData.isEmpty()) {
                    fusedBitmap = refOriented
                } else {
                    // Fast sub-grid motion estimation (dx, dy) for each secondary frame
                    val offsets = otherFramesData.map { targetPixels ->
                        estimateOffset(refPixels, targetPixels, width, height)
                    }

                    val fusedPixels = IntArray(totalPixels)
                    val numWorkers = 4
                    val chunkSize = totalPixels / numWorkers

                    coroutineScope {
                        (0 until numWorkers).map { workerIdx ->
                            async(Dispatchers.Default) {
                                val start = workerIdx * chunkSize
                                val end = if (workerIdx == numWorkers - 1) totalPixels else start + chunkSize

                                for (i in start until end) {
                                    val x = i % width
                                    val y = i / width

                                    val p0 = refPixels[i]
                                    val r0 = (p0 shr 16) and 0xFF
                                    val g0 = (p0 shr 8) and 0xFF
                                    val b0 = p0 and 0xFF

                                    var sumR = r0
                                    var sumG = g0
                                    var sumB = b0
                                    var count = 1

                                    for (k in otherFramesData.indices) {
                                        val offset = offsets[k]
                                        val sx = x + offset.dx
                                        val sy = y + offset.dy

                                        if (sx in 0 until width && sy in 0 until height) {
                                            val pk = otherFramesData[k][sy * width + sx]
                                            val rk = (pk shr 16) and 0xFF
                                            val gk = (pk shr 8) and 0xFF
                                            val bk = pk and 0xFF

                                            // Anti-ghosting motion threshold (reject moved elements, average static noise)
                                            val diff = kotlin.math.abs(rk - r0) + kotlin.math.abs(gk - g0) + kotlin.math.abs(bk - b0)
                                            if (diff < 48) {
                                                sumR += rk
                                                sumG += gk
                                                sumB += bk
                                                count++
                                            }
                                        }
                                    }

                                    val avgR = sumR / count
                                    val avgG = sumG / count
                                    val avgB = sumB / count

                                    fusedPixels[i] = (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
                                }
                            }
                        }.awaitAll()
                    }

                    fusedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    fusedBitmap.setPixels(fusedPixels, 0, width, 0, 0, width, height)
                    refOriented.recycle()
                    Log.i(TAG, "Multi-frame fusion of ${1 + otherFramesData.size} frames completed in ${System.currentTimeMillis() - fusionStart}ms")
                }
            }

            // 2. Fast parallel clarity sharpening & HDR color grading on noise-free canvas
            val filterStart = System.currentTimeMillis()
            val processedBitmap = applyHighClarityFilter(fusedBitmap, params, isPortraitMode)
            Log.i(TAG, "Applied high clarity & HDR filter in ${System.currentTimeMillis() - filterStart}ms")

            // 3. Neural Subject Segmentation & Optical Disc Bokeh in Portrait Mode
            val finalBitmap = if (isPortraitMode) {
                val bokehStart = System.currentTimeMillis()
                val bokehResult = applyPortraitBokeh(processedBitmap, bokehAperture)
                Log.i(TAG, "Applied portrait bokeh in ${System.currentTimeMillis() - bokehStart}ms")
                bokehResult
            } else {
                processedBitmap
            }

            // 4. Quick thumbnail generation for immediate UI feedback
            val thumbSize = 160
            val thumbBitmap = Bitmap.createScaledBitmap(
                finalBitmap,
                thumbSize,
                (thumbSize * height) / width,
                true
            )

            // 5. Save to DCIM/Camera with 99% JPEG quality for maximum sharpness & fidelity
            val saveStart = System.currentTimeMillis()
            val savedUri = saveToMediaStore(finalBitmap)
            Log.i(TAG, "Saved to Gallery (DCIM/Camera) in ${System.currentTimeMillis() - saveStart}ms. Total: ${System.currentTimeMillis() - startTime}ms")

            if (finalBitmap != fusedBitmap) {
                fusedBitmap.recycle()
            }

            withContext(Dispatchers.Main) {
                onComplete(savedUri, thumbBitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processAndSaveBurst", e)
            withContext(Dispatchers.Main) {
                onComplete(null, null)
            }
        }
    }

    private fun estimateOffset(
        refPixels: IntArray,
        targetPixels: IntArray,
        width: Int,
        height: Int
    ): FrameOffset {
        val startX = width / 4
        val endX = width * 3 / 4
        val startY = height / 4
        val endY = height * 3 / 4
        val stepX = ((endX - startX) / 48).coerceAtLeast(1)
        val stepY = ((endY - startY) / 48).coerceAtLeast(1)

        var bestDx = 0
        var bestDy = 0
        var minSAD = Long.MAX_VALUE

        // Stage 1: Coarse search [-14..14] with step 2
        for (dy in -14..14 step 2) {
            for (dx in -14..14 step 2) {
                var sad = 0L
                for (gy in startY until endY step stepY) {
                    val refRow = gy * width
                    val tgtRow = (gy + dy) * width
                    for (gx in startX until endX step stepX) {
                        val pRef = refPixels[refRow + gx]
                        val pTgt = targetPixels[tgtRow + (gx + dx)]
                        val lumaRef = (pRef shr 8) and 0xFF
                        val lumaTgt = (pTgt shr 8) and 0xFF
                        sad += kotlin.math.abs(lumaRef - lumaTgt)
                    }
                }
                if (sad < minSAD) {
                    minSAD = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        // Stage 2: Fine search [-2..+2] with step 1
        val rStartX = (bestDx - 2).coerceAtLeast(-16)
        val rEndX = (bestDx + 2).coerceAtMost(16)
        val rStartY = (bestDy - 2).coerceAtLeast(-16)
        val rEndY = (bestDy + 2).coerceAtMost(16)

        for (dy in rStartY..rEndY) {
            for (dx in rStartX..rEndX) {
                var sad = 0L
                for (gy in startY until endY step stepY) {
                    val refRow = gy * width
                    val tgtRow = (gy + dy) * width
                    for (gx in startX until endX step stepX) {
                        val pRef = refPixels[refRow + gx]
                        val pTgt = targetPixels[tgtRow + (gx + dx)]
                        val lumaRef = (pRef shr 8) and 0xFF
                        val lumaTgt = (pTgt shr 8) and 0xFF
                        sad += kotlin.math.abs(lumaRef - lumaTgt)
                    }
                }
                if (sad < minSAD) {
                    minSAD = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        Log.d(TAG, "Burst frame offset: dx=$bestDx, dy=$bestDy (SAD=$minSAD)")
        return FrameOffset(bestDx, bestDy)
    }

    private suspend fun applyHighClarityFilter(
        src: Bitmap,
        params: FilterParameters,
        isPortraitMode: Boolean
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = src.width
        val height = src.height
        val totalPixels = width * height

        // Precompute 256-entry lookup tables for R, G, B tone curves
        val rTable = IntArray(256)
        val gTable = IntArray(256)
        val bTable = IntArray(256)

        val expFactor = 2.0f.pow(params.exposure)
        val contrast = if (isPortraitMode) params.contrast * 0.95f else params.contrast
        val saturation = if (isPortraitMode) params.saturation * 1.05f else params.saturation
        val temp = if (isPortraitMode) params.temperature + 0.08f else params.temperature
        val tint = params.tint
        val highlightRec = params.highlightRecovery
        val shadowB = if (isPortraitMode) params.shadowBoost + 0.1f else params.shadowBoost
        val lutId = params.lutId
        val lutInt = params.lutIntensity
        val sharpness = params.sharpness

        for (i in 0..255) {
            val norm = i / 255.0f

            // Red
            var r = norm * expFactor
            r = clamp01(r + temp * 0.30f)
            r = applyToneCurves(r, highlightRec, shadowB, contrast)

            // Green
            var g = norm * expFactor
            g = clamp01(g + tint * 0.25f)
            g = applyToneCurves(g, highlightRec, shadowB, contrast)

            // Blue
            var b = norm * expFactor
            b = clamp01(b - temp * 0.30f)
            b = applyToneCurves(b, highlightRec, shadowB, contrast)

            // LUT blending
            if (lutInt > 0.01f) {
                val lut = evaluateLut(lutId, r, g, b)
                r = r * (1f - lutInt) + lut[0] * lutInt
                g = g * (1f - lutInt) + lut[1] * lutInt
                b = b * (1f - lutInt) + lut[2] * lutInt
            }

            rTable[i] = (clamp01(r) * 255.0f).toInt()
            gTable[i] = (clamp01(g) * 255.0f).toInt()
            bTable[i] = (clamp01(b) * 255.0f).toInt()
        }

        val inPixels = IntArray(totalPixels)
        src.getPixels(inPixels, 0, width, 0, 0, width, height)
        val outPixels = IntArray(totalPixels)

        val numChunks = 4
        val chunkSize = totalPixels / numChunks
        val doSharpen = sharpness > 0.05f
        val sharpWeight = (sharpness * 2.5f)
        val step3Y = 3 * width

        coroutineScope {
            val jobs = (0 until numChunks).map { chunkIndex ->
                async(Dispatchers.Default) {
                    val start = chunkIndex * chunkSize
                    val end = if (chunkIndex == numChunks - 1) totalPixels else start + chunkSize

                    for (i in start until end) {
                        val pixel = inPixels[i]
                        var r = (pixel shr 16) and 0xFF
                        var g = (pixel shr 8) and 0xFF
                        var b = pixel and 0xFF

                        // High-clarity dual-radius unsharp mask for tack-sharp detail at 12MP (3072x4096)
                        if (doSharpen && i >= step3Y && i < totalPixels - step3Y) {
                            val col = i % width
                            if (col >= 3 && col < width - 3) {
                                // 1. Optical edge boundary (3px radius)
                                val pUp3 = inPixels[i - step3Y]
                                val pDown3 = inPixels[i + step3Y]
                                val pLeft3 = inPixels[i - 3]
                                val pRight3 = inPixels[i + 3]

                                val edgeBlurR = (((pUp3 shr 16) and 0xFF) + ((pDown3 shr 16) and 0xFF) +
                                        ((pLeft3 shr 16) and 0xFF) + ((pRight3 shr 16) and 0xFF)) shr 2
                                val edgeBlurG = (((pUp3 shr 8) and 0xFF) + ((pDown3 shr 8) and 0xFF) +
                                        ((pLeft3 shr 8) and 0xFF) + ((pRight3 shr 8) and 0xFF)) shr 2
                                val edgeBlurB = ((pUp3 and 0xFF) + (pDown3 and 0xFF) +
                                        (pLeft3 and 0xFF) + (pRight3 and 0xFF)) shr 2

                                // 2. Fine micro-texture (1px radius)
                                val pUp1 = inPixels[i - width]
                                val pDown1 = inPixels[i + width]
                                val pLeft1 = inPixels[i - 1]
                                val pRight1 = inPixels[i + 1]

                                val microBlurR = (((pUp1 shr 16) and 0xFF) + ((pDown1 shr 16) and 0xFF) +
                                        ((pLeft1 shr 16) and 0xFF) + ((pRight1 shr 16) and 0xFF)) shr 2
                                val microBlurG = (((pUp1 shr 8) and 0xFF) + ((pDown1 shr 8) and 0xFF) +
                                        ((pLeft1 shr 8) and 0xFF) + ((pRight1 shr 8) and 0xFF)) shr 2
                                val microBlurB = ((pUp1 and 0xFF) + (pDown1 and 0xFF) +
                                        (pLeft1 and 0xFF) + (pRight1 and 0xFF)) shr 2

                                // Combined high-pass detail (65% optical edge + 35% micro-texture)
                                val diffR = (r - edgeBlurR) * 0.65f + (r - microBlurR) * 0.35f
                                val diffG = (g - edgeBlurG) * 0.65f + (g - microBlurG) * 0.35f
                                val diffB = (b - edgeBlurB) * 0.65f + (b - microBlurB) * 0.35f

                                r = clampInt((r + diffR * sharpWeight).toInt())
                                g = clampInt((g + diffG * sharpWeight).toInt())
                                b = clampInt((b + diffB * sharpWeight).toInt())
                            }
                        }

                        // Apply color curve lookup
                        r = rTable[r]
                        g = gTable[g]
                        b = bTable[b]

                        // Saturation
                        if (saturation < 0.98f || saturation > 1.02f) {
                            val luma = (r * 77 + g * 150 + b * 29) shr 8
                            r = clampInt((luma + (r - luma) * saturation).toInt())
                            g = clampInt((luma + (g - luma) * saturation).toInt())
                            b = clampInt((luma + (b - luma) * saturation).toInt())
                        }

                        outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
            jobs.awaitAll()
        }

        src.setPixels(outPixels, 0, width, 0, 0, width, height)
        src
    }

    private fun applyToneCurves(v: Float, highlightRec: Float, shadowB: Float, contrast: Float): Float {
        var res = v
        // Highlight recovery shoulder compression
        if (highlightRec > 1.01f) {
            val hlMask = smoothStep(0.35f, 0.90f, res)
            val compressed = res / (1.0f + (highlightRec - 1.0f) * res * 1.5f)
            res = res * (1f - hlMask) + compressed * hlMask
        }
        // Shadow boost
        if (shadowB > 0.01f) {
            val shadowMask = 1.0f - smoothStep(0.05f, 0.55f, res)
            res = clamp01(res + shadowB * 0.35f * shadowMask)
        }
        // Contrast
        res = clamp01((res - 0.18f) * contrast + 0.18f)
        return res
    }

    private fun evaluateLut(lutId: String, r: Float, g: Float, b: Float): FloatArray {
        return when (lutId) {
            "cinematic" -> {
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val sf = 1.0f - smoothStep(0.1f, 0.6f, luma)
                val hf = smoothStep(0.35f, 0.9f, luma)
                floatArrayOf(
                    clamp01(r + hf * 0.18f - sf * 0.08f),
                    clamp01(g + hf * 0.06f + sf * 0.04f),
                    clamp01(b - hf * 0.12f + sf * 0.22f)
                )
            }
            "golden_hour" -> {
                floatArrayOf(
                    clamp01(r.pow(0.85f) * 1.15f),
                    clamp01(g.pow(0.92f) * 1.05f),
                    clamp01(b.pow(1.18f) * 0.82f)
                )
            }
            "mono" -> {
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                val s = sCurve(luma)
                floatArrayOf(s, s, s)
            }
            "vivid" -> {
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                floatArrayOf(
                    clamp01(luma + (r - luma) * 1.4f),
                    clamp01(luma + (g - luma) * 1.35f),
                    clamp01(luma + (b - luma) * 1.45f)
                )
            }
            "cyberpunk" -> {
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                floatArrayOf(
                    clamp01(r * 1.25f + (1f - luma) * 0.1f),
                    clamp01(g * 0.85f + (1f - luma) * 0.15f),
                    clamp01(b * 1.35f + (1f - luma) * 0.25f)
                )
            }
            else -> {
                floatArrayOf(sCurve(r), sCurve(g), sCurve(b))
            }
        }
    }

    private fun sCurve(v: Float): Float = v * v * (3f - 2f * v)
    private fun smoothStep(e0: Float, e1: Float, x: Float): Float {
        val t = clamp01((x - e0) / (e1 - e0))
        return t * t * (3f - 2f * t)
    }
    private fun clamp01(v: Float): Float = max(0f, min(1f, v))
    private fun clampInt(v: Int): Int = max(0, min(255, v))

    private suspend fun applyPortraitBokeh(
        src: Bitmap,
        aperture: Float = 2.8f
    ): Bitmap = withContext(Dispatchers.Default) {
        val portraitStart = System.currentTimeMillis()
        val width = src.width
        val height = src.height
        val totalPixels = width * height

        // 1. Run ML Kit Neural Segmentation on downscaled bitmap for fast inference (< 25ms)
        val segScale = 512f / max(width, height)
        val segW = (width * segScale).toInt().coerceAtLeast(1)
        val segH = (height * segScale).toInt().coerceAtLeast(1)
        val smallBitmap = Bitmap.createScaledBitmap(src, segW, segH, true)

        var maskW = 0
        var maskH = 0
        val maskArray: FloatArray? = try {
            val inputImage = InputImage.fromBitmap(smallBitmap, 0)
            val result = selfieSegmenter.process(inputImage).await()
            val maskBuffer = result.buffer
            maskW = result.width
            maskH = result.height

            val floatArray = FloatArray(maskW * maskH)
            maskBuffer.rewind()
            maskBuffer.asFloatBuffer().get(floatArray)
            floatArray
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit segmentation error: ${e.message}, falling back to radial depth")
            null
        } finally {
            if (smallBitmap != src) smallBitmap.recycle()
        }

        // 2. Determine if a human subject is detected
        var hasPerson = false
        if (maskArray != null && maskW > 0 && maskH > 0) {
            var maxConfidence = 0f
            for (v in maskArray) {
                if (v > maxConfidence) maxConfidence = v
            }
            if (maxConfidence > 0.40f) {
                hasPerson = true
            }
        }

        // 3. Generate Optical Disc Bokeh Background
        val blurRadius = when {
            aperture <= 1.6f -> 14
            aperture <= 2.2f -> 11
            aperture <= 3.0f -> 8
            aperture <= 4.5f -> 6
            aperture <= 6.0f -> 4
            else -> 2
        }

        val bokehScale = 4
        val bw = (width / bokehScale).coerceAtLeast(1)
        val bh = (height / bokehScale).coerceAtLeast(1)
        val bTotal = bw * bh

        val bgSmall = Bitmap.createScaledBitmap(src, bw, bh, true)
        val bgPixels = IntArray(bTotal)
        bgSmall.getPixels(bgPixels, 0, bw, 0, 0, bw, bh)
        bgSmall.recycle()

        // Boost specular highlights so lights bloom into glowing circular bokeh balls
        for (i in 0 until bTotal) {
            val p = bgPixels[i]
            var r = (p shr 16) and 0xFF
            var g = (p shr 8) and 0xFF
            var b = p and 0xFF
            val luma = (r * 77 + g * 150 + b * 29) shr 8

            if (luma > 170) {
                val boost = 1.0f + ((luma - 170) / 85.0f) * 1.5f
                r = min(255, (r * boost).toInt())
                g = min(255, (g * boost).toInt())
                b = min(255, (b * boost).toInt())
            }
            bgPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // Apply 4-pass running-sum box blur (equivalent to smooth disc bokeh)
        val discPixels = IntArray(bTotal)
        val rad = (blurRadius / 2).coerceAtLeast(2)
        applyBoxBlurHorizontal(bgPixels, discPixels, bw, bh, rad)
        applyBoxBlurVertical(discPixels, bgPixels, bw, bh, rad)
        applyBoxBlurHorizontal(bgPixels, discPixels, bw, bh, rad)
        applyBoxBlurVertical(discPixels, bgPixels, bw, bh, rad)

        val bokehBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bokehBitmap.setPixels(bgPixels, 0, bw, 0, 0, bw, bh)

        val bokehUpscaled = Bitmap.createScaledBitmap(bokehBitmap, width, height, true)
        bokehBitmap.recycle()

        val fullBokehPixels = IntArray(totalPixels)
        bokehUpscaled.getPixels(fullBokehPixels, 0, width, 0, 0, width, height)
        bokehUpscaled.recycle()

        val srcPixels = IntArray(totalPixels)
        src.getPixels(srcPixels, 0, width, 0, 0, width, height)
        val outPixels = IntArray(totalPixels)

        // 4. Alpha Composition: Subject Mask (Tack-Sharp) + Optical Bokeh (Creamy Background)
        val numWorkers = 4
        val chunkSize = totalPixels / numWorkers

        coroutineScope {
            (0 until numWorkers).map { wIdx ->
                async(Dispatchers.Default) {
                    val start = wIdx * chunkSize
                    val end = if (wIdx == numWorkers - 1) totalPixels else start + chunkSize

                    for (i in start until end) {
                        val x = i % width
                        val y = i / width

                        val alpha: Float = if (hasPerson && maskArray != null) {
                            // Bilinear sample from neural mask
                            val mx = (x.toFloat() / width) * (maskW - 1)
                            val my = (y.toFloat() / height) * (maskH - 1)
                            val x0 = mx.toInt().coerceIn(0, maskW - 1)
                            val y0 = my.toInt().coerceIn(0, maskH - 1)
                            val x1 = (x0 + 1).coerceIn(0, maskW - 1)
                            val y1 = (y0 + 1).coerceIn(0, maskH - 1)
                            val fx = mx - x0
                            val fy = my - y0

                            val c00 = maskArray[y0 * maskW + x0]
                            val c10 = maskArray[y0 * maskW + x1]
                            val c01 = maskArray[y1 * maskW + x0]
                            val c11 = maskArray[y1 * maskW + x1]

                            val top = c00 * (1f - fx) + c10 * fx
                            val bot = c01 * (1f - fx) + c11 * fx
                            val rawAlpha = top * (1f - fy) + bot * fy

                            // Soft edge feathering so hair blends naturally without harsh cutouts
                            smoothStep(0.20f, 0.75f, rawAlpha)
                        } else {
                            // Object / Flower Fallback: Elliptical focal plane centered on subject
                            val normX = (x.toFloat() / width) - 0.5f
                            val normY = (y.toFloat() / height) - 0.52f
                            val dist = sqrt(normX * normX * 1.2f + normY * normY * 1.8f)
                            1.0f - smoothStep(0.18f, 0.48f, dist)
                        }

                        if (alpha >= 0.99f) {
                            outPixels[i] = srcPixels[i]
                        } else if (alpha <= 0.01f) {
                            outPixels[i] = fullBokehPixels[i]
                        } else {
                            val sP = srcPixels[i]
                            val bP = fullBokehPixels[i]

                            val sr = (sP shr 16) and 0xFF
                            val sg = (sP shr 8) and 0xFF
                            val sb = sP and 0xFF

                            val br = (bP shr 16) and 0xFF
                            val bg = (bP shr 8) and 0xFF
                            val bb = bP and 0xFF

                            val invA = 1.0f - alpha
                            val r = (sr * alpha + br * invA).toInt().coerceIn(0, 255)
                            val g = (sg * alpha + bg * invA).toInt().coerceIn(0, 255)
                            val b = (sb * alpha + bb * invA).toInt().coerceIn(0, 255)

                            outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
            }.awaitAll()
        }

        src.setPixels(outPixels, 0, width, 0, 0, width, height)
        Log.i(TAG, "Applied GCam ML portrait bokeh (hasPerson=$hasPerson, aperture=f/$aperture) in ${System.currentTimeMillis() - portraitStart}ms")
        src
    }

    private fun applyBoxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val div = 2 * radius + 1
        for (y in 0 until h) {
            val row = y * w
            var sumR = 0
            var sumG = 0
            var sumB = 0

            val firstP = src[row]
            val firstR = (firstP shr 16) and 0xFF
            val firstG = (firstP shr 8) and 0xFF
            val firstB = firstP and 0xFF

            sumR = firstR * (radius + 1)
            sumG = firstG * (radius + 1)
            sumB = firstB * (radius + 1)

            for (i in 1..radius) {
                val p = src[row + min(i, w - 1)]
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }

            for (x in 0 until w) {
                dst[row + x] = (0xFF shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)

                val leftX = max(0, x - radius)
                val rightX = min(w - 1, x + radius + 1)

                val pLeft = src[row + leftX]
                val pRight = src[row + rightX]

                sumR += ((pRight shr 16) and 0xFF) - ((pLeft shr 16) and 0xFF)
                sumG += ((pRight shr 8) and 0xFF) - ((pLeft shr 8) and 0xFF)
                sumB += (pRight and 0xFF) - (pLeft and 0xFF)
            }
        }
    }

    private fun applyBoxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val div = 2 * radius + 1
        for (x in 0 until w) {
            var sumR = 0
            var sumG = 0
            var sumB = 0

            val firstP = src[x]
            val firstR = (firstP shr 16) and 0xFF
            val firstG = (firstP shr 8) and 0xFF
            val firstB = firstP and 0xFF

            sumR = firstR * (radius + 1)
            sumG = firstG * (radius + 1)
            sumB = firstB * (radius + 1)

            for (i in 1..radius) {
                val p = src[min(i, h - 1) * w + x]
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }

            for (y in 0 until h) {
                val row = y * w
                dst[row + x] = (0xFF shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)

                val topY = max(0, y - radius)
                val botY = min(h - 1, y + radius + 1)

                val pTop = src[topY * w + x]
                val pBot = src[botY * w + x]

                sumR += ((pBot shr 16) and 0xFF) - ((pTop shr 16) and 0xFF)
                sumG += ((pBot shr 8) and 0xFF) - ((pTop shr 8) and 0xFF)
                sumB += (pBot and 0xFF) - (pTop and 0xFF)
            }
        }
    }

    private fun saveToMediaStore(bitmap: Bitmap): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "HDR_$timeStamp.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { stream: OutputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 99, stream) // Pristine 99% quality
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            // MediaScanner indexing
            try {
                val cursor = resolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)
                var path: String? = null
                cursor?.use {
                    if (it.moveToFirst()) {
                        val idx = it.getColumnIndex(MediaStore.Images.Media.DATA)
                        if (idx != -1) path = it.getString(idx)
                    }
                }
                if (path != null) {
                    MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/jpeg")) { p, u ->
                        Log.i(TAG, "Indexed into DCIM/Camera: $p -> $u")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaScanner error: ${e.message}")
            }

            Log.i(TAG, "Image saved with maximum clarity to DCIM/Camera: $uri")
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save photo to MediaStore", e)
            resolver.delete(uri, null, null)
            return null
        }
    }
}
