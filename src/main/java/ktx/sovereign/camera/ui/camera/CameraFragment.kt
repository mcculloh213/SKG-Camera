package ktx.sovereign.camera.ui.camera

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.InputConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import android.media.MediaActionSound
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.core.content.getSystemService
import androidx.lifecycle.*
import kotlinx.android.synthetic.main.fragment_camera.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import ktx.sovereign.camera.R
import ktx.sovereign.camera.contract.CameraSpec
import ktx.sovereign.camera.extension.getFieldOfView
import ktx.sovereign.camera.extension.isCapabilitySupported
import ktx.sovereign.camera.ui.camera.data.CameraInfo
import ktx.sovereign.camera.ui.camera.data.saveDepth
import ktx.sovereign.camera.ui.camera.data.saveJpeg
import ktx.sovereign.core.util.LogMAR
import javax.microedition.khronos.opengles.GL10

/**
 * https://android.googlesource.com/platform/packages/apps/DevCamera/+/refs/heads/master/?autodive=0%2F%2F%2F%2F
 */
class CameraFragment : Fragment(), SurfaceHolder.Callback,
    CoroutineScope by CoroutineScope(Dispatchers.Main) {
    private val logmar: LogMAR = LogMAR(1.0f, supremum = 1.0f)
    private lateinit var viewModel: CameraViewModel
    private var camera: Holder? = null
    @Volatile
    private var sendToPreview: Boolean = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        viewModel = ViewModelProvider(viewModelStore, CameraViewModel.Factory(context))
            .get(CameraViewModel::class.java)
        openCamera()
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        camera?.ready?.observe(viewLifecycleOwner, Observer {
            val ready = it ?: false
            if (ready) {
                camera?.openCamera()
            }
        })
        viewModel.cameraInfo.observe(viewLifecycleOwner, Observer {
            val info = it ?: return@Observer
            camera?.setCameraInfo(info)
            startCamera()
        })
        preview_view.holder.addCallback(this@CameraFragment)
        jpeg_capture.setOnClickListener {
            camera?.takePicture()
        }
        label_zoom_out.setOnClickListener {
            val zoom = logmar.stepDown()
            launch { camera?.setZoom(zoom) }
            slot_view.decreaseZoomLevel()
        }
        label_zoom_in.setOnClickListener {
            val zoom = logmar.stepUp()
            launch { camera?.setZoom(zoom) }
            slot_view.increaseZoomLevel()
        }
        label_freeze.setOnClickListener {
            sendToPreview = true
            camera?.takePicture()
        }
        menu_camera_filters.apply {
            setToggleClickListener(View.OnClickListener { close() })
            setOnFloatingActionOptionItemSelectedListener { item ->
                when (item.itemId) {
                    else -> Unit
                }
            }
        }
        slot_view.setOnClickSlotListener {
            onClick = { slot ->
                camera?.setZoom(logmar.stepTo(slot))
            }
        }
        viewModel.load()
    }
    override fun onStart() {
        super.onStart()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onPause() {
        camera?.closeCamera()
        camera = null
        super.onPause()
    }

    private var isSurfaceValid: Boolean = false
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.v("Camera", "Surface Created")
        val previewSize = Size(1440, 1080) // camera!!.getPreviewSize()
        when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                val renderWidth = displayWidth() // renderHeight * previewSize.height / previewSize.width
                val renderHeight = renderWidth * previewSize.height / previewSize.width // 3 * displayHeight() / 4
                val renderPad = (displayWidth() - renderWidth) / 2
                preview_frame.apply {
                    setPadding(renderPad, 0, 0, 0)
                    layoutParams.width = renderWidth + renderPad
                    layoutParams.height = renderHeight // = FrameLayout.LayoutParams(renderWidth + renderPad, renderHeight)
                }
            }
            Configuration.ORIENTATION_PORTRAIT -> {
                val renderHeight = displayHeight() // 3 * displayHeight() / 4
                val renderWidth = renderHeight * previewSize.height / previewSize.width
                val renderPad = (displayWidth() - renderWidth) / 2
                preview_frame.apply {
                    setPadding(renderPad, 0, 0, 0)
                    layoutParams.width = renderWidth + renderPad
                    layoutParams.height = renderHeight // = FrameLayout.LayoutParams(renderWidth + renderPad, renderHeight)
                }
            }
        }
        preview_view.holder.setFixedSize(previewSize.height, previewSize.width)
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        isSurfaceValid = true
        camera?.startPreview(holder.surface)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        isSurfaceValid = false
    }

    private fun openCamera() {
        val ctx = context ?: return
        camera?.closeCamera()
        camera = Holder(ctx).apply {
            setFirstFrameArrivedListener {
                launch {
                    preview_view.setBackgroundColor(Color.TRANSPARENT)
                }
            }
            setJpegImageAvailableListener { data, width, height ->
                launch(Dispatchers.IO) {
                    saveJpeg(ctx, data)?.also { uri ->
                        if (sendToPreview) {
                            sendToPreview = false
                            with (Intent(Intent.ACTION_VIEW)) {
                                addCategory(Intent.CATEGORY_DEFAULT)
                                setDataAndType(uri, "image/*")
                                setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(this)
                            }
                        }
                    }
                }
            }
        }
    }
    private fun startCamera() {
        if (isSurfaceValid) {
            camera!!.startPreview(preview_view.holder.surface)
//        } else {
//            val previewSize = camera!!.getPreviewSize()
//            val renderHeight = displayHeight() // 3 * displayHeight() / 4
//            val renderWidth = renderHeight * previewSize.height / previewSize.width
//            val renderPad = (displayWidth() - renderWidth) / 2
//            preview_frame.apply {
//                setPadding(renderPad, 0, 0, 0)
//                layoutParams.width = renderWidth + renderPad
//                layoutParams.height = renderHeight // = FrameLayout.LayoutParams(renderWidth + renderPad, renderHeight)
//            }
//            preview_view.holder.setFixedSize(previewSize.height, previewSize.width)
        }
    }

    private fun displayWidth(): Int {
        val metrics = DisplayMetrics()
        activity?.windowManager?.defaultDisplay?.getRealMetrics(metrics)
        return metrics.widthPixels
    }
    private fun displayHeight(): Int {
        val metrics = DisplayMetrics()
        activity?.windowManager?.defaultDisplay?.getRealMetrics(metrics)
        return metrics.heightPixels
    }

    companion object {
        @JvmStatic fun newInstance(): CameraFragment = CameraFragment()
    }
}
