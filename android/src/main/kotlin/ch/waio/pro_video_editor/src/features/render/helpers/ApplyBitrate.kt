package ch.waio.pro_video_editor.src.features.render.helpers

import ch.waio.pro_video_editor.RENDER_TAG
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings


@UnstableApi
fun applyBitrate(
    encoderFactoryBuilder: DefaultEncoderFactory.Builder,
    mimeType: String?,
    bitrate: Int?
) {
    if (bitrate == null) return
    Log.d(RENDER_TAG, "Requested Bitrate: $bitrate")

    val codecInfo = MediaCodecList(MediaCodecList.ALL_CODECS)
        .codecInfos
        .firstOrNull { it.isEncoder && it.supportedTypes.contains(mimeType) }

    if (codecInfo == null) {
        Log.e(RENDER_TAG, "No encoder found for $mimeType")
        return
    }

    val capabilities = codecInfo.getCapabilitiesForType(mimeType)
    val bitrateRange = capabilities.videoCapabilities.bitrateRange
    val supportsCBR = capabilities.encoderCapabilities
        .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

    if (!bitrateRange.contains(bitrate)) {
        Log.e(RENDER_TAG, "Bitrate $bitrate not in supported range: $bitrateRange")
        return
    }

    val bitrateMode = if (supportsCBR) {
        Log.d(RENDER_TAG, "CBR supported, applying CBR mode")
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
    } else {
        Log.w(RENDER_TAG, "CBR not supported, falling back to VBR")
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
    }

    val builder = VideoEncoderSettings.Builder()
        .setBitrateMode(bitrateMode)
        .setBitrate(bitrate)
    
    // Note: Media3 Transformer automatically selects appropriate encoder profile
    // The combination of proper bitrate + MediaMuxer metadata ensures WhatsApp compatibility
    if (mimeType == "video/avc") {
        Log.d(RENDER_TAG, "H.264 encoding with bitrate $bitrate for WhatsApp compatibility")
    }

    encoderFactoryBuilder.setRequestedVideoEncoderSettings(builder.build())
}
