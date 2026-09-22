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
import kotlinx.coroutines.*
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

    suspend fun processAndSaveImage(
        jpegBytes: ByteArray,
        params: FilterParameters,
        rotationDegrees: Int = 90,
        isFrontCamera: Boolean = false,
        isPortraitMode: Boolean = false,
        onComplete: (Uri?, Bitmap?) -> Unit
    ) = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        try {
            // 1. Fast decode
            val options = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val rawBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                ?: run {
                    Log.e(TAG, "Failed to decode captured JPEG bytes")
                    withContext(Dispatchers.Main) { onComplete(null, null) }
                    return@withContext
                }

            // 2. Rotate to upright portrait orientation (3072x4096)
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f) // mirror selfie
                }
            }
            val orientedBitmap = if (rotationDegrees != 0 || isFrontCamera) {
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true).also {
                    if (it != rawBitmap) rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }

            val width = orientedBitmap.width
            val height = orientedBitmap.height
            Log.i(TAG, "Oriented capture to upright portrait (${width}x${height})")

            // 3. Fast parallel clarity sharpening & HDR color grading
            val filterStart = System.currentTimeMillis()
            val processedBitmap = applyHighClarityFilter(orientedBitmap, params, isPortraitMode)
            Log.i(TAG, "Applied high clarity & HDR filter in ${System.currentTimeMillis() - filterStart}ms")

            // 4. Quick thumbnail generation for immediate UI feedback
            val thumbSize = 160
            val thumbBitmap = Bitmap.createScaledBitmap(
                processedBitmap,
                thumbSize,
                (thumbSize * height) / width,
                true
            )

            // 5. Save to DCIM/Camera with 99% JPEG quality for maximum sharpness & fidelity
            val saveStart = System.currentTimeMillis()
            val savedUri = saveToMediaStore(processedBitmap)
            Log.i(TAG, "Saved to Gallery (DCIM/Camera) in ${System.currentTimeMillis() - saveStart}ms. Total: ${System.currentTimeMillis() - startTime}ms")

            if (processedBitmap != orientedBitmap) {
                orientedBitmap.recycle()
            }

            withContext(Dispatchers.Main) {
                onComplete(savedUri, thumbBitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processAndSaveImage", e)
            withContext(Dispatchers.Main) {
                onComplete(null, null)
            }
        }
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
