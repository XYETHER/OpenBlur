package dev.motionblur.app.logic

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever

/** Metadata read from the user-selected URI without copying the video. */
data class ClipMetadata(
    val displayName: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Rational?,
    val hasAudio: Boolean,
) {
    companion object {
        fun inspect(context: Context, uri: Uri): ClipMetadata {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(0L)?.times(1000L) ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val frameRate = parseRate(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE))
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                return ClipMetadata(queryName(context, uri), durationUs, width, height, frameRate, hasAudio)
            } finally {
                retriever.release()
            }
        }

        private fun queryName(context: Context, uri: Uri): String {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index).orEmpty()
                }
            }
            return uri.lastPathSegment?.substringAfterLast('/') ?: "Selected video"
        }

        fun parseRate(value: String?): Rational? {
            if (value.isNullOrBlank()) return null
            val parts = value.split('/', limit = 2)
            val numerator = parts.firstOrNull()?.toLongOrNull() ?: return null
            val denominator = parts.getOrNull(1)?.toLongOrNull() ?: 1L
            if (numerator <= 0L || denominator <= 0L) return null
            return Rational(numerator, denominator)
        }
    }
}

data class Rational(val numerator: Long, val denominator: Long) {
    init {
        require(numerator > 0 && denominator > 0)
    }
    val asDouble: Double get() = numerator.toDouble() / denominator.toDouble()
}
