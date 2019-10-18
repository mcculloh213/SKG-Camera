package ktx.sovereign.camera

import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SizeF
import android.util.SparseIntArray
import android.view.*
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ktx.sovereign.camera.contract.CameraHolder
import ktx.sovereign.camera.extension.*
import ktx.sovereign.camera.renderer.GLESCameraRenderer
import ktx.sovereign.camera.view.AutoFitTextureView
import ktx.sovereign.database.provider.MediaProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

class Camera2Holder : CameraHolder, LifecycleObserver {
    companion object {
        private const val STATE_PREVIEW: Int = 0
        private const val STATE_WAITING_LOCK: Int = 1
        private const val STATE_WAITING_PRECAPTURE: Int = 2
        private const val STATE_WAITING_NON_PRECAPTURE: Int = 3
        private const val STATE_PICTURE_TAKEN: Int = 4
        private const val STATE_WAITING_FREEZE_LOCK: Int = 5
        private const val STATE_WAITING_PREFREEZE: Int = 6
        private const val STATE_WAITING_NON_PREFREEZE: Int = 7

        private const val CAPTURE_MODE_IMAGE: Int = 0
        private const val CAPTURE_MODE_FREEZE: Int = 1

        @JvmStatic private val ORIENTATIONS: SparseIntArray = SparseIntArray().apply {
            append(Surface.ROTATION_0, 90)
            append(Surface.ROTATION_90, 0)
            append(Surface.ROTATION_180, 270)
            append(Surface.ROTATION_270, 180)
        }
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    private val state: AtomicInteger = AtomicInteger(STATE_PREVIEW)
    private val stateCallback: CameraStateManager = CameraStateManager()
    private val captureCallback: CameraCaptureManager = CameraCaptureManager()
    private val imageCaptureSession: ImageCaptureSession = ImageCaptureSession()
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
    private var previewSize: Size = Size(0, 0)
    private var listener: CameraHolder.CameraStateListener? = null
    private lateinit var lifecycleOwner: LifecycleOwner
    private lateinit var target: Surface
    private lateinit var imageReader: ImageReader
    private lateinit var renderer: GLESCameraRenderer
    private lateinit var manager: CameraManager
    private lateinit var cameraId: String
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var previewRequest: CaptureRequest
    private lateinit var cropRegion: Rect

    override val texture: TextureView?
        get() = surface
    override val activeRegion: Rect
        get() = Rect()
    val sensorOrientation: Int
        get() = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    val isFlashSupported: Boolean
        get() = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    override fun setCameraStateListener(listener: CameraHolder.CameraStateListener) {
        this@Camera2Holder.listener = listener
    }
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
        lifecycleOwner = lifecycle.also { it.lifecycle.addObserver(this) }
    }
    override fun updateTransform() {
        val s = surface ?: return
        s.setTransform(configureTransform(s.width, s.height, s.getDisplayRotation()))
    }
    @Synchronized
    override fun setZoom(scale: Float) {
        if (isRepeating) {
            val c = manager.getCameraCharacteristicFor(CameraCharacteristics.LENS_FACING_BACK).characteristics
            val activeRect = c.get<Rect>(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    ?: throw RuntimeException()
            val maxDigitalZoom = c.get<Float>(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?: 1.0f
            val crop = CropRect.from(activeRect, scale, maxDigitalZoom)
            Log.i("Crop", crop.toString())
            cropRegion = crop.region
            val
            previewRequest = previewRequestBuilder.apply {
                set(CaptureRequest.SCALER_CROP_REGION, crop.region)
            }.build()
            captureSession?.setRepeatingRequest(previewRequest, captureCallback, handler)
        }
    }
    private data class CropRect(
            val left: Int,
            val top: Int,
            val right: Int,
            val bottom: Int,
            val max: SizeF,
            val zoom: SizeF,
            val dimens: Size,
            val center: Size,
            val delta: Size,
            val outer: Rect? = null
    ) {
        val region: Rect = Rect(left, top, right, bottom)
        override fun toString(): String = StringBuilder().append("\n")
                .append("{").append("\n").apply {
                    val r = outer ?: return@apply
                    append("\t").append("'active'").append(" : ").append("{").append("\n")
                    append(r.jsonString(2))
                    append("\t").append("}").append(",").append("\n")
                }
                .append("\t").append("'max_scale'").append(" : ").append("{ 'x': ${max.width}, 'y': ${max.height} }").append(",").append("\n")
                .append("\t").append("'zoom'").append(" : ").append("{ 'x': ${zoom.width}, 'y': ${zoom.height} }").append(",").append("\n")
                .append("\t").append("'delta'").append(" : ").append("{ 'dx': ${delta.width}, 'dy': ${delta.height} }").append(",").append("\n")
                .append("\t").append("'crop_region'").append(" : ").append("{").append("\n")
                .append(region.jsonString(2))
                .append("\t").append("}").append("\n")
                .append("}")
                .toString()
        private fun Rect.jsonString(indent: Int = 0): String = StringBuilder()
                .apply {
                    for (i in 0 until indent) {
                        append("\t")
                    }
                }.append("'bounds'").append(" : ").append("[$left, $top, $right, $bottom]").append(",").append("\n")
                .apply {
                    for (i in 0 until indent) {
                        append("\t")
                    }
                }.append("'dimens'").append(" : ").append("{ 'x': ${width()}, 'y': ${height()} }").append("\n")
                .toString()
        companion object {
            @JvmStatic fun from(outer: Rect, scale: Float, maxZoom: Float): CropRect {
                val max = SizeF(
                        outer.width() / maxZoom,
                        outer.height() / maxZoom
                )
                val zoom = SizeF(
                        clamp(scale, 1.0f, max.width),
                        clamp(scale, 1.0f, max.height)
                )
                val center = Size(outer.centerX(), outer.centerY())
                val delta = Size(
                        ((0.5f * outer.width()) / zoom.width).roundToInt(),
                        ((0.5f * outer.height()) / zoom.height).roundToInt()
                )
                return CropRect(
                        left = center.width - delta.width,
                        top = center.height - delta.height,
                        right = center.width + delta.width,
                        bottom = center.height + delta.height,
                        max = max,
                        zoom = zoom,
                        dimens = Size(delta.width * 2, delta.height * 2),
                        center = center,
                        delta = delta,
                        outer = outer
                )
            }
        }
    }
    override suspend fun setZoomAsync(scale: Float): Rect = withContext(coroutineContext) {
        setZoom(scale)
        Rect()
    }
    @Synchronized
    override fun toggleFreeze() {
        isRepeating = if (isRepeating) {
            imageCaptureSession.setMode(CAPTURE_MODE_FREEZE)
            lockFocus(STATE_WAITING_FREEZE_LOCK)
            false
        } else {
            captureSession?.setRepeatingRequest(previewRequest, captureCallback, handler)
            true
        }
    }
    override fun toggleFreeze(lifecycle: LifecycleOwner) {
        toggleFreeze()
    }
    override fun capture() {
        imageCaptureSession.setMode(CAPTURE_MODE_IMAGE)
        lockFocus()
    }
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
        imageReader = ImageReader.newInstance(previewSize.width, previewSize.height,
                ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener(imageCaptureSession, handler)
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
            target = Surface(texture)
            previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    ?: throw RuntimeException("Camera2 Holder lost reference to the camera!")
            previewRequestBuilder.addTarget(target)

            cameraDevice?.createCaptureSession(listOf(target, imageReader.surface), object: CameraCaptureSession.StateCallback() {
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
    private fun setAutoFlash(requestBuilder: CaptureRequest.Builder) {
        if (isFlashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        }
    }
    private fun lockFocus(requestState: Int = STATE_WAITING_LOCK) {
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START)
            state.set(requestState)
            captureSession?.capture(previewRequestBuilder.build(), captureCallback, handler)
        } catch (ex: CameraAccessException) {
            Log.e("Camera2Holder", ex.toString())
            ex.printStackTrace()
        }
    }
    private fun runPrecaptureSequence() {
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            state.set(STATE_WAITING_PRECAPTURE)
            captureSession?.capture(previewRequestBuilder.build(), captureCallback, handler)
        } catch (ex: CameraAccessException) {
            Log.e("Camera2Holder", ex.toString())
            ex.printStackTrace()
        }
    }
    private fun runPrefreezeSequence() {
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            state.set(STATE_WAITING_PREFREEZE)
            captureSession?.capture(previewRequestBuilder.build(), captureCallback, handler)
        } catch (ex: CameraAccessException) {
            Log.e("Camera2Holder", ex.toString())
            ex.printStackTrace()
        }
    }
    private fun captureStillPicture() {
        val s = surface ?: return
        try {
            val rotation = s.getDisplayRotation()

            val captureBuilder = cameraDevice?.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.JPEG_ORIENTATION,
                        (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }?.also { setAutoFlash(it) } ?: return

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                ) = unlockFocus()
            }

            captureSession?.apply {
                stopRepeating()
                abortCaptures()
                capture(captureBuilder.build(), captureCallback, null)
            }
        } catch (ex: CameraAccessException) {
            Log.e("Camera2Holder", ex.toString())
            ex.printStackTrace()
        }
    }
    private fun freezePicture() {
        val s = surface ?: return
        try {
            val rotation = s.getDisplayRotation()

            val captureBuilder = cameraDevice?.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.JPEG_ORIENTATION,
                        (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }?.also { setAutoFlash(it) } ?: return

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                ) {
                    unlockFocus()
                    session.stopRepeating()
                }
            }

            captureSession?.apply {
                stopRepeating()
                abortCaptures()
                capture(captureBuilder.build(), captureCallback, null)
            }
        } catch (ex: CameraAccessException) {
            Log.e("Camera2Holder", ex.toString())
            ex.printStackTrace()
        }
    }
    private fun unlockFocus() {
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            setAutoFlash(previewRequestBuilder)
            captureSession?.capture(previewRequestBuilder.build(), captureCallback, handler)
            state.set(STATE_PREVIEW)
            captureSession?.setRepeatingRequest(previewRequest, captureCallback, handler)
        } catch (ex: CameraAccessException) {
            Log.e("Camera2Holder", ex.toString())
            ex.printStackTrace()
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
        ) = process(partialResult)
        override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
        ) = process(result)
        private fun process(result: CaptureResult) {
            when (state.get()) {
                STATE_PREVIEW -> Unit
                STATE_WAITING_LOCK -> capturePicture(result)
                STATE_WAITING_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null
                            || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                            || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        state.set(STATE_WAITING_NON_PRECAPTURE)
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null
                            || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        state.set(STATE_PICTURE_TAKEN)
                        captureStillPicture()
                    }
                }
                STATE_WAITING_FREEZE_LOCK -> freezePicture(result)
                STATE_WAITING_PREFREEZE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null
                            || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                            || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        state.set(STATE_WAITING_NON_PREFREEZE)
                    }
                }
                STATE_WAITING_NON_PREFREEZE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null
                            || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        state.set(STATE_PICTURE_TAKEN)
                        captureStillPicture()
                    }
                }
            }
        }
        private fun capturePicture(result: CaptureResult) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (afState == null) {
                captureStillPicture()
            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                    state.set(STATE_PICTURE_TAKEN)
                    captureStillPicture()
                } else {
                    runPrecaptureSequence()
                }
            }
        }
        private fun freezePicture(result: CaptureResult) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (afState == null) {
                freezePicture()
            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                    state.set(STATE_PICTURE_TAKEN)
                    freezePicture()
                } else {
                    runPrefreezeSequence()
                }
            }
        }
    }
    private inner class ImageCaptureSession : ImageReader.OnImageAvailableListener {
        private var mode: Int = CAPTURE_MODE_IMAGE
        fun setMode(mode: Int) {
            this@ImageCaptureSession.mode = mode
        }
        override fun onImageAvailable(reader: ImageReader) {
            Log.i("Capture", "Image is available")
            when (mode) {
                CAPTURE_MODE_IMAGE -> {
                    val context = surface?.context ?: return
                    handler.post(ImageSaver(reader.acquireNextImage(), MediaProvider.createImageFile(context)))
                }
                CAPTURE_MODE_FREEZE -> {
                    val context = surface?.context ?: return
                    handler.post(ImageSaver(reader.acquireNextImage(), MediaProvider.createImageFile(context), true))
//                    val review = BitmapSaver(reader.acquireNextImage())
//                    launch(Dispatchers.Main) {
//                        review.bitmap.observe(lifecycleOwner, Observer { bitmap ->
//                            Log.i("Bitmap", "Bitmap captured!")
//                            listener?.onBitmapSaved(arrayOf(bitmap))
//                        })
//                    }
//                    handler.post(review)
                }
            }
        }
    }
    private inner class ImageSaver(
            private val image: Image,
            private val file: File,
            private val notify: Boolean = false
    ) : Runnable {
        override fun run() {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            FileOutputStream(file).use { stream -> stream.write(bytes) }
            image.close()
            if (notify) { listener?.onImageSaved(file) }
        }
    }
    private class BitmapSaver(
            private val image: Image
    ) : Runnable {
        val bitmap: LiveData<Bitmap>
            get() = _bitmap
        private val _bitmap: MutableLiveData<Bitmap> = MutableLiveData()
        override fun run() {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)
            _bitmap.postValue(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null))
        }
    }
}