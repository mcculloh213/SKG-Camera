package ktx.sovereign.camera.contract

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import ktx.sovereign.camera.renderer.GLESCameraRenderer
import java.io.File

interface CameraHolder : CoroutineScope {
    val texture: TextureView?
    val activeRegion: Rect
    fun setCameraStateListener(listener: CameraStateListener)
    fun createSurface(context: Context, parent: ViewGroup): View?
    fun bindDisplayId(displayId: Int)
    fun setRenderer(renderer: GLESCameraRenderer?)
    fun startCamera()
    fun startCamera(lifecycle: LifecycleOwner)
    fun updateTransform()
    fun setZoom(scale: Float)
    suspend fun setZoomAsync(scale: Float): Rect
    fun toggleFreeze()
    fun toggleFreeze(lifecycle: LifecycleOwner)
    fun capture()
    fun closeCamera()

    interface CameraStateListener {
        fun onImageSaved(file: File)
        fun onBitmapSaved(bitmap: Array<out Bitmap>)
    }
}