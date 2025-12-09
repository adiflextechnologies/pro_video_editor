package ch.waio.pro_video_editor.src.features.render.utils

import android.media.MediaMetadataRetriever
import android.util.Log
import ch.waio.pro_video_editor.RENDER_TAG
import java.io.File

fun getRotatedVideoDimensions(
    videoFile: File,
    rotationDegrees: Float
): Triple<Int, Int, Int> {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(videoFile.absolutePath)
        val widthRaw =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
        val heightRaw =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
        val rotation =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

        // Normalize the combined rotation to handle edge cases
        val combinedRotation = rotation + rotationDegrees.toInt()
        val normalizedRotation = ((combinedRotation % 360) + 360) % 360
        
        val (width, height) = if (normalizedRotation == 90 || normalizedRotation == 270) {
            heightRaw to widthRaw
        } else {
            widthRaw to heightRaw
        }

        Log.d(RENDER_TAG, "Video dimensions: raw=${widthRaw}x${heightRaw}, metadata_rotation=$rotation, user_rotation=${rotationDegrees.toInt()}, normalized=$normalizedRotation, final=${width}x${height}")
        
        Triple(width, height, normalizedRotation)
    } catch (e: Exception) {
        Log.e(RENDER_TAG, "Failed to get video dimensions: ${e.message}")
        Triple(0, 0, 0)
    } finally {
        retriever.release()
    }
}
