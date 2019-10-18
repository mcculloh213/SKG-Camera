package ktx.sovereign.camera.ui.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.core.content.getSystemService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ktx.sovereign.camera.extension.getCameraCharacteristicFor
import ktx.sovereign.camera.ui.camera.data.CameraInfo

internal val NO_SIZE = Size(0, 0)
private const val NO_FORMAT = -1
class CameraViewModel private constructor(private val manager: CameraManager) : ViewModel() {
    val currentLens: Int
        get() = _currentLens
    private var _currentLens: Int = CameraCharacteristics.LENS_FACING_BACK
        set(value) {
            field = value
            load()
        }
    val cameraInfo: LiveData<CameraInfo>
        get() = _cameraInfo
    private val _cameraInfo = MutableLiveData<CameraInfo>()

    private var _info: CameraInfo? = null
        set(value) {
            if (value != null) {
                _cameraInfo.postValue(value)
            }
            field = value
        }
    private lateinit var characteristics: CameraCharacteristics
    fun setLens(lens: Int) {
        _currentLens = lens
    }
    fun load() {
        val info = manager.getCameraCharacteristicFor(currentLens).also {
            characteristics = it.characteristics
        }
        var yuvSize = NO_SIZE
        var jpegSize = NO_SIZE
        var rawSize = NO_SIZE
        var rawFormat = NO_FORMAT
        var depthCloudSize = NO_SIZE
        var minStall = Long.MAX_VALUE
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let { map ->
            map.outputFormats.forEach { format ->
                when (format) {
                    ImageFormat.YUV_420_888 -> yuvSize = map.getOutputSizes(format).maxBy {
                        it.height * it.width
                    } ?: NO_SIZE
                    ImageFormat.JPEG -> jpegSize = map.getOutputSizes(format).maxBy {
                        it.height * it.width
                    } ?: NO_SIZE
                    ImageFormat.RAW10,
                        ImageFormat.RAW_SENSOR -> {
                        val size = map.getOutputSizes(format).maxBy {
                            it.height * it.width
                        } ?: NO_SIZE
                        val stall = map.getOutputStallDuration(format, size)
                        if (stall < minStall) {
                            rawFormat = format
                            rawSize = size
                            minStall = stall
                        }
                    }
                    ImageFormat.DEPTH_POINT_CLOUD -> depthCloudSize = map.getOutputSizes(format).maxBy {
                        it.height * it.width
                    } ?: NO_SIZE
                }
            }
        }
        val activeArea = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect()
        val faceMode = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.max()
            ?: 0

        _info = CameraInfo(
            cameraId = info.id,
            hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: 0,
            faceMode = faceMode,
            yuvSize = yuvSize,
            jpegSize = jpegSize,
            rawSize = rawSize,
            depthCloudSize = depthCloudSize,
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            rawFormat = rawFormat,
            edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf(),
            noiseModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf(),
            activeArea = activeArea
        )
    }
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel?> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
                return CameraViewModel(
                    manager = context.getSystemService()
                        ?: throw RuntimeException("Unable to access CameraManager")
                ) as T
            }
            throw ClassCastException("Unknown ViewModel Class: $modelClass")
        }
    }
}
