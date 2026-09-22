package com.example.hdrcamera.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.example.hdrcamera.model.FilterParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CameraGLRenderer(
    private val context: Context,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "CameraGLRenderer"

        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f, 0.0f,
             1.0f, -1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f,
             1.0f,  1.0f, 0.0f
        )

        private val TEX_COORDS = floatArrayOf(
            0.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 0.0f, 0.0f, 1.0f,
            0.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 0.0f, 1.0f
        )
    }

    private var surfaceView: GLSurfaceView? = null
    private var programId = 0
    private var cameraTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var updateSurface = false

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(QUAD_COORDS)
            position(0)
        }

    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(TEX_COORDS)
            position(0)
        }

    private val stMatrix = FloatArray(16)
    private var lutLoader: Texture3DLutLoader? = null

    // Uniform locations
    private var uSTMatrixLoc = -1
    private var uCameraTextureLoc = -1
    private var uLutTextureLoc = -1
    private var uTexelSizeLoc = -1
    private var uSharpnessLoc = -1
    private var uHighlightBoostLoc = -1
    private var uShadowBoostLoc = -1
    private var uExposureLoc = -1
    private var uContrastLoc = -1
    private var uSaturationLoc = -1
    private var uTemperatureLoc = -1
    private var uTintLoc = -1
    private var uLutIntensityLoc = -1
    private var uFastModeLoc = -1

    private var surfaceWidth = 1080
    private var surfaceHeight = 1920

    @Volatile
    private var currentParams = FilterParameters()

    fun setSurfaceView(view: GLSurfaceView) {
        surfaceView = view
    }

    fun updateParameters(params: FilterParameters) {
        currentParams = params
        try {
            surfaceView?.requestRender()
        } catch (_: Throwable) {
            // View or GLThread not ready yet
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // Compile shaders
        val vertShader = GLShaderUtils.loadShaderFromAssets(context, "shaders/camera_vert.glsl", GLES30.GL_VERTEX_SHADER)
        val fragShader = GLShaderUtils.loadShaderFromAssets(context, "shaders/hdr_filter_frag.glsl", GLES30.GL_FRAGMENT_SHADER)
        programId = GLShaderUtils.createProgram(vertShader, fragShader)

        // Query uniforms
        uSTMatrixLoc = GLES30.glGetUniformLocation(programId, "uSTMatrix")
        uCameraTextureLoc = GLES30.glGetUniformLocation(programId, "uCameraTexture")
        uLutTextureLoc = GLES30.glGetUniformLocation(programId, "uLutTexture")
        uTexelSizeLoc = GLES30.glGetUniformLocation(programId, "uTexelSize")
        uSharpnessLoc = GLES30.glGetUniformLocation(programId, "uSharpness")
        uHighlightBoostLoc = GLES30.glGetUniformLocation(programId, "uHighlightBoost")
        uShadowBoostLoc = GLES30.glGetUniformLocation(programId, "uShadowBoost")
        uExposureLoc = GLES30.glGetUniformLocation(programId, "uExposure")
        uContrastLoc = GLES30.glGetUniformLocation(programId, "uContrast")
        uSaturationLoc = GLES30.glGetUniformLocation(programId, "uSaturation")
        uTemperatureLoc = GLES30.glGetUniformLocation(programId, "uTemperature")
        uTintLoc = GLES30.glGetUniformLocation(programId, "uTint")
        uLutIntensityLoc = GLES30.glGetUniformLocation(programId, "uLutIntensity")
        uFastModeLoc = GLES30.glGetUniformLocation(programId, "uFastMode")

        // Create OES Camera Texture
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Initialize 3D LUT loader
        lutLoader = Texture3DLutLoader()

        // Create SurfaceTexture
        surfaceTexture = SurfaceTexture(cameraTextureId).apply {
            setOnFrameAvailableListener(this@CameraGLRenderer)
            onSurfaceTextureReady(this)
        }

        Matrix.setIdentityM(stMatrix, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(this) {
            if (updateSurface) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(stMatrix)
                updateSurface = false
            }
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (programId == 0) return

        GLES30.glUseProgram(programId)

        // Bind OES Camera Texture to Unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glUniform1i(uCameraTextureLoc, 0)

        // Bind 3D LUT Texture to Unit 1
        val params = currentParams
        val lutId = params.lutId
        lutLoader?.let { loader ->
            val lutTexId = loader.getOrCreateLutTexture(lutId)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
            GLES30.glUniform1i(uLutTextureLoc, 1)
        }

        // Pass Uniforms
        GLES30.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)
        // Set camera preview stream texel size (1080x1920)
        GLES30.glUniform2f(uTexelSizeLoc, 1.0f / 1080.0f, 1.0f / 1920.0f)
        GLES30.glUniform1f(uSharpnessLoc, params.sharpness)
        GLES30.glUniform1f(uHighlightBoostLoc, params.highlightRecovery)
        GLES30.glUniform1f(uShadowBoostLoc, params.shadowBoost)
        GLES30.glUniform1f(uExposureLoc, params.exposure)
        GLES30.glUniform1f(uContrastLoc, params.contrast)
        GLES30.glUniform1f(uSaturationLoc, params.saturation)
        GLES30.glUniform1f(uTemperatureLoc, params.temperature)
        GLES30.glUniform1f(uTintLoc, params.tint)
        GLES30.glUniform1f(uLutIntensityLoc, params.lutIntensity)
        GLES30.glUniform1i(uFastModeLoc, if (params.fastPreview) 1 else 0)

        // Vertex Positions
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, vertexBuffer)

        // Texture Coordinates
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 0, texCoordBuffer)

        // Draw Quad
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glUseProgram(0)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(this) {
            updateSurface = true
        }
        surfaceView?.requestRender()
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        lutLoader?.release()
        lutLoader = null
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
    }
}
