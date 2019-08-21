package ktx.sovereign.camera.renderer.gles

import android.content.Context
import android.opengl.*
import android.util.Log
import androidx.annotation.RawRes
import java.io.BufferedReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.opengles.GL10

object GLUtil {
    val TAG: String = GLUtil::class.java.simpleName
    const val SIZEOF_FLOAT: Int = 4
    const val SIZEOF_INT: Int = 4
    val IDENTITY_MATRIX: FloatArray by lazy {
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)
        matrix
    }

    fun createProgramFromRaw(context: Context, @RawRes vertexSourceRes: Int, @RawRes fragmentSourceRes: Int): Int =
        createProgram(readSourceFromRaw(context, vertexSourceRes), readSourceFromRaw(context, fragmentSourceRes))

    /**
     * Creates a new GLES program from the supplied vertex and fragment shaders.
     *
     * @return A handle to the program, or 0 on failure.
     */
    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) { return 0 }

        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) { return 0 }

        var program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        if (program == 0) { Log.e(TAG, "Could not create program") }

        GLES20.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader")
        GLES20.glAttachShader(program, fragmentShader)
        checkGlError("glAttachShader")
        GLES20.glLinkProgram(program)
        checkGlError("glLinkProgram")

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link Program: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            program = 0
        }
        return program
    }

    /**
     * Compiles the provided shader source.
     *
     * @return A handle to the shader, or 0 on failure
     */
    fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
//        checkGlError("glCompileShader type=$shaderType")
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader $shaderType: ")
            Log.e(TAG, GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }

    /**
     * Checks to see if a GLES error has been raised.
     */
    fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            val msg = "$op: glError 0x${error.toString(16)}"
            throw RuntimeException(msg)
        }
    }

    /**
     * Checks to see if the location we obtained is valid. GLES return -1 if a label
     * could not be found, but does not set the GL error
     *
     * Throws a [RuntimeException] if the location is invalid.
     */
    fun checkLocation(location: Int, label: String) {
        if (location < 0) {
            throw RuntimeException("Unable to locate '$label' in program")
        }
    }

    /**
     * Creates a texture from raw data.
     *
     * @param data Image data, in a "direct" [ByteBuffer]
     * @param width Texture width, in pixels (not bytes)
     * @param height Texture height, in pixels (not bytes)
     * @param format Image data format (use constant appropriate for [GLES20.glTexImage2D], e.g. [GLES20.GL_RGBA]
     * @return Handle to texture
     */
    fun createImageTexture(data: ByteBuffer, width: Int, height: Int, format: Int): Int {
        val textureHandles = IntArray(1)
        GLES20.glGenTextures(1, textureHandles, 0)
        val handle = textureHandles[0]
        checkGlError("glGenTextures")

        // Bind the texture handle to the 2D texture target
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handle)

        // Configure min/mag filtering, i.e. what scaling method do we use if we're rendering
        // is smaller or larger than the source image.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        checkGlError("loadImageTexture")

        // Load the data from the buffer into the texture handle
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, /*level*/ 0, format,
            width, height, /*border*/ 0, format, GLES20.GL_UNSIGNED_BYTE, data)
        checkGlError("loadImageTexture")

        return handle
    }

    fun genTexture(type: Int = GLES20.GL_TEXTURE_2D): Int {
        val textureHandle = IntArray(1)
        GLES20.glGenTextures(1, textureHandle, 0)
//        checkGlError("glGenTextures")
        GLES20.glBindTexture(type, textureHandle[0])

        if (type == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE)
        } else {
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_REPEAT)
        }

        return textureHandle[0]
    }

    /**
     * Allocates a direct [FloatBuffer], and populates it with the float array data.
     */
    fun createFloatBuffer(coords: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(coords.size * SIZEOF_FLOAT)
        bb.order(ByteOrder.nativeOrder())
        return bb.asFloatBuffer().apply {
            put(coords)
            position(0)
        }
    }

    /**
     * Writes GL version info to the log.
     */
    fun logVersionInfo() {
        Log.i(TAG, "vendor  : ${GLES20.glGetString(GLES20.GL_VENDOR)}")
        Log.i(TAG, "renderer: ${GLES20.glGetString(GLES20.GL_RENDERER)}")
        Log.i(TAG, "version : ${GLES20.glGetString(GLES20.GL_VERSION)}")

        val values = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, values, 0)
        GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, values, 1)
        if (GLES30.glGetError() == GLES30.GL_NO_ERROR) {
            Log.i(TAG, "iversion: ${values[0]}.${values[1]}")
        }
    }

    private fun readSourceFromRaw(context: Context, @RawRes shaderRes: Int): String {
        return context.resources.openRawResource(shaderRes).bufferedReader().use(BufferedReader::readText)
    }
}