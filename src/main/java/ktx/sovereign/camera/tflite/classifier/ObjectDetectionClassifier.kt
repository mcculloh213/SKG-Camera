package ktx.sovereign.camera.tflite.classifier

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF

class ObjectDetectionClassifier(
    context: Context,
    device: Device,
    numThreads: Int = NUM_THREADS
) : Classifier(context, device, numThreads) {
    private val detections: FloatArray = FloatArray(1)
    private val classes: Array<out FloatArray> = Array(1) { FloatArray(NUM_DETECTIONS) }
    private val scores: Array<out FloatArray> = Array(1) { FloatArray(NUM_DETECTIONS) }
    private val locations: Array<out Array<out FloatArray>> = Array(1) {
        Array(NUM_DETECTIONS) { FloatArray(4) }
    }
    private val output: MutableMap<Int, Any> = HashMap()
    override fun getImageSizeX(): Int = 300
    override fun getImageSizeY(): Int = 300
    override fun getModelPath(): String = "detect.tflite"
    override fun getLabelPath(): String = "labelmap.txt"
    override fun getNumBytesPerChannel(): Int = 1 // Is Quantized Model.
    override fun addPixelValue(pixelValue: Int) {
        imageBuffer?.apply {
            put(((pixelValue shr 16) and 0xFF).toByte())
            put(((pixelValue shr 8) and 0xFF).toByte())
            put((pixelValue and 0xFF).toByte())
        }
    }
    override fun getProbability(index: Int): Float = scores[0][index]
    override fun setProbability(index: Int, value: Number) {}
    override fun getNormalizedProbability(index: Int): Float = scores[0][index]
    override fun runInference() {
        output.clear()
        trace("Set Outputs") {
            output[0] = locations
            output[1] = classes
            output[2] = scores
            output[3] = detections
        }
        trace("Run Inference") {
            imageBuffer?.let {
                val input = arrayOf(it)
                model.runForMultipleInputsOutputs(input, output)
            }
        }
    }
    override fun buildResults(results: MutableList<Result>) {
        for (i in 0 until NUM_DETECTIONS) {
            val rect = RectF(
                locations[0][i][1] * getImageSizeY(),
                locations[0][i][0] * getImageSizeX(),
                locations[0][i][3] * getImageSizeY(),
                locations[0][i][2] * getImageSizeX()
            )
            results.add(Result(
                i.toString(),
                labels[(classes[0][i]).toInt() + 1],
                scores[0][i],
                rect
            ))
        }
    }
    companion object {
        private const val NUM_DETECTIONS = 10
        private const val NUM_THREADS = 4
    }
}