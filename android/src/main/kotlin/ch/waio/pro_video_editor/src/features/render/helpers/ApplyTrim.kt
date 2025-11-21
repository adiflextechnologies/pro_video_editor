package ch.waio.pro_video_editor.src.features.render.helpers

import ch.waio.pro_video_editor.RENDER_TAG
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi

@UnstableApi
fun applyTrim(mediaItemBuilder: MediaItem.Builder, startUs: Long?, endUs: Long?) {
    if (startUs == null && endUs == null) return

    val startMs = (startUs ?: 0L) / 1000
    val endMs = endUs?.div(1000) ?: C.TIME_END_OF_SOURCE
    Log.d(RENDER_TAG, "Applying trim: start=$startMs ms, end=$endMs ms")

    val clippingConfig = MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(startMs)
        .setEndPositionMs(endMs)
        .build()

    mediaItemBuilder.setClippingConfiguration(clippingConfig)
}
