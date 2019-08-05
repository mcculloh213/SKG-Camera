package ktx.sovereign.camera

import android.content.Context
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaScannerConnection
import android.util.DisplayMetrics
import android.util.Log
import android.util.Rational
import android.view.*
import android.webkit.MimeTypeMap
import androidx.camera.core.*
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import ktx.sovereign.camera.contract.CameraHolder
import ktx.sovereign.camera.extension.*
import ktx.sovereign.camera.util.AutoFitPreviewBuilder
import ktx.sovereign.camera.view.AutoFitTextureView
import ktx.sovereign.database.provider.MediaProvider
import java.io.File
import java.lang.RuntimeException
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

class CameraXHolder : CameraHolder {
    private val supervisor: Job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + supervisor

    override val activeRegion: Rect
        get() = cropRegion
            ?: manager?.getActiveArraySizeFor(lens)
            ?: Rect()
    private var manager: CameraManager? = null
    private var surface: AutoFitTextureView? = null
    private var displayId: Int = -1
    private var lens = CameraX.LensFacing.BACK
    private val lensCharacteristic: Int
        get() = when (lens) {
            CameraX.LensFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            CameraX.LensFacing.BACK -> CameraCharacteristics.LENS_FACING_BACK
        }
    private var cropRegion: Rect? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var analyzer: ImageAnalysis? = null

    override fun createSurface(context: Context, parent: ViewGroup): View? {
        manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager?
        val inflater = LayoutInflater.from(context)
        surface = inflater.inflate(R.layout.layout_camerax, parent, false) as AutoFitTextureView
        return surface
    }
    override fun bindDisplayId(displayId: Int) {
        this@CameraXHolder.displayId = displayId
    }
    override fun startCamera() { }
    override fun startCamera(lifecycle: LifecycleOwner) {
        val m = manager ?: throw RuntimeException("ʕ•ᴥ•ʔ")
        val s = surface ?: throw RuntimeException("CameraX Holder lost reference to TextureView.")
//        val id = m.cameraIdList.firstOrNull { m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lensCharacteristic }
//            ?: throw RuntimeException("Whoops, no Camera ID! ʕ•ᴥ•ʔ")
        val info = m.getCameraCharacteristicFor(lens)
        val realSize = s.getRealSize() // Point().also { (s.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(it) }
        val displaySize = s.getSize() // Point().also { (s.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getSize(it) }
        val displayRotation = s.getDisplayRotation() // (s.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val aspectRatio = Rational(realSize.x, realSize.y)

//        val metrics = DisplayMetrics().also { s.display.getRealMetrics(it) }
//        val aspectRatio = Rational(metrics.widthPixels, metrics.heightPixels)

        val maxSize = info.characteristics.getPreviewSize(aspectRatio)
        val previewSize = if (info.characteristics.areDimensionsSwapped(displayRotation)) {
            info.characteristics.getOptimalSize(s.height, s.width, displaySize.y, displaySize.x, maxSize)
        } else {
            info.characteristics.getOptimalSize(s.width, s.height, displaySize.x, displaySize.y, maxSize)
        }

        Log.i("Aspect Ratio", "Size: $aspectRatio\tMetrics: ${s.getAspectRatio()}")

        val config = PreviewConfig.Builder()
            .setLensFacing(lens)
            .setTargetAspectRatio(Rational(previewSize.width, previewSize.height))
            .setTargetRotation(s.display.rotation)
            .build()

        preview = AutoFitPreviewBuilder.build(config, s)

        val captureConfig = ImageCaptureConfig.Builder()
            .setLensFacing(lens)
            .setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
            .setTargetAspectRatio(aspectRatio)
            .setTargetRotation(s.display.rotation)
            .build()

        capture = ImageCapture(captureConfig)
        CameraX.setErrorListener({ code, message ->
            Log.e("CameraX", "Error ($code): $message")
        }, null)
        CameraX.bindToLifecycle(lifecycle, preview, capture)
    }
    override fun updateTransform() {
        surface?.apply {
            val matrix = Matrix()
            val centerX = width / 2f
            val centerY = height / 2f
            val theta = display.rotation.toFloat()
            matrix.postRotate(-theta, centerX, centerY)
            setTransform(matrix)
        }
    }
    override suspend fun setZoom(scale: Float): Rect = withContext(coroutineContext) {
        val m = manager ?: throw RuntimeException("No camera manager. ʕ•ᴥ•ʔ")
        val id = m.cameraIdList.firstOrNull { m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lensCharacteristic }
            ?: throw RuntimeException("Whoops, no Camera ID! ʕ•ᴥ•ʔ")
        val p = preview ?: throw RuntimeException("CameraX Holder does not contain a Preview. Relax, have a koala ʕ•ᴥ•ʔ")
        val characteristics = m.getCameraCharacteristics(id)

        val activeRect = characteristics.get<Rect>(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: throw RuntimeException("Unable to get active array. ʕ•ᴥ•ʔ")
        Log.i("Zoom", "Active Rect: $activeRect")
        val maxDigitalZoom = characteristics.get<Float>(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        Log.i("Zoom", "Max Digital Zoom: $maxDigitalZoom")
        val zoomTo = clamp(scale, 1.0f, maxDigitalZoom * 10.0f)
        Log.i("Zoom", "Clamped: $zoomTo")
        val centerX = activeRect.centerX()
        val centerY = activeRect.centerY()
        val dX = ((0.5f * activeRect.width()) / zoomTo).roundToInt()
        val dY = ((0.5f * activeRect.height())/ zoomTo).roundToInt()

        cropRegion = Rect(centerX - dX, centerY - dY, centerX + dX, centerY + dY)
        Log.i("Zoom", "Crop Region: $cropRegion")
        p.zoom(cropRegion)
        cropRegion!!
    }
    override fun capture() {
        capture?.let {
            val s = surface ?: return
            val file = MediaProvider.createImageFile(s.context)
            val meta = ImageCapture.Metadata().apply {
                isReversedHorizontal = lens == CameraX.LensFacing.FRONT
            }
            it.takePicture(file, object : ImageCapture.OnImageSavedListener {
                override fun onImageSaved(file: File) {
                    Log.i("ImageCapture", "Capture succeeded! ${file.absolutePath}")
                    val mime = MimeTypeMap.getSingleton().getExtensionFromMimeType(file.extension)
                    MediaScannerConnection.scanFile(s.context, arrayOf(file.absolutePath), arrayOf(mime), null)
                }
                override fun onError(useCaseError: ImageCapture.UseCaseError, message: String, cause: Throwable?) {
                    Log.e("ImageCapture", "Capture failed: $message")
                    cause?.printStackTrace()
                }
            }, meta)
        }
    }
    override fun closeCamera() {
        CameraX.unbindAll()
        supervisor.cancelChildren()
    }
}