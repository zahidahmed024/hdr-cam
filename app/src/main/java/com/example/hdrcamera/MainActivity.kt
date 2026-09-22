package com.example.hdrcamera

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.hdrcamera.camera.CameraCharacteristicsHelper
import com.example.hdrcamera.camera.CameraManagerEngine
import com.example.hdrcamera.data.ProfileManager
import com.example.hdrcamera.processing.UltraHdrProcessor
import com.example.hdrcamera.ui.CameraScreen
import com.example.hdrcamera.ui.theme.HDRCameraTheme

class MainActivity : ComponentActivity() {

    private lateinit var cameraEngine: CameraManagerEngine
    private lateinit var profileManager: ProfileManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupCameraApp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request 10-bit HDR Display Output
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
        }

        // Keep screen on while using camera
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Immersive full-screen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        profileManager = ProfileManager(this)

        if (hasCameraPermission()) {
            setupCameraApp()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupCameraApp() {
        val helper = CameraCharacteristicsHelper(this)
        val processor = UltraHdrProcessor(this)
        cameraEngine = CameraManagerEngine(this, helper, processor)

        setContent {
            HDRCameraTheme {
                CameraScreen(
                    cameraEngine = cameraEngine,
                    profileManager = profileManager
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::cameraEngine.isInitialized) {
            cameraEngine.resumeCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::cameraEngine.isInitialized) {
            cameraEngine.pauseCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraEngine.isInitialized) {
            cameraEngine.closeCamera()
        }
    }
}
