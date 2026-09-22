package com.example.hdrcamera.gl

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log

class HDRSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    companion object {
        private const val TAG = "HDRSurfaceView"
    }

    init {
        setEGLContextClientVersion(3)
        // Request 10-bit RGBA (10:10:10:2) with 16-bit depth
        try {
            setEGLConfigChooser(10, 10, 10, 2, 16, 0)
            holder.setFormat(PixelFormat.RGBA_1010102)
            Log.i(TAG, "Configured 10-bit RGBA_1010102 HDR EGL Surface")
        } catch (e: Exception) {
            Log.w(TAG, "10-bit surface chooser fallback to standard RGBA_8888: ${e.message}")
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            holder.setFormat(PixelFormat.RGBA_8888)
        }
        preserveEGLContextOnPause = true
    }
}
