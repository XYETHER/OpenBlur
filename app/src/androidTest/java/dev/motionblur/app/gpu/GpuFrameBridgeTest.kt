package dev.motionblur.app.gpu

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuFrameBridgeTest {
    @Test
    fun importsDecodedFrameDirectlyIntoGpuTexture() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val asset = context.assets.openFd("motion-audio.mp4")
        val extractor = MediaExtractor()
        extractor.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
        asset.close()
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        val format = extractor.getTrackFormat(track)
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        extractor.selectTrack(track)
        GpuFrameBridge(width, height).use { bridge ->
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                decoder.configure(format, bridge.decoderSurface, null, 0)
                decoder.start()
                var queued = false
                val info = MediaCodec.BufferInfo()
                var imported = false
                for (attempt in 0 until 200) {
                    if (!queued) {
                        val input = decoder.dequeueInputBuffer(10_000)
                        if (input >= 0) {
                            val buffer = decoder.getInputBuffer(input)!!
                            val bytes = extractor.readSampleData(buffer, 0)
                            assertTrue(bytes > 0)
                            decoder.queueInputBuffer(input, 0, bytes, extractor.sampleTime, 0)
                            extractor.advance()
                            queued = true
                        }
                    }
                    val output = decoder.dequeueOutputBuffer(info, 10_000)
                    if (output >= 0) {
                        decoder.releaseOutputBuffer(output, info.size > 0)
                        if (info.size > 0) {
                            val ring = bridge.awaitFrame(2_000)
                            assertNotNull("Decoder Surface never reached GPU bridge", ring)
                            assertTrue(ring!!.next!!.textureId > 0)
                            assertTrue(ring.next.timestampNs >= 0L)
                            imported = true
                            break
                        }
                    }
                }
                assertTrue("No decoded frame was imported into GPU texture", imported)
            } finally {
                decoder.stop()
                decoder.release()
                extractor.release()
            }
        }
    }
}
