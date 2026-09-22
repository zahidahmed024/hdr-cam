package com.example.hdrcamera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Build
import android.util.Range
import android.util.Rational
import android.util.Size

data class DeviceCameraInfo(
    val cameraId: String,
    val facing: Int,
    val hardwareLevel: Int,
    val physicalCameraIds: Set<String>,
    val zoomRatioRange: Range<Float>,
    val aeCompensationRange: Range<Int>,
    val aeCompensationStep: Rational,
    val is10BitHlgSupported: Boolean,
    val optimalPreviewSize: Size,
    val maxPhotoSize: Size,
    val sensorOrientation: Int,
    val activeArraySize: android.graphics.Rect
)

class CameraCharacteristicsHelper(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun getCameraInfo(facing: Int = CameraCharacteristics.LENS_FACING_BACK): DeviceCameraInfo? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val lensFacing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
            if (lensFacing == facing) {
                return parseCameraInfo(id, chars)
            }
        }
        return null
    }

    fun getBackCameraInfo(): DeviceCameraInfo? = getCameraInfo(CameraCharacteristics.LENS_FACING_BACK)
    fun getFrontCameraInfo(): DeviceCameraInfo? = getCameraInfo(CameraCharacteristics.LENS_FACING_FRONT)

    private fun parseCameraInfo(cameraId: String, chars: CameraCharacteristics): DeviceCameraInfo {
        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val hardwareLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

        val physicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            chars.physicalCameraIds
        } else {
            emptySet()
        }

        val zoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1.0f, 10.0f)
        } else {
            Range(1.0f, 4.0f)
        }

        val aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-24, 24)
        val aeStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 6)

        // Check 10-bit HLG profile availability (API 33+)
        var is10BitHlgSupported = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            if (profiles != null && profiles.supportedProfiles.contains(DynamicRangeProfiles.HLG10)) {
                is10BitHlgSupported = true
            }
        }

        // Preview stream size (prefer 1920x1440 4:3 or 1920x1080 16:9 for fast thermal-efficient preview)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray()
        val optimalPreview = previewSizes.firstOrNull { it.width == 1920 && it.height == 1440 }
            ?: previewSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: previewSizes.firstOrNull { it.width <= 1920 }
            ?: Size(1920, 1080)

        // Max Photo Size (prefer 4096x3072 or highest available)
        val photoSizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG) ?: emptyArray()
        val maxPhoto = photoSizes.firstOrNull { it.width == 4096 && it.height == 3072 }
            ?: photoSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: Size(4096, 3072)

        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: android.graphics.Rect(0, 0, maxPhoto.width, maxPhoto.height)

        return DeviceCameraInfo(
            cameraId = cameraId,
            facing = facing,
            hardwareLevel = hardwareLevel,
            physicalCameraIds = physicalCameraIds,
            zoomRatioRange = zoomRatioRange,
            aeCompensationRange = aeRange,
            aeCompensationStep = aeStep,
            is10BitHlgSupported = is10BitHlgSupported,
            optimalPreviewSize = optimalPreview,
            maxPhotoSize = maxPhoto,
            sensorOrientation = orientation,
            activeArraySize = activeArray
        )
    }
}
