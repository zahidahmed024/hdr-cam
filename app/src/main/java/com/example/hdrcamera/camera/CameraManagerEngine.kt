package com.example.hdrcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.hdrcamera.model.FilterParameters
import com.example.hdrcamera.processing.UltraHdrProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private data class CaptureData(
    val params: FilterParameters,
    val isPortraitMode: Boolean,
    val callback: (android.net.Uri?, android.graphics.Bitmap?) -> Unit
)

class CameraManagerEngine(
    private val context: Context,
    private val characteristicsHelper: CameraCharacteristicsHelper,
    private val processor: UltraHdrProcessor
) {
    companion object {
        private const val TAG = "CameraManagerEngine"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cachedSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null

    var currentFacing = CameraCharacteristics.LENS_FACING_BACK
        private set
    var onFacingChanged: ((Int) -> Unit)? = null

    private var currentZoomRatio = 1.0f
    private var currentExposureIndex = -3 // -0.5 EV highlight protection
    private var is10BitActive = false

    var onSessionConfigured: ((Boolean) -> Unit)? = null
    var onCaptureStateChanged: ((Boolean) -> Unit)? = null

    // Single-shot pending capture callback reference
    private val pendingCapture = AtomicReference<CaptureData?>()

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2Background").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(surfaceTexture: SurfaceTexture) {
        cachedSurfaceTexture = surfaceTexture
        val cameraInfo = characteristicsHelper.getCameraInfo(currentFacing) ?: run {
            Log.e(TAG, "No suitable camera found for facing: $currentFacing")
            return
        }

        val previewSize = cameraInfo.optimalPreviewSize
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(surfaceTexture)

        // Setup capture ImageReader
        val photoSize = cameraInfo.maxPhotoSize
        Log.i(TAG, "Setting up capture ImageReader: ${photoSize.width}x${photoSize.height} for camera ${cameraInfo.cameraId} (facing $currentFacing)")

        val reader = ImageReader.newInstance(
            photoSize.width,
            photoSize.height,
            ImageFormat.JPEG,
            2
        )
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage()
            if (image != null) {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                val captureInfo = pendingCapture.getAndSet(null)
                if (captureInfo != null) {
                    val rotation = cameraInfo.sensorOrientation
                    val isFront = (currentFacing == CameraCharacteristics.LENS_FACING_FRONT)
                    CoroutineScope(Dispatchers.IO).launch {
                        processor.processAndSaveImage(
                            jpegBytes = bytes,
                            params = captureInfo.params,
                            rotationDegrees = rotation,
                            isFrontCamera = isFront,
                            isPortraitMode = captureInfo.isPortraitMode
                        ) { uri, thumb ->
                            onCaptureStateChanged?.invoke(false)
                            captureInfo.callback(uri, thumb)
                        }
                    }
                } else {
                    onCaptureStateChanged?.invoke(false)
                }
            }
        }, backgroundHandler)

        try {
            cameraManager.openCamera(
                cameraInfo.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession(camera, cameraInfo)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "CameraDevice error code: $error")
                        camera.close()
                        cameraDevice = null
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    private fun createCaptureSession(camera: CameraDevice, cameraInfo: DeviceCameraInfo) {
        val previewSurf = previewSurface ?: return
        val reader = imageReader ?: return

        val previewOutput = OutputConfiguration(previewSurf)
        val captureOutput = OutputConfiguration(reader.surface)

        // Configure 10-bit HLG dynamic range on preview stream if supported
        is10BitActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && cameraInfo.is10BitHlgSupported) {
            try {
                previewOutput.dynamicRangeProfile = DynamicRangeProfiles.HLG10
                is10BitActive = true
                Log.i(TAG, "Applied DynamicRangeProfiles.HLG10 to preview stream")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set HLG10 dynamic range profile on preview: ${e.message}")
            }
        }

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(previewOutput, captureOutput),
            Executors.newSingleThreadExecutor(),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startRepeatingPreview(session)
                    onSessionConfigured?.invoke(is10BitActive)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                    onSessionConfigured?.invoke(false)
                }
            }
        )

        try {
            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating capture session", e)
        }
    }

    private fun startRepeatingPreview(session: CameraCaptureSession) {
        val camera = cameraDevice ?: return
        val previewSurf = previewSurface ?: return

        try {
            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurf)

                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExposureIndex)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
                }

                if (currentFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                }
            }

            session.setRepeatingRequest(
                previewRequestBuilder!!.build(),
                null,
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting repeating preview request", e)
        }
    }

    fun triggerTapToFocus(normX: Float, normY: Float) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val cameraInfo = characteristicsHelper.getCameraInfo(currentFacing) ?: return
        val activeArray = cameraInfo.activeArraySize

        // Map normalized screen touch (0..1) to sensor matrix coordinates taking rotation into account
        val sensorX: Int
        val sensorY: Int
        if (currentFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            sensorX = ((1f - normY) * activeArray.width()).toInt().coerceIn(0, activeArray.width() - 1)
            sensorY = ((1f - normX) * activeArray.height()).toInt().coerceIn(0, activeArray.height() - 1)
        } else {
            sensorX = (normY * activeArray.width()).toInt().coerceIn(0, activeArray.width() - 1)
            sensorY = ((1f - normX) * activeArray.height()).toInt().coerceIn(0, activeArray.height() - 1)
        }

        val boxHalf = 150
        val left = (sensorX - boxHalf).coerceAtLeast(0)
        val right = (sensorX + boxHalf).coerceAtMost(activeArray.width())
        val top = (sensorY - boxHalf).coerceAtLeast(0)
        val bottom = (sensorY + boxHalf).coerceAtMost(activeArray.height())

        val meteringRect = MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)

        try {
            // Cancel AF trigger first
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, backgroundHandler)

            // Set new focus & exposure regions
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)

            session.capture(builder.build(), null, backgroundHandler)

            // Resume repeating with the new active focus region
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            Log.i(TAG, "Triggered tap-to-focus at sensor ($sensorX, $sensorY)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed triggering tap to focus", e)
        }
    }

    fun switchCamera() {
        val nextFacing = if (currentFacing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        currentFacing = nextFacing
        currentZoomRatio = 1.0f

        closeCameraHardware()

        cachedSurfaceTexture?.let { st ->
            openCamera(st)
        }
        onFacingChanged?.invoke(currentFacing)
    }

    fun setZoomRatio(ratio: Float) {
        currentZoomRatio = ratio
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating zoom ratio", e)
        }
    }

    fun setExposureCompensation(index: Int) {
        currentExposureIndex = index
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExposureIndex)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating exposure compensation", e)
        }
    }

    fun takePicture(
        params: FilterParameters,
        isPortraitMode: Boolean = false,
        onPhotoProcessed: (android.net.Uri?, android.graphics.Bitmap?) -> Unit
    ) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        val cameraInfo = characteristicsHelper.getCameraInfo(currentFacing)

        onCaptureStateChanged?.invoke(true)
        pendingCapture.set(CaptureData(params, isPortraitMode, onPhotoProcessed))

        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExposureIndex)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
                }
                cameraInfo?.sensorOrientation?.let { orientation ->
                    set(CaptureRequest.JPEG_ORIENTATION, orientation)
                }
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }

            session.capture(captureBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed taking picture", e)
            pendingCapture.set(null)
            onCaptureStateChanged?.invoke(false)
        }
    }

    private fun closeCameraHardware() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            previewSurface?.release()
            previewSurface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera hardware", e)
        }
    }

    fun resumeCamera() {
        if (cameraDevice == null) {
            cachedSurfaceTexture?.let { st ->
                openCamera(st)
            }
        }
    }

    fun pauseCamera() {
        closeCameraHardware()
    }

    fun closeCamera() {
        closeCameraHardware()
        stopBackgroundThread()
    }
}
