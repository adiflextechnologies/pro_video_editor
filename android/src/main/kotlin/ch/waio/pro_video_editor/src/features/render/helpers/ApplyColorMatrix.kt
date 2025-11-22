package ch.waio.pro_video_editor.src.features.render.helpers

import ch.waio.pro_video_editor.RENDER_TAG
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SingleColorLut

@UnstableApi
fun applyColorMatrix(
    videoEffects: MutableList<Effect>,
    colorMatrixList: List<List<Double>>
) {
    if (colorMatrixList.isEmpty()) return

    val combinedMatrix = combineColorMatrices(colorMatrixList)
    if (combinedMatrix.size == 20) {
        // Should be the best lutSize for that case.
        val lutSize = 33
        val lutData = generateLutFromColorMatrix(combinedMatrix, lutSize)
        val singleColorLut = SingleColorLut.createFromCube(lutData)
        videoEffects += singleColorLut
    } else {
        Log.w(RENDER_TAG, "Color matrix must be 4x5 (20 elements), skipping LUT.")
    }
}

// Function to generate 3D LUT data from a 4x5 color matrix
private fun generateLutFromColorMatrix(matrix: List<Double>, size: Int): Array<Array<IntArray>> {
    val lut = Array(size) { Array(size) { IntArray(size) } }
    for (r in 0 until size) {
        for (g in 0 until size) {
            for (b in 0 until size) {
                val rf = r.toDouble() / (size - 1)
                val gf = g.toDouble() / (size - 1)
                val bf = b.toDouble() / (size - 1)

                val rr =
                    (matrix[0] * rf + matrix[1] * gf + matrix[2] * bf + matrix[3]) + (matrix[4] / 255.0)
                val gg =
                    (matrix[5] * rf + matrix[6] * gf + matrix[7] * bf + matrix[8]) + (matrix[9] / 255.0)
                val bb =
                    (matrix[10] * rf + matrix[11] * gf + matrix[12] * bf + matrix[13]) + (matrix[14] / 255.0)

                val rInt = (rr.coerceIn(0.0, 1.0) * 255).toInt()
                val gInt = (gg.coerceIn(0.0, 1.0) * 255).toInt()
                val bInt = (bb.coerceIn(0.0, 1.0) * 255).toInt()

                // Combine RGB into a single ARGB integer
                lut[r][g][b] = (0xFF shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
            }
        }
    }
    return lut
}

private fun multiplyColorMatrices(m1: List<Double>, m2: List<Double>): List<Double> {
    val result = MutableList(20) { 0.0 }
    for (i in 0..3) {
        for (j in 0..4) {
            result[i * 5 + j] =
                m1[i * 5 + 0] * m2[0 + j] +
                        m1[i * 5 + 1] * m2[5 + j] +
                        m1[i * 5 + 2] * m2[10 + j] +
                        m1[i * 5 + 3] * m2[15 + j] +
                        if (j == 4) m1[i * 5 + 4] else 0.0
        }
    }
    return result
}

private fun combineColorMatrices(matrices: List<List<Double>>): List<Double> {
    if (matrices.isEmpty()) return listOf()
    var result = matrices[0]
    for (i in 1 until matrices.size) {
        result = multiplyColorMatrices(matrices[i], result)
    }
    return result
}
