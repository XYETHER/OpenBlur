package dev.motionblur.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only local inspection; no processing, copies or network. */
data class SourceClip(val uri: Uri, val name: String, val duration: Long,
    val width: Int, val height: Int, val captureRate: String?)

fun inspectSource(context: Context, uri: Uri): SourceClip {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        fun meta(key: Int) = retriever.extractMetadata(key)
        require(meta(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "No readable video track." }
        val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotated = (meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 180 != 0
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: "Selected video"
        return SourceClip(uri, name, duration, if (rotated) h else w, if (rotated) w else h,
            meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.takeIf {
                it.toDoubleOrNull()?.let { n -> n.isFinite() && n > 0 } == true })
    } finally { retriever.release() }
}

suspend fun sourceThumbnails(context: Context, source: SourceClip): List<Bitmap> {
    if (source.duration <= 0) return emptyList()
    val retriever = MediaMetadataRetriever()
    val frames = mutableListOf<Bitmap>()
    try {
        retriever.setDataSource(context, source.uri)
        repeat(10) { index ->
            currentCoroutineContext().ensureActive()
            val timeUs = index * source.duration * 1000 / 10
            val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
            } else {
                // API 26 has no scaled retrieval: retain only one decoded frame,
                // immediately downsample it, and release its full-resolution storage.
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                    val scale = minOf(160f / full.width, 90f / full.height, 1f)
                    val small = Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1),
                        (full.height * scale).toInt().coerceAtLeast(1), true)
                    if (small !== full) full.recycle()
                    small
                }
            }
            frame?.let { frames.add(it) }
        }
        return frames
    } catch (error: Throwable) {
        frames.forEach { it.recycle() }
        throw error
    } finally { retriever.release() }
}
