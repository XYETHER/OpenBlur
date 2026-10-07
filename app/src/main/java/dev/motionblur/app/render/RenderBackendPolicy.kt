package dev.motionblur.app.render

enum class RenderBackend { GPU, UNAVAILABLE }

data class RenderBackendCapabilities(
    val esMajor: Int,
    val esMinor: Int,
    val externalOes: Boolean,
    val recordable: Boolean,
)

data class RenderBackendDecision(
    val backend: RenderBackend,
    val allowAutomaticCpuFallback: Boolean,
    val message: String? = null,
)

/** GPU is the product route. CPU rendering is never selected silently. */
object RenderBackendPolicy {
    fun decide(capabilities: RenderBackendCapabilities): RenderBackendDecision {
        val es31 = capabilities.esMajor > 3 ||
            (capabilities.esMajor == 3 && capabilities.esMinor >= 1)
        if (!es31) return unavailable("OpenGL ES 3.1 or newer is required for GPU motion blur.")
        if (!capabilities.externalOes) return unavailable("This device cannot import decoded video frames into OpenGL.")
        if (!capabilities.recordable) return unavailable("This device cannot connect GPU output to the video encoder.")
        return RenderBackendDecision(RenderBackend.GPU, allowAutomaticCpuFallback = false)
    }

    private fun unavailable(message: String) = RenderBackendDecision(
        backend = RenderBackend.UNAVAILABLE,
        allowAutomaticCpuFallback = false,
        message = message,
    )
}
