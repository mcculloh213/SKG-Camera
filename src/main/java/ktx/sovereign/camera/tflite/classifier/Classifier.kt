package ktx.sovereign.camera.tflite.classifier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import ktx.sovereign.camera.tflite.util.ResultConfidenceComparator
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import kotlin.math.min

abstract class Classifier protected constructor(
    context: Context,
    device: Device,
    numThreads: Int
) {
    enum class Device { CPU, NNAPI, GPU }
    enum class Model { FLOAT, QUANTIZED }
    data class Result(
        val id: String,
        val title: String,
        val confidence: Float,
        val location: RectF?
    )
    private val values: IntArray by lazy { IntArray(getImageSizeX() * getImageSizeY()) }
    private val options: Interpreter.Options = Interpreter.Options()
    private var gpuDelegate: GpuDelegate? = null
    protected val labels: MutableList<String> = mutableListOf()
    protected var imageBuffer: ByteBuffer? = null
    private val modelBuffer: MappedByteBuffer
    protected val model: Interpreter

    init {
        modelBuffer = loadModel(context) ?: throw RuntimeException("Unable to load TFLite Model.")
        when (device) {
            Device.NNAPI -> options.setUseNNAPI(true)
            Device.GPU -> gpuDelegate = GpuDelegate().also { gpu -> options.addDelegate(gpu) }
            else -> { }
        }
        options.setNumThreads(numThreads)
        model = Interpreter(modelBuffer, options)
        labels.apply {
            clear()
            addAll(loadLabels(context))
        }
        initImageBuffer()
    }

    fun performRecognition(bitmap: Bitmap): List<Result> {
        val results = mutableListOf<Result>()
        trace("Recognize Image") {
            trace("Preprocessing") {
                bitmap.copyToByteBuffer()
            }
            trace("Inference") {
                val startTime = SystemClock.uptimeMillis()
                runInference()
                val endTime = SystemClock.uptimeMillis()
                Log.v("Runtime", "Time to run model inference: ${endTime - startTime}")
            }
            trace("Build Results") {
                buildResults(results)
            }
        }
        return results
    }
    protected open fun buildResults(results: MutableList<Result>) {
        val queue = PriorityQueue<Result>(3, ResultConfidenceComparator())
        labels.forEachIndexed { idx, label ->
            queue.add(
                Result(
                    idx.toString(),
                    label,
                    getNormalizedProbability(idx),
                    null
                )
            )
        }
        val size = min(queue.size, MAX_RESULTS)
        for (i in 0 until size) {
            val result = queue.poll() ?: continue
            results.add(result)
        }
    }
    fun close() {
        model.close()
        gpuDelegate?.apply { close() }
        gpuDelegate = null
    }

    abstract fun getImageSizeX(): Int
    abstract fun getImageSizeY(): Int
    abstract fun getModelPath(): String
    abstract fun getLabelPath(): String
    protected abstract fun getNumBytesPerChannel(): Int
    protected abstract fun addPixelValue(pixelValue: Int)
    protected abstract fun getProbability(index: Int): Float
    protected abstract fun setProbability(index: Int, value: Number)
    protected abstract fun getNormalizedProbability(index: Int): Float
    protected abstract fun runInference()

    protected fun initImageBuffer() {
        imageBuffer = ByteBuffer.allocateDirect(
            DIM_BATCH_SIZE * getImageSizeX() * getImageSizeY()
                    * DIM_PIXEL_SIZE * getNumBytesPerChannel()
        ).apply { order(ByteOrder.nativeOrder()) }
    }
    protected fun getNumLabels(): Int = labels.size
    @Throws(IOException::class)
    private fun loadLabels(context: Context): List<String> {
        val labels = mutableListOf<String>()
        context.assets.open(getLabelPath()).bufferedReader().useLines { lines ->
            labels.addAll(lines)
        }
        return labels
    }
    @Throws(IOException::class)
    private fun loadModel(context: Context): MappedByteBuffer? {
        val afd = context.assets.openFd(getModelPath())
        var buffer: MappedByteBuffer? = null
        FileInputStream(afd.fileDescriptor).use { input ->
            buffer = input.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
        return buffer
    }
    private fun Bitmap.copyToByteBuffer() {
        val b = imageBuffer ?: return
        b.rewind()
        getPixels(values, 0, width, 0, 0, width, height)
        var px = 0
        val startTime = SystemClock.uptimeMillis()
        for (i in 0 until getImageSizeX()) {
            for (j in 0 until getImageSizeY()) {
                val v = values[px++]
                addPixelValue(v)
            }
        }
        val endTime = SystemClock.uptimeMillis()
        Log.v("Runtime", "Time to put values into ByteBuffer: ${endTime - startTime}")
    }
    protected fun trace(section: String, block: () -> Unit) {
        Trace.beginSection(section)
        block.invoke()
        Trace.endSection()
    }
    interface ResultsCallback {
        fun onFrameClassified(results: List<Result>)
    }
    companion object {
        @Throws(IOException::class)
        @JvmStatic fun create(context: Context, model: Model, device: Device, numThreads: Int) = when (model) {
            Model.QUANTIZED -> ObjectDetectionClassifier(context, device, numThreads)
            Model.FLOAT -> FloatMobileNetClassifier(context, device, numThreads)
        }
        @JvmStatic val IMAGE_MEAN: Float = 127.5f
        @JvmStatic val IMAGE_STD: Float = 127.5f
        const val DIM_IMG_SIZE_X: Int = 224
        const val DIM_IMG_SIZE_Y: Int = 224

        @JvmStatic private val TFLITE_MODEL: String = ""
        @JvmStatic private val TFLITE_LABELS: String = ""

        private const val DIM_BATCH_SIZE: Int = 1
        private const val DIM_PIXEL_SIZE: Int = 3
        private const val FILTER_STAGES: Int = 3
        private const val FILTER_FACTOR: Float = 0.4f
        private const val MAX_RESULTS: Int = 3
    }
}