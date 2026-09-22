package com.example.hdrcamera.model

data class FilterProfile(
    val id: String,
    val name: String,
    val description: String,
    val isCustom: Boolean = false,
    val params: FilterParameters
) {
    companion object {
        val DEFAULT_PROFILES = listOf(
            FilterProfile(
                id = "natural_hdr",
                name = "Natural HDR",
                description = "True-to-life dynamic range with highlight protection and smooth shadow lift",
                isCustom = false,
                params = FilterParameters(
                    exposure = -0.1f,
                    contrast = 1.05f,
                    saturation = 1.05f,
                    temperature = 0.0f,
                    tint = 0.0f,
                    sharpness = 0.35f,
                    highlightRecovery = 1.3f,
                    shadowBoost = 0.25f,
                    lutId = "natural",
                    lutIntensity = 0.6f
                )
            ),
            FilterProfile(
                id = "cinematic_teal_orange",
                name = "Cinematic",
                description = "Blockbuster warm skin tones with deep teal shadows and punchy contrast",
                isCustom = false,
                params = FilterParameters(
                    exposure = -0.2f,
                    contrast = 1.25f,
                    saturation = 1.15f,
                    temperature = 0.15f,
                    tint = -0.05f,
                    sharpness = 0.5f,
                    highlightRecovery = 1.4f,
                    shadowBoost = 0.15f,
                    lutId = "cinematic",
                    lutIntensity = 0.85f
                )
            ),
            FilterProfile(
                id = "golden_hour",
                name = "Golden Hour",
                description = "Lush warm sunlight highlights and soft recovered amber shadows",
                isCustom = false,
                params = FilterParameters(
                    exposure = 0.1f,
                    contrast = 1.1f,
                    saturation = 1.2f,
                    temperature = 0.45f,
                    tint = -0.1f,
                    sharpness = 0.4f,
                    highlightRecovery = 1.5f,
                    shadowBoost = 0.35f,
                    lutId = "golden_hour",
                    lutIntensity = 0.8f
                )
            ),
            FilterProfile(
                id = "monochrome_pro",
                name = "Monochrome Pro",
                description = "High dynamic range fine art black and white with micro-contrast clarity",
                isCustom = false,
                params = FilterParameters(
                    exposure = 0.0f,
                    contrast = 1.35f,
                    saturation = 0.0f,
                    temperature = 0.0f,
                    tint = 0.0f,
                    sharpness = 0.7f,
                    highlightRecovery = 1.45f,
                    shadowBoost = 0.3f,
                    lutId = "mono",
                    lutIntensity = 1.0f
                )
            ),
            FilterProfile(
                id = "vivid_landscape",
                name = "Vivid Nature",
                description = "Deep sky blues, rich foliage greens, and crisp atmospheric clarity",
                isCustom = false,
                params = FilterParameters(
                    exposure = -0.15f,
                    contrast = 1.2f,
                    saturation = 1.35f,
                    temperature = -0.05f,
                    tint = 0.1f,
                    sharpness = 0.65f,
                    highlightRecovery = 1.4f,
                    shadowBoost = 0.2f,
                    lutId = "vivid",
                    lutIntensity = 0.75f
                )
            ),
            FilterProfile(
                id = "cyberpunk_moody",
                name = "Cyberpunk",
                description = "Electrifying magenta highlights, cool cyan midtones, and stark shadows",
                isCustom = false,
                params = FilterParameters(
                    exposure = -0.25f,
                    contrast = 1.3f,
                    saturation = 1.4f,
                    temperature = -0.3f,
                    tint = -0.25f,
                    sharpness = 0.6f,
                    highlightRecovery = 1.6f,
                    shadowBoost = 0.1f,
                    lutId = "cyberpunk",
                    lutIntensity = 0.9f
                )
            )
        )
    }
}
