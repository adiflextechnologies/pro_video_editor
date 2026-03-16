package ch.waio.pro_video_editor.src.features.render.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory

import ch.waio.pro_video_editor.RENDER_TAG
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import java.io.File


@UnstableApi
fun applyImageLayer(
    videoEffects: MutableList<Effect>,
    inputFile: File,
    imageBytes: ByteArray?,
    rotationDegrees: Float,
    cropWidth: Int?,
    cropHeight: Int?,
    scaleX: Float?,
    scaleY: Float?,
) {
    if (imageBytes == null || imageBytes.isEmpty()) {
        Log.d(RENDER_TAG, "No image overlay to apply (imageBytes is null or empty)")
        return
    }
    
    Log.d(RENDER_TAG, "Applying image overlay - imageBytes size: ${imageBytes.size}")

    try {
        val overlayBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        if (overlayBitmap == null) {
            Log.e(RENDER_TAG, "Failed to decode overlay bitmap - BitmapFactory returned null")
            return
        }

        Log.d(RENDER_TAG, "Overlay bitmap decoded: ${overlayBitmap.width}x${overlayBitmap.height}")

        // The overlay was pre-sized by Flutter to videoMetadata.resolution, which already
        // accounts for the video's rotation metadata (it is the *display* resolution).
        // Using getRotatedVideoDimensions() here would double-count the rotation for videos
        // stored with rotation metadata (e.g. 9:16 portrait stored as 1920×1080 + rotation=90),
        // causing the combined rotation to be 0° instead of 90° and returning landscape
        // dimensions — which then stretches the portrait overlay to landscape, producing wrong
        // paint-stroke positions on 9:16 videos.
        //
        // We therefore use the overlay bitmap's own dimensions as the target, which matches
        // the display width×height of the video exactly.
        var videoWidth = overlayBitmap.width
        var videoHeight = overlayBitmap.height

        // Apply any user-requested crop or scale to the overlay dimensions.
        if (cropWidth != null) videoWidth = cropWidth
        if (cropHeight != null) videoHeight = cropHeight
        if (scaleX != null) videoWidth = (videoWidth * scaleX).toInt()
        if (scaleY != null) videoHeight = (videoHeight * scaleY).toInt()

        val finalBitmap = if (videoWidth != overlayBitmap.width || videoHeight != overlayBitmap.height) {
            Log.d(RENDER_TAG, "Scaling overlay from ${overlayBitmap.width}x${overlayBitmap.height} to ${videoWidth}x${videoHeight}")
            val scaled = Bitmap.createScaledBitmap(overlayBitmap, videoWidth, videoHeight, true)
            if (scaled == null) {
                Log.e(RENDER_TAG, "Failed to scale overlay bitmap")
                return
            }
            scaled
        } else {
            overlayBitmap
        }

        val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(finalBitmap)
        val overlayEffect = OverlayEffect(listOf(bitmapOverlay))

        videoEffects += overlayEffect
        Log.d(RENDER_TAG, "Image overlay effect added: ${finalBitmap.width}x${finalBitmap.height}")
    } catch (e: Exception) {
        Log.e(RENDER_TAG, "Error applying image overlay: ${e.message}", e)
    }
}

