@file:JvmName("CameraManagerUtils")
package ktx.sovereign.camera.extension

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.camera.core.CameraX

fun CameraManager.getCameraCharacteristicFor(lens: CameraX.LensFacing): LensInfo {
    val id = cameraIdList.firstOrNull {
        getCameraCharacteristics(it)
            .get(CameraCharacteristics.LENS_FACING) == getLensFacingCharacteristic(lens)
    } ?: Build.UNKNOWN
    return LensInfo(lens, id, getCameraCharacteristics(id))
}
fun CameraManager.getCameraCharacteristicFor(lens: Int): LensInfo {
    val id = cameraIdList.firstOrNull {
        getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lens
    } ?: Build.UNKNOWN
    return LensInfo(getLensFacingCharacteristic(lens), id, getCameraCharacteristics(id))
}
private fun getLensFacingCharacteristic(which: CameraX.LensFacing): Int = when (which) {
    CameraX.LensFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
    CameraX.LensFacing.BACK -> CameraCharacteristics.LENS_FACING_BACK
}
private fun getLensFacingCharacteristic(which: Int): CameraX.LensFacing = when (which) {
    CameraCharacteristics.LENS_FACING_FRONT -> CameraX.LensFacing.FRONT
    CameraCharacteristics.LENS_FACING_BACK -> CameraX.LensFacing.BACK
    else -> throw IllegalArgumentException("$which is not LENS_FACING_FRONT or LENS_FACING_BACK")
}
fun CameraManager.getActiveArraySizeFor(lens: CameraX.LensFacing): Rect {
    val info = getCameraCharacteristicFor(lens)
    return info.characteristics.get<Rect>(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect()
}

data class LensInfo(
    val which: CameraX.LensFacing,
    val id: String,
    val characteristics: CameraCharacteristics
)