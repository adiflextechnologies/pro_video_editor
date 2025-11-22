package ch.waio.pro_video_editor.src.features.render.utils

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

@UnstableApi
fun mapFormatToMimeType(format: String): String {
    return when (format.lowercase()) {
        "mp4" -> MimeTypes.VIDEO_H264 // Codec for MP4
        // "webm" -> MimeTypes.VIDEO_VP9 // Codec for WebM
        "h264" -> MimeTypes.VIDEO_H264
        "h265", "hevc" -> MimeTypes.VIDEO_H265
        "av1" -> MimeTypes.VIDEO_AV1
        else -> MimeTypes.VIDEO_MP4 // fallback default
    }
}
