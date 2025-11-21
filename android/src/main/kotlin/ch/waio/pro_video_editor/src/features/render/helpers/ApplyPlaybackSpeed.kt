package ch.waio.pro_video_editor.src.features.render.helpers

import ch.waio.pro_video_editor.RENDER_TAG
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SpeedChangeEffect

@UnstableApi
fun applyPlaybackSpeed(
    videoEffects: MutableList<Effect>,
    audioEffects: MutableList<AudioProcessor>,
    playbackSpeed: Float?
) {
    if (playbackSpeed == null || playbackSpeed <= 0f) return;

    Log.d(RENDER_TAG, "Applying playback speed: $playbackSpeed×")
    videoEffects += SpeedChangeEffect(playbackSpeed)

    val audio = SonicAudioProcessor()
    audio.setSpeed(playbackSpeed)
    audioEffects += audio
}