package ktx.sovereign.camera.renderer.gles

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

open class EglSurfaceBase(protected var eglCore: EglCore) {
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    /**
     * Returns the surface's width, in pixels.
     *
     *
     * If this is called on a window surface, and the underlying surface is in the process
     * of changing size, we may not see the new size right away (e.g. in the "surfaceChanged"
     * callback).  The size should match after the next buffer swap.
     */
    var width: Int = -1
        get() = if (field < 0) {
            eglCore.querySurface(eglSurface, EGL14.EGL_WIDTH)
        } else {
            field
        }
        private set(value) {
            field = value
        }
    /**
     * Returns the surface's height, in pixels.
     *
     *
     * If this is called on a window surface, and the underlying surface is in the process
     * of changing size, we may not see the new size right away (e.g. in the "surfaceChanged"
     * callback).  The size should match after the next buffer swap.
     */
    var height: Int = -1
        get() = if (field < 0) {
            eglCore.querySurface(eglSurface, EGL14.EGL_HEIGHT)
        } else {
            field
        }
        private set(value) {
            field = value
        }

    /**
     * Creates a window surface
     *
     * @param surface May be a [Surface] or [SurfaceTexture]
     */
    fun createWindowSurface(surface: Any) {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("EGLSurface has already been created")
        }
        eglSurface = eglCore.createWindowSurface(surface)
    }

    /**
     * Creates an off-screen surface
     */
    fun createOffscreenSurface(width: Int, height: Int) {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("EGLSurface has already been created")
        }
        eglSurface = eglCore.createOffscreenSurface(width, height)
        this@EglSurfaceBase.width = width
        this@EglSurfaceBase.height = height
    }

    fun releaseEglSurface() {
        eglCore.releaseSurface(eglSurface)
        eglSurface = EGL14.EGL_NO_SURFACE
        width = -1
        height = -1
    }

    /**
     * Makes our EGL context and surface current.
     */
    fun makeCurrent() {
        eglCore.makeCurrent(eglSurface)
    }

    /**
     * Makes our EGL context and surface current for drawing, using the supplied surface
     * for reading.
     */
    fun makeCurrentReadFrom(surface: EglSurfaceBase) {
        eglCore.makeCurrent(eglSurface, surface.eglSurface)
    }

    /**
     * Calls eglSwapBuffers.  Use this to "publish" the current frame.
     *
     * @return false on failure
     */
    fun swapBuffers(): Boolean {
        val result = eglCore.swapBuffers(eglSurface)
        if (!result) {
            Log.d(GLUtil.TAG, "WARNING: swapBuffers() failed")
        }
        return result
    }

    /**
     * Sends the presentation time stamp to EGL.
     *
     * @param ns Timestamp, in nanoseconds.
     */
    fun setPresentationTime(ns: Long) {
        eglCore.setPresentationTime(eglSurface, ns)
    }

    /**
     * Saves the EGL surface to a file.
     *
     * Expects that this object's EGL surface is current.
     *
     * glReadPixels fills in a "direct" ByteBuffer with what is essentially big-endian RGBA
     * data (i.e. a byte of red, followed by a byte of green...).  While the Bitmap
     * constructor that takes an int[] wants little-endian ARGB (blue/red swapped), the
     * Bitmap "copy pixels" method wants the same format GL provides.
     *
     * Ideally we'd have some way to re-use the ByteBuffer, especially if we're calling
     * here often.
     *
     * Making this even more interesting is the upside-down nature of GL, which means
     * our output will look upside down relative to what appears on screen if the
     * typical GL conventions are used.
     */
    @Throws(IOException::class)
    fun saveFrame(file: File) {
        if (!eglCore.isCurrent(eglSurface)) {
            throw RuntimeException("Expected EGL context/surface is not current")
        }

        val w = width
        val h = height
        val bb = ByteBuffer.allocateDirect(width * height * 4)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
        GLUtil.checkGlError("glReadPixels")
        bb.rewind()

        with (BufferedOutputStream(FileOutputStream(file))) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                bmp.copyPixelsFromBuffer(bb)
                bmp.compress(Bitmap.CompressFormat.PNG, 100, this)
                bmp.recycle()
            }
        }
        Log.d(GLUtil.TAG, "Saved $w x $h frame as '${file.name}'")
    }
}