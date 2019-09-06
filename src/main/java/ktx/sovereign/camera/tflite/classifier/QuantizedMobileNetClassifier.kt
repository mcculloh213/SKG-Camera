package ktx.sovereign.camera.tflite.classifier

import android.content.Context

class QuantizedMobileNetClassifier(
    context: Context,
    device: Device,
    numThreads: Int
) : Classifier(context, device, numThreads) {
    private val probabilityMatrix: Array<ByteArray> = arrayOf(ByteArray(getNumLabels()))

    override fun getImageSizeX(): Int = 224
    override fun getImageSizeY(): Int = 224
    override fun getModelPath(): String = MOBILE_NET_QUANTIZED_MODEL
    override fun getLabelPath(): String = "labels.txt"
    override fun getNumBytesPerChannel(): Int = 1
    override fun addPixelValue(pixelValue: Int) {
        imageBuffer?.apply {
            put(((pixelValue shr 16) and 0xFF).toByte())
            put(((pixelValue shr 8) and 0xFF).toByte())
            put((pixelValue and 0xFF).toByte())
        }
    }
    override fun getProbability(index: Int): Float = probabilityMatrix[0][index].toFloat()
    override fun setProbability(index: Int, value: Number) {
        probabilityMatrix[0][index] = value.toByte()
    }
    override fun getNormalizedProbability(index: Int): Float {
        return (probabilityMatrix[0][index].toInt() and 0xFF) / 255.0f
    }
    override fun runInference() = imageBuffer?.let { buffer -> model.run(buffer, probabilityMatrix) } ?: Unit
    companion object {
        @JvmStatic private val MOBILE_NET_QUANTIZED_MODEL: String = "mobilenet_v1_1.0_224_quant.tflite"
    }
}