package ktx.sovereign.camera.contract

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope

interface CameraHolder : CoroutineScope {
    fun createSurface(context: Context, parent: ViewGroup): View?
    fun bindDisplayId(displayId: Int)
    fun startCamera()
    fun startCamera(lifecycle: LifecycleOwner)
    fun updateTransform()
    fun setZoom(scale: Float)
    fun capture()
    fun closeCamera()
}