package dev.motionblur.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CompressedSamplePolicyTest {
    @Test
    fun supportedFlagsArePreservedExactly() {
        assertEquals(9, validatedCompressedSampleFlags(9, encryptedFlag = 2, partialFlag = 4, mediaKind = "audio"))
    }

    @Test
    fun encryptedCompressedSampleIsRejected() {
        assertRejected(2)
    }

    @Test
    fun partialCompressedSampleIsRejected() {
        assertRejected(4)
    }

    private fun assertRejected(flags: Int) {
        try {
            validatedCompressedSampleFlags(flags, encryptedFlag = 2, partialFlag = 4, mediaKind = "audio")
            fail("Unsupported compressed sample flags must be rejected")
        } catch (_: UnsupportedMediaException) {
        }
    }
}