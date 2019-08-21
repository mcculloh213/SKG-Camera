package ktx.sovereign.camera.renderer

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.util.Rational
import android.util.Size
import androidx.annotation.CallSuper
import ktx.sovereign.camera.R
import ktx.sovereign.camera.renderer.gles.EglCore
import ktx.sovereign.camera.renderer.gles.GLUtil
import ktx.sovereign.camera.renderer.gles.WindowSurface
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

open class GLESCameraRenderer(
    protected val context: Context,
    private val renderingSurface: SurfaceTexture,
    width: Int,
    height: Int
) : Thread(), SurfaceTexture.OnFrameAvailableListener {
    private val eglCore: EglCore = EglCore(null, EglCore.FLAG_RECORDABLE or EglCore.FLAG_TRY_GLES3)
    private val window: WindowSurface by lazy { WindowSurface(eglCore, renderingSurface) }
    val previewSurface: SurfaceTexture
        get() {
            var s = cameraSurface
            if (s == null) {
                Log.i("Preview", "Init w/handle ${textureHandles[0]}")
                s = SurfaceTexture(textureHandles[0]).also {
                    cameraSurface = it
                }
            }
            return s
        }
    private val textureHandles: IntArray = IntArray(MAX_TEXTURES)
    private val transformMatrix: FloatArray = FloatArray(16)
    private val projectionMatrix: FloatArray = FloatArray(16)
    private val textures: ArrayList<Texture> = arrayListOf()

    lateinit var handler: RenderHandler

    protected var viewport: Size = Size(width, height)
    protected var aspectRatio: Rational = Rational(width, height)
    protected var program: Int = 0
    private var textureCoordinateHandle: Int = 0
    private var positionHandle: Int = 0
    private var renderListener: OnRendererReadyListener? = null
    private var cameraSurface: SurfaceTexture? = null

    private lateinit var textureBuffer: FloatBuffer
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var drawListBuffer: ShortBuffer


    private var frame: Int = 0
    init {
        name = "GLESCameraRenderer"
    }

    fun setOnRendererReadyListener(listener: OnRendererReadyListener) {
        this@GLESCameraRenderer.renderListener = listener
    }
    fun setViewport(width: Int, height: Int) {
        viewport = Size(width, height)
        aspectRatio = Rational(width, height)
    }
    fun setCameraPreview(surface: SurfaceTexture) {
        cameraSurface?.release()
        cameraSurface = surface.also {
            Log.i("Preview", "Attaching to GLES Context ${textureHandles[0]}")
            it.attachToGLContext(textureHandles[0])
            it.setOnFrameAvailableListener(this@GLESCameraRenderer)
        }
    }

    @Synchronized
    override fun start() {
        initializeRenderer()
        super.start()
    }
    override fun run() {
        Looper.prepare()
        handler = RenderHandler(this)

        initGL()
        Looper.loop()
        strikeGL()

        renderListener?.onRendererFinished()
    }

    fun initGL() {
        window.makeCurrent()
        initGLComponents()
    }
    fun strikeGL() {
        deinitGLComponents()

        window.release()
        eglCore.release()
    }
    fun shutdown() {
        Looper.myLooper()?.quit()
    }

    protected fun updatePreviewTexture() {
        while (frame > 0) {
            previewSurface.updateTexImage()
            previewSurface.getTransformMatrix(transformMatrix)
            frame--
        }
    }
    fun draw() {
        Matrix.orthoM(projectionMatrix, 0, -aspectRatio.toFloat(), aspectRatio.toFloat(), -1f, 1f, -1f, 1f)
        GLES20.glViewport(0, 0, viewport.width, viewport.height) // TODO: Should be viewport width/height
        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 0.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        setUniformsAndAttributes()
        setExtraTextures()
        drawElements()
        onDrawCleanup()
    }
    protected open fun setUniformsAndAttributes() {
        val textureParamHandle = GLES20.glGetUniformLocation(program, "cameraTexture")
        val textureTransformHandle = GLES20.glGetUniformLocation(program, "mCameraTextureTransform")
        val projectionMatrixHandle = GLES20.glGetUniformLocation(program, "uPMatrix")
        textureCoordinateHandle = GLES20.glGetAttribLocation(program, "vCameraTextureCoordinate")
        positionHandle = GLES20.glGetAttribLocation(program, "vPosition")

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 4 * 2, vertexBuffer)

        // Camera texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureHandles[0])
        GLES20.glUniform1i(textureParamHandle, 0)

        GLES20.glEnableVertexAttribArray(textureCoordinateHandle)
        GLES20.glVertexAttribPointer(textureCoordinateHandle, 2, GLES20.GL_FLOAT, false, 4 * 2, textureBuffer)

        GLES20.glUniformMatrix4fv(textureTransformHandle, 1, false, transformMatrix, 0)
        if (projectionMatrixHandle != -1) {
            GLES20.glUniformMatrix4fv(projectionMatrixHandle, 1, false, projectionMatrix, 0)
        }
    }
    protected fun setExtraTextures() {
        textures.forEach { texture ->
            val handle = GLES20.glGetUniformLocation(program, texture.uniformName)

            GLES20.glActiveTexture(texture.texId)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandles[texture.texNum])
            GLES20.glUniform1i(handle, texture.texNum)
        }
    }
    protected fun drawElements() {
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, DRAW_ORDER.size, GLES20.GL_UNSIGNED_SHORT, drawListBuffer)
    }
    protected fun onDrawCleanup() {
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureCoordinateHandle)
    }
    protected fun initGLComponents() {
        prepareGLComponents()

        setupVertexBuffer()
        setupTextures()
        setupCameraTexture()
        setupShaders()

        onSetupComplete()
    }
    protected fun deinitGLComponents() {
        GLES20.glDeleteTextures(MAX_TEXTURES, textureHandles, 0)
        GLES20.glDeleteProgram(program)

        previewSurface.release()
        previewSurface.setOnFrameAvailableListener(null)
    }
    @CallSuper
    protected open fun prepareGLComponents() { }
    protected open fun setupVertexBuffer() {
        drawListBuffer = ByteBuffer.allocateDirect(DRAW_ORDER.size * 2).apply {
            order(ByteOrder.nativeOrder())
        }.asShortBuffer()
        with (drawListBuffer) {
            put(DRAW_ORDER)
            position(0)
        }

        vertexBuffer = ByteBuffer.allocateDirect(SQUARE_COORDS.size * 4).apply {
            order(ByteOrder.nativeOrder())
        }.asFloatBuffer()
        with (vertexBuffer) {
            put(SQUARE_COORDS)
            position(0)
        }
    }
    protected open fun setupTextures() {
        textureBuffer = ByteBuffer.allocateDirect(TEXTURE_COORDS.size * 4).apply {
            order(ByteOrder.nativeOrder())
        }.asFloatBuffer()
        with (textureBuffer) {
            put(TEXTURE_COORDS)
            position(0)
        }

        GLES20.glGenTextures(MAX_TEXTURES, textureHandles, 0)
        GLUtil.checkGlError("glGenTextures")
    }
    protected open fun setupCameraTexture() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureHandles[0])
        GLUtil.checkGlError("Texture bind")

        previewSurface.setOnFrameAvailableListener(this@GLESCameraRenderer)
    }
    protected open fun setupShaders() {
        program = GLUtil.createProgramFromRaw(context, R.raw.vertex, R.raw.camera_frag)
    }
    protected fun onSetupComplete() = renderListener?.onRendererReady() ?: Unit

    private fun initializeRenderer() {
        // setup camera
        // setup media recorder
        // set initial viewport

        // load shaders
    }

    override fun onFrameAvailable(surface: SurfaceTexture?) {
        val swap: Boolean
        frame++

        synchronized(this) {
            updatePreviewTexture()
            swap = when (eglCore.glVersion) {
                3 -> gles3FrameAvailable()
                2 -> gles2FrameAvailable()
                else -> false
            }
            if (!swap) {
                Log.e("GLES Draw", "Buffer swap failed, killing renderer thread.")
                shutdown()
            }
        }
    }

    private fun gles3FrameAvailable(): Boolean {
        draw()
        // handle misc recording details we don't need yet
        window.makeCurrent()
        return window.swapBuffers()
    }
    private fun gles2FrameAvailable(): Boolean {
        draw()
        // handle misc recording details we don't need yet
        window.makeCurrent()
        return window.swapBuffers()
    }

    private data class Texture (
        val texNum: Int,
        val texId: Int,
        val uniformName: String
    )
    class RenderHandler(
        renderer: GLESCameraRenderer
    ) : Handler() {
        companion object {
            const val MSG_SHUTDOWN: Int = 0
        }
        private val ref: WeakReference<GLESCameraRenderer> = WeakReference(renderer)

        fun sendShutdown() {
            sendMessage(obtainMessage(MSG_SHUTDOWN))
        }

        override fun handleMessage(msg: Message) {
            val renderer = ref.get() ?: return

            when (msg.what) {
                MSG_SHUTDOWN -> renderer.shutdown()
                else -> throw RuntimeException("Unknown message ${msg.what}")
            }
        }
    }
    interface OnRendererReadyListener {
        /**
         * Called when [onSetupComplete] is finished.
         */
        fun onRendererReady()

        /**
         * Called once the looper is killed and [run] completes.
         */
        fun onRendererFinished()
    }
    companion object {
        const val MAX_TEXTURES: Int = 16
        @JvmStatic val GL_TEXTURES: IntArray = intArrayOf(
            GLES20.GL_TEXTURE1, GLES20.GL_TEXTURE2, GLES20.GL_TEXTURE3,
            GLES20.GL_TEXTURE4, GLES20.GL_TEXTURE5, GLES20.GL_TEXTURE6,
            GLES20.GL_TEXTURE7, GLES20.GL_TEXTURE8, GLES20.GL_TEXTURE9,
            GLES20.GL_TEXTURE10, GLES20.GL_TEXTURE11, GLES20.GL_TEXTURE12,
            GLES20.GL_TEXTURE13, GLES20.GL_TEXTURE14, GLES20.GL_TEXTURE15,
            GLES20.GL_TEXTURE16
        )
        @JvmStatic val SQUARE_COORDS: FloatArray = floatArrayOf(
            -1.0f,  1.0f,   // Top-Left
             1.0f,  1.0f,   // Top-Right
            -1.0f, -1.0f,   // Bottom-Left
             1.0f, -1.0f    // Bottom-Right
        )
        @JvmStatic val DRAW_ORDER: ShortArray = shortArrayOf(0, 1, 2, 1, 3, 2)
        @JvmStatic val TEXTURE_COORDS: FloatArray = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
        )
        const val VIDEO_BIT_RATE: Int = 10_000_000
        const val VIDEO_WIDTH: Int = 720
        const val VIDEO_HEIGHT: Int = 1_280
    }
}