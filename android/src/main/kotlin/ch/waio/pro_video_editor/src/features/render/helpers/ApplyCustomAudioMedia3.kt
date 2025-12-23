package ch.waio.pro_video_editor.src.features.render.helpers

import ch.waio.pro_video_editor.RENDER_TAG
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import java.io.File

/**
 * Creates a Media3 Composition with custom audio mixed in
 * This uses Media3's native audio mixing capabilities
 */
@UnstableApi
fun createCompositionWithCustomAudio(
    videoPath: String,
    audioPath: String,
    volume: Double = 1.0,
    startTimeMs: Long? = null,
    endTimeMs: Long? = null
): Composition {
    Log.d(RENDER_TAG, "Creating composition with custom audio:")
    Log.d(RENDER_TAG, "  Video: $videoPath")
    Log.d(RENDER_TAG, "  Audio: $audioPath")
    Log.d(RENDER_TAG, "  Volume: $volume")
    
    // Video MediaItem (without audio)
    val videoFile = File(videoPath)
    val videoMediaItemBuilder = MediaItem.Builder()
        .setUri(Uri.fromFile(videoFile))
    
    // Inspect rotation metadata and apply ScaleAndRotateTransformation to flatten rotation into pixels
    var videoRotation = 0
    try {
        val mmr = android.media.MediaMetadataRetriever()
        mmr.setDataSource(videoPath)
        videoRotation = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        mmr.release()
        if (videoRotation != 0) {
            Log.d(RENDER_TAG, "  Video has rotation: ${videoRotation}°; applying rotation transform to flatten metadata")
        }
    } catch (e: Exception) {
        Log.w(RENDER_TAG, "  Failed to read rotation metadata: ${e.message}")
    }

    // Preserve rotation metadata; do not add a rotation transform.
    val videoEffects = emptyList<androidx.media3.common.Effect>()

    val effectsForVideo = Effects(/* audioProcessors= */ emptyList(), /* videoEffects= */ videoEffects)

    val videoEditedItem = EditedMediaItem.Builder(videoMediaItemBuilder.build())
        .setRemoveAudio(true) // Remove original audio
        .setEffects(effectsForVideo)
        .build()
    
    // Audio MediaItem
    val audioFile = File(audioPath)
    val audioMediaItemBuilder = MediaItem.Builder()
        .setUri(Uri.fromFile(audioFile))
    
    // Apply trim if specified
    if (startTimeMs != null && endTimeMs != null) {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startTimeMs)
            .setEndPositionMs(endTimeMs)
            .build()
        audioMediaItemBuilder.setClippingConfiguration(clipping)
    }
    
    // Create audio processors for volume adjustment
    val audioProcessors = mutableListOf<AudioProcessor>()
    
    // Volume adjustment using ChannelMixingAudioProcessor
    if (volume != 1.0) {
        // Note: Media3 doesn't have a simple volume processor
        // Volume would need to be handled differently or post-process
        Log.d(RENDER_TAG, "Note: Volume adjustment requires custom audio processor")
    }
    
    val audioEffects = Effects(audioProcessors, emptyList())
    val audioEditedItem = EditedMediaItem.Builder(audioMediaItemBuilder.build())
        .setRemoveVideo(true) // Audio only
        .setEffects(audioEffects)
        .build()
    
    // Create a composition with video and audio sequences (use Builder API)
    val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(videoEditedItem))

    val audioSequence = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(audioEditedItem))
    
    return Composition.Builder(listOf(videoSequence, audioSequence))
        .setTransmuxAudio(false) // Don't just copy, process audio
        .setTransmuxVideo(false) // Process video too
        .build()
}
