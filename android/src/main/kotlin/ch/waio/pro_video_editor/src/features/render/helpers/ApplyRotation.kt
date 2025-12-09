package ch.waio.pro_video_editor.src.features.render.helpers

import ch.waio.pro_video_editor.RENDER_TAG
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation

@UnstableApi
fun applyRotation(videoEffects: MutableList<Effect>, rotationDegrees: Float) {
    // Normalize rotation to handle edge cases (e.g., 450 -> 90, -90 -> 270)
    val normalizedRotation = ((rotationDegrees % 360f) + 360f) % 360f
    
    if (normalizedRotation == 0f) return;

    Log.d(RENDER_TAG, "Applying rotation: $rotationDegrees degrees (normalized: $normalizedRotation)")
    videoEffects += ScaleAndRotateTransformation.Builder()
        .setRotationDegrees(normalizedRotation)
        .build()
}
