package ktx.sovereign.camera.renderer.filter

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.util.Log
import ktx.sovereign.camera.R
import ktx.sovereign.camera.renderer.GLESCameraRenderer
import ktx.sovereign.camera.renderer.gles.GLUtil

class TouchColorFilter(
    context: Context,
    renderingSurface: SurfaceTexture,
    width: Int,
    height: Int
) : GLESCameraRenderer(context, renderingSurface, width, height) {
    private var offsetR: Float = 0.5f
    private var offsetG: Float = 0.5f
    private var offsetB: Float = 0.5f

    override fun setupShaders() {
        program = GLUtil.createProgramFromRaw(context, R.raw.vertex, R.raw.touchcolor_frag)
    }
    override fun setUniformsAndAttributes() {
        super.setUniformsAndAttributes()
        val redHandle = GLES20.glGetUniformLocation(program, "offsetR")
        val greenHandle = GLES20.glGetUniformLocation(program, "offsetG")
        val blueHandle = GLES20.glGetUniformLocation(program, "offsetB")

        GLES20.glUniform1f(redHandle, offsetR)
        GLES20.glUniform1f(greenHandle, offsetG)
        GLES20.glUniform1f(blueHandle, offsetB)
    }

    fun setTouchPoint(rawX: Float, rawY: Float) {
        offsetR = rawX / viewport.width
        offsetG = rawY / viewport.height
        offsetB = offsetR / offsetG
    }
}