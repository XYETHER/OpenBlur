package dev.motionblur.app.logic

fun parseSeconds(text: String): Long? {
    val seconds = text.toDoubleOrNull() ?: return null
    if (!seconds.isFinite() || seconds < 0 || seconds > Long.MAX_VALUE / 1000.0) return null
    return (seconds * 1000).toLong()
}

/** All editor times are source-relative milliseconds; preview never changes trim. */
data class Timeline(
    val duration: Long,
    val start: Long = 0,
    val end: Long = duration,
    val previewStart: Long = start,
) {
    init {
        require(duration > 0 && start >= 0 && start < end && end <= duration)
        require(previewStart in start..(end - minOf(4000L, end - start)))
    }
    val selectedLength get() = end - start
    val windowLength get() = minOf(4000L, selectedLength)
    fun trim(from: Long, to: Long): Timeline {
        require(from >= 0 && from < to && to <= duration)
        return copy(start = from, end = to,
            previewStart = previewStart.coerceIn(from, to - minOf(4000L, to - from)))
    }
    fun dragPreview(pointer: Long, grabOffset: Long) = copy(
        previewStart = (pointer - grabOffset).coerceIn(start, end - windowLength))
    fun clampPlayhead(position: Long) = position.coerceIn(start, end - 1)
}

enum class VideoCodec(val label: String, val mime: String) {
    H264("H.264", "video/avc"), H265("H.265", "video/hevc")
}

data class EditorDraft(
    val preset: String = "Dynamic Medium",
    val strength: Float = 50f,
    val times: Int = 1,
    val quality: String = "Balanced",
    val advanced: Boolean = false,
    val dynamicBlur: Boolean = true,
    val compute: String = "Auto",
    val fallback: String = "Ask before restarting",
    val decoder: String = "Auto (hardware first)",
    val encoder: String = "Auto (hardware first)",
    val videoCodec: VideoCodec = VideoCodec.H264,
    val bitrateMbps: Float? = null,
    val encodingEffort: Float? = null,
) {
    init {
        RenderSettings(strength, times)
        require(bitrateMbps == null || (bitrateMbps.isFinite() && bitrateMbps in 0.25f..100f))
        require(encodingEffort == null || (encodingEffort.isFinite() && encodingEffort in 0f..1f))
    }
    fun sameRenderSettings(other: EditorDraft) = copy(advanced = false) == other.copy(advanced = false)
    fun choosePreset(name: String): EditorDraft {
        val value = when (name) {
            "Dynamic Light" -> 25f
            "Dynamic Medium" -> 50f
            "Dynamic Strong" -> 100f
            "Dynamic Extreme" -> 200f
            else -> throw IllegalArgumentException("Unknown preset")
        }
        return copy(preset = name, strength = value)
    }
    fun manualStrength(value: Float) = copy(preset = "Custom", strength = value)
}
