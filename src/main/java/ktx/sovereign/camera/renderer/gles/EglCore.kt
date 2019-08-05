package ktx.sovereign.camera.renderer.gles

import android.graphics.SurfaceTexture
import android.opengl.*
import android.util.Log
import android.view.Surface
import java.lang.Exception

/**
 * Core EGL State ([EGLDisplay], [EGLContext], [EGLConfig]).
 *
 * The [EGLContext] must only be attached to one thread at a time, and is therefore not thread-safe.
 *
 * @param sharedContext The [EGLContext] to share, or null if sharing is not desired.
 * @param flags         Configuration bit flags, e.g. [FLAG_RECORDABLE]
 */
class EglCore @JvmOverloads constructor(sharedContext: EGLContext? = null, flags: Int = 0) {
    companion object {
        @JvmStatic private val TAG: String = EglCore::class.java.simpleName
        /**
         * Constructor flag: Surface must be recordable. This discourages EGL from using a
         * pixel format that cannot be converted effeciently to something usable by the video
         * encoder.
         */
        const val FLAG_RECORDABLE: Int = 0x01
        /**
         * Constructor flag: Ask for GLES3, fall back to GLES2 if not available. Without this
         * flag, GLES2 is used.
         */
        const val FLAG_TRY_GLES3: Int = 0x02
        /**
         * Android-specific extension
         */
        private const val EGL_RECORDABLE_ANDROID: Int = 0x3142

        /**
         * Writes the current [EGLDisplay], [EGLContext], and [EGLSurface] to the log.
         */
        @JvmStatic fun logCurrent(msg: String) {
            val display = EGL14.eglGetCurrentDisplay()
            val context = EGL14.eglGetCurrentContext()
            val surface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            Log.i(TAG, "Current EGL ($msg): display=$display, context=$context, surface=$surface")
        }
    }
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    var glVersion: Int = -1

    /**
     * Prepares EGL Display & Context
     */
    init {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) { throw RuntimeException("[$eglDisplay] -- EGL is already set up") }
        val ctx = sharedContext ?: EGL14.EGL_NO_CONTEXT

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) { throw RuntimeException("[$eglDisplay] -- Unable to get EGL14 display") }
        val v = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, v, 0, v, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY // example := null
            throw RuntimeException("[$eglDisplay] -- Unable to initialize EGL14 display")
        }

        // Try to get a GLES3 sharedContext, if requested
        if ((flags and FLAG_TRY_GLES3) != 0) {
            getConfig(flags, 3)?.let { config ->
                val attrib3_list = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL14.EGL_NONE
                )
                val context = EGL14.eglCreateContext(eglDisplay, config, sharedContext, attrib3_list, 0)
                if (EGL14.eglGetError() == EGL14.EGL_SUCCESS) {
                    eglConfig = config
                    eglContext = context
                    glVersion = 3
                }
            }
        }

        // GLES2 only, or GLES3 attempt failed
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            getConfig(flags, 2)?.let { config ->
                val attrib2_list = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
                )
                val context = EGL14.eglCreateContext(eglDisplay, config, sharedContext, attrib2_list, 0)
                checkEglError("eglCreateContext")
                eglConfig = config
                eglContext = context
                glVersion = 2
            } ?: throw RuntimeException("Unable to find a suitable EGLConfig")
        }

        // Confirm with query
        val values = IntArray(1)
        EGL14.eglQueryContext(eglDisplay, eglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, values, 0)
        Log.i(TAG, "EGLContext created! Client version: ${values[0]}")
    }

    /**
     * Destroys the specified surface. Note the [EGLSurface] won't actually be destroyed if it's
     * still current in a context.
     */
    fun releaseSurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    /**
     * Creates an [EGLSurface] associated with a [Surface] or [SurfaceTexture].
     *
     * If this is destined for MediaCodec, the [EGLConfig] should have the "recordable" attribute.
     */
    fun createWindowSurface(surface: Any): EGLSurface {
        when (surface) {
            is Surface, is SurfaceTexture -> {
                val attrib_surface = intArrayOf(
                    EGL14.EGL_NONE
                )
                val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attrib_surface, 0)
                    ?: throw RuntimeException("Failed to create EGL Surface")
                checkEglError("eglCreateWindowSurface")
                return eglSurface
            }
            else -> throw RuntimeException("$surface is not a valid surface")
        }
    }

    /**
     * Creates an [EGLSurface] associated with an offscreen buffer.
     */
    fun createOffscreenSurface(width: Int, height: Int): EGLSurface {
        val attrib_surface = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        val eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, attrib_surface, 0)
            ?: throw RuntimeException("Failed to create EGL Surface")
        checkEglError("eglCreatePbufferSurface")
        return eglSurface
    }

    /**
     * Makes our [EGLContext] current, using the supplied [EGLSurface] for both "draw" and "read".
     */
    fun makeCurrent(eglSurface: EGLSurface) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.d(TAG, "EGL Display has not been set.")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    /**
     * Makes our [EGLContext] current, using separate [EGLSurface]s for "draw" and "read".
     */
    fun makeCurrent(drawSurface: EGLSurface, readSurface: EGLSurface) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.d(TAG, "EGL Display has not been set.")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, drawSurface, readSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent(draw,read) failed")
        }
    }

    /**
     * Makes no [EGLContext] current.
     */
    fun makeNothingCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    /**
     * Calls [EGL14.eglSwapBuffers]. Use this to "publish" the current frame.
     *
     * @return false on failure
     */
    fun swapBuffers(eglSurface: EGLSurface): Boolean = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

    /**
     * Sends the presentation timestamp to EGL. The timestamp is expressed in nanoseconds.
     */
    fun setPresentationTime(eglSurface: EGLSurface, ns: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ns)
    }

    /**
     * Details if our [EGLContext] and the supplied [EGLSurface] are current.
     */
    fun isCurrent(eglSurface: EGLSurface): Boolean =
        eglContext == EGL14.eglGetCurrentContext() &&
                eglSurface == EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)

    /**
     * Performs a simple surface query.
     */
    fun querySurface(eglSurface: EGLSurface, what: Int): Int {
        val value = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, what, value, 0)
        return value[0]
    }

    /**
     * Queries a string value.
     */
    fun queryString(what: Int): String =
        EGL14.eglQueryString(eglDisplay, what)

    /**
     * Discards all resources held by this class, notably the [EGLContext]. This must be
     * called from the thread where teh context was created.
     *
     * On completion, no context will be current.
     */
    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
    }

     protected fun finalize() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                Log.w(TAG, "WARNING: EglCore was not explicitly released -- state may be leaked")
                release()
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error releasing EGL Core")
            ex.printStackTrace()
        }
     }

    /**
     * Finds a suitable [EGLConfig].
     *
     * @param flags     Bit flags from the constructor
     * @param version   GLES version (either 2 or 3)
     */
    private fun getConfig(flags: Int, version: Int): EGLConfig? {
        var renderableType = EGL14.EGL_OPENGL_ES2_BIT
        if (version >= 3) { renderableType = renderableType or EGLExt.EGL_OPENGL_ES3_BIT_KHR }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_STENCIL_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderableType,
            EGL14.EGL_NONE, 0 , EGL14.EGL_NONE          // placeholder for recordable [@-3]
        )

        if ((flags and FLAG_RECORDABLE) != 0) {
            attribList[attribList.size - 3] = EGL_RECORDABLE_ANDROID
            attribList[attribList.size - 2] = 1
        }

        val configs = Array<EGLConfig?>(1) { null }
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            Log.w(TAG, "Unable to find RGB8888 / $version EGLConfig")
            return null
        }
        return configs[0]
    }

    /**
     * Checks for EGL errors. Throws a [RuntimeException] if an error has been raised.
     */
    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException("[$msg] EGL error: 0x${error.toString(16)}")
        }
    }
}