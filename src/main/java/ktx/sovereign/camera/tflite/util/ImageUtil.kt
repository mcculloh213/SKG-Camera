@file:JvmName("ImageUtil")
package ktx.sovereign.camera.tflite.util

import android.graphics.Matrix
import android.graphics.PointF
import android.util.Size
import androidx.core.math.MathUtils.clamp
import kotlin.math.abs
import kotlin.math.max

val kMaxChannelValue: Int = 262143

fun getYUVByteSize(width: Int, height: Int): Int {
    val ySize = width * height
    val uvSize = ((width + 1) / 2) * ((height + 1) / 2) * 2
    return ySize + uvSize
}

fun getTransformationMatrix(
    inWidth: Int, inHeight: Int,
    outWidth: Int, outHeight: Int,
    rotation: Int,
    maintainAspectRatio: Boolean = true
): Matrix {
    val matrix = Matrix()

    if (rotation != 0) {
        matrix.apply {
            // Translate center of image to origin
            postTranslate(-inWidth / 2.0f, -inHeight / 2.0f)
            // Rotate around origin
            postRotate(rotation.toFloat())
        }
    }

    val size = when ((abs(rotation) + 90) % 180 == 0) {
        true -> Size(inHeight, inWidth)
        false -> Size(inWidth, inHeight)
    }

    // apply scaling
    if (size.width != outWidth || size.height != outHeight) {
        val scale = PointF(outWidth / inWidth.toFloat(), outHeight / inHeight.toFloat())
        if (maintainAspectRatio) {
            val scaleFactor = max(scale.x, scale.y)
            matrix.postScale(scaleFactor, scaleFactor)
        } else {
            matrix.postScale(scale.x, scale.y)
        }
    }

    if (rotation != 0) {
        // Translate back from origin centered reference
        matrix.postTranslate(outWidth / 2.0f, outHeight / 2.0f)
    }

    return matrix
}

fun convertYUV420SPToARGB8888(input:ByteArray, width:Int, height:Int, output:IntArray) {
    val frameSize = width * height
    var j = 0
    var yp = 0
    while (j < height) {
        var uvp = frameSize + (j shr 1) * width
        var u = 0
        var v = 0
        var i = 0
        while (i < width) {
            val y = 0xff and input[yp].toInt()
            if ((i and 1) == 0) {
                v = 0xff and input[uvp++].toInt()
                u = 0xff and input[uvp++].toInt()
            }
            output[yp] = YUV2RGB(y, u, v)
            i++
            yp++
        }
        j++
    }
}

fun convertYUV420ToARGB8888(
    yData: ByteArray, uData: ByteArray, vData: ByteArray,
    width: Int, height: Int,
    yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
    output: IntArray
) {
    var yp = 0
    for (j in 0 until height) {
        val pY = yRowStride * j
        val pUV = uvRowStride * (j shr 1)

        for (i in 0 until width) {
            val uv_offset = pUV + (i shr 1) * uvPixelStride
            output[yp++] = YUV2RGB(
                0xff and yData[pY + i].toInt(),
                0xff and uData[uv_offset].toInt(),
                0xff and vData[uv_offset].toInt()
            )
        }
    }
}

fun YUV2RGB(y: Int, u: Int, v: Int): Int {
    val yP = if ((y - 16) < 0) 0 else (y - 16)
    val uP = u - 128
    val vP = v - 128

    val y1192 = 1192 * yP
    val r = (y1192 + 1634 * vP)
    val g = (y1192 - 833 * vP - 400 * uP)
    val b = (y1192 + 2066 * uP)

    val rP = clamp(r, 0, kMaxChannelValue)
    val gP = clamp(g, 0, kMaxChannelValue)
    val bP = clamp(b, 0, kMaxChannelValue)

    return (0xff000000).toInt() or ((rP shl 6) and 0xff0000) or ((gP shr 2) and 0xff00) or ((bP shr 10) and 0xff)
}