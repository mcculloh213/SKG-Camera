package ktx.sovereign.camera

import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.*
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ktx.sovereign.camera.contract.CameraHolder
import ktx.sovereign.camera.extension.*
import ktx.sovereign.camera.renderer.GLESCameraRenderer
import ktx.sovereign.camera.renderer.filter.BlueOrangeFilter
import ktx.sovereign.camera.renderer.filter.EdgeDetectionFilter
import ktx.sovereign.camera.renderer.filter.TouchColorFilter
import ktx.sovereign.camera.view.AutoFitTextureView
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

class Camera2Holder : CameraHolder, LifecycleObserver {
    companion object {
        private const val STATE_PREVIEW: Int = 0
        private const val STATE_WAITING_LOCK: Int = 1
        private const val STATE_WAITING_PRECAPTURE: Int = 2
        private const val STATE_WAITING_NON_PRECAPTURE: Int = 3
        private const val STATE_PICTURE_TAKEN: Int = 4
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    private val state: AtomicInteger = AtomicInteger(STATE_PREVIEW)
    private val stateCallback: CameraStateManager = CameraStateManager()
    private val captureCallback: CameraCaptureManager = CameraCaptureManager()
    private val lock: Semaphore = Semaphore(1)
    private val thread: HandlerThread by lazy {
        HandlerThread("Camera2").also { it.start() }
    }
    private val handler: Handler by lazy {
        Handler(thread.looper)
    }

    private var surface: AutoFitTextureView? = null
    private var displayId: Int = -1
    private var captureSession: CameraCaptureSession? = null
    private var cameraDevice: CameraDevice? = null
    private var isRepeating: Boolean = false
    private lateinit var renderer: GLESCameraRenderer
    private lateinit var manager: CameraManager
    private lateinit var cameraId: String
    private lateinit var previewSize: Size
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var previewRequest: CaptureRequest
    private lateinit var cropRegion: Rect

    override val activeRegion: Rect
        get() = Rect()

    override fun createSurface(context: Context, parent: ViewGroup): View? {
        manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        surface = (LayoutInflater.from(context).inflate(R.layout.layout_camerax, parent, false) as AutoFitTextureView).also {
            if (context is TextureView.SurfaceTextureListener) {
                it.surfaceTextureListener = context
            }
        }
        return surface
    }
    override fun bindDisplayId(displayId: Int) {
        this@Camera2Holder.displayId = displayId
    }
    override fun setRenderer(renderer: GLESCameraRenderer?) {
        this@Camera2Holder.renderer = renderer ?: return
    }
    override fun startCamera() {
        val s = surface ?: throw RuntimeException("Camera2 Holder lost reference to TextureView")
        val rotation = s.getDisplayRotation()
        configureOutput(s.width, s.height, rotation, s.getSize())
        s.setTransform(configureTransform(s.width, s.height, rotation))
        renderer.setViewport(previewSize.width, previewSize.height)
        try {
            if (!lock.tryAcquire(2500L, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out while trying to acquire camera.")
            }
            manager.openCamera(cameraId, stateCallback, handler)
        } catch(ex: SecurityException) {
            Log.e("Camera2", ex.message ?: "Camera permission was not granted.")
            throw RuntimeException("I have no idea how you got here.", ex)
        } catch (ex: CameraAccessException) {
            Log.e("Camera2", ex.message ?: "Unable to access camera")
            ex.printStackTrace()
        } catch (ex: InterruptedException) {
            Log.e("Camera2", ex.message ?: "Interrupted")
            throw RuntimeException("Interrupted while trying to acquire camera.", ex)
        }
    }
    override fun startCamera(lifecycle: LifecycleOwner) {
        startCamera()
        lifecycle.lifecycle.addObserver(this)
    }
    override fun updateTransform() {
        val s = surface ?: return
        s.setTransform(configureTransform(s.width, s.height, s.getDisplayRotation()))
    }
    override fun setZoom(scale: Float) {
        if (isRepeating) {
            val c = manager.getCameraCharacteristicFor(CameraCharacteristics.LENS_FACING_BACK).characteristics
            val activeRect = c.get<Rect>(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    ?: throw RuntimeException()
            val maxDigitalZoom = c.get<Float>(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?: 1.0f
            val zoomTo = clamp(scale, 1.0f, maxDigitalZoom * 10.0f)
            val cX = activeRect.centerX()
            val cY = activeRect.centerY()
            val dX = ((0.5f * activeRect.width()) / zoomTo).roundToInt()
            val dY = ((0.5f * activeRect.height()) / zoomTo).roundToInt()

            val region = Rect(cX - dX, cY - dY, cX + dX, cY + dY).also {
                cropRegion = it
            }
            previewRequest = previewRequestBuilder.apply {
                set(CaptureRequest.SCALER_CROP_REGION, region)
            }.build()
            captureSession?.setRepeatingRequest(previewRequest, captureCallback, handler)
        }
    }
    override suspend fun setZoomAsync(scale: Float): Rect = withContext(coroutineContext) {
        setZoom(scale)
        Rect()
    }
    override fun toggleFreeze() {
        isRepeating = if (isRepeating) {
            captureSession?.stopRepeating()
            false
        } else {
            captureSession?.setRepeatingRequest(previewRequest, captureCallback, handler)
            true
        }
    }
    override fun toggleFreeze(lifecycle: LifecycleOwner) {
        toggleFreeze()
    }
    override fun capture() { }
    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    override fun closeCamera() {
        try {
            lock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (ex: InterruptedException) {
            throw RuntimeException("Interrupted while trying to close the camera. It done borked.", ex)
        } finally {
            lock.release()
        }
    }

    private fun configureOutput(width: Int, height: Int, rotation: Int, display: Point) {
        val info = manager.getCameraCharacteristicFor(CameraCharacteristics.LENS_FACING_BACK)
        val maxSize = info.characteristics.getLargestOutputSize()
        previewSize = if (info.characteristics.areDimensionsSwapped(rotation)) {
            Log.i("Configuration", "Dimensions are swapped")
            info.characteristics.getOptimalSize(height, width, display.y, display.x, maxSize)
        } else {
            Log.i("Configuration", "Dimensions are not swapped")
            info.characteristics.getOptimalSize(width, height, display.x, display.y, maxSize)
        }
        renderer.setViewport(previewSize.width, previewSize.height)
        cameraId = info.id
    }
    private fun configureTransform(width: Int, height: Int, rotation: Int): Matrix {
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        when (rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                val scale = max(width.toFloat() / previewSize.width, height.toFloat() / previewSize.height)
                with (matrix) {
                    setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                    postScale(scale, scale, centerX, centerY)
                    postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
                }
            }
            Surface.ROTATION_180 -> {
                matrix.postRotate(180f, centerX, centerY)
            }
        }
        return matrix
    }

    private fun createPreview() {
        try {
            val texture = renderer.previewSurface
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val preview = Surface(texture)
            previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                ?: throw RuntimeException("Camera2 Holder lost reference to the camera!")
            previewRequestBuilder.addTarget(preview)

            cameraDevice?.createCaptureSession(listOf(preview), object: CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraDevice?.let { _ ->
                        captureSession = session
                        try {
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            previewRequest = previewRequestBuilder.build()
                            captureSession?.setRepeatingRequest(previewRequest, captureCallback, handler)
                            isRepeating = true
                        } catch (ex: CameraAccessException) {
                            Log.e("Session", ex.message ?: "Failed to access camera, I guess?")
                        }
                    } ?: return
                }
                override fun onConfigureFailed(session: CameraCaptureSession) { }
            }, null)
        } catch (ex: CameraAccessException) {
            Log.e("CameraPreview", ex.message ?: "Failed to update the preview")
        }
    }

    private inner class CameraStateManager : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            lock.release()
            this@Camera2Holder.cameraDevice = camera
            createPreview()
        }
        override fun onDisconnected(camera: CameraDevice) {
            lock.release()
            camera.close()
            this@Camera2Holder.cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            Log.e("CameraState", "Error occurred: $error")
            onDisconnected(camera)
        }
    }

    private inner class CameraCaptureManager : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
            process(partialResult)
        }
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            process(result)
        }
        private fun process(result: CaptureResult) {
            when (state.get()) {
                STATE_PREVIEW -> Unit
                STATE_WAITING_LOCK -> Unit
                STATE_WAITING_PRECAPTURE -> {

                }
                STATE_WAITING_NON_PRECAPTURE -> {

                }
            }
        }
    }
}