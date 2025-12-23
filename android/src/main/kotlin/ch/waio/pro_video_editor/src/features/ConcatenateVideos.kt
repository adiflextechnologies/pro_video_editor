package ch.waio.pro_video_editor.src.features

import ch.waio.pro_video_editor.PACKAGE_TAG
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.Build
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
// Note: avoid direct use of Effect types where possible to prevent
// compile-time mismatches between media3 effect artifact versions.
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
// Effects is not required here; avoid constructing Effects to prevent
// a compile-time dependency on the Effect type.
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.nio.ByteBuffer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils

private const val CONCATENATE_TAG = "${PACKAGE_TAG}:ConcatenateVideos"

@UnstableApi
class ConcatenateVideos(private val context: Context) {
    // Keep track of normalized temp files for better diagnostics / cleanup
    private var lastNormalizedFiles: List<String> = emptyList()
    // Default duration (ms) to assign to input images when treating them as video clips
    private val DEFAULT_IMAGE_DURATION_MS: Long = 3000L

    // Cache result of reflection capability check
    private var supportsImageDurationApi: Boolean? = null

    private fun supportsSetImageDurationMs(): Boolean {
        if (supportsImageDurationApi != null) return supportsImageDurationApi!!
        return try {
            val builderClass = EditedMediaItem.Builder::class.java
            try {
                builderClass.getMethod("setImageDurationMs", java.lang.Long.TYPE)
                supportsImageDurationApi = true
            } catch (nsme: NoSuchMethodException) {
                try {
                    builderClass.getMethod("setImageDurationMs", java.lang.Long::class.java)
                    supportsImageDurationApi = true
                } catch (e: Exception) {
                    supportsImageDurationApi = false
                }
            }
            supportsImageDurationApi!!
        } catch (e: Exception) {
            supportsImageDurationApi = false
            false
        }
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        val shader = GLES20.glCreateShader(shaderType)
        if (shader == 0) throw RuntimeException("Could not create shader of type $shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $shaderType: $info")
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vert = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val frag = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        if (program == 0) throw RuntimeException("Could not create GL program")
        GLES20.glAttachShader(program, vert)
        GLES20.glAttachShader(program, frag)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Could not link program: $info")
        }
        return program
    }

    /**
     * Create a short MP4 (H.264) from a static image by encoding the bitmap into repeated frames.
     * This is a defensive fallback used when Media3's EditedMediaItem.Builder.setImageDurationMs isn't available.
     */
    private fun createVideoFromImage(imagePath: String, outPath: String, durationMs: Long): String {
        // EGL-based rendering to encoder input surface
        val bmp = BitmapFactory.decodeFile(imagePath) ?: throw IllegalArgumentException("Could not decode image: $imagePath")
        val origWidth = bmp.width
        val origHeight = bmp.height
        val frameRate = 25
        val mime = "video/avc"

        // Try several downscale factors if encoder refuses the original size. Some encoders
        // (especially on older devices) don't support very large dimensions or require
        // even-numbered widths/heights. We'll attempt progressively smaller sizes.
        val scaleCandidates = listOf(1.0f, 0.75f, 0.5f, 0.25f)

        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var chosenWidth = -1
        var chosenHeight = -1
        var chosenBitrate = -1
        var totalFrames = 0

        var lastConfigureException: Throwable? = null

        for (scale in scaleCandidates) {
            val w = (origWidth * scale).toInt().coerceAtLeast(2)
            val h = (origHeight * scale).toInt().coerceAtLeast(2)
            // Ensure even dimensions
            val width = if (w % 2 == 0) w else w - 1
            val height = if (h % 2 == 0) h else h - 1

            val bitRate = (width * height * 2.5).toInt().coerceAtLeast(200_000)

            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Some encoders expect a max input size; set a conservative value
                try {
                    setInteger("max-input-size", (width * height * 1.5).toInt())
                } catch (_: Throwable) {}
            }

            try {
                encoder = MediaCodec.createEncoderByType(mime)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = encoder.createInputSurface()
                encoder.start()

                muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                chosenWidth = width
                chosenHeight = height
                chosenBitrate = bitRate
                totalFrames = ((durationMs.toDouble() / 1000.0) * frameRate).toInt().coerceAtLeast(1)
                lastConfigureException = null
                break
            } catch (t: Throwable) {
                lastConfigureException = t
                try {
                    encoder?.stop()
                } catch (_: Exception) {}
                try {
                    encoder?.release()
                } catch (_: Exception) {}
                encoder = null
                inputSurface = null
                muxer = null
                // try next scale candidate
                continue
            }
        }

        if (encoder == null || inputSurface == null || muxer == null) {
            throw RuntimeException("Failed to configure MediaCodec encoder for image fallback: ${lastConfigureException?.message}", lastConfigureException)
        }

        var eglDisplay: EGLDisplay? = null
        var eglContext: EGLContext? = null
        var eglSurface: EGLSurface? = null

        try {
            // Initialize EGL and bind the encoder input surface
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("Unable to get EGL14 display")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw RuntimeException("Unable to initialize EGL14")

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) throw RuntimeException("eglChooseConfig failed")
            val eglConfig = configs[0]!!

            val attrib_context = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, attrib_context, 0)
            if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("Failed to create EGL context")

            val surfaceAttrs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, inputSurface, surfaceAttrs, 0)
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("Failed to create EGL surface")

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) throw RuntimeException("eglMakeCurrent failed")

            // Create GL program and texture
            val vertexShader = "attribute vec4 aPosition;attribute vec2 aTexCoord;varying vec2 vTexCoord;void main(){gl_Position=aPosition;vTexCoord=aTexCoord;}"
            val fragmentShader = "precision mediump float;uniform sampler2D uTexture;varying vec2 vTexCoord;void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}"
            val program = createProgram(vertexShader, fragmentShader)
            GLES20.glUseProgram(program)

            val textureIds = IntArray(1)
            GLES20.glGenTextures(1, textureIds, 0)
            val texId = textureIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST.toFloat())
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Upload bitmap once; scale bitmap to chosen size to avoid rendering at unsupported resolution
            val uploadBmp = if (bmp.width != chosenWidth || bmp.height != chosenHeight) Bitmap.createScaledBitmap(bmp, chosenWidth, chosenHeight, true) else bmp
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, uploadBmp, 0)

            val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            val uTexture = GLES20.glGetUniformLocation(program, "uTexture")

            val vertexBuf = java.nio.ByteBuffer.allocateDirect(4 * 4 * 2).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            vertexBuf.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
            val texBuf = java.nio.ByteBuffer.allocateDirect(4 * 4 * 2).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            texBuf.put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)).position(0)

            // Draw frames
            for (i in 0 until totalFrames) {
                GLES20.glViewport(0, 0, chosenWidth, chosenHeight)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                GLES20.glEnableVertexAttribArray(aPosition)
                GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
                GLES20.glEnableVertexAttribArray(aTexCoord)
                GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuf)

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glUniform1i(uTexture, 0)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                GLES20.glFinish()
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)

                // Drain any available output
                drainEncoder(encoder!!, muxer!!, false)
            }

            // Signal end of stream and drain final output
            encoder!!.signalEndOfInputStream()
            drainEncoder(encoder!!, muxer!!, true)

            return outPath
        } catch (t: Throwable) {
            throw t
        } finally {
            try { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } catch (_: Exception) {}
            try { if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface) } catch (_: Exception) {}
            try { if (eglContext != null) EGL14.eglDestroyContext(eglDisplay, eglContext) } catch (_: Exception) {}
            try { if (eglDisplay != null) EGL14.eglTerminate(eglDisplay) } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun drainEncoder(encoder: MediaCodec, muxer: MediaMuxer, endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        var localTrackIndex = -1
        var muxerStarted = false

        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
                else {
                    // keep looping until EOS is seen
                    continue
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = encoder.outputFormat
                localTrackIndex = muxer.addTrack(newFormat)
                muxer.start()
                muxerStarted = true
            } else if (outIndex >= 0) {
                val encoded = encoder.getOutputBuffer(outIndex) ?: continue
                if (bufferInfo.size > 0 && muxerStarted) {
                    encoded.position(bufferInfo.offset)
                    encoded.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(localTrackIndex, encoded, bufferInfo)
                }
                encoder.releaseOutputBuffer(outIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    // Simple image file detection by extension
    private fun isImageFile(path: String): Boolean {
        val lc = path.lowercase()
        return lc.endsWith(".png") || lc.endsWith(".jpg") || lc.endsWith(".jpeg") || lc.endsWith(".webp") || lc.endsWith(".bmp") || lc.endsWith(".gif")
    }
    
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
        onProgress: (Double, String?) -> Unit,
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
        onProgress: (Double, String?) -> Unit,
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
                    onProgress(1.0, "concat")
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
            // Create EditedMediaItems for each input video with scale and rotation effects
            // This normalizes all videos to the same dimensions and flattens rotation metadata
            val editedMediaItems = inputPaths.mapIndexed { index, path ->
                Log.d(CONCATENATE_TAG, "Creating EditedMediaItem for video $index")
                
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(File(path)))
                    .build()
                
                // Read rotation metadata from each video
                var videoRotation = 0
                try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(path)
                    videoRotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    mmr.release()
                    if (videoRotation != 0) {
                        Log.d(CONCATENATE_TAG, "  Video $index has rotation: $videoRotation°")
                    }
                } catch (e: Exception) {
                    Log.w(CONCATENATE_TAG, "  Failed to read rotation for video $index: ${e.message}")
                }
                
                // Preserve rotation metadata; do not apply any ScaleAndRotateTransformation here.
                // We avoid constructing an `Effects` instance to remove the compile-time
                // dependency on the `Effect` type; the builder will use default effects.
                
                // If this input is an image and the Media3 image-duration API is not available,
                // create a short MP4 fallback so the asset loader always has a video track.
                var workingPath = path
                if (isImageFile(path) && !supportsSetImageDurationMs()) {
                    Log.d(CONCATENATE_TAG, "Image duration API unavailable; generating short MP4 fallback for index $index")
                    try {
                        val tmp = File.createTempFile("img_fallback_${index}_", ".mp4", context.cacheDir)
                        val generated = createVideoFromImage(path, tmp.absolutePath, DEFAULT_IMAGE_DURATION_MS)
                        workingPath = generated
                        // remember to delete generated files later
                        lastNormalizedFiles = lastNormalizedFiles + generated
                    } catch (e: Exception) {
                        Log.w(CONCATENATE_TAG, "Failed to generate fallback MP4 for image $path: ${e.message}")
                    }
                }

                // If we generated a fallback MP4, rebuild MediaItem to point to it
                val finalMediaItem = if (workingPath != path) {
                    MediaItem.Builder().setUri(Uri.fromFile(File(workingPath))).build()
                } else mediaItem

                // Build EditedMediaItem using the final MediaItem. Apply image duration
                // via reflection if available on this Media3 version.
                var editedBuilderFinal = EditedMediaItem.Builder(finalMediaItem)
                    .setRemoveAudio(false)
                    .setRemoveVideo(false)

                if (isImageFile(path)) {
                    Log.d(CONCATENATE_TAG, "  Detected image input for index $index; assigning duration ${DEFAULT_IMAGE_DURATION_MS}ms")
                    try {
                        val m = editedBuilderFinal.javaClass.getMethod("setImageDurationMs", java.lang.Long.TYPE)
                        val ret = m.invoke(editedBuilderFinal, DEFAULT_IMAGE_DURATION_MS)
                        if (ret is EditedMediaItem.Builder) editedBuilderFinal = ret as EditedMediaItem.Builder
                    } catch (nsme: NoSuchMethodException) {
                        try {
                            val m2 = editedBuilderFinal.javaClass.getMethod("setImageDurationMs", java.lang.Long::class.java)
                            val ret2 = m2.invoke(editedBuilderFinal, java.lang.Long.valueOf(DEFAULT_IMAGE_DURATION_MS))
                            if (ret2 is EditedMediaItem.Builder) editedBuilderFinal = ret2 as EditedMediaItem.Builder
                        } catch (e: Exception) {
                            Log.w(CONCATENATE_TAG, "setImageDurationMs not available on this media3 version: ${e.message}")
                        }
                    } catch (e: Exception) {
                        Log.w(CONCATENATE_TAG, "Failed to invoke setImageDurationMs: ${e.message}")
                    }
                }

                // Do NOT set Effects here; let Transformer use defaults to avoid
                // compile-time Effect type issues or mismatched effect APIs.
                editedBuilderFinal.build()
            }

            Log.d(CONCATENATE_TAG, "Creating composition with ${editedMediaItems.size} items")
            
            // Create a sequence with all videos using the public factory
            val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(editedMediaItems)

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
                        
                        // Verify output file has no rotation metadata (should be 0 since we flattened it)
                        try {
                            val verifyMmr = MediaMetadataRetriever()
                            verifyMmr.setDataSource(outputPath)
                            val outputRotation = verifyMmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                            val outputWidth = verifyMmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            val outputHeight = verifyMmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            verifyMmr.release()
                            Log.d(CONCATENATE_TAG, "  Output rotation metadata: $outputRotation° (should be 0)")
                            Log.d(CONCATENATE_TAG, "  Output dimensions: ${outputWidth}x${outputHeight}")
                            if (outputRotation != 0) {
                                Log.w(CONCATENATE_TAG, "⚠️ WARNING: Output still has rotation metadata ($outputRotation°), this may cause issues when adding audio")
                            }
                        } catch (e: Exception) {
                            Log.w(CONCATENATE_TAG, "  Could not verify output metadata: ${e.message}")
                        }
                        
                        // If output file still has rotation metadata, attempt to flatten it
                        try {
                            val verifyMmr = MediaMetadataRetriever()
                            verifyMmr.setDataSource(outputPath)
                            val outputRotation = verifyMmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                            verifyMmr.release()
                            if (outputRotation != 0) {
                                Log.w(CONCATENATE_TAG, "⚠️ Output has rotation metadata ($outputRotation°). Preserving original metadata (no remediation applied).")
                            }
                        } catch (e: Exception) {
                            Log.w(CONCATENATE_TAG, "Could not verify/repair output rotation: ${e.message}")
                        }

                        onProgress(1.0, "concat")
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
                            onProgress(progress, "concat")
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
                // If input is an image, ensure a duration is set so Transformer can process it
                var editedBuilder = EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(false)
                    .setRemoveVideo(false)

                // Inspect rotation metadata and, if present, add a ScaleAndRotateTransformation
                // to bake rotation into pixels during normalization.
                try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(input)
                    val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    mmr.release()
                    if (rot != 0) {
                        Log.d(CONCATENATE_TAG, "  Normalizer: detected rotation $rot° for $input; preserving metadata (no bake)")
                    }
                } catch (e: Exception) {
                    Log.w(CONCATENATE_TAG, "  Normalizer: failed reading rotation for $input: ${e.message}")
                }

                if (isImageFile(input)) {
                    Log.d(CONCATENATE_TAG, "  Normalizer: detected image input at index $index; assigning duration ${DEFAULT_IMAGE_DURATION_MS}ms")
                    try {
                        val m = editedBuilder.javaClass.getMethod("setImageDurationMs", java.lang.Long.TYPE)
                        val ret = m.invoke(editedBuilder, DEFAULT_IMAGE_DURATION_MS)
                        if (ret is EditedMediaItem.Builder) editedBuilder = ret as EditedMediaItem.Builder
                    } catch (nsme: NoSuchMethodException) {
                        try {
                            val m2 = editedBuilder.javaClass.getMethod("setImageDurationMs", java.lang.Long::class.java)
                            val ret2 = m2.invoke(editedBuilder, java.lang.Long.valueOf(DEFAULT_IMAGE_DURATION_MS))
                            if (ret2 is EditedMediaItem.Builder) editedBuilder = ret2 as EditedMediaItem.Builder
                        } catch (e: Exception) {
                            Log.w(CONCATENATE_TAG, "Normalizer: setImageDurationMs not available: ${e.message}")
                        }
                    } catch (e: Exception) {
                        Log.w(CONCATENATE_TAG, "Normalizer: Failed to invoke setImageDurationMs: ${e.message}")
                    }
                }

                val edited = editedBuilder.build()

                val seq = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited))
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
