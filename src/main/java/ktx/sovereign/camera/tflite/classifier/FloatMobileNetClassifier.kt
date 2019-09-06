package ktx.sovereign.camera.tflite.classifier

import android.content.Context

class FloatMobileNetClassifier(
    context: Context,
    device: Device,
    numThreads: Int
) : Classifier(context, device, numThreads) {
    private val probabilityMatrix: Array<FloatArray> = arrayOf(FloatArray(getNumLabels()))

    override fun getImageSizeX(): Int = 224
    override fun getImageSizeY(): Int = 224
    override fun getModelPath(): String = MOBILE_NET_FLOAT_MODEL
    override fun getLabelPath(): String = "labels.txt"
    override fun getNumBytesPerChannel(): Int = 4
    override fun addPixelValue(pixelValue: Int) {
        imageBuffer?.apply {
            putFloat((((pixelValue shr 16) and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
            putFloat((((pixelValue shr 8) and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
            putFloat(((pixelValue and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
        }
    }
    override fun getProbability(index: Int): Float = probabilityMatrix[0][index]
    override fun setProbability(index: Int, value: Number) {
        probabilityMatrix[0][index] = value.toFloat()
    }
    override fun getNormalizedProbability(index: Int): Float = probabilityMatrix[0][index]
    override fun runInference() = imageBuffer?.let { buffer -> model.run(buffer, probabilityMatrix) } ?: Unit
    companion object {
        @JvmStatic private val MOBILE_NET_FLOAT_MODEL: String = "mobilenet_v1_1.0_224.tflite"
    }
}