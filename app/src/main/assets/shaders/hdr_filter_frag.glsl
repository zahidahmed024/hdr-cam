#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp sampler3D;

in vec2 vTexCoord;
out vec4 fragColor;

uniform samplerExternalOES uCameraTexture;
uniform sampler3D uLutTexture;

uniform vec2 uTexelSize;
uniform float uSharpness;
uniform float uHighlightBoost;
uniform float uShadowBoost;
uniform float uExposure;
uniform float uContrast;
uniform float uSaturation;
uniform float uTemperature;
uniform float uTint;
uniform float uLutIntensity;
uniform int uFastMode;

// High-clarity multi-tap unsharp mask
vec3 applySharpening(vec3 center, vec2 uv) {
    if (uSharpness < 0.02) {
        return center;
    }
    // Step by 3.5 texels to span visible screen pixels on high-DPI displays
    vec2 step = uTexelSize * 3.5;
    vec3 up    = texture(uCameraTexture, uv + vec2(0.0, step.y)).rgb;
    vec3 down  = texture(uCameraTexture, uv - vec2(0.0, step.y)).rgb;
    vec3 left  = texture(uCameraTexture, uv - vec2(step.x, 0.0)).rgb;
    vec3 right = texture(uCameraTexture, uv + vec2(step.x, 0.0)).rgb;
    
    vec3 blurred = (up + down + left + right) * 0.25;
    vec3 highPass = center - blurred;
    return clamp(center + highPass * (uSharpness * 3.0), 0.0, 1.0);
}

// Perceptual white balance shift (warm/cool and magenta/green)
vec3 adjustWhiteBalance(vec3 color, float temp, float tint) {
    color.r = clamp(color.r + temp * 0.30, 0.0, 1.0);
    color.b = clamp(color.b - temp * 0.30, 0.0, 1.0);
    color.g = clamp(color.g + tint * 0.25, 0.0, 1.0);
    return color;
}

// Highlight recovery (soft-shoulder compression) & shadow lift
vec3 adjustHighlightsAndShadows(vec3 color, float highlightRecover, float shadowBoost) {
    float luminance = dot(color, vec3(0.299, 0.587, 0.114));
    
    // Highlight recovery: compress bright zones to reveal sky & highlights
    if (highlightRecover > 1.01) {
        float highlightMask = smoothstep(0.35, 0.90, luminance);
        // Shoulder curve: pulls down harsh whites
        vec3 compressed = color / (1.0 + (highlightRecover - 1.0) * color * 1.5);
        color = mix(color, compressed, highlightMask);
    }
    
    // Shadow lift: gently illuminate dark underexposed areas
    if (shadowBoost > 0.01) {
        float shadowMask = 1.0 - smoothstep(0.05, 0.55, luminance);
        color = clamp(color + vec3(shadowBoost * 0.35 * shadowMask), 0.0, 1.0);
    }
    
    return color;
}

// Dynamic range contrast and chroma saturation
vec3 adjustContrastAndSaturation(vec3 color, float contrast, float saturation) {
    // Contrast around mid-gray 0.18
    color = clamp((color - 0.18) * contrast + 0.18, 0.0, 1.0);
    
    // Saturation
    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    color = clamp(mix(vec3(luma), color, saturation), 0.0, 1.0);
    
    return color;
}

void main() {
    vec3 raw = texture(uCameraTexture, vTexCoord).rgb;
    
    // 1. Exposure compensation
    if (abs(uExposure) > 0.01) {
        raw = clamp(raw * pow(2.0, uExposure), 0.0, 1.0);
    }
    
    // 2. Micro-detail unsharp mask sharpening
    vec3 sharp = applySharpening(raw, vTexCoord);
    
    // 3. White Balance (Temperature / Tint)
    vec3 wb = adjustWhiteBalance(sharp, uTemperature, uTint);
    
    // 4. Highlight Recovery & Shadow Boost
    vec3 hdr = adjustHighlightsAndShadows(wb, uHighlightBoost, uShadowBoost);
    
    // 5. Contrast & Saturation
    vec3 graded = adjustContrastAndSaturation(hdr, uContrast, uSaturation);
    
    // 6. 3D LUT Color Tone Profile
    if (uLutIntensity > 0.01) {
        vec3 lutColor = texture(uLutTexture, clamp(graded, 0.0, 1.0)).rgb;
        graded = mix(graded, lutColor, uLutIntensity);
    }
    
    fragColor = vec4(clamp(graded, 0.0, 1.0), 1.0);
}
