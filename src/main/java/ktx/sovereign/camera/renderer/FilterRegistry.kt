package ktx.sovereign.camera.renderer

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.SparseIntArray
import androidx.annotation.IdRes
import ktx.sovereign.camera.R
import ktx.sovereign.camera.renderer.gles.GLUtil

class FilterRegistry(
    context: Context,
    renderingSurface: SurfaceTexture,
    width: Int,
    height: Int
) : GLESCameraRenderer(context, renderingSurface, width, height) {
    private val registry: SparseIntArray = SparseIntArray()
    @IdRes private var active: Int = R.id.filter_default

    fun setActiveFilter(@IdRes filterIdRes: Int) {
        active = filterIdRes
        program = registry[filterIdRes, R.id.filter_default]
    }

    override fun setupShaders() {
        loadFilters()
        program = registry[active]
    }

    private fun loadFilters() {
        registry.apply {
            put(R.id.filter_default, GLUtil.createProgramFromRaw(context, R.raw.vertex, R.raw.camera_frag))
            put(R.id.filter_blue_orange, GLUtil.createProgramFromRaw(context, R.raw.vertex, R.raw.blue_orange_frag))
            put(R.id.filter_edge_detection, GLUtil.createProgramFromRaw(context, R.raw.vertex, R.raw.edge_derivative))
            put(R.id.filter_touch_color, GLUtil.createProgramFromRaw(context, R.raw.vertex, R.raw.touchcolor_frag))
        }
    }
}