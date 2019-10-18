package ktx.sovereign.camera.ui.camera.data

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.util.Size

data class CameraInfo(
    val cameraId: String,
    val hardwareLevel: Int,
    val faceMode: Int,
    val yuvSize: Size,
    val jpegSize: Size,
    val rawSize: Size,
    val depthCloudSize: Size,
    val sensorOrientation: Int,
    val rawFormat: Int,
    val edgeModes: IntArray,
    val noiseModes: IntArray,
    val activeArea: Rect = Rect(),
    val cropRegion: Rect = Rect()
) {
    val yuvSmallSize = Size(320, 240)
    val previewSize: Size
        get() {
            val aspectRatio = yuvSize.width / yuvSize.height.toFloat()
            val aspect = if (aspectRatio > 1f) aspectRatio else (1f / aspectRatio)
            Log.i("Aspect Ratio", "Aspect: $aspectRatio clamp: $aspect")
            return when {
                aspect > 1.6 -> Size(1920, 1080)
                hardwareLevel >= CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 ->
                    Size(1440, 1080)
                else -> Size(1280, 960)
            }.also { Log.v("Aspect Ratio", "Size: $it") }
        }
    fun setActiveArea(area: Rect) = activeArea.set(area)
    fun setCropRegion(region: Rect) = activeArea.set(region)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CameraInfo

        if (cameraId != other.cameraId) return false
        if (yuvSize != other.yuvSize) return false
        if (jpegSize != other.jpegSize) return false
        if (rawSize != other.rawSize) return false
        if (sensorOrientation != other.sensorOrientation) return false
        if (rawFormat != other.rawFormat) return false
        if (activeArea != other.activeArea) return false
        if (cropRegion != other.cropRegion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = cameraId.hashCode()
        result = 31 * result + yuvSize.hashCode()
        result = 31 * result + jpegSize.hashCode()
        result = 31 * result + rawSize.hashCode()
        result = 31 * result + sensorOrientation
        result = 31 * result + rawFormat
        result = 31 * result + activeArea.hashCode()
        result = 31 * result + cropRegion.hashCode()
        return result
    }
}