package ch.waio.pro_video_editor.src.features.render

import ch.waio.pro_video_editor.PACKAGE_TAG
import ch.waio.pro_video_editor.RENDER_TAG
import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import ch.waio.pro_video_editor.src.features.render.helpers.applyAudio
import ch.waio.pro_video_editor.src.features.render.helpers.applyBitrate
import ch.waio.pro_video_editor.src.features.render.helpers.applyBlur
import ch.waio.pro_video_editor.src.features.render.helpers.applyColorMatrix
import ch.waio.pro_video_editor.src.features.render.helpers.applyCrop
import ch.waio.pro_video_editor.src.features.render.helpers.applyFlip
import ch.waio.pro_video_editor.src.features.render.helpers.applyImageLayer
import ch.waio.pro_video_editor.src.features.render.helpers.applyPlaybackSpeed
import ch.waio.pro_video_editor.src.features.render.helpers.applyRotation
import ch.waio.pro_video_editor.src.features.render.helpers.applyScale
import ch.waio.pro_video_editor.src.features.render.helpers.applyTrim
import ch.waio.pro_video_editor.src.features.render.utils.mapFormatToMimeType
import ch.waio.pro_video_editor.src.features.render.helpers.AudioMixer
import java.io.File

@UnstableApi
class RenderVideo(private val context: Context) {
    companion object {
        // Keep track of running transformers so they can be cancelled externally
        val runningTransformers = java.util.concurrent.ConcurrentHashMap<String, Transformer>()

        fun cancelRender(id: String) {
            val transformer = runningTransformers.remove(id)
            transformer?.cancel()
        }
    }
    fun render(
        id: String,
        imageBytes: ByteArray?,
        inputFormat: String,
        outputFormat: String,
        inputPath: String,
        outputPath: String?,
        rotateTurns: Int?,
        flipX: Boolean = false,
        flipY: Boolean = false,
        cropWidth: Int?,
        cropHeight: Int?,
        cropX: Int?,
        cropY: Int?,
        scaleX: Float?,
        scaleY: Float?,
        bitrate: Int?,
        enableAudio: Boolean = true,
        playbackSpeed: Float? = null,
        startUs: Long? = null,
        endUs: Long? = null,
        colorMatrixList: List<List<Double>>,
        blur: Double?,
        customAudioPath: String? = null,
        customAudioVolume: Double = 1.0,
        customAudioStartTime: Long? = null,
        customAudioEndTime: Long? = null,
        customAudioFadeInDuration: Long = 0L,
        customAudioFadeOutDuration: Long = 0L,
        onProgress: (Double, String?) -> Unit,
        onComplete: (ByteArray?) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val inputFile = File(inputPath)
        if (!inputFile.exists()) {
            Log.e(RENDER_TAG, "Input video file does not exist: $inputPath")
            onError(IllegalArgumentException("Input video file not found: $inputPath"))
            return
        }
        
        // Read the original video's rotation metadata BEFORE any processing
        // This is critical for preserving orientation when custom audio is added
        var originalVideoRotation = 0
        try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(inputPath)
            val rotationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            originalVideoRotation = rotationStr?.toIntOrNull() ?: 0
            retriever.release()
            Log.d(RENDER_TAG, "Original video rotation metadata: $originalVideoRotation degrees")
        } catch (e: Exception) {
            Log.w(RENDER_TAG, "Could not read original video rotation: ${e.message}")
        }
        
        Log.d(RENDER_TAG, "Starting video render - Input: $inputPath")
        Log.d(RENDER_TAG, "  Output format: $outputFormat")
        Log.d(RENDER_TAG, "  Enable audio: $enableAudio")
        Log.d(RENDER_TAG, "  Custom audio: $customAudioPath")
        Log.d(RENDER_TAG, "  Trim: $startUs to $endUs microseconds")
        Log.d(RENDER_TAG, "  Image overlay: ${imageBytes?.size ?: 0} bytes")
        
        val outputFile =
            if (outputPath != null) {
                File(outputPath)
            } else {
                File(
                    context.cacheDir,
                    "video_output_${System.currentTimeMillis()}.$outputFormat"
                )
            }

        // If custom audio is provided, we need a two-step process:
        // 1. Render video with Media3 (muted or without original audio)
        // 2. Mix custom audio with FFmpeg
        val needsCustomAudioMixing = customAudioPath != null && customAudioPath.isNotEmpty()
        val intermediateFile = if (needsCustomAudioMixing) {
            File(context.cacheDir, "video_intermediate_${System.currentTimeMillis()}.$outputFormat")
        } else {
            outputFile
        }

        val videoEffects = mutableListOf<Effect>()
        val audioEffects = mutableListOf<AudioProcessor>()
        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.fromFile(inputFile))

        // Calculate user-requested rotation
        val userRotationDegrees = (4 - (rotateTurns ?: 0)) * 90f
        
        // IMPORTANT: When custom audio is being mixed, check if the video already has rotation baked in
        // (i.e., rotation metadata is 0). Combined videos have rotation flattened into pixels during
        // concatenation, so we should NOT reapply the original rotation.
        // 
        // Only apply original rotation if:
        // 1. Custom audio is being used AND
        // 2. Original video has rotation metadata != 0 AND
        // 3. User hasn't applied additional rotation (userRotationDegrees is 0 or 360) AND
        // 4. The intermediate video ALSO has the same rotation (not yet flattened)
        val totalRotationDegrees = if (needsCustomAudioMixing && originalVideoRotation != 0 && userRotationDegrees.toInt() % 360 == 0) {
            // Check if current video still has rotation metadata
            // If it's already 0 (e.g., from concatenation), don't reapply
            try {
                val currentRetriever = android.media.MediaMetadataRetriever()
                currentRetriever.setDataSource(inputPath)
                val currentRotation = currentRetriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0
                currentRetriever.release()
                
                if (currentRotation == 0 && originalVideoRotation != 0) {
                    // Video already has rotation flattened (e.g., from concatenation)
                    // Don't reapply rotation
                    Log.d(RENDER_TAG, "Video already has rotation flattened (current=0°, original=$originalVideoRotation°), NOT reapplying")
                    0f
                } else if (currentRotation == originalVideoRotation) {
                    // Video still has original rotation, flatten it
                    Log.d(RENDER_TAG, "Custom audio mixing: applying rotation $originalVideoRotation° to flatten into pixels")
                    originalVideoRotation.toFloat()
                } else {
                    // Unexpected state, log and use user rotation
                    Log.w(RENDER_TAG, "Rotation mismatch: current=$currentRotation°, original=$originalVideoRotation°, using user rotation")
                    userRotationDegrees
                }
            } catch (e: Exception) {
                Log.w(RENDER_TAG, "Could not verify current rotation, using original: ${e.message}")
                originalVideoRotation.toFloat()
            }
        } else {
            userRotationDegrees
        }

        applyRotation(videoEffects, totalRotationDegrees)
        applyFlip(videoEffects, flipX, flipY)
        applyCrop(
            videoEffects, inputFile, totalRotationDegrees,
            flipX, flipY, cropWidth, cropHeight, cropX, cropY,
        )
        applyScale(videoEffects, scaleX, scaleY)
        applyTrim(mediaItemBuilder, startUs, endUs)
        applyColorMatrix(videoEffects, colorMatrixList)
        applyBlur(videoEffects, blur)
        applyImageLayer(
            videoEffects, inputFile, imageBytes, totalRotationDegrees,
            cropWidth, cropHeight, scaleX, scaleY
        )
        applyPlaybackSpeed(videoEffects, audioEffects, playbackSpeed)

        val mediaItem = mediaItemBuilder.build()
        val effects = Effects(audioEffects, videoEffects)

        val editedMediaItemBuilder = EditedMediaItem.Builder(mediaItem).setEffects(effects)

        // If custom audio will be applied, remove original audio during video rendering
        applyAudio(editedMediaItemBuilder, if (needsCustomAudioMixing) false else enableAudio)

        var shouldStopPolling = false
        val outputMimeType = mapFormatToMimeType(outputFormat)
        var editedMediaItem = editedMediaItemBuilder.build()
        val encoderFactoryBuilder = DefaultEncoderFactory.Builder(context)

        applyBitrate(encoderFactoryBuilder, outputMimeType, bitrate)


        val mainHandler = Handler(Looper.getMainLooper())

        // Build transformer with listeners
        val transformerBuilder = Transformer.Builder(context)
            .setEncoderFactory(encoderFactoryBuilder.build())
            .setVideoMimeType(outputMimeType)

        var mixingScheduled = false

        // Add listener and build
        val transformer = transformerBuilder
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    shouldStopPolling = true
                    try {
                        // If custom audio needs to be mixed, use Android's MediaMuxer
                        if (needsCustomAudioMixing && customAudioPath != null) {
                            Log.d(RENDER_TAG, "Video rendering complete (0-70%), mixing custom audio with MediaMuxer (70-100%)...")
                            Log.d(RENDER_TAG, "Video path: ${intermediateFile.absolutePath}")
                            Log.d(RENDER_TAG, "Audio path: $customAudioPath")
                            Log.d(RENDER_TAG, "Output path: ${outputFile.absolutePath}")
                            Log.d(RENDER_TAG, "Audio volume: $customAudioVolume")
                            Log.d(RENDER_TAG, "Audio trim: $customAudioStartTime to $customAudioEndTime")
                            
                            val audioMixer = AudioMixer(context)
                            // Pass microsecond-based start/end times directly to AudioMixer
                            // (RenderVideoModel provides customAudioStartTime/customAudioEndTime in microseconds)
                            
                            // Verify intermediate file exists before starting background thread
                            if (!intermediateFile.exists()) {
                                Log.e(RENDER_TAG, "❌ Intermediate file does not exist: ${intermediateFile.absolutePath}")
                                runningTransformers.remove(id)
                                onError(Exception("Intermediate video file not found: ${intermediateFile.absolutePath}"))
                                return
                            }
                            
                            Log.d(RENDER_TAG, "✅ Intermediate file exists (${intermediateFile.length()} bytes), starting audio mixing on background thread")
                            
                            // Diagnostic: Log intermediate file's rotation metadata
                            try {
                                val diagRetriever = android.media.MediaMetadataRetriever()
                                diagRetriever.setDataSource(intermediateFile.absolutePath)
                                val intermediateRotation = diagRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                                val intermediateWidth = diagRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                                val intermediateHeight = diagRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                                diagRetriever.release()
                                Log.d(RENDER_TAG, "📊 Intermediate file diagnostics:")
                                Log.d(RENDER_TAG, "   Input rotation was: $originalVideoRotation°")
                                Log.d(RENDER_TAG, "   Intermediate rotation: $intermediateRotation° (after Media3 processing)")
                                Log.d(RENDER_TAG, "   Intermediate dimensions: ${intermediateWidth}x${intermediateHeight}")
                                if (intermediateRotation != 0 && originalVideoRotation == 0) {
                                    Log.w(RENDER_TAG, "⚠️ Media3 added rotation metadata (${intermediateRotation}°) to a video that had rotation=0!")
                                }
                            } catch (e: Exception) {
                                Log.w(RENDER_TAG, "Could not read intermediate file metadata: ${e.message}")
                            }
                            
                            mixingScheduled = true
                            // Ensure UI immediately shows audio mixing started (70%)
                            mainHandler.post {
                                // Notify listeners that audio mixing stage has started
                                onProgress(0.7, "mix")
                            }
                            // Run audio mixing on background thread to avoid blocking main thread
                            Executors.newSingleThreadExecutor().execute {
                                try {
                                    // Map audio mixing progress (0.0-1.0) to overall progress (0.7-1.0)
                                    val audioMixSuccess = audioMixer.mixAudio(
                                        videoPath = intermediateFile.absolutePath,
                                        audioPath = customAudioPath,
                                        outputPath = outputFile.absolutePath,
                                        volume = customAudioVolume,
                                        audioStartUs = customAudioStartTime,
                                        audioEndUs = customAudioEndTime,
                                        fadeInMs = customAudioFadeInDuration,
                                        fadeOutMs = customAudioFadeOutDuration,
                                        sourceVideoRotation = originalVideoRotation,
                                        onProgress = { audioProgress, _ ->
                                            // Map audio mixing progress (0.0-1.0) to overall 70-100%
                                            val overallProgress = 0.7 + (audioProgress * 0.3)
                                            Log.d(RENDER_TAG, "Audio mixing callback: audioProgress=$audioProgress -> overallProgress=$overallProgress")
                                            // Post progress to main thread for EventChannel
                                                mainHandler.post { onProgress(overallProgress, "mix") }
                                        }
                                    )
                                    
                                    // Clean up intermediate file after successful mixing
                                    if (audioMixSuccess) {
                                        Log.d(RENDER_TAG, "Audio mixing successful, cleaning up intermediate file")
                                        
                                        // Diagnostic: Verify final output file's rotation
                                        try {
                                            val finalRetriever = android.media.MediaMetadataRetriever()
                                            finalRetriever.setDataSource(outputFile.absolutePath)
                                            val finalRotation = finalRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                                            val finalWidth = finalRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                                            val finalHeight = finalRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                                            finalRetriever.release()
                                            Log.d(RENDER_TAG, "📊 Final output file diagnostics:")
                                            Log.d(RENDER_TAG, "   Final rotation: $finalRotation° (should match input or be 0)")
                                            Log.d(RENDER_TAG, "   Final dimensions: ${finalWidth}x${finalHeight}")
                                            if (finalRotation != 0 && originalVideoRotation == 0) {
                                                Log.e(RENDER_TAG, "❌ ERROR: Final output has rotation (${finalRotation}°) but input had rotation=0!")
                                            }
                                        } catch (e: Exception) {
                                            Log.w(RENDER_TAG, "Could not read final output metadata: ${e.message}")
                                        }
                                        
                                        intermediateFile.delete()
                                    } else {
                                        Log.w(RENDER_TAG, "Audio mixing failed, returning video without custom audio")
                                        // Copy intermediate to output as fallback
                                        intermediateFile.copyTo(outputFile, overwrite = true)
                                        intermediateFile.delete()
                                    }
                                    
                                    // Remove transformer from registry and return result on main thread
                                    mainHandler.post {
                                        runningTransformers.remove(id)
                                        Log.d(RENDER_TAG, "Video generation complete, transformer removed from registry")
                                        
                                        // Return final result
                                        if (outputPath != null) {
                                            onComplete(null)
                                        } else {
                                            val resultBytes = outputFile.readBytes()
                                            onComplete(resultBytes)
                                        }
                                    }
                                } catch (e: Exception) {
                                    mainHandler.post {
                                        onError(e)
                                        runningTransformers.remove(id)
                                        if (outputPath == null) outputFile.delete()
                                        if (intermediateFile.exists()) intermediateFile.delete()
                                    }
                                }
                            }
                        } else {
                            // No audio mixing needed, return result immediately
                            runningTransformers.remove(id)
                            Log.d(RENDER_TAG, "Video generation complete, transformer removed from registry")
                            
                            // Return final result
                            if (outputPath != null) {
                                onComplete(null)
                            } else {
                                val resultBytes = outputFile.readBytes()
                                onComplete(resultBytes)
                            }
                        }
                    } catch (e: Exception) {
                        onError(e)
                    } finally {
                        mainHandler.removeCallbacksAndMessages(null) // stop progress polling
                        if (!mixingScheduled && outputPath == null) {
                            outputFile.delete()
                        }
                    }
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    shouldStopPolling = true
                    onError(exception)
                    // Remove transformer registry on error
                    runningTransformers.remove(id)
                    if (outputPath == null) outputFile.delete()
                    if (needsCustomAudioMixing && intermediateFile.exists()) {
                        intermediateFile.delete()
                    }
                }
            })
            .build()

        // Register transformer for cancellation and start transformation
        runningTransformers[id] = transformer
        transformer.start(editedMediaItem, intermediateFile.absolutePath)

        // Progress tracking setup
        val progressHolder = ProgressHolder()

        mainHandler.post(object : Runnable {
            override fun run() {
                if (shouldStopPolling) return

                val progressState = transformer.getProgress(progressHolder)
                if (progressHolder.progress >= 0) {
                    // Map video rendering progress (0-100%) to overall 0-70%
                    val videoProgress = progressHolder.progress / 100.0
                    val overallProgress = videoProgress * 0.7
                    onProgress(overallProgress, "render")
                }

                // Continue polling if transformer started
                if (!shouldStopPolling && progressState != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    mainHandler.postDelayed(this, 200)
                }
            }
        })
    }
}