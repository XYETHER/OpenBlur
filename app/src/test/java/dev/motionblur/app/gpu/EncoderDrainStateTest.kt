package dev.motionblur.app.gpu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderDrainStateTest {
    @Test
    fun requestsInputEosExactlyOnce() {
        val state = EncoderDrainState()

        assertTrue(state.requestInputEos())
        assertFalse(state.requestInputEos())
    }

    @Test
    fun acceptsCodecEosOnlyAfterInputEos() {
        val state = EncoderDrainState()

        assertFalse(state.acceptCodecEos())
        assertTrue(state.requestInputEos())
        assertTrue(state.acceptCodecEos())
        assertFalse(state.acceptCodecEos())
    }
}
