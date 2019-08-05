package ktx.sovereign.camera.renderer.gles

import android.graphics.SurfaceTexture
import android.view.Surface

class WindowSurface(
    eglCore: EglCore,
    private var surface: Any?,
    private val releaseSurface: Boolean = false
) : EglSurfaceBase(eglCore) {
    init {
        surface?.let {
            when (it) {
                is Surface, is SurfaceTexture -> createWindowSurface(it)
                else -> throw IllegalArgumentException(
                    "Invalid parameter type 'surface'. " +
                            "Expected either Surface or Surface Texture, but " +
                            "received ${surface?.javaClass?.simpleName ?: "null"}"
                )
            }
        }
    }
    /**
     * Releases any resources associated with the EGL surface (and, if configured to do so,
     * with the Surface as well).
     * <p>
     * Does not require that the surface's EGL context be current.
     */
    fun release() {
        releaseEglSurface()
        surface?.let {
            if (it is Surface && releaseSurface) {
                it.release()
            }
        }
        surface = null
    }
    /**
     * Recreate the EGLSurface, using the new EglBase.  The caller should have already
     * freed the old EGLSurface with releaseEglSurface().
     *
     * This is useful when we want to update the EGLSurface associated with a Surface.
     * For example, if we want to share with a different EGLContext, which can only
     * be done by tearing down and recreating the context.  (That's handled by the caller;
     * this just creates a new EGLSurface for the Surface we were handed earlier.)
     *
     * If the previous EGLSurface isn't fully destroyed, e.g. it's still current on a
     * context somewhere, the create call will fail with complaints from the Surface
     * about already being connected.
     */
    fun recreate(eglCore: EglCore) {
        this@WindowSurface.eglCore = eglCore
        surface?.let { createWindowSurface(it) }
    }
}