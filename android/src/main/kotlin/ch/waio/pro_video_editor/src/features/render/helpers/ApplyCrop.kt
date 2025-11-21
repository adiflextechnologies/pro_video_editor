package ch.waio.pro_video_editor.src.features.render.helpers

import ch.waio.pro_video_editor.RENDER_TAG
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import ch.waio.pro_video_editor.src.features.render.utils.getRotatedVideoDimensions
import java.io.File

@UnstableApi
fun applyCrop(
    videoEffects: MutableList<Effect>,
    inputFile: File,
    rotationDegrees: Float,
    flipX: Boolean,
    flipY: Boolean,
    cropWidth: Int?,
    cropHeight: Int?,
    cropX: Int?,
    cropY: Int?,
) {
    if (cropX == null && cropY == null && cropWidth == null && cropHeight == null) return;

    try {
        val (originalVideoWidth, originalVideoHeight, videoRotation) = getRotatedVideoDimensions(
            inputFile,
            rotationDegrees
        )

        val videoWidth = originalVideoWidth.toFloat();
        val videoHeight = originalVideoHeight.toFloat();

        if (videoWidth > 0 && videoHeight > 0) {
            // Default to full frame if values are not provided
            var cropX = cropX ?: 0
            var cropY = cropY ?: 0
            var cropWidth = cropWidth ?: (videoWidth - cropX).toInt()
            var cropHeight = cropHeight ?: (videoHeight - cropY).toInt()

            //  Swap crop dimensions if rotated 90° or 270°
            var rotation = rotationDegrees.toInt() % 360;
            when (rotation) {
                90, 270 -> {
                    val tempWidth = cropWidth
                    cropWidth = cropHeight
                    cropHeight = tempWidth

                    val tempX = cropX
                    cropX = cropY
                    cropY = tempX
                }
            }
            if (rotation == 90 || rotation == 180) {
                cropY = (videoHeight - cropHeight - cropY).toInt();
            }
            if (rotation == 270 || rotation == 180) {
                cropX = (videoWidth - cropWidth - cropX).toInt();
            }

            if (flipX) {
                cropX = (videoWidth - cropWidth - cropX).toInt()
            }
            if (flipY) {
                cropY = (videoHeight - cropHeight - cropY).toInt()
            }

            // Convert to NDC
            val leftNDC = (cropX / videoWidth) * 2f - 1f
            val rightNDC = ((cropX + cropWidth) / videoWidth) * 2f - 1f
            val topNDC = 1f - (cropY / videoHeight) * 2f
            val bottomNDC = 1f - ((cropY + cropHeight) / videoHeight) * 2f

            Log.d(
                RENDER_TAG,
                "Applying crop: left=$leftNDC, right=$rightNDC, top=$topNDC, bottom=$bottomNDC"
            )
            videoEffects += Crop(leftNDC, rightNDC, bottomNDC, topNDC)
        } else {
            Log.w(RENDER_TAG, "Skipping crop: invalid video dimensions.")
        }
    } catch (e: Exception) {
        Log.w(RENDER_TAG, "Failed to apply cropping: ${e.message}")
    }
}
