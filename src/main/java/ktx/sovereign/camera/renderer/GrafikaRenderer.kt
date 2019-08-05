package ktx.sovereign.camera.renderer

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import ktx.sovereign.camera.renderer.gles.EglCore
import ktx.sovereign.camera.renderer.gles.WindowSurface

class GrafikaRenderer(
    private val onRender: ((WindowSurface, SurfaceTexture) -> Unit)? = null
) : Thread("[GrafikaRenderer]"), TextureView.SurfaceTextureListener {
    private val lock = Object()
    private var texture: SurfaceTexture? = null
    private lateinit var eglCore: EglCore
    private var done: Boolean = false

    override fun run() {
        while (true) {
            var surface: SurfaceTexture? = null
            synchronized(lock) {
                while (!done && surface == null) {
                    surface = texture
                    try {
                        lock.wait()
                    } catch (ex: InterruptedException) {
                        throw RuntimeException(ex)
                    }
                }
            }
            if (done) {
                break
            }
            Log.d("GrafikaRenderer", "Got SurfaceTexture: $surface")

            eglCore = EglCore(null, EglCore.FLAG_TRY_GLES3)
            val window = WindowSurface(eglCore, surface).also {
                it.makeCurrent()
            }
        }
    }

    fun halt() {
        synchronized(lock) {
            done = true
            lock.notify()
        }
    }

    override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int) {

    }
    override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) {

    }
    override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean {
        return false
    }
    override fun onSurfaceTextureAvailable(p0: SurfaceTexture?, p1: Int, p2: Int) {

    }
}