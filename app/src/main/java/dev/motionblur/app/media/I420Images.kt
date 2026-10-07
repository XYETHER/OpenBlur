package dev.motionblur.app.media

import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

internal object I420Images {
    fun size(width: Int, height: Int): Int {
        requireMedia(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0,
            "Only positive even-sized YUV420 video is supported")
        val size = width.toLong() * height * 3 / 2
        requireMedia(size <= Int.MAX_VALUE, "Video dimensions exceed buffer limits")
        return size.toInt()
    }

    fun read(image: Image, width: Int, height: Int, pts: Long): I420Frame {
        validate(image, width, height)
        val bytes = ByteArray(size(width, height))
        transfer(image, bytes, width, height, false)
        return I420Frame(width, height, pts, bytes)
    }

    fun write(image: Image, bytes: ByteArray, width: Int, height: Int) {
        validate(image, width, height)
        require(bytes.size == size(width, height)) { "Effect returned incorrectly sized I420 buffer" }
        transfer(image, bytes, width, height, true)
    }

    private fun validate(image: Image, width: Int, height: Int) {
        requireMedia(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3,
            "Codec did not expose 8-bit flexible YUV420 images")
        requireMedia(image.cropRect.width() == width && image.cropRect.height() == height,
            "Codec crop changed the original dimensions")
        requireMedia(image.cropRect.left % 2 == 0 && image.cropRect.top % 2 == 0,
            "Unaligned YUV420 crop is unsupported")
    }

    private fun transfer(image: Image, data: ByteArray, w: Int, h: Int, write: Boolean) {
        var offset = 0
        image.planes.forEachIndexed { index, plane ->
            val divisor = if (index == 0) 1 else 2
            val width = w / divisor
            val height = h / divisor
            val buffer = plane.buffer.duplicate()
            val base = buffer.position() + image.cropRect.top / divisor * plane.rowStride +
                image.cropRect.left / divisor * plane.pixelStride
            copyPlane(buffer, base, plane.rowStride, plane.pixelStride, data, offset, width, height, write)
            offset += width * height
        }
    }

    internal fun copyPlane(buffer: ByteBuffer, base: Int, rowStride: Int, pixelStride: Int,
                          data: ByteArray, offset: Int, width: Int, height: Int, write: Boolean) {
        requireMedia(base >= 0 && base.toLong() + (height - 1L) * rowStride +
            (width - 1L) * pixelStride < buffer.limit(), "Codec plane buffer is truncated")
        for (y in 0 until height) {
            if (pixelStride == 1) {
                buffer.position(base + y * rowStride)
                if (write) buffer.put(data, offset + y * width, width)
                else buffer.get(data, offset + y * width, width)
            } else for (x in 0 until width) {
                val source = base + y * rowStride + x * pixelStride
                val packed = offset + y * width + x
                if (write) buffer.put(source, data[packed]) else data[packed] = buffer.get(source)
            }
        }
    }
}
