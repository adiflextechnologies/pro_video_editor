package ch.waio.pro_video_editor.src.features

import PACKAGE_TAG
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.Build
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val CONCATENATE_TAG = "${PACKAGE_TAG}:ConcatenateVideos"

@UnstableApi
class ConcatenateVideos(private val context: Context) {
    // Keep track of normalized temp files for better diagnostics / cleanup
    private var lastNormalizedFiles: List<String> = emptyList()
    
    /**
     * Concatenate multiple videos into a single video file
     * @param inputPaths List of video file paths to concatenate
     * @param outputPath Output file path for the concatenated video
     * @param onProgress Callback for progress updates (0.0 to 1.0)
     * @param onComplete Callback when concatenation is complete
     * @param onError Callback when an error occurs
     */
    fun concatenate(
        inputPaths: List<String>,
        outputPath: String,
        onProgress: (Double) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        // Ensure everything runs on the main thread
        val mainHandler = Handler(Looper.getMainLooper())
        
        mainHandler.post {
            try {
                concatenateOnMainThread(inputPaths, outputPath, onProgress, onComplete, onError)
            } catch (e: Exception) {
                Log.e(CONCATENATE_TAG, "❌ Error in concatenate: ${e.message}", e)
                onError(e)
            }
        }
    }

    private fun concatenateOnMainThread(
        inputPaths: List<String>,
        outputPath: String,
        onProgress: (Double) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
        , triedNormalization: Boolean = false,
        triedRemux: Boolean = false
    ) {
        if (inputPaths.isEmpty()) {
            onError(IllegalArgumentException("No input videos provided"))
            return
        }

        if (inputPaths.size == 1) {
            // If only one video, just copy it
            try {
                File(inputPaths[0]).copyTo(File(outputPath), overwrite = true)
                onProgress(1.0)
                onComplete(outputPath)
            } catch (e: Exception) {
                onError(e)
            }
            return
        }

        Log.d(CONCATENATE_TAG, "Starting video concatenation:")
        Log.d(CONCATENATE_TAG, "  Input videos: ${inputPaths.size}")
        // Log detailed metadata for each input video to diagnose muxer stalls
        inputPaths.forEachIndexed { index, path ->
            try {
                val file = File(path)
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(path)
                val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
                val width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: -1
                val height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: -1
                val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "unknown"
                Log.d(CONCATENATE_TAG, "  [$index] ${file.name} size=${file.length() / 1024}KB duration=${durationMs}ms w=${width} h=${height} rot=${rotation} mime=${mime}")
                mmr.release()
            } catch (e: Exception) {
                Log.w(CONCATENATE_TAG, "  [$index] Failed to read metadata for $path: ${e.message}")
            }
        }
    Log.d(CONCATENATE_TAG, "  Output: $outputPath")

    // Detect emulator early: encoders on emulators are often unreliable and cause muxer stalls.
    val isEmulator = (Build.FINGERPRINT?.startsWith("generic") == true
        || Build.FINGERPRINT.contains("vbox")
        || Build.MODEL.contains("Emulator")
        || Build.MODEL.contains("Android SDK built for x86")
        || Build.MANUFACTURER.contains("Genymotion")
        || Build.HARDWARE.contains("goldfish")
        || Build.PRODUCT.contains("sdk"))

    if (isEmulator) {
        val msg = "Device appears to be an emulator. MediaCodec/MediaMuxer on emulators is unreliable and frequently causes muxer stalls (no output samples). Please test on a physical device to validate concatenation."
        Log.w(CONCATENATE_TAG, msg)
        onError(IllegalStateException(msg))
        return
    }

        val outputFile = File(outputPath)
        
        try {
            // Create EditedMediaItems for each input video with scale effect
            // This normalizes all videos to the same dimensions
            val editedMediaItems = inputPaths.mapIndexed { index, path ->
                Log.d(CONCATENATE_TAG, "Creating EditedMediaItem for video $index")
                
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(File(path)))
                    .build()
                
                // Apply scaling effect to normalize video dimensions
                val videoEffects = listOf(
                    ScaleAndRotateTransformation.Builder().build()
                )
                
                val effects = Effects(
                    /* audioProcessors= */ emptyList(),
                    /* videoEffects= */ videoEffects
                )
                
                EditedMediaItem.Builder(mediaItem)
                    .setEffects(effects)
                    .setRemoveAudio(false)
                    .setRemoveVideo(false)
                    .build()
            }

            Log.d(CONCATENATE_TAG, "Creating composition with ${editedMediaItems.size} items")
            
            // Create a sequence with all videos
            val sequence = EditedMediaItemSequence(editedMediaItems)

            // Create composition - force re-encoding for compatibility
            val composition = Composition.Builder(listOf(sequence))
                .setTransmuxVideo(false) // Force re-encode video
                .setTransmuxAudio(false) // Force re-encode audio
                .build()

            Log.d(CONCATENATE_TAG, "Building transformer...")
            
            // Prepare progress control
            val progressHolder = ProgressHolder()
            val shouldStopPolling = AtomicBoolean(false)

            // Create transformer with more lenient settings
            val transformer = Transformer.Builder(context)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setEnableFallback(true)
                        .build()
                )
                // Increase muxer watchdog timeout to give encoders more time on slow or constrained devices
                .setMaxDelayBetweenMuxerSamplesMs(120000) // 120 second timeout
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        Log.d(CONCATENATE_TAG, "✅ Concatenation completed successfully")
                        Log.d(CONCATENATE_TAG, "  Output size: ${outputFile.length() / 1024}KB")
                        onProgress(1.0)
                        onComplete(outputPath)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        Log.e(CONCATENATE_TAG, "❌ Concatenation failed: ${exception.message}", exception)
                        Log.e(CONCATENATE_TAG, "  triedNormalization=$triedNormalization")
                        shouldStopPolling.set(true)
                        Log.e(CONCATENATE_TAG, "  Error code: ${exception.errorCode}")
                        if (exception.cause != null) {
                            Log.e(CONCATENATE_TAG, "  Cause: ${exception.cause?.message}")
                        }

                        // If we haven't tried normalization yet, attempt to normalize each clip and retry
                        if (!triedNormalization) {
                            Log.w(CONCATENATE_TAG, "Attempting per-clip normalization and retry...")
                            normalizeInputs(inputPaths, { normalizedPaths ->
                                // remember normalized files for diagnostics/cleanup
                                lastNormalizedFiles = normalizedPaths
                                Log.d(CONCATENATE_TAG, "Normalization complete, retrying concatenation with ${normalizedPaths.size} files")
                                // Retry concatenation with normalized files, mark triedNormalization = true
                                concatenateOnMainThread(normalizedPaths, outputPath, onProgress, onComplete, onError, true)
                            }, { normErr ->
                                Log.e(CONCATENATE_TAG, "Normalization failed: ${normErr.message}")
                                if (outputFile.exists()) outputFile.delete()
                                onError(normErr)
                            })
                            return
                        }

                        // Log any normalized files we created and attempt cleanup
                        if (lastNormalizedFiles.isNotEmpty()) {
                            Log.w(CONCATENATE_TAG, "Normalized files created: ${lastNormalizedFiles.size}")
                            lastNormalizedFiles.forEachIndexed { i, p ->
                                try {
                                    val f = File(p)
                                    Log.w(CONCATENATE_TAG, "  [$i] ${f.name} size=${f.length() / 1024}KB")
                                    if (f.exists()) f.delete()
                                } catch (e: Exception) {
                                    Log.w(CONCATENATE_TAG, "  Failed to delete normalized file $p: ${e.message}")
                                }
                            }
                            lastNormalizedFiles = emptyList()
                        }

                        // If normalization already tried and failed, attempt a remux (copy tracks) fallback once
                        if (triedNormalization && !triedRemux) {
                            Log.w(CONCATENATE_TAG, "Normalization didn't help; attempting remux fallback...")
                            remuxInputs(inputPaths, { remuxedPaths ->
                                lastNormalizedFiles = remuxedPaths
                                Log.d(CONCATENATE_TAG, "Remux complete, retrying concatenation with ${remuxedPaths.size} files")
                                concatenateOnMainThread(remuxedPaths, outputPath, onProgress, onComplete, onError, true, true)
                            }, { remuxErr ->
                                Log.e(CONCATENATE_TAG, "Remux failed: ${remuxErr.message}")
                                if (outputFile.exists()) outputFile.delete()
                                onError(remuxErr)
                            })
                            return
                        }

                        // Clean up normalized/remuxed files we created and the output file on error
                        if (lastNormalizedFiles.isNotEmpty()) {
                            Log.w(CONCATENATE_TAG, "Normalized/Remuxed files created: ${lastNormalizedFiles.size}")
                            lastNormalizedFiles.forEachIndexed { i, p ->
                                try {
                                    val f = File(p)
                                    Log.w(CONCATENATE_TAG, "  [$i] ${f.name} size=${f.length() / 1024}KB")
                                    if (f.exists()) f.delete()
                                } catch (e: Exception) {
                                    Log.w(CONCATENATE_TAG, "  Failed to delete normalized file $p: ${e.message}")
                                }
                            }
                            lastNormalizedFiles = emptyList()
                        }

                        if (outputFile.exists()) {
                            outputFile.delete()
                        }
                        onError(exception)
                    }
                })
                .build()

            Log.d(CONCATENATE_TAG, "Starting transformation...")
            
            // Start transformation
            transformer.start(composition, outputPath)

            // Poll for progress on main thread
            val mainHandler = Handler(Looper.getMainLooper())

            mainHandler.post(object : Runnable {
                override fun run() {
                    if (shouldStopPolling.get()) return

                    try {
                        val state = transformer.getProgress(progressHolder)
                        
                        if (progressHolder.progress >= 0) {
                            val progress = progressHolder.progress.toDouble() / 100.0
                            onProgress(progress)
                            Log.d(CONCATENATE_TAG, "Progress: ${(progress * 100).toInt()}%")
                        }

                        // Continue polling if not finished
                        if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                            mainHandler.postDelayed(this, 100)
                        }
                    } catch (e: Exception) {
                        Log.e(CONCATENATE_TAG, "Progress polling error: ${e.message}")
                        shouldStopPolling.set(true)
                    }
                }
            })

        } catch (e: Exception) {
            Log.e(CONCATENATE_TAG, "❌ Error during concatenation setup: ${e.message}", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            onError(e)
        }
    }

    /**
     * Remux input files to temporary MP4s by copying their tracks using MediaExtractor + MediaMuxer.
     * This does not re-encode but can fix container-level issues (timestamps/boxes) that break some muxers.
     */
    private fun remuxInputs(
        inputPaths: List<String>,
        onComplete: (List<String>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val remuxed = mutableListOf<String>()

        fun remuxNext(idx: Int) {
            if (idx >= inputPaths.size) {
                onComplete(remuxed)
                return
            }

            val input = inputPaths[idx]
            try {
                val extractor = android.media.MediaExtractor()
                extractor.setDataSource(input)

                val trackCount = extractor.trackCount
                if (trackCount == 0) {
                    extractor.release()
                    onError(IllegalArgumentException("No tracks found in input: $input"))
                    return
                }

                val tempFile = File.createTempFile("remux_${idx}_", ".mp4", context.cacheDir)
                val muxer = android.media.MediaMuxer(tempFile.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val indexMap = IntArray(trackCount) { -1 }
                for (i in 0 until trackCount) {
                    val format = extractor.getTrackFormat(i)
                    try {
                        val muxIndex = muxer.addTrack(format)
                        indexMap[i] = muxIndex
                    } catch (e: Exception) {
                        // Skip tracks that cannot be added
                        indexMap[i] = -1
                    }
                }

                muxer.start()

                val bufferSize = 256 * 1024
                val buffer = java.nio.ByteBuffer.allocate(bufferSize)
                val bufferInfo = android.media.MediaCodec.BufferInfo()

                for (i in 0 until trackCount) {
                    if (indexMap[i] < 0) continue
                    extractor.selectTrack(i)
                    extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                    while (true) {
                        bufferInfo.offset = 0
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) {
                            break
                        }
                        bufferInfo.presentationTimeUs = extractor.sampleTime
                        bufferInfo.flags = extractor.sampleFlags
                        try {
                            muxer.writeSampleData(indexMap[i], buffer, bufferInfo)
                        } catch (e: Exception) {
                            Log.w(CONCATENATE_TAG, "Failed to write sample during remux: ${e.message}")
                        }
                        extractor.advance()
                    }
                    extractor.unselectTrack(i)
                }

                muxer.stop()
                muxer.release()
                extractor.release()

                Log.d(CONCATENATE_TAG, "Remuxed [$idx] $input -> ${tempFile.absolutePath} size=${tempFile.length() / 1024}KB")
                remuxed.add(tempFile.absolutePath)
                remuxNext(idx + 1)
            } catch (e: Exception) {
                onError(e)
            }
        }

        remuxNext(0)
    }

    /**
     * Normalize input videos by re-encoding each into a temp file using Transformer.
     * Calls onComplete with list of normalized file paths or onError on failure.
     */
    private fun normalizeInputs(
        inputPaths: List<String>,
        onComplete: (List<String>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val normalized = mutableListOf<String>()

        fun normalizeNext(index: Int) {
            if (index >= inputPaths.size) {
                onComplete(normalized)
                return
            }

            val input = inputPaths[index]
            try {
                val tempFile = File.createTempFile("norm_${index}_", ".mp4", context.cacheDir)
                Log.d(CONCATENATE_TAG, "Normalizing [$index] $input -> ${tempFile.absolutePath}")

                val mediaItem = MediaItem.Builder().setUri(Uri.fromFile(File(input))).build()
                val edited = EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(false)
                    .setRemoveVideo(false)
                    .build()

                val seq = EditedMediaItemSequence(listOf(edited))
                val comp = Composition.Builder(listOf(seq))
                    .setTransmuxVideo(false)
                    .setTransmuxAudio(false)
                    .build()

                val transformer = Transformer.Builder(context)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setEnableFallback(true)
                            .build()
                    )
                    .setMaxDelayBetweenMuxerSamplesMs(60000)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            Log.d(CONCATENATE_TAG, "Normalization completed for index $index -> ${tempFile.length() / 1024}KB")
                            normalized.add(tempFile.absolutePath)
                            // proceed to next
                            normalizeNext(index + 1)
                        }

                        override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                            Log.e(CONCATENATE_TAG, "Normalization failed for index $index: ${exception.message}", exception)
                            if (tempFile.exists()) tempFile.delete()
                            onError(exception)
                        }
                    })
                    .build()

                transformer.start(comp, tempFile.absolutePath)

            } catch (e: Exception) {
                onError(e)
            }
        }

        normalizeNext(0)
    }
}
