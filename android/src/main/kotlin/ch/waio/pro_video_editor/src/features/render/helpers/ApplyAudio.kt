package ch.waio.pro_video_editor.src.features.render.helpers

import ch.waio.pro_video_editor.RENDER_TAG
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem


@UnstableApi
fun applyAudio(
    editedMediaItemBuilder: EditedMediaItem.Builder,
    enableAudio: Boolean?
) {
    // Remove Audio
    if (enableAudio == false) {
        Log.d(RENDER_TAG, "Removing audio from video")
        editedMediaItemBuilder.setRemoveAudio(true)
    }
}
