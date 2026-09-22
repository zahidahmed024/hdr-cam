package com.example.hdrcamera.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class Texture3DLutLoader {
    private val lutCache = mutableMapOf<String, Int>()
    private val lutSize = 32

    fun getOrCreateLutTexture(lutId: String): Int {
        lutCache[lutId]?.let { return it }

        val textureId = generateLutTexture(lutId)
        lutCache[lutId] = textureId
        return textureId
    }

    private fun generateLutTexture(lutId: String): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_BASE_LEVEL, 0)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAX_LEVEL, 0)

        val totalPixels = lutSize * lutSize * lutSize
        val buffer = ByteBuffer.allocateDirect(totalPixels * 4).order(ByteOrder.nativeOrder())

        for (z in 0 until lutSize) {
            val bNorm = z.toFloat() / (lutSize - 1)
            for (y in 0 until lutSize) {
                val gNorm = y.toFloat() / (lutSize - 1)
                for (x in 0 until lutSize) {
                    val rNorm = x.toFloat() / (lutSize - 1)

                    val graded = evaluateLutColor(lutId, rNorm, gNorm, bNorm)
                    buffer.put((clamp01(graded[0]) * 255f).toInt().toByte())
                    buffer.put((clamp01(graded[1]) * 255f).toInt().toByte())
                    buffer.put((clamp01(graded[2]) * 255f).toInt().toByte())
                    buffer.put(255.toByte())
                }
            }
        }
        buffer.position(0)

        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGBA8,
            lutSize,
            lutSize,
            lutSize,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            buffer
        )

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        return textureId
    }

    private fun evaluateLutColor(lutId: String, r: Float, g: Float, b: Float): FloatArray {
        return when (lutId) {
            "cinematic" -> {
                // Teal shadows, warm orange highlights
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val shadowFactor = 1.0f - smoothStep(0.1f, 0.6f, luma)
                val highlightFactor = smoothStep(0.35f, 0.9f, luma)

                var rOut = r + highlightFactor * 0.18f - shadowFactor * 0.08f
                var gOut = g + highlightFactor * 0.06f + shadowFactor * 0.04f
                var bOut = b - highlightFactor * 0.12f + shadowFactor * 0.22f
                floatArrayOf(rOut, gOut, bOut)
            }
            "golden_hour" -> {
                // Sunset glow, rich gold highlights, warm lifted shadows
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val rOut = r.pow(0.85f) * 1.15f
                val gOut = g.pow(0.92f) * 1.05f
                val bOut = b.pow(1.18f) * 0.82f
                floatArrayOf(rOut, gOut, bOut)
            }
            "mono" -> {
                // Rich fine-grain monochrome with film-like S-curve
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                val sCurved = sCurve(luma)
                floatArrayOf(sCurved, sCurved, sCurved)
            }
            "vivid" -> {
                // High saturation, vibrant sky blues and greens
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val rOut = luma + (r - luma) * 1.4f
                val gOut = luma + (g - luma) * 1.35f
                val bOut = luma + (b - luma) * 1.45f
                floatArrayOf(rOut, gOut, bOut)
            }
            "cyberpunk" -> {
                // Neon magenta and electric cyan split
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val rOut = r * 1.25f + (1f - luma) * 0.1f
                val gOut = g * 0.85f + (1f - luma) * 0.15f
                val bOut = b * 1.35f + (1f - luma) * 0.25f
                floatArrayOf(rOut, gOut, bOut)
            }
            else -> {
                // "natural": gentle highlight preservation and subtle film tone curve
                val rOut = sCurve(r)
                val gOut = sCurve(g)
                val bOut = sCurve(b)
                floatArrayOf(rOut, gOut, bOut)
            }
        }
    }

    private fun sCurve(v: Float): Float {
        return v * v * (3f - 2f * v)
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = clamp01((x - edge0) / (edge1 - edge0))
        return t * t * (3f - 2f * t)
    }

    private fun clamp01(v: Float): Float = max(0f, min(1f, v))

    fun release() {
        for (id in lutCache.values) {
            val arr = intArrayOf(id)
            GLES30.glDeleteTextures(1, arr, 0)
        }
        lutCache.clear()
    }
}
