package dev.motionblur.app.gpu

/** Rotates exactly three reusable GPU slots into temporal display order. */
internal class FrameRingOrder(slotCount: Int) {
    data class Indices(val previous: Int?, val current: Int?, val next: Int?)

    private var previous: Int? = null
    private var current: Int? = null
    private var next: Int? = null
    private var write = 0

    init {
        require(slotCount == SLOT_COUNT) { "A GPU frame ring requires exactly $SLOT_COUNT slots" }
    }

    fun push(): Indices {
        val incoming = write
        write = (write + 1) % SLOT_COUNT
        when {
            next == null -> next = incoming
            current == null -> {
                current = next
                next = incoming
            }
            else -> {
                previous = current
                current = next
                next = incoming
            }
        }
        return Indices(previous, current, next)
    }

    companion object {
        const val SLOT_COUNT = 3
    }
}
