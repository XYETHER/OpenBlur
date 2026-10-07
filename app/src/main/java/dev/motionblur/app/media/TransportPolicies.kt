package dev.motionblur.app.media

/** Source selection represented as an exclusive interval and an output timeline starting at zero. */
internal data class SelectedOutputInterval(val sourceStartUs: Long, val sourceEndUs: Long) {
    init {
        require(sourceStartUs >= 0L && sourceEndUs > sourceStartUs) { "Invalid selected output interval" }
    }

    val durationUs: Long = sourceEndUs - sourceStartUs

    fun outputPtsUs(sourcePtsUs: Long): Long {
        require(sourcePtsUs in sourceStartUs until sourceEndUs) { "Frame is outside selected output interval" }
        return sourcePtsUs - sourceStartUs
    }

    fun endOfStreamPtsUs(lastFrameOutputPtsUs: Long): Long {
        require(lastFrameOutputPtsUs in 0 until durationUs) { "Last frame must start inside selected output interval" }
        return durationUs
    }
}

/** Rejects samples the transport cannot safely copy and returns every supported flag unchanged. */
internal fun validatedCompressedSampleFlags(
    flags: Int,
    encryptedFlag: Int,
    partialFlag: Int,
    mediaKind: String,
): Int {
    requireMedia(flags and (encryptedFlag or partialFlag) == 0, "Encrypted/partial $mediaKind is unsupported")
    return flags
}
