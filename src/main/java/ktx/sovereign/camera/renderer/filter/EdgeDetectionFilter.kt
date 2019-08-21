package ktx.sovereign.camera.renderer.filter

import android.content.Context
import android.graphics.SurfaceTexture
import ktx.sovereign.camera.R
import ktx.sovereign.camera.renderer.GLESCameraRenderer
import ktx.sovereign.camera.renderer.gles.GLUtil

class EdgeDetectionFilter(
    context: Context,
    renderingSurface: SurfaceTexture,
    width: Int,
    height: Int
) : GLESCameraRenderer(context, renderingSurface, width, height) {
    override fun setupShaders() {
        program = GLUtil.createProgramFromRaw(context, R.raw.vertex, R.raw.edge_derivative)
    }
}