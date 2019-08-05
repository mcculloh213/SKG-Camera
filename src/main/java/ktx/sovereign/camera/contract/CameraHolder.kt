package ktx.sovereign.camera.contract

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope

interface CameraHolder : CoroutineScope {
    val activeRegion: Rect

    fun createSurface(context: Context, parent: ViewGroup): View?
    fun bindDisplayId(displayId: Int)
    fun startCamera()
    fun startCamera(lifecycle: LifecycleOwner)
    fun updateTransform()
    suspend fun setZoom(scale: Float): Rect
    fun capture()
    fun closeCamera()
}