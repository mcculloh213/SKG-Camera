package ktx.sovereign.camera.ui.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.InputConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import android.media.MediaActionSound
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.getSystemService
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ktx.sovereign.camera.contract.CameraSpec
import ktx.sovereign.camera.extension.getFieldOfView
import ktx.sovereign.camera.extension.isCapabilitySupported
import ktx.sovereign.camera.renderer.FilterRegistry
import ktx.sovereign.camera.ui.camera.data.CameraInfo
import ktx.sovereign.camera.ui.camera.data.saveDepth
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt


typealias FirstFrameReceivedListener = () -> Unit
typealias PerformanceDataAvailableListener = (Int, Int) -> Unit
typealias JpegImageAvailableListener = (data: ByteArray, width: Int, height: Int) -> Unit
private const val SECOND_YUV_IMAGEREADER_STREAM = false
private const val SECOND_SURFACE_TEXTURE_STREAM = true
private const val RAW_STREAM_ENABLE = true
private const val USE_REPROCESSING = true
private const val YUV1_IMAGEREADER_SIZE = 8
private const val YUV2_IMAGEREADER_SIZE = 8
private const val RAW_IMAGEREADER_SIZE = 8
private const val IMAGEWRITER_SIZE = 2
private const val STORE_NTH_DEPTH_CLOUD = 30
private const val DEPTH_CLOUD_STORE_ENABLED = true
class Holder(
    private val context: Context
) : CameraSpec, SurfaceTexture.OnFrameAvailableListener,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {

    val ready: LiveData<Boolean>
        get() = _ready
    private val _ready = MutableLiveData<Boolean>().apply {
        value = false
    }
    val noiseEdgeText: LiveData<Pair<String, String>>
        get() = _noiseEdgeText
    private val _noiseEdgeText = MutableLiveData<Pair<String, String>>()

    val isDepthCloudSupported: Boolean
        get() = info.depthCloudSize != NO_SIZE
    val isRawSupported: Boolean
        get() = info.rawSize != NO_SIZE
    val isYuvReprocessingAvailable: Boolean
        get() = characteristics.isCapabilitySupported(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING)

    private val manager: CameraManager = context.getSystemService() ?:
    throw RuntimeException("Failed to access CameraManager.")
    private val shutter = MediaActionSound()
    private val cropArea = Rect()
    private var depthCloudImageReader: ImageReader? = null
    private var yuv2ImageReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null
    private var surface: Surface? = null
    private var texture: SurfaceTexture? = null
    private var renderer: FilterRegistry? = null
    @Volatile
    private var previewSurface: Surface? = null
    @Volatile
    private var cameraDevice: CameraDevice? = null
    private lateinit var info: CameraInfo
    private lateinit var characteristics: CameraCharacteristics
    private lateinit var operationsThread: HandlerThread
    private lateinit var operationsHandler: Handler
    private lateinit var jpegImageReader: ImageReader
    private lateinit var jpegReaderThread: HandlerThread
    private lateinit var jpegReaderHandler: Handler
    private lateinit var yuv1ImageReader: ImageReader

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            tryToStartCaptureSession()
        }
        override fun onDisconnected(camera: CameraDevice) = Unit
        override fun onError(camera: CameraDevice, error: Int) = Unit
    }

    private lateinit var currentCaptureSession: CameraCaptureSession
    private var reprocessedImageWriter: ImageWriter? = null
    private val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {}
        override fun onConfigureFailed(session: CameraCaptureSession) = onReady(session)
        override fun onReady(session: CameraCaptureSession) {
            currentCaptureSession = session
            sendPreviewCaptureRequest(false)

            if (session.isReprocessable) {
                reprocessedImageWriter = ImageWriter.newInstance(session.inputSurface, IMAGEWRITER_SIZE).apply {
                    setOnImageReleasedListener({ writer ->
                        Log.v("ImageWriter", "Image has been released")
                    }, null)
                }
            }
        }
    }

    private var lastTotalCaptureResult: TotalCaptureResult? = null
    private var firstFrameArrived: Boolean = false
    @Volatile
    private var firstFrameArrivedListener: FirstFrameReceivedListener? = null
    fun setFirstFrameArrivedListener(listener: FirstFrameReceivedListener?) {
        firstFrameArrivedListener = listener
    }
    @Volatile
    private var performanceDataAvailableListener: PerformanceDataAvailableListener? = null
    fun setPerformanceDataAvailableListener(listener: PerformanceDataAvailableListener?) {
        performanceDataAvailableListener = listener
    }
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (!firstFrameArrived) {
                firstFrameArrived = true
                firstFrameArrivedListener?.invoke()
            }
            publishFrameData(result)
            lastTotalCaptureResult = result
        }
    }
    private val reprocessingCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            Log.v("Reprocess", "Completed reprocessing")
        }
    }

    fun setCameraInfo(cameraInfo: CameraInfo) {
        info = cameraInfo
        characteristics = manager.getCameraCharacteristics(info.cameraId)
        launch {
            operationsThread = HandlerThread("[ops]").apply { start() }
            operationsHandler = Handler(operationsThread.looper)
            prepareReaders()
            _ready.postValue(true)
        }
        setNoiseAndEdgeModes()
    }
    fun setZoom(scale: Float) {
        if (::characteristics.isInitialized) {
            val maxDigitalZoom = characteristics.get<Float>(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?: 1.0f
            val zoomTo = clamp(scale, 1.0f, maxDigitalZoom * 10.0f)
            val cX = info.activeArea.centerX()
            val cY = info.activeArea.centerY()
            val dX = ((0.5f * info.activeArea.width()) / zoomTo).roundToInt()
            val dY = ((0.5f * info.activeArea.height()) / zoomTo).roundToInt()
            cropArea.set(cX - dX, cY - dY, cX + dX, cY + dY)
            sendPreviewCaptureRequest(
                autofocusTrigger = true,
                updateCrop = true
            )
        }
    }
    override fun getPreviewSize(): Size = info.previewSize
    override fun getFieldOfView(): FloatArray = characteristics.getFieldOfView()
    override fun getOrientation(): Int = info.sensorOrientation
    override fun openCamera() {
        operationsHandler.post {
            try {
                manager.openCamera(info.cameraId, cameraStateCallback, null)
            } catch (ex: CameraAccessException) {
                Log.e("CameraHolder", "Unable to open camera: ${ex.message}")
            } catch (ex: SecurityException) {
                Log.e("CameraHolder", "Forbidden.")
            }
        }
    }
    override fun startPreview(surface: Surface) {
        previewSurface = surface
        tryToStartCaptureSession()
    }
    override fun closeCamera() {
        texture?.release()
        cameraDevice?.let { device ->
            try {
                currentCaptureSession.abortCaptures()
            } catch (ex: CameraAccessException) {
                Log.e("Camera", "Could not abort running captures")
            } finally {
                currentCaptureSession.close()
            }
            device.close()
        }
    }
    override fun takePicture() {
        Log.v("Camera", "Taking picture")
        shutter.play(MediaActionSound.SHUTTER_CLICK)
        operationsHandler.post { reprocess() }
    }
    override fun setBurst(go: Boolean) {}
    override fun setCallback(callback: CameraSpec.MyCameraCallback) {}
    override fun isRawAvailable(): Boolean = isRawSupported
    override fun isReprocessingAvailable(): Boolean = isYuvReprocessingAvailable
    override fun triggerAFScan() = sendPreviewCaptureRequest(true)
    override fun setCAF() {
        sendPreviewCaptureRequest(false)
    }
    private var captureFlow = CaptureFlow()
    private data class CaptureFlow(
        val captureYuv1: Boolean = true,
        val captureYuv2: Boolean = false,
        val captureRaw: Boolean = false,
        val captureNoiseMode: Int = CaptureRequest.NOISE_REDUCTION_MODE_FAST,
        val captureEdgeMode: Int = CaptureRequest.EDGE_MODE_FAST,
        val captureFace: Boolean = false
    ) {
        val captureDepthCloud: Boolean
            get() = !captureYuv1 && !captureYuv2 && !captureRaw
        fun getNoiseModeText(): String = when (captureNoiseMode) {
            CaptureRequest.NOISE_REDUCTION_MODE_OFF -> "Noise: Off"
            CaptureRequest.NOISE_REDUCTION_MODE_FAST -> "Noise: Fast"
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "Noise: HiQ"
            CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL -> "Noise: Min"
            CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "Noise: ZSL"
            else -> "Noise: ???"
        }
        fun getEdgeModeText(): String = when (captureEdgeMode) {
            CaptureRequest.EDGE_MODE_OFF -> "Edge: Off"
            CaptureRequest.EDGE_MODE_FAST -> "Edge: Fast"
            CaptureRequest.EDGE_MODE_HIGH_QUALITY -> "Edge: HiQ"
            CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG -> "Edge: ZSL"
            else -> "Edge: ???"
        }
    }
    override fun setCaptureFlow(
        yuv1: Boolean?,
        yuv2: Boolean?,
        raw10: Boolean?,
        nr: Boolean?,
        edge: Boolean?,
        face: Boolean?
    ) {
        captureFlow = captureFlow.copy(
            captureYuv1 = yuv1 ?: captureFlow.captureYuv1,
            captureYuv2 = yuv2 ?: captureFlow.captureYuv2,
            captureRaw = raw10 ?: captureFlow.captureRaw,
            captureNoiseMode = if (nr == true) {
                getNextMode(captureFlow.captureNoiseMode, info.noiseModes)
            } else {
                captureFlow.captureNoiseMode
            },
            captureEdgeMode = if (edge == true) {
                getNextMode(captureFlow.captureEdgeMode, info.edgeModes)
            } else {
                captureFlow.captureEdgeMode
            },
            captureFace = face ?: captureFlow.captureFace
        )
        _noiseEdgeText.postValue(Pair(captureFlow.getNoiseModeText(), captureFlow.getEdgeModeText()))
        if (::currentCaptureSession.isInitialized) {
            sendPreviewCaptureRequest(false)
        }
    }
    private var reprocessingFlow = ReprocessingFlow()
    private data class ReprocessingFlow(
        val reprocessingNoiseMode: Int = CaptureRequest.NOISE_REDUCTION_MODE_OFF,
        val reprocessingEdgeMode: Int = CaptureRequest.EDGE_MODE_OFF
    ) {
        fun getNoiseModeText(): String = when (reprocessingNoiseMode) {
            CaptureRequest.NOISE_REDUCTION_MODE_OFF -> "Noise: Off"
            CaptureRequest.NOISE_REDUCTION_MODE_FAST -> "Noise: Fast"
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "Noise: HiQ"
            CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL -> "Noise: Min"
            CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "Noise: ZSL"
            else -> "Noise: ???"
        }
        fun getEdgeModeText(): String = when (reprocessingEdgeMode) {
            CaptureRequest.EDGE_MODE_OFF -> "Edge: Off"
            CaptureRequest.EDGE_MODE_FAST -> "Edge: Fast"
            CaptureRequest.EDGE_MODE_HIGH_QUALITY -> "Edge: HiQ"
            CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG -> "Edge: ZSL"
            else -> "Edge: ???"
        }
    }
    override fun setReprocessingFlow(nr: Boolean?, edge: Boolean?) {
        reprocessingFlow = reprocessingFlow.copy(
            reprocessingNoiseMode = if (nr == true) {
                getNextMode(reprocessingFlow.reprocessingNoiseMode, info.noiseModes)
            } else {
                reprocessingFlow.reprocessingNoiseMode
            },
            reprocessingEdgeMode = if (edge == true) {
                getNextMode(reprocessingFlow.reprocessingEdgeMode, info.edgeModes)
            } else {
                reprocessingFlow.reprocessingEdgeMode
            }
        )
        _noiseEdgeText.postValue(Pair(reprocessingFlow.getNoiseModeText(), reprocessingFlow.getEdgeModeText()))
    }
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {}
    fun sendPreviewCaptureRequest(autofocusTrigger: Boolean, updateCrop: Boolean = false) {
        try {
            val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY)
                if (autofocusTrigger) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                set(CaptureRequest.NOISE_REDUCTION_MODE, captureFlow.captureNoiseMode)
                set(CaptureRequest.EDGE_MODE, captureFlow.captureEdgeMode)
                set(
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                    if (captureFlow.captureFace) info.faceMode else CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF)

                with (captureFlow) {
                    if (captureYuv1) {
                        addTarget(yuv1ImageReader.surface)
                    }
                    if (captureRaw) {
                        addTarget(rawImageReader!!.surface)
                    }
                    addTarget(previewSurface!!)
                    if (isDepthCloudSupported && captureDepthCloud) {
                        addTarget(depthCloudImageReader!!.surface)
                    }
                    if (captureYuv2) {
                        if (SECOND_SURFACE_TEXTURE_STREAM) {
                            addTarget(surface!!)
                        }
                        if (SECOND_YUV_IMAGEREADER_STREAM) {
                            addTarget(yuv2ImageReader!!.surface)
                        }
                    }
                }

                if (updateCrop) {
                    set(CaptureRequest.SCALER_CROP_REGION, cropArea)
                }

                if (autofocusTrigger) {
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                    currentCaptureSession.capture(build(), captureCallback, operationsHandler)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                }
            }
            currentCaptureSession.setRepeatingRequest(request.build(), captureCallback, operationsHandler)
        } catch (ex: CameraAccessException) {
            Log.e("PreviewRequest", "Could not access camera: ${ex.message}")
        }
    }
    private var yuv1LastReceivedImage: Image? = null
    fun reprocess() {
        Log.v("Camera", "Reprocessing")
        yuv1LastReceivedImage?.let { image ->
            Log.v("Camera", "Using last YUV image")
            reprocessedImageWriter!!.queueInputImage(image)
            try {
                val request = cameraDevice!!.createReprocessCaptureRequest(lastTotalCaptureResult!!).apply {
                    set(CaptureRequest.JPEG_ORIENTATION, info.sensorOrientation)
                    set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                    set(CaptureRequest.NOISE_REDUCTION_MODE, reprocessingFlow.reprocessingNoiseMode)
                    set(CaptureRequest.EDGE_MODE, reprocessingFlow.reprocessingEdgeMode)
                    addTarget(jpegImageReader.surface)
                    Log.v("Reprocess", "Modes: ${reprocessingFlow.getNoiseModeText()}, ${reprocessingFlow.getEdgeModeText()}")
                }
                currentCaptureSession.capture(request.build(), reprocessingCaptureCallback, operationsHandler)
            } catch (ex: CameraAccessException) {
                Log.e("Reprocess", "Could not access camera: ${ex.message}")
            }
//            yuv1LastReceivedImage = null
        }
    }

    private suspend fun prepareReaders() = withContext(coroutineContext) {
        prepareJpegImageReader()
        prepareYuv1ImageReader()
        if (isDepthCloudSupported) {
            prepareDepthCloudImageReader()
        }
        if (SECOND_YUV_IMAGEREADER_STREAM) {
            prepareYuv2ImageReader()
        }
        if (SECOND_SURFACE_TEXTURE_STREAM) {
            prepareSurfaceTextureStream()
        }
        if (RAW_STREAM_ENABLE && isRawSupported) {
            prepareRawImageReader()
        }
        shutter.load(MediaActionSound.SHUTTER_CLICK)
    }
    private fun prepareJpegImageReader() {
        jpegReaderThread = HandlerThread("[JpegImageReader]").apply { start() }
        jpegReaderHandler = Handler(jpegReaderThread.looper)
        jpegImageReader = ImageReader.newInstance(
            info.yuvSize.width, info.yuvSize.height, ImageFormat.JPEG, 2
        ).apply {
            setOnImageAvailableListener(jpegImageListener, jpegReaderHandler)
        }
    }
    private fun prepareYuv1ImageReader() {
        yuv1ImageReader = ImageReader.newInstance(
            info.yuvSize.width, info.yuvSize.height, ImageFormat.YUV_420_888, YUV1_IMAGEREADER_SIZE
        ).apply {
            setOnImageAvailableListener(yuv1ImageListener, operationsHandler)
        }
    }
    private fun prepareDepthCloudImageReader() {
        depthCloudImageReader = ImageReader.newInstance(
            info.depthCloudSize.width, info.depthCloudSize.height, ImageFormat.DEPTH_POINT_CLOUD, 2
        ).apply {
            setOnImageAvailableListener(depthCloudImageListener, operationsHandler)
        }
    }
    private fun prepareYuv2ImageReader() {
        yuv2ImageReader = ImageReader.newInstance(
            info.yuvSmallSize.width, info.yuvSmallSize.height, ImageFormat.YUV_420_888, YUV1_IMAGEREADER_SIZE
        ).apply {
            setOnImageAvailableListener({ reader ->

            }, operationsHandler)
        }
    }
    @SuppressLint("Recycle")
    private fun prepareSurfaceTextureStream() {
        val previewSize = Size(1440, 1080)
        val textures = intArrayOf(0)

        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE)

        val texId = textures[0]
        texture = SurfaceTexture(texId).apply {
            setDefaultBufferSize(info.yuvSmallSize.width, info.yuvSmallSize.height)
            setOnFrameAvailableListener(this@Holder)
        }
        surface = Surface(texture)
    }
    private fun prepareRawImageReader() {
        rawImageReader = ImageReader.newInstance(
            info.rawSize.width, info.rawSize.height, info.rawFormat, RAW_IMAGEREADER_SIZE
        ).apply {
            setOnImageAvailableListener({ reader ->

            }, operationsHandler)
        }
    }
    private fun setNoiseAndEdgeModes() {
        if (info.hardwareLevel >= CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) {
            if (info.noiseModes.contains(CameraCharacteristics.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG)) {
                captureFlow = captureFlow.copy(
                    captureNoiseMode = CameraCharacteristics.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG
                )
            }
            if (info.edgeModes.contains(CameraCharacteristics.EDGE_MODE_ZERO_SHUTTER_LAG)) {
                captureFlow = captureFlow.copy(
                    captureEdgeMode = CameraCharacteristics.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG
                )
            }
        }
    }
    private fun tryToStartCaptureSession() {
        Log.v("Camera", "Trying to start session")
        if (cameraDevice != null && _ready.value == true && previewSurface != null) {
            operationsHandler.post {
                startCaptureSession()
            }
        } else {
            Log.v("Camera", "Could not start session: Camera - ${cameraDevice != null} Ready - ${_ready.value == true} Surface - ${previewSurface != null}")
        }
    }
    private fun startCaptureSession() {
        Log.v("Camera", "Starting session")
        val surfaces = mutableListOf<Surface>()
        surfaces.add(previewSurface!!)
        surfaces.add(yuv1ImageReader.surface)
        if (isDepthCloudSupported) {
            surfaces.add(depthCloudImageReader!!.surface)
        }
        if (SECOND_YUV_IMAGEREADER_STREAM) {
            surfaces.add(yuv2ImageReader!!.surface)
        }
        if (SECOND_SURFACE_TEXTURE_STREAM) {
            surfaces.add(surface!!)
        }
        if (RAW_STREAM_ENABLE && isRawSupported) {
            surfaces.add(rawImageReader!!.surface)
        }
        if (USE_REPROCESSING && isYuvReprocessingAvailable) {
            surfaces.add(jpegImageReader.surface)
        }

        try {
            if (USE_REPROCESSING && isYuvReprocessingAvailable) {
                val config = InputConfiguration(
                    info.yuvSize.width, info.yuvSize.height, ImageFormat.YUV_420_888
                )
                cameraDevice!!.createReprocessableCaptureSession(config, surfaces, sessionStateCallback, null)
            } else {
                cameraDevice!!.createCaptureSession(surfaces, sessionStateCallback, null)
            }
        } catch (ex: Exception) {
            Log.e("Camera", "Failed to create session", ex)
        }
    }
    private fun publishFrameData(result: TotalCaptureResult) {}
    private fun getNextMode(currentMode: Int, supportedModes: IntArray): Int {
        var getNext = false
        supportedModes.forEach { mode ->
            if (getNext) {
                return mode
            }
            if (currentMode == mode) {
                getNext = true
            }
        }
        if (getNext) {
            return supportedModes[0]
        }
        return currentMode
    }

    private val yuv1ImageListener = ImageReader.OnImageAvailableListener { reader ->
        val img = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        yuv1LastReceivedImage?.apply { close() }
        yuv1LastReceivedImage = img
    }
    private var depthCloudImageCounter = 0
    private val depthCloudImageListener = ImageReader.OnImageAvailableListener { reader ->
        val img = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        img.planes.let { planes ->
            if (planes.isNotEmpty() && DEPTH_CLOUD_STORE_ENABLED
                && depthCloudImageCounter % STORE_NTH_DEPTH_CLOUD == 0) {
                val buffer = planes[0].buffer
                saveDepth(context, buffer)
            }
        }
        img.close()
        depthCloudImageCounter++
    }
    private var jpegImageAvailableListener: JpegImageAvailableListener? = null
    fun setJpegImageAvailableListener(listener: JpegImageAvailableListener?) {
        jpegImageAvailableListener = listener
    }
    private val jpegImageListener = ImageReader.OnImageAvailableListener { reader ->
        Log.v("JPEG", "Received Image")
        val img = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        val buffer = img.planes[0].buffer
        val jpeg = if (buffer.hasArray()) {
            buffer.array()
        } else {
            ByteArray(buffer.capacity()).also {
                buffer.get(it)
            }
        }
        jpegImageAvailableListener?.invoke(jpeg, img.width, img.height)
        img.close()
    }
}