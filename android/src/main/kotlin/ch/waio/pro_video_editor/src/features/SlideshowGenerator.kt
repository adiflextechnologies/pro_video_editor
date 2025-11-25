package ch.waio.pro_video_editor.src.features

import ch.waio.pro_video_editor.PACKAGE_TAG
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import ch.waio.pro_video_editor.src.features.render.helpers.AudioMixer
import kotlin.math.max

private const val SLIDESHOW_TAG = "${PACKAGE_TAG}:SlideshowGenerator"

/**
 * SlideshowGenerator: Creates a video slideshow from multiple images with transitions
 * 
 * This implementation creates a single video by encoding frames with images appearing
 * sequentially at their designated time positions. This avoids the concatenation bugs
 * in the existing ConcatenateVideos implementation.
 */
class SlideshowGenerator(private val context: Context) {
    
    data class SlideConfig(
        val imagePath: String,
        val durationMs: Long,
        val transitionInType: String = "fade",
        val transitionOutType: String = "fade",
        val transitionInDurationMs: Long = 500,
        val transitionOutDurationMs: Long = 500
    )
    
    /**
     * Generate a slideshow video from multiple images
     * 
     * @param slides List of slide configurations with image paths and durations
     * @param outputPath Output file path for the slideshow video
     * @param width Video width (default: 1920)
     * @param height Video height (default: 1080)
     * @param fps Frames per second (default: 30)
     * @param audioPath Optional audio file to add to the slideshow
     * @param onProgress Callback for progress updates (0.0 to 1.0)
     * @param onComplete Callback when slideshow generation is complete
     * @param onError Callback when an error occurs
     */
    fun generateSlideshow(
        slides: List<SlideConfig>,
        outputPath: String,
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 30,
        audioPath: String? = null,
        onProgress: (Double, String?) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        try {
            if (slides.isEmpty()) {
                onError(IllegalArgumentException("No slides provided"))
                return
            }
            
            Log.d(SLIDESHOW_TAG, "🎬 Starting slideshow generation")
            Log.d(SLIDESHOW_TAG, "  Slides: ${slides.size}")
            Log.d(SLIDESHOW_TAG, "  Resolution: ${width}x${height}@${fps}fps")
            Log.d(SLIDESHOW_TAG, "  Output: $outputPath")
            
            // Calculate total duration (display duration + transitions for each slide)
            val totalDurationMs = slides.sumOf { it.durationMs + it.transitionInDurationMs + it.transitionOutDurationMs }
            val totalFrames = (totalDurationMs * fps / 1000).toInt()
            
            Log.d(SLIDESHOW_TAG, "  Total duration: ${totalDurationMs}ms (${totalFrames} frames)")
            
            onProgress(0.0, "slideshow")
            
            // Step 1: Create video with images (70% of progress)
            val videoPath = if (audioPath != null) {
                File.createTempFile("slideshow_video_", ".mp4", context.cacheDir).absolutePath
            } else {
                outputPath
            }
            
            createVideoWithImages(
                slides = slides,
                outputPath = videoPath,
                width = width,
                height = height,
                fps = fps,
                totalFrames = totalFrames,
                onProgress = { progress ->
                    onProgress(progress * 0.7, "slideshow")
                }
            )
            
            onProgress(0.7, "slideshow")
            
            // Step 2: Add audio if provided (30% of progress)
            if (audioPath != null) {
                Log.d(SLIDESHOW_TAG, "🎵 Adding audio to slideshow...")
                mixAudioWithVideo(
                    videoPath = videoPath,
                    audioPath = audioPath,
                    outputPath = outputPath,
                    durationMs = totalDurationMs,
                    onProgress = { progress ->
                        onProgress(0.7 + (progress * 0.3), "slideshow")
                    }
                )
                
                // Clean up temp video file
                try {
                    File(videoPath).delete()
                } catch (e: Exception) {
                    Log.w(SLIDESHOW_TAG, "Failed to delete temp video: ${e.message}")
                }
            }
            
            onProgress(1.0, "slideshow")
            
            Log.d(SLIDESHOW_TAG, "✅ Slideshow generated successfully")
            Log.d(SLIDESHOW_TAG, "  Output size: ${File(outputPath).length() / 1024}KB")
            onComplete(outputPath)
            
        } catch (e: Exception) {
            Log.e(SLIDESHOW_TAG, "❌ Error generating slideshow: ${e.message}", e)
            onError(e)
        }
    }
    
    /**
     * Create a video by encoding frames with images appearing sequentially
     */
    private fun createVideoWithImages(
        slides: List<SlideConfig>,
        outputPath: String,
        width: Int,
        height: Int,
        fps: Int,
        totalFrames: Int,
        onProgress: (Double) -> Unit
    ) {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var videoTrackIndex = -1
        var muxerStarted = false
        
        try {
            // Configure video encoder
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, width, height)

            // Choose a color format supported by the device for byte-buffer input.
            val supportedColorFormat = selectSupportedColorFormat(mime)
            if (supportedColorFormat != null) {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, supportedColorFormat)
                Log.d(SLIDESHOW_TAG, "Using color format $supportedColorFormat for mime $mime")
            } else {
                // Fallback to surface-based encoding if no suitable ByteBuffer YUV format found.
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                Log.w(SLIDESHOW_TAG, "No supported YUV byte-buffer format found; falling back to COLOR_FormatSurface (Surface input)")
            }
            format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000) // 8 Mbps
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            
            // Try to configure encoder with chosen color format, but robustly fall back
            var configured = false
            // Prefer using a Surface input on Android devices because Surface encoding
            // avoids error-prone manual YUV byte-buffer conversions that often cause
            // desaturated / black-and-white output on some devices. Keep ByteBuffer
            // YUV formats as fallbacks in case Surface isn't supported.
            val candidateFormats = mutableListOf<Int?>()
            candidateFormats.add(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            candidateFormats.add(supportedColorFormat)
            // Additional fallbacks: semi-planar, planar, flexible
            candidateFormats.add(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            candidateFormats.add(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
            candidateFormats.add(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

            for (candidate in candidateFormats) {
                var encoderCandidate: MediaCodec? = null
                try {
                    encoderCandidate = MediaCodec.createEncoderByType(mime)
                    if (candidate != null) {
                        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, candidate)
                    }
                    encoderCandidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoder = encoderCandidate
                    configured = true
                    Log.d(SLIDESHOW_TAG, "MediaCodec configured with color format: $candidate")
                    break
                } catch (e: Exception) {
                    Log.w(SLIDESHOW_TAG, "Failed to configure color format $candidate: ${e.message}")
                    try {
                        encoderCandidate?.release()
                    } catch (_: Exception) {}
                    // Try next candidate
                }
            }

            if (!configured) {
                throw IllegalStateException("Unable to configure MediaCodec with any tested color formats")
            }

            val enc = encoder!!

            // Determine whether encoder expects Surface input from the configured format
            var configuredColorFormat: Int? = null
            try {
                configuredColorFormat = format.getInteger(MediaFormat.KEY_COLOR_FORMAT)
            } catch (_: Exception) {}
            val usingSurface = configuredColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            var inputSurface: Surface? = null
            if (usingSurface) {
                try {
                    // Create input surface before starting the encoder as required by many devices
                    inputSurface = enc.createInputSurface()
                    Log.d(SLIDESHOW_TAG, "Encoder uses input Surface; created inputSurface")
                } catch (e: Exception) {
                    Log.w(SLIDESHOW_TAG, "Failed to create input surface: ${e.message}")
                    inputSurface = null
                }
            }

            // Now start the encoder
            enc.start()
            
            // Create muxer
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            // Load and prepare all images
            val loadedImages = slides.map { slide ->
                loadAndScaleImage(slide.imagePath, width, height)
            }
            
            Log.d(SLIDESHOW_TAG, "✅ Loaded ${loadedImages.size} images")
            
            val bufferInfo = MediaCodec.BufferInfo()
            val frameDurationUs = 1_000_000L / fps
            var currentFrameIndex = 0
            var inputEOS = false
            var chosenColorFormat = supportedColorFormat
            
            // Process each frame
            while (currentFrameIndex < totalFrames || !inputEOS) {
                // Feed input frames
                if (!inputEOS && currentFrameIndex < totalFrames) {
                    // Determine which slide this frame belongs to
                    val (slideIndex, frameProgress, slideStartFrame) = getSlideForFrame(
                            frameIndex = currentFrameIndex,
                            slides = slides,
                            fps = fps
                        )
                        
                        // Get the image for this frame
                        val image = loadedImages[slideIndex]

                        // Compute slide total duration for this slide
                        val slideTotalDurationMs = slides[slideIndex].durationMs + slides[slideIndex].transitionInDurationMs + slides[slideIndex].transitionOutDurationMs

                        // Apply transition effect
                        val alpha = calculateTransitionAlpha(
                            frameProgress = frameProgress,
                            transitionIn = slides[slideIndex].transitionInType,
                            transitionOut = slides[slideIndex].transitionOutType,
                            transitionInDurationMs = slides[slideIndex].transitionInDurationMs,
                            transitionOutDurationMs = slides[slideIndex].transitionOutDurationMs,
                            slideTotalDurationMs = slideTotalDurationMs
                        )

                        val (tx, ty, scale) = calculateTransform(
                            frameProgress = frameProgress,
                            transitionIn = slides[slideIndex].transitionInType,
                            transitionOut = slides[slideIndex].transitionOutType,
                            transitionInDurationMs = slides[slideIndex].transitionInDurationMs,
                            transitionOutDurationMs = slides[slideIndex].transitionOutDurationMs,
                            slideTotalDurationMs = slideTotalDurationMs,
                            width = width,
                            height = height
                        )

                        // Compute finalBitmap (including crossfade) for both paths
                        var finalBitmap: Bitmap = image
                        var composited: Bitmap? = null
                        val currSlide = slides[slideIndex]
                        val inFraction = if ( (currSlide.durationMs + currSlide.transitionInDurationMs + currSlide.transitionOutDurationMs) > 0 ) currSlide.transitionInDurationMs.toFloat() / (currSlide.durationMs + currSlide.transitionInDurationMs + currSlide.transitionOutDurationMs).toFloat() else 0f
                        if (frameProgress < inFraction && slideIndex > 0) {
                            val prevIndex = slideIndex - 1
                            val prevSlide = slides[prevIndex]
                            // Check if either side wants a crossfade effect
                            if (currSlide.transitionInType == "crossfade" || prevSlide.transitionOutType == "crossfade") {
                                val prevSlideStartFrame = slideStartFrame - ((prevSlide.durationMs + prevSlide.transitionInDurationMs + prevSlide.transitionOutDurationMs) * fps / 1000).toInt()
                                val prevSlideFrames = ((prevSlide.durationMs + prevSlide.transitionInDurationMs + prevSlide.transitionOutDurationMs) * fps / 1000).toInt()
                                val prevFrameIndex = currentFrameIndex - prevSlideStartFrame
                                val prevProgress = prevFrameIndex.toFloat() / prevSlideFrames
                                val prevSlideTotalMs = prevSlide.durationMs + prevSlide.transitionInDurationMs + prevSlide.transitionOutDurationMs
                                val prevAlpha = calculateTransitionAlpha(prevProgress, prevSlide.transitionInType, prevSlide.transitionOutType, prevSlide.transitionInDurationMs, prevSlide.transitionOutDurationMs, prevSlideTotalMs)
                                val prevTransform = calculateTransform(prevProgress, prevSlide.transitionInType, prevSlide.transitionOutType, prevSlide.transitionInDurationMs, prevSlide.transitionOutDurationMs, prevSlideTotalMs, width, height)
                                val prevBitmap = loadedImages[prevIndex]
                                val transformedPrev = transformBitmap(prevBitmap, prevTransform.first, prevTransform.second, prevTransform.third, width, height)
                                val transformedCurr = transformBitmap(image, tx, ty, scale, width, height)
                                composited = composeBitmaps(transformedPrev, prevAlpha, transformedCurr, alpha, width, height)
                                // Recycle temp bitmaps after compositing
                                transformedPrev.recycle()
                                transformedCurr.recycle()
                                finalBitmap = composited
                            } else {
                                // No crossfade; just use transformed
                                finalBitmap = transformBitmap(image, tx, ty, scale, width, height)
                            }
                        } else {
                            // Not in crossfade window
                            finalBitmap = transformBitmap(image, tx, ty, scale, width, height)
                        }

                    if (usingSurface && inputSurface != null) {
                        // Draw on inputSurface using Canvas
                        try {
                            val canvas = inputSurface.lockCanvas(null)
                            // Draw the finalBitmap centered or covering the canvas
                            val srcRect = android.graphics.Rect(0, 0, finalBitmap.width, finalBitmap.height)
                            val dstRect = android.graphics.Rect(0, 0, width, height)
                            canvas.drawBitmap(finalBitmap, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
                            inputSurface.unlockCanvasAndPost(canvas)
                        } catch (e: Exception) {
                            Log.w(SLIDESHOW_TAG, "Failed to render frame to surface: ${e.message}")
                        }
                        currentFrameIndex++
                        // Periodic progress reporting
                        if (currentFrameIndex % (fps * 2) == 0) {
                            val progress = currentFrameIndex.toDouble() / totalFrames
                            onProgress(progress)
                            Log.d(SLIDESHOW_TAG, "  Surface encoding progress: ${(progress * 100).toInt()}%")
                        }
                    } else {
                        val inputBufferIndex = enc.dequeueInputBuffer(10_000)
                        if (inputBufferIndex >= 0) {
                                    // Determine which slide this frame belongs to
                                    val (slideIndex, frameProgress, slideStartFrame) = getSlideForFrame(
                            frameIndex = currentFrameIndex,
                            slides = slides,
                            fps = fps
                        )
                        
                        // Get the image for this frame
                        val image = loadedImages[slideIndex]

                        // Compute slide total duration for this slide
                        val slideTotalDurationMs = slides[slideIndex].durationMs + slides[slideIndex].transitionInDurationMs + slides[slideIndex].transitionOutDurationMs

                        // Convert bitmap to YUV420 and feed to encoder
                        val inputBuffer = enc.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            
                            // Apply transition effect
                            val alpha = calculateTransitionAlpha(
                                frameProgress = frameProgress,
                                transitionIn = slides[slideIndex].transitionInType,
                                transitionOut = slides[slideIndex].transitionOutType,
                                transitionInDurationMs = slides[slideIndex].transitionInDurationMs,
                                transitionOutDurationMs = slides[slideIndex].transitionOutDurationMs,
                                slideTotalDurationMs = slideTotalDurationMs
                            )

                            val (tx, ty, scale) = calculateTransform(
                                frameProgress = frameProgress,
                                transitionIn = slides[slideIndex].transitionInType,
                                transitionOut = slides[slideIndex].transitionOutType,
                                transitionInDurationMs = slides[slideIndex].transitionInDurationMs,
                                transitionOutDurationMs = slides[slideIndex].transitionOutDurationMs,
                                slideTotalDurationMs = slideTotalDurationMs,
                                width = width,
                                height = height
                            )

                            // Log transform info for debugging
                            Log.d(SLIDESHOW_TAG, "  frameIndex=$currentFrameIndex slide=$slideIndex progress=$frameProgress alpha=$alpha tx=$tx ty=$ty scale=$scale")

                            // Determine if crossfade is needed between previous and current slide
                            var finalBitmap: Bitmap = image
                            var composited: Bitmap? = null
                            val currSlide = slides[slideIndex]
                            val inFraction = if ( (currSlide.durationMs + currSlide.transitionInDurationMs + currSlide.transitionOutDurationMs) > 0 ) currSlide.transitionInDurationMs.toFloat() / (currSlide.durationMs + currSlide.transitionInDurationMs + currSlide.transitionOutDurationMs).toFloat() else 0f
                            if (frameProgress < inFraction && slideIndex > 0) {
                                val prevIndex = slideIndex - 1
                                val prevSlide = slides[prevIndex]
                                // Check if either side wants a crossfade effect
                                if (currSlide.transitionInType == "crossfade" || prevSlide.transitionOutType == "crossfade") {
                                    // Compute previous slide frame progress
                                    val prevSlideStartFrame = slideStartFrame - ((prevSlide.durationMs + prevSlide.transitionInDurationMs + prevSlide.transitionOutDurationMs) * fps / 1000).toInt()
                                    val prevSlideFrames = ((prevSlide.durationMs + prevSlide.transitionInDurationMs + prevSlide.transitionOutDurationMs) * fps / 1000).toInt()
                                    val prevFrameIndex = currentFrameIndex - prevSlideStartFrame
                                    val prevProgress = prevFrameIndex.toFloat() / prevSlideFrames
                                    val prevSlideTotalMs = prevSlide.durationMs + prevSlide.transitionInDurationMs + prevSlide.transitionOutDurationMs
                                    val prevAlpha = calculateTransitionAlpha(prevProgress, prevSlide.transitionInType, prevSlide.transitionOutType, prevSlide.transitionInDurationMs, prevSlide.transitionOutDurationMs, prevSlideTotalMs)
                                    val prevTransform = calculateTransform(prevProgress, prevSlide.transitionInType, prevSlide.transitionOutType, prevSlide.transitionInDurationMs, prevSlide.transitionOutDurationMs, prevSlideTotalMs, width, height)
                                    val prevBitmap = loadedImages[prevIndex]
                                    val transformedPrev = transformBitmap(prevBitmap, prevTransform.first, prevTransform.second, prevTransform.third, width, height)
                                    val transformedCurr = transformBitmap(image, tx, ty, scale, width, height)
                                    composited = composeBitmaps(transformedPrev, prevAlpha, transformedCurr, alpha, width, height)
                                    // Recycle temp bitmaps after compositing
                                    transformedPrev.recycle()
                                    transformedCurr.recycle()
                                    finalBitmap = composited
                                } else {
                                    // No crossfade; just use transformed
                                    finalBitmap = transformBitmap(image, tx, ty, scale, width, height)
                                }
                            } else {
                                // Not in crossfade window
                                finalBitmap = transformBitmap(image, tx, ty, scale, width, height)
                            }

                            // Convert bitmap to YUV with alpha
                            var yuvData = bitmapToYUV420(finalBitmap, width, height, 1.0f)
                            // After configure succeeded, read the active color format from the configured format
                            try {
                                chosenColorFormat = format.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                            } catch (_: Exception) {}

                            // Convert if encoder expects planar or semi-planar YUV layout
                            if (chosenColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                                yuvData = convertNV12ToPlanar(yuvData, width, height)
                            } else if (chosenColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                                // Some encoders expect NV21 order for semi-planar (V then U) while our conversion produces NV12 (U then V).
                                // Convert NV12 -> NV21 to satisfy encoders that expect VU ordering.
                                yuvData = convertNV12ToNV21(yuvData, width, height)
                            }
                            // (encoder.outputFormat isn't available until format changed; rely on the chosenColorFormat)
                            // Recycle the temporary final bitmap if we created a composite
                            if (composited != null) composited.recycle() else finalBitmap.recycle()
                            if (inputBuffer.capacity() >= yuvData.size) {
                                inputBuffer.put(yuvData)
                            } else {
                                Log.w(SLIDESHOW_TAG, "Input buffer too small (${inputBuffer.capacity()}) for YUV data (${yuvData.size}), skipping frame")
                            }
                            
                            val presentationTimeUs = currentFrameIndex * frameDurationUs
                            enc.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                yuvData.size,
                                presentationTimeUs,
                                0
                            )
                            
                            currentFrameIndex++
                            
                            // Report progress
                            if (currentFrameIndex % (fps * 2) == 0) { // Every 2 seconds
                                val progress = currentFrameIndex.toDouble() / totalFrames
                                onProgress(progress)
                                Log.d(SLIDESHOW_TAG, "  Encoding progress: ${(progress * 100).toInt()}%")
                            }
                        }
                        }
                    }
                }
                
                // Signal end of input after all frames
                if (currentFrameIndex >= totalFrames && !inputEOS) {
                    if (usingSurface && inputSurface != null) {
                        try {
                            enc.signalEndOfInputStream()
                            inputEOS = true
                            Log.d(SLIDESHOW_TAG, "  EOS signaled to encoder via Surface")
                        } catch (e: Exception) {
                            Log.w(SLIDESHOW_TAG, "Failed to signal EOS via surface: ${e.message}")
                        }
                    } else {
                        val inputBufferIndex = enc.dequeueInputBuffer(10_000)
                        if (inputBufferIndex >= 0) {
                            enc.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEOS = true
                            Log.d(SLIDESHOW_TAG, "  EOS signaled to encoder")
                        }
                    }
                }
                
                // Get output
                val outputBufferIndex = enc.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = enc.outputFormat
                        videoTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                        Log.d(SLIDESHOW_TAG, "  Muxer started")
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = enc.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        }
                        
                        enc.releaseOutputBuffer(outputBufferIndex, false)
                        
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(SLIDESHOW_TAG, "  EOS received from encoder")
                            break
                        }
                    }
                }
            }
            
            onProgress(1.0)
            
            // Cleanup images
            loadedImages.forEach { it.recycle() }
            
        } finally {
            try {
                encoder?.stop()
            } catch (e: Exception) {
                Log.w(SLIDESHOW_TAG, "Encoder stop skipped: ${e.message}")
            }

            try {
                encoder?.release()
            } catch (e: Exception) {
                Log.w(SLIDESHOW_TAG, "Encoder release skipped: ${e.message}")
            }

            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
            } catch (e: Exception) {
                Log.w(SLIDESHOW_TAG, "Muxer stop skipped: ${e.message}")
            }

            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.w(SLIDESHOW_TAG, "Muxer release skipped: ${e.message}")
            }
        }
    }
    
    /**
     * Determine which slide a frame belongs to and the progress within that slide
     * Returns (slideIndex, frameProgress) where frameProgress is 0.0 to 1.0
     */
    private fun getSlideForFrame(
        frameIndex: Int,
        slides: List<SlideConfig>,
        fps: Int
    ): Triple<Int, Float, Int> { // slideIndex, frameProgress, startFrame
        var accumulatedFrames = 0
        
        slides.forEachIndexed { index, slide ->
            val slideFrames = ((slide.durationMs + slide.transitionInDurationMs + slide.transitionOutDurationMs) * fps / 1000).toInt()
            if (frameIndex < accumulatedFrames + slideFrames) {
                val frameInSlide = frameIndex - accumulatedFrames
                val progress = frameInSlide.toFloat() / slideFrames
                return Triple(index, progress, accumulatedFrames)
            }
            accumulatedFrames += slideFrames
        }
        
        // Fallback to last slide
        return Triple(slides.size - 1, 1.0f, accumulatedFrames)
    }
    
    /**
     * Calculate alpha value based on transition type and progress
     */
    private fun calculateTransitionAlpha(
        frameProgress: Float,
        transitionIn: String,
        transitionOut: String,
        transitionInDurationMs: Long,
        transitionOutDurationMs: Long,
        slideTotalDurationMs: Long
    ): Float {
        // Convert durations to fraction of the slide total
        val fadeInFraction = if (slideTotalDurationMs > 0) (transitionInDurationMs.toFloat() / slideTotalDurationMs.toFloat()) else 0.0f
        val fadeOutFraction = if (slideTotalDurationMs > 0) (transitionOutDurationMs.toFloat() / slideTotalDurationMs.toFloat()) else 0.0f

        return when {
            frameProgress < fadeInFraction && transitionIn == "fade" -> {
                frameProgress / fadeInFraction
            }
            frameProgress > (1.0f - fadeOutFraction) && transitionOut == "fade" -> {
                (1.0f - frameProgress) / fadeOutFraction
            }
            else -> 1.0f
        }
    }

    /**
     * Calculate translation and scale for basic transitions (slide and zoom).
     * Returns Triple(tx, ty, scale) to be applied when drawing the image.
     */
    private fun calculateTransform(
        frameProgress: Float,
        transitionIn: String,
        transitionOut: String,
        transitionInDurationMs: Long,
        transitionOutDurationMs: Long,
        slideTotalDurationMs: Long,
        width: Int,
        height: Int
    ): Triple<Float, Float, Float> {
        val inFraction = if (slideTotalDurationMs > 0) (transitionInDurationMs.toFloat() / slideTotalDurationMs.toFloat()) else 0.0f
        val outFraction = if (slideTotalDurationMs > 0) (transitionOutDurationMs.toFloat() / slideTotalDurationMs.toFloat()) else 0.0f

        // Default no transform
        var tx = 0f
        var ty = 0f
        var scale = 1.0f

        // Apply slide-in transforms
        if (frameProgress < inFraction) {
            val t = if (inFraction > 0) frameProgress / inFraction else 1.0f
            when (transitionIn) {
                "slideLeft" -> { tx = width * (1.0f - t) }
                "slideRight" -> { tx = -width * (1.0f - t) }
                "slideUp" -> { ty = height * (1.0f - t) }
                "slideDown" -> { ty = -height * (1.0f - t) }
                "zoomIn" -> { scale = 1.2f - 0.2f * t }
                "zoomOut" -> { scale = 0.8f + 0.2f * t }
                else -> {}
            }
        }

        // Apply slide-out transforms
        if (frameProgress > (1.0f - outFraction)) {
            val t = if (outFraction > 0) (frameProgress - (1.0f - outFraction)) / outFraction else 1.0f
            when (transitionOut) {
                "slideLeft" -> { tx = -width * t }
                "slideRight" -> { tx = width * t }
                "slideUp" -> { ty = -height * t }
                "slideDown" -> { ty = height * t }
                "zoomIn" -> { scale = 1.0f + 0.2f * t }
                "zoomOut" -> { scale = 1.0f - 0.2f * t }
                else -> {}
            }
        }

        return Triple(tx, ty, scale)
    }
    
    /**
     * Load and scale an image to fit the video dimensions
     */
    private fun loadAndScaleImage(imagePath: String, targetWidth: Int, targetHeight: Int): Bitmap {
        // Load image
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imagePath, options)
        
        // Calculate sample size
        val srcWidth = options.outWidth
        val srcHeight = options.outHeight
        var sampleSize = 1
        
        if (srcWidth > targetWidth || srcHeight > targetHeight) {
            val widthRatio = srcWidth / targetWidth
            val heightRatio = srcHeight / targetHeight
            sampleSize = max(widthRatio, heightRatio)
        }
        
        // Load scaled image
        val loadOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        var sourceBitmap = BitmapFactory.decodeFile(imagePath, loadOptions)
        if (sourceBitmap == null) {
            Log.w(SLIDESHOW_TAG, "Failed to decode image at $imagePath; using placeholder black image")
            sourceBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sourceBitmap)
            canvas.drawColor(Color.BLACK)
        }
        
        // Create final bitmap with correct dimensions (letterbox/pillarbox)
        val finalBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBitmap)
        
        // Fill with black background
        canvas.drawColor(Color.BLACK)
        
        // Calculate scaling to fit image within video dimensions
        val srcRatio = sourceBitmap.width.toFloat() / sourceBitmap.height
        val targetRatio = targetWidth.toFloat() / targetHeight
        
        val matrix = Matrix()
        if (srcRatio > targetRatio) {
            // Image is wider - fit to width
            val scale = targetWidth.toFloat() / sourceBitmap.width
            val scaledHeight = sourceBitmap.height * scale
            val offsetY = (targetHeight - scaledHeight) / 2
            matrix.postScale(scale, scale)
            matrix.postTranslate(0f, offsetY)
        } else {
            // Image is taller - fit to height
            val scale = targetHeight.toFloat() / sourceBitmap.height
            val scaledWidth = sourceBitmap.width * scale
            val offsetX = (targetWidth - scaledWidth) / 2
            matrix.postScale(scale, scale)
            matrix.postTranslate(offsetX, 0f)
        }
        
        canvas.drawBitmap(sourceBitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        sourceBitmap.recycle()
        
        return finalBitmap
    }

    private fun transformBitmap(source: Bitmap, tx: Float, ty: Float, scale: Float, width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)

        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(tx, ty)

        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))

        return result
    }

    /**
     * Compose two transformed bitmaps with given alpha values onto a single bitmap.
     */
    private fun composeBitmaps(prev: Bitmap, prevAlpha: Float, curr: Bitmap, currAlpha: Float, width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)

        val prevPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        prevPaint.alpha = (prevAlpha.coerceIn(0f, 1f) * 255).toInt()
        val currPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        currPaint.alpha = (currAlpha.coerceIn(0f, 1f) * 255).toInt()

        canvas.drawBitmap(prev, 0f, 0f, prevPaint)
        canvas.drawBitmap(curr, 0f, 0f, currPaint)

        return result
    }
    
    /**
     * Convert a bitmap to YUV420 planar format with alpha
     */
    private fun bitmapToYUV420(bitmap: Bitmap, width: Int, height: Int, alpha: Float): ByteArray {
        val frameSize = width * height
        val yuvSize = frameSize + (frameSize / 2) // Y + U + V
        val yuv = ByteArray(yuvSize)
        
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        
        var yIndex = 0
        var uvIndex = frameSize
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = argb[y * width + x]
                
                // Apply alpha
                var r = ((pixel shr 16) and 0xFF) * alpha
                var g = ((pixel shr 8) and 0xFF) * alpha
                var b = (pixel and 0xFF) * alpha
                
                // RGB to YUV conversion
                val yValue = ((66 * r + 129 * g + 25 * b + 128) / 256 + 16).toInt()
                val uValue = ((-38 * r - 74 * g + 112 * b + 128) / 256 + 128).toInt()
                val vValue = ((112 * r - 94 * g - 18 * b + 128) / 256 + 128).toInt()
                
                yuv[yIndex++] = yValue.coerceIn(0, 255).toByte()
                
                if (y % 2 == 0 && x % 2 == 0) {
                    yuv[uvIndex++] = uValue.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = vValue.coerceIn(0, 255).toByte()
                }
            }
        }
        
        return yuv
    }

    /** Convert NV12 (Y + interleaved UV) to planar YUV (Y + U-plane + V-plane) */
    private fun convertNV12ToPlanar(nv12: ByteArray, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        val chromaSize = frameSize / 4
        val out = ByteArray(frameSize + chromaSize * 2)
        // Copy Y
        System.arraycopy(nv12, 0, out, 0, frameSize)
        // nv12: interleaved UV starting at frameSize
        var uvIndex = frameSize
        val uPlane = ByteArray(chromaSize)
        val vPlane = ByteArray(chromaSize)
        var idx = 0
        while (uvIndex < nv12.size) {
            uPlane[idx] = nv12[uvIndex++] // U
            if (uvIndex < nv12.size) vPlane[idx] = nv12[uvIndex++] // V
            idx++
        }
        System.arraycopy(uPlane, 0, out, frameSize, chromaSize)
        System.arraycopy(vPlane, 0, out, frameSize + chromaSize, chromaSize)
        return out
    }

    /** Convert NV12 (Y + interleaved UV) to NV21 (Y + interleaved VU) */
    private fun convertNV12ToNV21(nv12: ByteArray, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        val uvSize = frameSize / 2
        val out = ByteArray(frameSize + uvSize)
        // Copy Y plane
        System.arraycopy(nv12, 0, out, 0, frameSize)
        // Interleaved UV plane -> convert to VU by swapping every pair
        var src = frameSize
        var dst = frameSize
        while (src + 1 < nv12.size) {
            val u = nv12[src]
            val v = nv12[src + 1]
            out[dst] = v
            out[dst + 1] = u
            src += 2
            dst += 2
        }
        return out
    }

    /**
     * Select a supported color format for the given mime that supports direct ByteBuffer input.
     * Prefer semi-planar (NV12) or planar formats for compatibility.
     */
    private fun selectSupportedColorFormat(mime: String): Int? {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val codecs = codecList.codecInfos
            for (codecInfo in codecs) {
                if (!codecInfo.isEncoder) continue
                val types = codecInfo.supportedTypes
                if (!types.any { it.equals(mime, ignoreCase = true) }) continue
                val caps = codecInfo.getCapabilitiesForType(mime)
                val colorFormats = caps.colorFormats
                // Prefer these formats in order
                val preferred = listOf(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                )
                for (pf in preferred) {
                    if (colorFormats.contains(pf)) return pf
                }
                // If none matched, try to return any other format that's not COLOR_FormatSurface
                colorFormats.forEach { f ->
                    if (f != MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) return f
                }
            }
        } catch (e: Exception) {
            Log.w(SLIDESHOW_TAG, "Failed to get supported color formats: ${e.message}")
        }
        return null
    }
    
    /**
     * Mix audio with video
     */
    private fun mixAudioWithVideo(
        videoPath: String,
        audioPath: String,
        outputPath: String,
        durationMs: Long,
        onProgress: (Double) -> Unit
    ) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        
        try {
            videoExtractor.setDataSource(videoPath)
            audioExtractor.setDataSource(audioPath)
            
            // Find tracks
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    break
                }
            }
            
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }
            
            if (videoTrackIndex == -1) {
                throw IllegalArgumentException("No video track found")
            }
            
            // If the audio is MP3 (audio/mpeg), delegate to AudioMixer which handles transcoding
            var isMp3 = false
            for (i in 0 until audioExtractor.trackCount) {
                val fmt = audioExtractor.getTrackFormat(i)
                val m = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/mpeg")) {
                    isMp3 = true
                    break
                }
            }

            if (isMp3) {
                // Use AudioMixer to transcode MP3 -> AAC and mux into MP4
                val audioMixer = AudioMixer(context)
                // Map progress from audioMixer (0.0..1.0) directly
                val success = audioMixer.mixAudio(
                    videoPath = videoPath,
                    audioPath = audioPath,
                    outputPath = outputPath,
                    volume = 1.0,
                    audioStartUs = null,
                    audioEndUs = null,
                    fadeInMs = 0,
                    fadeOutMs = 0,
                    onProgress = { p, _ -> onProgress(p) }
                )

                if (!success) {
                    Log.w(SLIDESHOW_TAG, "Audio mixing via AudioMixer failed; falling back to original video without audio")
                    try {
                        File(videoPath).copyTo(File(outputPath), overwrite = true)
                    } catch (e: Exception) {
                        Log.w(SLIDESHOW_TAG, "Failed to fallback copy video: ${e.message}")
                    }
                }

                return
            }

            // Create muxer
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            // Add video track
            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            
            // Add audio track if available
            var muxerAudioTrack = -1
            if (audioTrackIndex != -1) {
                audioExtractor.selectTrack(audioTrackIndex)
                val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
                muxerAudioTrack = muxer.addTrack(audioFormat)
            }
            
            muxer.start()
            var muxerStarted = true
            
            // Copy video samples
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                
                if (bufferInfo.size < 0) break
                
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                
                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
                
                // Report progress
                val progress = bufferInfo.presentationTimeUs / (durationMs * 1000.0)
                if (progress % 0.1 < 0.01) { // Every 10%
                    onProgress(progress.coerceIn(0.0, 1.0))
                }
            }
            
            // Copy audio samples (trim to video duration)
            if (muxerAudioTrack != -1) {
                audioExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val durationUs = durationMs * 1000
                
                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                    
                    if (bufferInfo.size < 0) break
                    
                    bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                    
                    // Stop if audio exceeds video duration
                    if (bufferInfo.presentationTimeUs > durationUs) break
                    
                    bufferInfo.flags = audioExtractor.sampleFlags
                    
                    muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                    audioExtractor.advance()
                }
            }
            
            onProgress(1.0)
            
        } finally {
            videoExtractor.release()
            audioExtractor.release()
            try {
                // Only stop if started (to avoid IllegalStateException)
                muxer?.stop()
            } catch (e: IllegalStateException) {
                Log.w(SLIDESHOW_TAG, "Muxer stop skipped: ${e.message}")
            }
            muxer?.release()
        }
    }
}
