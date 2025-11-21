package ch.waio.pro_video_editor.src.features.render.helpers

import ch.waio.pro_video_editor.RENDER_TAG
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GaussianBlur

@UnstableApi
fun applyBlur(videoEffects: MutableList<Effect>, blur: Double?) {
    if (blur == null || blur <= 0.0) return;

    Log.d(RENDER_TAG, "Applying Blur: Sigma: $blur")

    val blurEffect = GaussianBlur(blur.toFloat() * 2.5f)
    videoEffects += blurEffect
}