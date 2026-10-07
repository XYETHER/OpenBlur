package dev.motionblur.app.logic

/** Immutable, validated user-facing blur configuration. */
data class RenderSettings(
    val strength: Float,
    val times: Int,
) {
    init {
        require(strength.isFinite() && strength in 0f..200f) { "strength must be between 0 and 200" }
        require(times in 1..4) { "times must be between 1 and 4" }
    }

    companion object {
        fun preset(preset: BlurPreset): RenderSettings = RenderSettings(preset.strength, 1)
    }
}

enum class BlurPreset(val strength: Float) {
    DYNAMIC_LIGHT(25f),
    DYNAMIC_MEDIUM(50f),
    DYNAMIC_STRONG(100f),
    DYNAMIC_EXTREME(200f),
}
