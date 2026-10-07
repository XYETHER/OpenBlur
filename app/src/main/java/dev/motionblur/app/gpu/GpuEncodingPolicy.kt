package dev.motionblur.app.gpu

internal object GpuEncodingPolicy {
    fun targetBitRate(
        width: Int,
        height: Int,
        frameRate: Int,
        sourceBitRate: Int?,
    ): Int {
        require(width > 0 && height > 0 && frameRate > 0)
        val qualityFloor = (width.toDouble() * height * frameRate * 0.60).toLong()
        val transcodeFloor = sourceBitRate?.takeIf { it > 0 }?.let { (it * 1.5).toLong() } ?: 0L
        return maxOf(qualityFloor, transcodeFloor)
            .coerceIn(2_000_000L, 100_000_000L)
            .toInt()
    }
}
