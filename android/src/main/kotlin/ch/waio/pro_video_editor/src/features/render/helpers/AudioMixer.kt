package ch.waio.pro_video_editor.src.features.render.helpers

import RENDER_TAG
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

/**
 * Audio mixer using Android's native MediaCodec and MediaMuxer
 * Mixes custom audio with video without requiring FFmpeg
 */
class AudioMixer(private val context: Context) {

    /**
     * Mix custom audio with video
     */
    /**
     * Mix custom audio with video.
     *
     * Note: audioStartUs and audioEndUs are in microseconds (us) to match
     * the units used across the plugin (Dart side passes durations in microseconds).
     */
    fun mixAudio(
        videoPath: String,
        audioPath: String,
        outputPath: String,
        volume: Double = 1.0,
        audioStartUs: Long? = null,
        audioEndUs: Long? = null,
        fadeInMs: Long = 0,
        fadeOutMs: Long = 0
    ): Boolean {
        Log.d(RENDER_TAG, "=== Audio Mixing with MediaMuxer ===")
        Log.d(RENDER_TAG, "Video: $videoPath")
        Log.d(RENDER_TAG, "Audio: $audioPath")
        Log.d(RENDER_TAG, "Output: $outputPath")
        Log.d(RENDER_TAG, "Volume: $volume")
        
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        
        try {
            // Setup video extractor
            videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(videoPath)
            
            // Setup audio extractor
            audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(audioPath)
            
            // Create muxer
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            // Add video track
            val videoTrackIndex = findTrack(videoExtractor, "video/")
            if (videoTrackIndex < 0) {
                Log.e(RENDER_TAG, "No video track found")
                return false
            }
            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            
            // Ensure video format has required metadata for WhatsApp compatibility
            // WhatsApp requires proper SAR (Sample Aspect Ratio) and frame rate metadata
            if (!videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                Log.d(RENDER_TAG, "Adding default frame rate (30fps) for WhatsApp compatibility")
                videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            }
            
            // Ensure rotation is set to 0 if not present (some apps expect this)
            if (!videoFormat.containsKey("rotation-degrees")) {
                videoFormat.setInteger("rotation-degrees", 0)
            }
            
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            
            // Add audio track. If audio mime is MP3 (audio/mpeg), we must transcode to AAC
            val audioTrackIndex = findTrack(audioExtractor, "audio/")
            if (audioTrackIndex < 0) {
                Log.e(RENDER_TAG, "No audio track found")
                return false
            }
            audioExtractor.selectTrack(audioTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""

            if (audioMime.startsWith("audio/mpeg")) {
                // MP3 cannot be muxed into MP4 directly. Transcode MP3 -> AAC and write via muxer.
                Log.d(RENDER_TAG, "Audio is MP3 - will transcode to AAC for muxing")

                // We will add video track first, but delay starting muxer until encoder output format is available.
                // Copy video samples after muxer.start() below.

                // Prepare encoder to transcode MP3->AAC and collect encoded samples into muxer
                val success = transcodeMp3AndMux(audioExtractor, audioFormat, muxer, videoExtractor, muxerVideoTrack,
                    volume.toFloat(), audioStartUs, audioEndUs, fadeInMs, fadeOutMs)

                if (!success) {
                    Log.w(RENDER_TAG, "Audio mixing failed during transcode, returning video without custom audio")
                }
            } else {
                // For supported codecs (AAC/PCM), add audio track directly and proceed
                val muxerAudioTrack = muxer.addTrack(audioFormat)
                // Start muxing
                muxer.start()

                // Copy video track
                Log.d(RENDER_TAG, "Copying video track...")
                copyTrack(videoExtractor, muxer, muxerVideoTrack)

                // Copy audio track with volume adjustment
                Log.d(RENDER_TAG, "Copying audio track with volume: $volume")
                copyAudioTrack(
                    audioExtractor,
                    muxer,
                    muxerAudioTrack,
                    volume.toFloat(),
                    audioStartUs,
                    audioEndUs,
                    fadeInMs,
                    fadeOutMs
                )
            }
            
            Log.d(RENDER_TAG, "✅ Audio mixing successful")
            return true
            
        } catch (e: Exception) {
            Log.e(RENDER_TAG, "❌ Audio mixing failed: ${e.message}", e)
            return false
        } finally {
            try {
                videoExtractor?.release()
                audioExtractor?.release()
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                Log.e(RENDER_TAG, "Error releasing resources: ${e.message}")
            }
        }
    }
    
    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) {
                return i
            }
        }
        return -1
    }
    
    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, trackIndex: Int) {
        val bufferInfo = MediaCodec.BufferInfo()
        val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
        
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        
        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            
            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags
            
            muxer.writeSampleData(trackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }
    
    private fun copyAudioTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        volume: Float,
        audioStartUs: Long?,
        audioEndUs: Long?,
        fadeInMs: Long,
        fadeOutMs: Long
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
        
    // Seek to start position if specified. audioStartUs/audioEndUs are already in microseconds.
    val startUs = audioStartUs ?: 0L
    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

    val fadeInUs = fadeInMs * 1000
    val fadeOutUs = fadeOutMs * 1000
    val endUs = audioEndUs
        
        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            
            val presentationTimeUs = extractor.sampleTime
            
            // Check if we've reached the end position
            if (endUs != null && presentationTimeUs >= endUs) break
            
            // Apply volume and fade effects
            if (volume != 1.0f || fadeInUs > 0 || fadeOutUs > 0) {
                    applyVolumeAndFade(
                    buffer, 
                    sampleSize, 
                    volume, 
                    presentationTimeUs - startUs,
                    fadeInUs,
                    fadeOutUs,
                    (endUs ?: presentationTimeUs) - startUs
                )
            }
            
            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = presentationTimeUs
            bufferInfo.flags = extractor.sampleFlags
            
            muxer.writeSampleData(trackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }

    /**
     * Transcode MP3 (or other unsupported) audio to AAC using MediaCodec and write
     * encoded AAC frames into the provided muxer alongside the already-added video track.
     * This function will add the AAC track to the muxer and start the muxer.
     */
    private fun transcodeMp3AndMux(
        audioExtractor: MediaExtractor,
        inputFormat: MediaFormat,
        muxer: MediaMuxer,
        videoExtractor: MediaExtractor,
        videoTrackIndexInMuxer: Int,
        volume: Float,
        audioStartUs: Long?,
        audioEndUs: Long?,
        fadeInMs: Long,
        fadeOutMs: Long
    ): Boolean {
        try {
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // Configure encoder for AAC
            val aacFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channelCount)
            aacFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            aacFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            aacFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val bufferInfo = MediaCodec.BufferInfo()

            // We'll add the encoder output format as the audio track to muxer once available
            var muxerStarted = false
            var muxerAudioTrack = -1

            // Prepare to feed decoder with extractor data
            val timeoutUs = 10000L
            val startUs = audioStartUs ?: 0L
            val endUs = audioEndUs

            audioExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            var sawInputEOS = false
            var sawDecoderEOS = false
            var sawEncoderEOS = false

            // Loop until encoder signals EOS
            while (!sawEncoderEOS) {
                // Feed decoder input
                if (!sawInputEOS) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inputBuf = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = audioExtractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val presentationTimeUs = audioExtractor.sampleTime
                            if (endUs != null && presentationTimeUs >= endUs) {
                                // reached end
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                                audioExtractor.advance()
                            }
                        }
                    }
                }

                // Drain decoder output and feed to encoder
                var decoderOutputAvailable = true
                while (decoderOutputAvailable && !sawDecoderEOS) {
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        outIndex >= 0 -> {
                            val decodedBuf = decoder.getOutputBuffer(outIndex)!!
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                decoder.releaseOutputBuffer(outIndex, false)
                            } else {
                                // Send PCM to encoder
                                val inputEncIndex = encoder.dequeueInputBuffer(timeoutUs)
                                if (inputEncIndex >= 0) {
                                    val encInputBuf = encoder.getInputBuffer(inputEncIndex)!!
                                    encInputBuf.clear()
                                    // Apply simple volume/fade on PCM (16-bit) if needed
                                    val pcm = ByteArray(bufferInfo.size)
                                    decodedBuf.get(pcm)
                                    decodedBuf.position(0)

                                    // Apply volume and fade to PCM samples (16-bit little endian)
                                    val fadeInUs = fadeInMs * 1000
                                    val fadeOutUs = fadeOutMs * 1000
                                    val totalDurationUs = endUs ?: bufferInfo.presentationTimeUs
                                    applyVolumeAndFadeToPcm(pcm, volume, bufferInfo.presentationTimeUs - startUs, fadeInUs, fadeOutUs, totalDurationUs - startUs)

                                    encInputBuf.put(pcm)
                                    encoder.queueInputBuffer(inputEncIndex, 0, pcm.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                                }
                                decoder.releaseOutputBuffer(outIndex, false)
                            }

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawDecoderEOS = true
                                // signal encoder EOS after feeding remaining
                            }
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // ignore
                        }
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            decoderOutputAvailable = false
                        }
                    }
                }

                // Drain encoder output
                var encoderOutputAvailable = true
                while (encoderOutputAvailable) {
                    val encOutIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        encOutIndex >= 0 -> {
                            val encodedBuf = encoder.getOutputBuffer(encOutIndex)!!
                            if (bufferInfo.size > 0) {
                                // When we first get output format, add track and start muxer if not started
                                if (!muxerStarted) {
                                    val outFormat = encoder.outputFormat
                                    muxerAudioTrack = muxer.addTrack(outFormat)
                                    // At this point video track was already added earlier; now start muxer
                                    muxer.start()
                                    muxerStarted = true
                                    // Copy video samples before/after starting? We'll copy remaining video samples now.
                                    Log.d(RENDER_TAG, "Copying video track (post-muxer start)...")
                                    copyTrack(videoExtractor, muxer, videoTrackIndexInMuxer)
                                }

                                val outBuf = ByteBuffer.allocate(bufferInfo.size)
                                encodedBuf.position(bufferInfo.offset)
                                encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
                                outBuf.put(encodedBuf)
                                outBuf.position(0)

                                muxer.writeSampleData(muxerAudioTrack, outBuf, bufferInfo)
                            }

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawEncoderEOS = true
                            }
                            encoder.releaseOutputBuffer(encOutIndex, false)
                        }
                        encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // Will be handled when we receive actual data
                        }
                        encOutIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            encoderOutputAvailable = false
                        }
                    }
                }

                // If both decoder and encoder signalled EOS, break
                if (sawDecoderEOS && sawEncoderEOS) break
            }

            // Cleanup codecs
            try { decoder.stop(); decoder.release() } catch (_: Exception) {}
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}

            Log.d(RENDER_TAG, "✅ Transcode+Mux complete")
            return true
        } catch (e: Exception) {
            Log.e(RENDER_TAG, "Transcode failed: ${e.message}", e)
            return false
        }
    }

    private fun applyVolumeAndFadeToPcm(
        pcm: ByteArray,
        volume: Float,
        currentTimeUs: Long,
        fadeInUs: Long,
        fadeOutUs: Long,
        totalDurationUs: Long
    ) {
        if (pcm.isEmpty()) return
        var fadeMultiplier = 1.0f

        // Fade in
        if (fadeInUs > 0 && currentTimeUs < fadeInUs) {
            fadeMultiplier = currentTimeUs.toFloat() / fadeInUs.toFloat()
        }

        // Fade out
        if (fadeOutUs > 0 && currentTimeUs > (totalDurationUs - fadeOutUs)) {
            val fadeOutProgress = (totalDurationUs - currentTimeUs).toFloat() / fadeOutUs.toFloat()
            fadeMultiplier = minOf(fadeMultiplier, fadeOutProgress)
        }

        val finalVolume = volume * fadeMultiplier

        // Modify 16-bit little-endian samples
        var i = 0
        while (i + 1 < pcm.size) {
            val low = pcm[i].toInt() and 0xFF
            val high = pcm[i + 1].toInt()
            val sample = (high shl 8) or low
            val signed = if (sample >= 0x8000) sample - 0x10000 else sample
            val adjusted = (signed * finalVolume).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i] = (adjusted and 0xFF).toByte()
            pcm[i + 1] = ((adjusted shr 8) and 0xFF).toByte()
            i += 2
        }
    }
    
    private fun applyVolumeAndFade(
        buffer: ByteBuffer,
        size: Int,
        volume: Float,
        currentTimeUs: Long,
        fadeInUs: Long,
        fadeOutUs: Long,
        totalDurationUs: Long
    ) {
        buffer.position(0)
        
        // Calculate fade multiplier
        var fadeMultiplier = 1.0f
        
        // Fade in
        if (fadeInUs > 0 && currentTimeUs < fadeInUs) {
            fadeMultiplier = currentTimeUs.toFloat() / fadeInUs.toFloat()
        }
        
        // Fade out
        if (fadeOutUs > 0 && currentTimeUs > (totalDurationUs - fadeOutUs)) {
            val fadeOutProgress = (totalDurationUs - currentTimeUs).toFloat() / fadeOutUs.toFloat()
            fadeMultiplier = minOf(fadeMultiplier, fadeOutProgress)
        }
        
        val finalVolume = volume * fadeMultiplier
        
        // Apply volume to 16-bit PCM samples
        for (i in 0 until size step 2) {
            if (buffer.remaining() < 2) break
            
            val sample = buffer.getShort(i)
            val adjusted = (sample * finalVolume).toInt()
            val clamped = adjusted.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer.putShort(i, clamped.toShort())
        }
    }
}
