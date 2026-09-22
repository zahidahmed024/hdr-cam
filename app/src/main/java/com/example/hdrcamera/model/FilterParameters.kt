package com.example.hdrcamera.model

data class FilterParameters(
    val exposure: Float = 0.0f,          // -2.0 to 2.0
    val contrast: Float = 1.0f,          // 0.5 to 1.8
    val saturation: Float = 1.0f,        // 0.0 to 2.0
    val temperature: Float = 0.0f,       // -1.0 (cool) to +1.0 (warm)
    val tint: Float = 0.0f,              // -1.0 (magenta) to +1.0 (green)
    val sharpness: Float = 0.4f,         // 0.0 to 1.5
    val highlightRecovery: Float = 1.25f, // 0.5 to 2.0
    val shadowBoost: Float = 0.25f,      // 0.0 to 1.0
    val lutId: String = "natural",       // natural, cinematic, golden_hour, vivid, mono, cyberpunk
    val lutIntensity: Float = 0.75f,     // 0.0 to 1.0
    val fastPreview: Boolean = false     // Viewfinder optimization
)
