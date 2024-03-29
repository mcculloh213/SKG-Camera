@file:JvmName("CameraCharacteristicsUtils")
package ktx.sovereign.camera.extension

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.util.Rational
import android.util.Size
import android.util.SizeF
import android.view.Surface
import ktx.sovereign.camera.util.RectangularAreaComparator
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.atan

private const val MAX_PREVIEW_WIDTH = 1920
private const val MAX_PREVIEW_HEIGHT = 1080

fun CameraCharacteristics.isFlashSupported(): Boolean =
    get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
fun CameraCharacteristics.getLargestOutputSize(format: Int = ImageFormat.JPEG): Size {
    val map = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(0, 0)
    return Collections.max(listOf(*map.getOutputSizes(format)), RectangularAreaComparator())
}
fun CameraCharacteristics.getPreviewSize(aspectRatio: Rational): Size {
    val sizes = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(SurfaceTexture::class.java)
        ?.asList() ?: return Size(0, 0)
    return selectOutputSize(sizes, aspectRatio)
}
fun CameraCharacteristics.getOptimalSize(
    surfaceWidth: Int,
    surfaceHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    aspectRatio: Size
): Size {
    val width = if (maxWidth > MAX_PREVIEW_WIDTH) { MAX_PREVIEW_WIDTH } else { maxWidth }
    val height = if (maxHeight > MAX_PREVIEW_HEIGHT) { MAX_PREVIEW_HEIGHT } else { maxHeight }

    val map = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(0, 0)
    val choices = map.getOutputSizes(SurfaceTexture::class.java)

    val bigEnough = ArrayList<Size>()
    val notBigEnough = ArrayList<Size>()
    val w = aspectRatio.width
    val h = aspectRatio.height
    choices.forEach {
        if (it.width <= width && it.height <= height && it.height == it.width * h / w) {
            if (it.width >= surfaceWidth && it.height >= surfaceHeight) {
                bigEnough.add(it)
            } else {
                notBigEnough.add(it)
            }
        }
    }
    return when {
        bigEnough.size > 0 -> bigEnough.asSequence().sortedWith(RectangularAreaComparator()).first()
        notBigEnough.size > 0 -> notBigEnough.asSequence().sortedWith(RectangularAreaComparator()).last()
        else -> choices[0]
    }
}
fun CameraCharacteristics.areDimensionsSwapped(displayRotation: Int): Boolean {
    val orientation = get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    return when (displayRotation) {
        Surface.ROTATION_0, Surface.ROTATION_180 -> orientation == 90 || orientation == 270
        Surface.ROTATION_90, Surface.ROTATION_270 -> orientation == 0 || orientation == 180
        else -> false
    }
}
fun CameraCharacteristics.isCapabilitySupported(capability: Int): Boolean =
    get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(capability) ?: false
fun CameraCharacteristics.getFieldOfView(): FloatArray {
    val availableFocalLengths = get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
    var focalLength = 4.5f
    if (availableFocalLengths.isNotEmpty()) {
        focalLength = availableFocalLengths[0]
    }

    val physicalSize = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: SizeF(6.32f, 4.69f)
    val pixelArraySize = get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: Size(1, 1)
    val activeArraySize = get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect()
    val activeSize = SizeF(
        activeArraySize.width() / pixelArraySize.width.toFloat(),
        activeArraySize.height() / pixelArraySize.height.toFloat()
        )
    return floatArrayOf(
        Math.toDegrees(
            2.0 * atan(physicalSize.width * activeSize.width / 2 / focalLength)
        ).toFloat(),
        Math.toDegrees(
            2.0 * atan(physicalSize.height * activeSize.height / 2 / focalLength)
        ).toFloat()
    )
}
private fun selectOutputSize(sizes: List<Size>, aspectRatio: Rational): Size = if (aspectRatio.toFloat() > 1.0f) {
    sizes.firstOrNull { it.height == (it.width * 9 / 16) && it.height < 1080 } ?: sizes[0]
} else {
    val potentials = sizes.filter { it.height.toFloat() / it.width.toFloat() == aspectRatio.toFloat() }
    if (potentials.isNotEmpty()) {
        potentials.firstOrNull { it.height == 1080 || it.height == 720 } ?: potentials[0]
    } else {
        sizes[0]
    }
}

