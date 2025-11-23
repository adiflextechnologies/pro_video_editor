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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
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
        
        try {
            // Configure video encoder
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000) // 8 Mbps
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            
            encoder = MediaCodec.createEncoderByType(mime)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            
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
            var muxerStarted = false
            var inputEOS = false
            
            // Process each frame
            while (currentFrameIndex < totalFrames || !inputEOS) {
                // Feed input frames
                if (!inputEOS && currentFrameIndex < totalFrames) {
                    val inputBufferIndex = encoder.dequeueInputBuffer(10_000)
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
                        val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
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
                            val yuvData = bitmapToYUV420(finalBitmap, width, height, 1.0f)
                            // Recycle the temporary final bitmap if we created a composite
                            if (composited != null) composited.recycle() else finalBitmap.recycle()
                            inputBuffer.put(yuvData)
                            
                            val presentationTimeUs = currentFrameIndex * frameDurationUs
                            encoder.queueInputBuffer(
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
                
                // Signal end of input after all frames
                if (currentFrameIndex >= totalFrames && !inputEOS) {
                    val inputBufferIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputBufferIndex >= 0) {
                        encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEOS = true
                        Log.d(SLIDESHOW_TAG, "  EOS signaled to encoder")
                    }
                }
                
                // Get output
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = encoder.outputFormat
                        videoTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                        Log.d(SLIDESHOW_TAG, "  Muxer started")
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        }
                        
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                        
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
            encoder?.stop()
            encoder?.release()
            muxer?.stop()
            muxer?.release()
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
        val sourceBitmap = BitmapFactory.decodeFile(imagePath, loadOptions)
        
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
            muxer?.stop()
            muxer?.release()
        }
    }
}
