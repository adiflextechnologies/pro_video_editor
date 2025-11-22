package ch.waio.pro_video_editor.src.features.render.helpers

import ch.waio.pro_video_editor.RENDER_TAG
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation

@UnstableApi
fun applyFlip(videoEffects: MutableList<Effect>, flipX: Boolean, flipY: Boolean) {
    if (!flipX && !flipY) return

    Log.d(RENDER_TAG, "Applying flip: flipX: $flipX, flipY: $flipY")
    videoEffects += ScaleAndRotateTransformation.Builder()
        .setScale(if (flipX) -1f else 1f, if (flipY) -1f else 1f)
        .build()
}