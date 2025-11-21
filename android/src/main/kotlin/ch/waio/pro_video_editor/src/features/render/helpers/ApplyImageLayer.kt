package ch.waio.pro_video_editor.src.features.render.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory

import ch.waio.pro_video_editor.RENDER_TAG
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import ch.waio.pro_video_editor.src.features.render.utils.getRotatedVideoDimensions
import com.google.common.collect.ImmutableList
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

    var (videoWidth, videoHeight, videoRotation) = getRotatedVideoDimensions(
        inputFile,
        rotationDegrees
    )

    var isRotated90Deg = videoRotation == 90 || videoRotation == 270;
    if (cropWidth != null) {
        if (isRotated90Deg) {
            videoHeight = cropWidth;
        } else {
            videoWidth = cropWidth;
        }
    }
    if (cropHeight != null) {
        if (isRotated90Deg) {
            videoWidth = cropHeight;
        } else {
            videoHeight = cropHeight;
        }
    }

    if (scaleX != null) videoWidth = (videoWidth * scaleX).toInt()
    if (scaleY != null) videoHeight = (videoHeight * scaleY).toInt()

    Log.d(RENDER_TAG, "Applying Image-Layer: Size $videoWidth x $videoHeight")

    try {
        val overlayBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        if (overlayBitmap == null) {
            Log.e(RENDER_TAG, "Failed to decode overlay bitmap - BitmapFactory returned null")
            return
        }
        
        Log.d(RENDER_TAG, "Overlay bitmap decoded: ${overlayBitmap.width}x${overlayBitmap.height}")
        Log.d(RENDER_TAG, "Scaling to video dimensions: ${videoWidth}x${videoHeight}")
        
        val scaledOverlay = Bitmap.createScaledBitmap(overlayBitmap, videoWidth, videoHeight, true)
        if (scaledOverlay == null) {
            Log.e(RENDER_TAG, "Failed to scale overlay bitmap")
            return
        }
        
        val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(scaledOverlay)
        val overlayEffect = OverlayEffect(listOf(bitmapOverlay))
        
        videoEffects += overlayEffect
        Log.d(RENDER_TAG, "Image overlay effect added successfully")
    } catch (e: Exception) {
        Log.e(RENDER_TAG, "Error applying image overlay: ${e.message}", e)
    }
}

