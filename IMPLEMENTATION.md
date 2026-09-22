# Real-Time 10-Bit HDR Camera Engine (Android / Xiaomi)
## Technical Implementation Document & Architecture

Target Device Profile:
- **Manufacturer & Model**: Xiaomi (`25067PYE3C`)
- **Android Target**: SDK 36 (Android 16 preview) / minSdk 33 (Android 13 Tiramisu for `DYNAMIC_RANGE_TEN_BIT`)
- **Hardware Level**: `LEVEL_3`
- **Optical Config**: `LOGICAL_MULTI_CAMERA` (Physical IDs: `[2, 3, 4]` - Ultra-Wide, Primary Wide, Telephoto)
- **Key Capabilities**: 10-bit Dynamic Range (`HLG10`), RAW Capture, Optical Stabilization (OIS), Zero Shutter Lag (ZSL) Reprocessing, Full Manual Controls.

---

## 1. System Architecture Overview

```
                        ┌─────────────────────────────────────────────────────────┐
                        │             Camera2 HAL (Xiaomi Sensor ISP)             │
                        └──────────┬───────────────────────────────────┬──────────┘
                                   │                                   │
              Stream 1: Viewfinder (1080p / 10-bit HLG10)   Stream 2: Capture (4096x3072 Ultra HDR)
                                   │                                   │
                                   ▼                                   │
                        ┌───────────────────────┐                      │
                        │ SurfaceTexture (EGL)  │                      │
                        └──────────┬────────────┘                      │
                                   │ Frame Available                   │ (Dormant until shutter press)
                                   ▼                                   │
                        ┌───────────────────────┐                      │
                        │ GPU Render Pipeline   │                      │
                        │ (OpenGL ES 3.0)       │                      │
                        │                       │                      │
                        │  1. 3D LUT (Color)    │                      │
                        │  2. Highlight Curve   │                      │
                        │  3. Unsharp Mask      │                      │
                        └──────────┬────────────┘                      │
                                   │                                   │
                                   ▼                                   ▼
             ┌───────────────────────────────────────────┐ ┌──────────────────────────────────────┐
             │ SurfaceView (Hardware HDR Overlay)        │ │ ImageReader (JPEG_R / Ultra HDR)     │
             │ Format: PixelFormat.RGBA_1010102          │ │ Bakes identical LUT & Sharpness      │
             │ Window: ActivityInfo.COLOR_MODE_HDR       │ │ Saves to MediaStore                  │
             └───────────────────────────────────────────┘ └──────────────────────────────────────┘
```

---

## 2. Core Pillars & Design Decisions

### A. Dual-Stream Efficiency (Low-Res Viewfinder + High-Res Capture)
* **Viewfinder Preview Stream**: Configured at `1920x1080` (16:9) or `1920x1440` (4:3) at 30/60 fps.
  * *Why*: Full-sensor preview (4096x3072 = 12.6M pixels) consumes ~6x more GPU memory bandwidth than 1080p (2.1M pixels). 1080p preview maintains 100% color/HDR accuracy while running with < 5% GPU load and zero thermal throttling.
* **Capture Stream**: Bound to an `ImageReader` configured for `4096x3072` with format `ImageFormat.JPEG_R` (Ultra HDR). Remains dormant with zero compute overhead until the shutter button is pressed.

### B. Hardware 10-Bit Display Pipeline
To display true HDR without highlight clipping on the OLED screen:
1. `window.colorMode = ActivityInfo.COLOR_MODE_HDR` ensures the display compositor boosts OLED peak brightness.
2. `SurfaceView` with `PixelFormat.RGBA_1010102` (or `RGBA_F16`) ensures 10-bit precision throughout the rendering pipeline.

### C. Optical Zoom & Multi-Camera Switching
* **Logical Multi-Camera**: Rear camera `0` automatically delegates to physical sensors `2`, `3`, or `4`.
* **Native Zoom API**: Controls zoom exclusively through `CaptureRequest.CONTROL_ZOOM_RATIO`. Setting zoom `< 1.0f` switches to the ultra-wide lens, while `>= 3.0f` switches to the telephoto lens without manual stream re-initialization.

### D. Highlight Recovery & Dynamic Detail Enhancement
* **Highlight Protection**: Slightly underexposing sensor capture (`CONTROL_AE_EXPOSURE_COMPENSATION` set to `-0.5` or `-1.0 EV`) retains highlights in skies/lamps, while 10-bit precision prevents shadow banding.
* **Highlight Roll-Off**: Custom tone curve in the fragment shader applies an S-curve knee preventing harsh 1.0 clipping.
* **Micro-Detail (Clarity)**: Unsharp mask (Laplacian edge filter) running directly in the fragment shader for live texture enhancement.

---

## 3. Project Structure

```
hdr-camera-app/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── luts/
│       │   │   ├── cinematic_teal_orange.cube
│       │   │   ├── monochrome_high_contrast.cube
│       │   │   └── vivid_landscape.cube
│       │   └── shaders/
│       │       ├── camera_vert.glsl
│       │       └── hdr_color_grade_frag.glsl
│       └── java/com/example/hdrcamera/
│           ├── MainActivity.kt
│           ├── camera/
│           │   ├── CameraManagerEngine.kt
│           │   ├── CameraCharacteristicsHelper.kt
│           │   └── CapturePipeline.kt
│           ├── gl/
│           │   ├── CameraGLRenderer.kt
│           │   ├── Texture3DLutLoader.kt
│           │   └── ShaderProgram.kt
│           ├── ui/
│           │   ├── CameraScreen.kt
│           │   ├── ControlOverlay.kt
│           │   └── theme/
│           └── processing/
│               ├── UltraHdrSaver.kt
│               └── ImageTransformUtils.kt
├── build.gradle.kts
├── settings.gradle.kts
└── IMPLEMENTATION.md
```

---

## 4. Key Implementation Steps & Code Blueprint

### Step 1: Camera Session with 10-Bit Dynamic Range & Multi-Stream Output

```kotlin
// In CameraManagerEngine.kt
fun createCameraSession(
    cameraDevice: CameraDevice,
    previewSurface: Surface,
    highResImageReader: ImageReader,
    handler: Handler
) {
    // 1. Preview Output Configuration (1080p, 10-bit HLG)
    val previewOutputConfig = OutputConfiguration(previewSurface).apply {
        dynamicRangeProfile = DynamicRangeProfiles.HLG10
    }

    // 2. High-Res Ultra HDR Output Configuration (4096x3072)
    val captureOutputConfig = OutputConfiguration(highResImageReader.surface).apply {
        dynamicRangeProfile = DynamicRangeProfiles.HLG10
    }

    val sessionConfiguration = SessionConfiguration(
        SessionConfiguration.SESSION_REGULAR,
        listOf(previewOutputConfig, captureOutputConfig),
        Executors.newSingleThreadExecutor(),
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                val previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    // Enable high-quality edge enhancement and noise reduction
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    // Highlight protection bias
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -3) // -0.5 EV
                    // Native Zoom (Defaults to 1.0f primary lens)
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f)
                }
                session.setRepeatingRequest(previewRequestBuilder.build(), null, handler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) { /* Handle error */ }
        }
    )
    cameraDevice.createCaptureSession(sessionConfiguration)
}
```

---

### Step 2: Real-Time HDR Shaders (GLSL)

#### Vertex Shader (`camera_vert.glsl`):
```glsl
#version 300 es
layout (location = 0) in vec4 aPosition;
layout (location = 1) in vec4 aTexCoord;

uniform mat4 uSTMatrix;
out vec2 vTexCoord;

void main() {
    gl_Position = aPosition;
    vTexCoord = (uSTMatrix * aTexCoord).xy;
}
```

#### Fragment Shader (`hdr_color_grade_frag.glsl`):
```glsl
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform samplerExternalOES uCameraTexture;
uniform sampler3D uLutTexture;      // 3D Color LUT (e.g. 33x33x33 or 64x64x64)

uniform vec2 uTexelSize;            // vec2(1.0/1920.0, 1.0/1080.0)
uniform float uSharpness;           // Detail/Clarity slider: 0.0 to 1.5
uniform float uHighlightBoost;      // Highlight recovery/boost: 0.5 to 2.0
uniform float uLutIntensity;        // LUT blend factor: 0.0 to 1.0

vec3 applyUnsharpMask(vec3 center, vec2 uv) {
    vec3 up    = texture(uCameraTexture, uv + vec2(0.0, uTexelSize.y)).rgb;
    vec3 down  = texture(uCameraTexture, uv - vec2(0.0, uTexelSize.y)).rgb;
    vec3 left  = texture(uCameraTexture, uv - vec2(uTexelSize.x, 0.0)).rgb;
    vec3 right = texture(uCameraTexture, uv + vec2(uTexelSize.x, 0.0)).rgb;
    
    vec3 blurred = (up + down + left + right) * 0.25;
    vec3 highPass = center - blurred;
    return clamp(center + highPass * uSharpness, 0.0, 1.0);
}

vec3 adjustHighlights(vec3 color, float boost) {
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    float highlightWeight = smoothstep(0.45, 0.95, luminance);
    vec3 graded = pow(color, vec3(1.0 / max(boost, 0.001)));
    return mix(color, graded, highlightWeight);
}

void main() {
    vec3 rawColor = texture(uCameraTexture, vTexCoord).rgb;
    
    // 1. Edge & Detail Enhancement (Unsharp Mask)
    vec3 sharpened = (uSharpness > 0.01) ? applyUnsharpMask(rawColor, vTexCoord) : rawColor;
    
    // 2. Highlight Detail Recovery / Boost
    vec3 highlightAdjusted = adjustHighlights(sharpened, uHighlightBoost);
    
    // 3. Real-Time 3D LUT Color Profile
    vec3 lutColor = texture(uLutTexture, clamp(highlightAdjusted, 0.0, 1.0)).rgb;
    vec3 finalColor = mix(highlightAdjusted, lutColor, uLutIntensity);
    
    fragColor = vec4(finalColor, 1.0);
}
```

---

### Step 3: High-Res Ultra HDR Capture & Save

When user taps the capture button:
```kotlin
// In CapturePipeline.kt
fun takePhoto(
    session: CameraCaptureSession,
    imageReader: ImageReader,
    currentZoom: Float,
    currentExposure: Int
) {
    val captureRequest = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
        addTarget(imageReader.surface)
        set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom)
        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExposure)
        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
    }
    session.capture(captureRequest.build(), null, null)
}
```

---

## 5. Thermal & Battery Best Practices

1. **Limit Viewfinder Frame Rate to 30 FPS**: While the sensor supports 60 FPS, running 30 FPS for the preview reduces display + GPU workload by 50% with zero degradation to photo capture quality.
2. **Screen Brightness Throttling**: Avoid forcing display brightness to 100% manually. Allow Android's HDR display subsystem to manage localized peak highlights.
3. **Release Surfaces Immediately**: Dispose and release `SurfaceTexture` / `EGLContext` when `onPause()` is called.

---

## 6. Implementation Roadmap

- [ ] **Phase 1: Project Scaffolding & Manifest**: Setup Android 13+ camera permissions, Gradle dependencies (Camera2, Jetpack Lifecycle, OpenGL ES).
- [ ] **Phase 2: Camera2 Hardware Session**: Initialize camera ID `0`, configure `HLG10` dynamic range profile, verify physical lens switching with `CONTROL_ZOOM_RATIO`.
- [ ] **Phase 3: EGL & Shader Engine**: Build `SurfaceView` with `RGBA_1010102`, setup OpenGL ES 3.0 external texture renderer, implement 3D LUT loader and fragment shader.
- [ ] **Phase 4: UI Sliders & Controls**: Expose live sliders for Zoom (0.6x – 5x), Highlight Recovery, and Micro-Detail / Sharpness.
- [ ] **Phase 5: Ultra HDR Capture & Storage**: Hook up `ImageReader` (4096x3072, `JPEG_R`), apply identical LUT/tuning, and save directly to MediaStore.
