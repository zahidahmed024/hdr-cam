package com.example.hdrcamera.ui

import android.content.Intent
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.opengl.GLSurfaceView
import android.view.ViewGroup
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.hdrcamera.camera.CameraManagerEngine
import com.example.hdrcamera.data.ProfileManager
import com.example.hdrcamera.gl.CameraGLRenderer
import com.example.hdrcamera.gl.HDRSurfaceView
import com.example.hdrcamera.model.FilterParameters
import com.example.hdrcamera.model.FilterProfile
import com.example.hdrcamera.ui.theme.*
import kotlinx.coroutines.delay

enum class CameraAppMode(val label: String) {
    PHOTO("PHOTO"),
    PORTRAIT("PORTRAIT"),
    PRO_HDR("PRO HDR")
}

@Composable
fun CameraScreen(
    cameraEngine: CameraManagerEngine,
    profileManager: ProfileManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var profiles by remember { mutableStateOf(profileManager.getAllProfiles()) }
    var selectedProfileId by remember { mutableStateOf(profiles.first().id) }
    var currentParams by remember { mutableStateOf(profiles.first().params) }

    var currentMode by remember { mutableStateOf(CameraAppMode.PHOTO) }
    var selectedLensZoom by remember { mutableStateOf(1.0f) }
    var is10BitActive by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var isProcessingBurst by remember { mutableStateOf(false) }
    var portraitAperture by remember { mutableStateOf(2.8f) }
    var isFrontCamera by remember { mutableStateOf(cameraEngine.currentFacing == CameraCharacteristics.LENS_FACING_FRONT) }

    var showTuningPanel by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showQuickPreview by remember { mutableStateOf(false) }

    var lastPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var lastPhotoThumb by remember { mutableStateOf<Bitmap?>(null) }

    // Tap to focus state
    var tapFocusPoint by remember { mutableStateOf<Offset?>(null) }
    var showFocusRing by remember { mutableStateOf(false) }

    // Renderer reference
    var glRenderer by remember { mutableStateOf<CameraGLRenderer?>(null) }

    // Focus ring auto-hide timer
    LaunchedEffect(tapFocusPoint) {
        if (tapFocusPoint != null) {
            showFocusRing = true
            delay(1500)
            showFocusRing = false
        }
    }

    // Camera callbacks
    LaunchedEffect(cameraEngine) {
        cameraEngine.onSessionConfigured = { active ->
            is10BitActive = active
        }
        cameraEngine.onCaptureStateChanged = { capturing ->
            isCapturing = capturing
        }
        cameraEngine.onProcessingStateChanged = { processing ->
            isProcessingBurst = processing
        }
        cameraEngine.onFacingChanged = { facing ->
            isFrontCamera = (facing == CameraCharacteristics.LENS_FACING_FRONT)
            if (isFrontCamera) {
                selectedLensZoom = 1.0f
            }
        }
    }

    // Immediately push parameter changes to GL Renderer
    LaunchedEffect(glRenderer, currentParams) {
        glRenderer?.updateParameters(currentParams)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // 1. OpenGL 10-bit HDR Viewfinder Surface with Tap-to-Focus
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        tapFocusPoint = offset
                        val normX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val normY = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                        cameraEngine.triggerTapToFocus(normX, normY)
                    }
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    val view = HDRSurfaceView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    val renderer = CameraGLRenderer(ctx) { surfaceTexture ->
                        cameraEngine.openCamera(surfaceTexture)
                    }
                    view.setRenderer(renderer)
                    view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    renderer.setSurfaceView(view)
                    renderer.updateParameters(currentParams)
                    glRenderer = renderer
                    view
                },
                update = { _ ->
                    glRenderer?.updateParameters(currentParams)
                },
                modifier = Modifier.fillMaxSize()
            )

            // Animated Tap-To-Focus Ring
            if (showFocusRing && tapFocusPoint != null) {
                val focusOffset = tapFocusPoint!!
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (focusOffset.x - 36.dp.toPx()).toInt(),
                                (focusOffset.y - 36.dp.toPx()).toInt()
                            )
                        }
                        .size(72.dp)
                        .border(1.5.dp, AccentGold, RoundedCornerShape(8.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(AccentGold)
                            .align(Alignment.Center)
                    )
                }
            }
        }

        // Capture flash overlay animation
        AnimatedVisibility(
            visible = isCapturing,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.5f))
            )
        }

        // 2. Top Status Bar & Controls
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                    )
                )
                .padding(top = 40.dp, start = 16.dp, end = 16.dp, bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 10-Bit HDR Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (is10BitActive) Color(0x3300E5FF) else Color(0x33FFFFFF))
                        .border(
                            1.dp,
                            if (is10BitActive) AccentCyan else Color(0x44FFFFFF),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (is10BitActive) AccentCyan else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (is10BitActive) "10-BIT HLG HDR" else "8-BIT SDR",
                        color = if (is10BitActive) AccentCyan else Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // HDR+ Multi-Frame Fusion Active Badge
                AnimatedVisibility(
                    visible = isProcessingBurst,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0x44FFB300))
                            .border(1.dp, AccentGold, RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            color = AccentGold,
                            strokeWidth = 1.5.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "HDR+ FUSION",
                            color = AccentGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Portrait Indicator Badge if in Portrait mode
                if (currentMode == CameraAppMode.PORTRAIT) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0x44FFB300))
                            .border(1.dp, AccentGold, RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Face,
                            contentDescription = "Portrait Mode",
                            tint = AccentGold,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "PORTRAIT BOKEH",
                            color = AccentGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Tuning Sliders Button Toggle
                IconButton(
                    onClick = { showTuningPanel = !showTuningPanel },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (showTuningPanel) AccentGold else Color(0x3313151A))
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Tune Parameters",
                        tint = if (showTuningPanel) Color.Black else TextPrimary
                    )
                }
            }
        }

        // 3. Sliding / Collapsible Parameter Adjustment Drawer
        AnimatedVisibility(
            visible = showTuningPanel,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 96.dp, start = 16.dp, end = 16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceGlass),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 390.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live HDR Color & Clarity",
                            color = AccentGold,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Row {
                            TextButton(
                                onClick = {
                                    val profile = profiles.find { it.id == selectedProfileId }
                                    if (profile != null) {
                                        currentParams = profile.params
                                        val aeStep = (profile.params.exposure * 6f).toInt().coerceIn(-24, 24)
                                        cameraEngine.setExposureCompensation(aeStep)
                                    }
                                }
                            ) {
                                Text("Reset", color = TextSecondary, fontSize = 12.sp)
                            }

                            Button(
                                onClick = { showSaveDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGold),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Save Preset", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 1. Exposure Compensation
                    ParameterSlider(
                        title = "Exposure Compensation (EV)",
                        value = currentParams.exposure,
                        range = -2.0f..2.0f,
                        onValueChange = {
                            currentParams = currentParams.copy(exposure = it)
                            val aeStep = (it * 6f).toInt().coerceIn(-24, 24)
                            cameraEngine.setExposureCompensation(aeStep)
                        }
                    )

                    // 2. Micro-Detail / Sharpness (Clarity)
                    ParameterSlider(
                        title = "Clarity / Sharpness",
                        value = currentParams.sharpness,
                        range = 0.0f..1.5f,
                        onValueChange = { currentParams = currentParams.copy(sharpness = it) }
                    )

                    // 3. Highlight Recovery
                    ParameterSlider(
                        title = "Highlight Recovery (Shoulder Roll-Off)",
                        value = currentParams.highlightRecovery,
                        range = 0.5f..2.0f,
                        onValueChange = { currentParams = currentParams.copy(highlightRecovery = it) }
                    )

                    // 4. Shadow Boost
                    ParameterSlider(
                        title = "Shadow Boost",
                        value = currentParams.shadowBoost,
                        range = 0.0f..1.0f,
                        onValueChange = { currentParams = currentParams.copy(shadowBoost = it) }
                    )

                    // 5. Contrast
                    ParameterSlider(
                        title = "Contrast",
                        value = currentParams.contrast,
                        range = 0.5f..1.8f,
                        onValueChange = { currentParams = currentParams.copy(contrast = it) }
                    )

                    // 6. Saturation
                    ParameterSlider(
                        title = "Saturation",
                        value = currentParams.saturation,
                        range = 0.0f..2.0f,
                        onValueChange = { currentParams = currentParams.copy(saturation = it) }
                    )

                    // 7. Temperature
                    ParameterSlider(
                        title = "Color Temperature (Warm / Cool)",
                        value = currentParams.temperature,
                        range = -1.0f..1.0f,
                        onValueChange = { currentParams = currentParams.copy(temperature = it) }
                    )

                    // 8. Tint
                    ParameterSlider(
                        title = "Tint (Green / Magenta)",
                        value = currentParams.tint,
                        range = -1.0f..1.0f,
                        onValueChange = { currentParams = currentParams.copy(tint = it) }
                    )

                    // 9. LUT Intensity
                    ParameterSlider(
                        title = "LUT Blend Intensity",
                        value = currentParams.lutIntensity,
                        range = 0.0f..1.0f,
                        onValueChange = { currentParams = currentParams.copy(lutIntensity = it) }
                    )
                }
            }
        }

        // 4. Bottom Controls Layer
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
                .padding(bottom = 28.dp)
        ) {
            // A. Optical Lens Selector Chips
            if (!isFrontCamera) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val zoomSteps = listOf(
                        0.6f to "0.6x (UW)",
                        1.0f to "1x (W)",
                        2.0f to "2x",
                        3.0f to "3x (T)",
                        5.0f to "5x"
                    )

                    zoomSteps.forEach { (zoom, label) ->
                        val isSelected = selectedLensZoom == zoom
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) AccentGold else Color(0x44222630))
                                .clickable {
                                    selectedLensZoom = zoom
                                    cameraEngine.setZoomRatio(zoom)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.Black else TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(AccentGold)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "1x (Front Selfie)",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // B. Filter Profiles & Presets Carousel
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(profiles) { profile ->
                    val isSelected = profile.id == selectedProfileId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) AccentGold.copy(alpha = 0.25f) else Color(0x331E222D))
                            .border(
                                1.dp,
                                if (isSelected) AccentGold else Color(0x22FFFFFF),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                selectedProfileId = profile.id
                                currentParams = profile.params
                                val aeStep = (profile.params.exposure * 6f).toInt().coerceIn(-24, 24)
                                cameraEngine.setExposureCompensation(aeStep)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = profile.name,
                                    color = if (isSelected) AccentGold else TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                if (profile.isCustom) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Bookmark,
                                        contentDescription = "Custom",
                                        tint = AccentGold,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Add Custom Profile Button
                item {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x22FFFFFF))
                            .clickable { showSaveDialog = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Profile",
                                tint = AccentGold,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New", color = AccentGold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Portrait Optical Aperture Selector (f/1.4 to f/5.6)
            AnimatedVisibility(
                visible = currentMode == CameraAppMode.PORTRAIT,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val apertures = listOf(
                        1.4f to "f/1.4",
                        2.0f to "f/2.0",
                        2.8f to "f/2.8",
                        4.0f to "f/4.0",
                        5.6f to "f/5.6"
                    )
                    Text(
                        text = "BOKEH",
                        color = AccentGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    apertures.forEach { (ap, label) ->
                        val isSel = portraitAperture == ap
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) AccentGold else Color(0x33222630))
                                .border(1.dp, if (isSel) AccentGold else Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                                .clickable { portraitAperture = ap }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (isSel) Color.Black else TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // C. Camera Mode Selector (PHOTO | PORTRAIT | PRO HDR)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CameraAppMode.values().forEach { mode ->
                    val isSelected = currentMode == mode
                    Text(
                        text = mode.label,
                        color = if (isSelected) AccentGold else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clickable {
                                currentMode = mode
                                if (mode == CameraAppMode.PORTRAIT && !isFrontCamera) {
                                    selectedLensZoom = 2.0f
                                    cameraEngine.setZoomRatio(2.0f)
                                } else if (mode == CameraAppMode.PRO_HDR) {
                                    showTuningPanel = true
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // D. Shutter, Gallery & Camera Switch Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Thumbnail Preview with HDR+ processing indicator
                val burstTransition = rememberInfiniteTransition(label = "burst_proc_anim")
                val spinDegree by burstTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "gallery_spin_angle"
                )

                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x331E222D))
                        .border(
                            width = if (isProcessingBurst) 2.dp else 1.dp,
                            brush = if (isProcessingBurst) Brush.sweepGradient(
                                listOf(AccentGold, AccentCyan, Color.Transparent, AccentGold)
                            ) else Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x33FFFFFF))),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            if (lastPhotoThumb != null || lastPhotoUri != null) {
                                showQuickPreview = true
                            } else {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        type = "image/*"
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (lastPhotoThumb != null) {
                        androidx.compose.foundation.Image(
                            bitmap = lastPhotoThumb!!.asImageBitmap(),
                            contentDescription = "Last captured photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.PhotoLibrary,
                            contentDescription = "Gallery",
                            tint = Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    if (isProcessingBurst) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = AccentGold,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }

                // Shutter Button
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                        .border(3.dp, Color.White, CircleShape)
                        .clickable(enabled = !isCapturing) {
                            val isPortrait = (currentMode == CameraAppMode.PORTRAIT)
                            cameraEngine.takePicture(
                                params = currentParams,
                                isPortraitMode = isPortrait,
                                burstCount = 3,
                                bokehAperture = portraitAperture
                            ) { uri, thumb ->
                                lastPhotoUri = uri
                                lastPhotoThumb = thumb
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .background(if (isCapturing) AccentGold else Color.White)
                    )
                }

                // Camera Switch Button (Front / Back)
                IconButton(
                    onClick = { cameraEngine.switchCamera() },
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0x331E222D))
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // 5. Save Custom Profile Dialog
        if (showSaveDialog) {
            SaveProfileDialog(
                currentParams = currentParams,
                onDismiss = { showSaveDialog = false },
                onSave = { name ->
                    val saved = profileManager.saveCustomProfile(name, currentParams)
                    profiles = profileManager.getAllProfiles()
                    selectedProfileId = saved.id
                    showSaveDialog = false
                }
            )
        }

        // 6. In-App Quick Photo Viewer
        if (showQuickPreview) {
            QuickPhotoViewer(
                uri = lastPhotoUri,
                bitmap = lastPhotoThumb,
                onDismiss = { showQuickPreview = false }
            )
        }
    }
}

@Composable
fun ParameterSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = TextSecondary, fontSize = 12.sp)
            Text(String.format("%.2f", value), color = AccentGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = AccentGold,
                activeTrackColor = AccentGold,
                inactiveTrackColor = SliderInactive
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}
