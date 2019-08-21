package ktx.sovereign.camera.util

import android.content.Context
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.Size
import android.view.*
import androidx.camera.core.Preview
import androidx.camera.core.PreviewConfig
import ktx.sovereign.camera.renderer.GLESCameraRenderer
import ktx.sovereign.hmt.extension.isHMT
import java.lang.ref.WeakReference
import java.util.*
import kotlin.math.roundToInt

class AutoFitPreviewBuilder private constructor(config: PreviewConfig, surfaceRef: WeakReference<TextureView>) {
    val useCase: Preview

    private var bufferRotation: Int = 0
    private var viewFinderRotation: Int? = null
    private var bufferDimens: Size = Size(0, 0)
    private var viewFinderDimens: Size = Size(0, 0)
    private var viewFinderDisplay: Int = -1

    private var displayManager: DisplayManager? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) { }
        override fun onDisplayAdded(displayId: Int) { }
        override fun onDisplayRemoved(displayId: Int) {
            val surface = surfaceRef.get() ?: return
            if (displayId == viewFinderDisplay) {
                val display = displayManager?.getDisplay(displayId) ?: return
                val rotation = getDisplaySurfaceRotation(display)
                updateTransform(surface, rotation, bufferDimens, viewFinderDimens)
            }
        }
    }

    init {
        val ref = surfaceRef.get() ?: throw IllegalArgumentException("Invalid reference to camera surface")
        viewFinderDisplay = ref.display.displayId
        viewFinderRotation = getDisplaySurfaceRotation(ref.display) ?: 0
        useCase = Preview(config).also { preview ->
            preview.setOnPreviewOutputUpdateListener {
                val surface = surfaceRef.get() ?: return@setOnPreviewOutputUpdateListener
                Log.d("AutoFitPreview", "Preview output changed -- Size: ${it.textureSize}\tRotation ${it.rotationDegrees}")

                (surface.parent as ViewGroup).apply {
                    removeView(surface)
                    addView(surface, 0)
                }

                surface.surfaceTexture = it.surfaceTexture

                bufferRotation = it.rotationDegrees
                val rotation = getDisplaySurfaceRotation(surface.display)
                updateTransform(surface, rotation, it.textureSize, viewFinderDimens)
            }
        }
        ref.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            val s = view as TextureView
            val dimens = Size(right - left, bottom - top)
            Log.d("AutoFitPreview", "Surface layout changed -- Size: $dimens")
            val rotation = getDisplaySurfaceRotation(s.display)
            updateTransform(s, rotation, bufferDimens, dimens)
        }
        displayManager = ref.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager?
        displayManager?.registerDisplayListener(displayListener, null)

        ref.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View?) {
                displayManager?.registerDisplayListener(displayListener, null)
                    ?: Log.e("AutoFitPreview", "DisplayManager is null")
            }
            override fun onViewDetachedFromWindow(view: View?) {
                displayManager?.unregisterDisplayListener(displayListener)
                    ?: Log.e("AutoFitPreview", "DisplayManager is null")
            }
        })
    }

    private fun updateTransform(surface: TextureView?, rotation: Int?, newBufferDimens: Size, newSurfaceDimens: Size) {
        surface ?: return

        if (rotation == viewFinderRotation &&
            Objects.equals(newBufferDimens, bufferDimens) &&
            Objects.equals(newSurfaceDimens, viewFinderDimens)) {
            return
        }

        if (rotation == null) {
            return
        } else {
            viewFinderRotation = rotation
        }

        if (newBufferDimens.width == 0 || newBufferDimens.height == 0) {
            return
        } else {
            bufferDimens = newBufferDimens
        }

        if (newSurfaceDimens.width == 0 || newSurfaceDimens.height == 0) {
            return
        } else {
            viewFinderDimens = newSurfaceDimens
        }

        val matrix = Matrix()

        Log.d("AutoFitPreview", "Applying output transformation:\n" +
                "Surface size: $viewFinderDimens\n" +
                "Preview output size: $bufferDimens\n" +
                "Surface rotation: $viewFinderRotation\n" +
                "Preview output rotation: $bufferRotation")

        val centerX = viewFinderDimens.width / 2f
        val centerY = viewFinderDimens.height / 2f
        matrix.postRotate(-viewFinderRotation!!.toFloat(), centerX, centerY)

        val bufferRatio = bufferDimens.height / bufferDimens.width.toFloat()

        val scaledWidth: Int
        val scaledHeight: Int
        when {
            isHMT() -> {
                scaledWidth = viewFinderDimens.width
                scaledHeight = (viewFinderDimens.width * bufferRatio).roundToInt()
            }
            viewFinderDimens.width > viewFinderDimens.height -> {
                scaledHeight = viewFinderDimens.width
                scaledWidth = (viewFinderDimens.width * bufferRatio).roundToInt()
            }
            else -> {
                scaledHeight = viewFinderDimens.height
                scaledWidth = (viewFinderDimens.height * bufferRatio).roundToInt()
            }
        }

        val scaleX = scaledWidth / viewFinderDimens.width.toFloat()
        val scaleY = scaledHeight / viewFinderDimens.height.toFloat()

        matrix.preScale(scaleX, scaleY, centerX, centerY)

        surface.setTransform(matrix)
    }

    companion object {
        fun getDisplaySurfaceRotation(display: Display?) = when(display?.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> null
        }
        fun build(config: PreviewConfig, surface: TextureView) =
            AutoFitPreviewBuilder(config, WeakReference(surface)).useCase
    }
}