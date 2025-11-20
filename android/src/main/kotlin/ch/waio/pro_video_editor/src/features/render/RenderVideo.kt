package ch.waio.pro_video_editor.src.features.render

import PACKAGE_TAG
import RENDER_TAG
import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import applyAudio
import applyBitrate
import applyBlur
import applyColorMatrix
import applyCrop
import applyFlip
import applyImageLayer
import applyPlaybackSpeed
import applyRotation
import applyScale
import applyTrim
import mapFormatToMimeType
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
        onProgress: (Double) -> Unit,
        onComplete: (ByteArray?) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val inputFile = File(inputPath)
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

        val rotationDegrees = (4 - (rotateTurns ?: 0)) * 90f

        applyRotation(videoEffects, rotationDegrees)
        applyFlip(videoEffects, flipX, flipY)
        applyCrop(
            videoEffects, inputFile, rotationDegrees,
            flipX, flipY, cropWidth, cropHeight, cropX, cropY,
        )
        applyScale(videoEffects, scaleX, scaleY)
        applyTrim(mediaItemBuilder, startUs, endUs)
        applyColorMatrix(videoEffects, colorMatrixList)
        applyBlur(videoEffects, blur)
        applyImageLayer(
            videoEffects, inputFile, imageBytes, rotationDegrees,
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

        // Add listener and build
        val transformer = transformerBuilder
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    shouldStopPolling = true
                    try {
                        // If custom audio needs to be mixed, use Android's MediaMuxer
                        if (needsCustomAudioMixing && customAudioPath != null) {
                            Log.d(RENDER_TAG, "Video rendering complete, mixing custom audio with MediaMuxer...")
                            
                            val audioMixer = AudioMixer(context)
                            // Pass microsecond-based start/end times directly to AudioMixer
                            // (RenderVideoModel provides customAudioStartTime/customAudioEndTime in microseconds)
                            val audioMixSuccess = audioMixer.mixAudio(
                                videoPath = intermediateFile.absolutePath,
                                audioPath = customAudioPath,
                                outputPath = outputFile.absolutePath,
                                volume = customAudioVolume,
                                audioStartUs = customAudioStartTime,
                                audioEndUs = customAudioEndTime,
                                fadeInMs = customAudioFadeInDuration,
                                fadeOutMs = customAudioFadeOutDuration
                            )
                            
                            // Clean up intermediate file

                    // Ensure we remove transformer from registry after completion
                    runningTransformers.remove(id)
                            intermediateFile.delete()
                            
                            if (!audioMixSuccess) {
                                Log.w(RENDER_TAG, "Audio mixing failed, returning video without custom audio")
                                // Copy intermediate to output as fallback
                                intermediateFile.copyTo(outputFile, overwrite = true)
                            }
                        }
                        
                        // Return final result
                        if (outputPath != null) {
                            onComplete(null)
                        } else {
                            val resultBytes = outputFile.readBytes()
                            onComplete(resultBytes)
                        }
                    } catch (e: Exception) {
                        onError(e)
                    } finally {
                        mainHandler.removeCallbacksAndMessages(null) // stop progress polling
                        if (outputPath == null) outputFile.delete()
                        if (needsCustomAudioMixing && intermediateFile.exists()) {
                            intermediateFile.delete()
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
                    onProgress(progressHolder.progress / 100.0)
                }

                // Continue polling if transformer started
                if (!shouldStopPolling && progressState != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    mainHandler.postDelayed(this, 200)
                }
            }
        })
    }
}