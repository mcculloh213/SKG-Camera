package ktx.sovereign.camera.tflite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.Image
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ktx.sovereign.camera.tflite.classifier.Classifier
import ktx.sovereign.camera.tflite.util.convertYUV420ToARGB8888
import ktx.sovereign.camera.tflite.util.getTransformationMatrix
import java.lang.IllegalStateException
import java.util.concurrent.Semaphore
import kotlin.coroutines.CoroutineContext

class ClassificationAnalyzer(
    private val classifier: Classifier,
    private val callback: Classifier.ResultsCallback? = null
) : CoroutineScope, ImageAnalysis.Analyzer {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO

    private val lock: Semaphore = Semaphore(1)
    private val yuv: Array<ByteArray?> = Array(3) { null }
    private val analysisBitmap: Bitmap = Bitmap.createBitmap(
        classifier.getImageSizeX(), classifier.getImageSizeY(), Bitmap.Config.ARGB_8888
    )
    private var yRowStride: Int = 0
    private var isProcessingFrame: Boolean = false
    private var cropToFrameTransform: Matrix = Matrix()
    private lateinit var rgb: IntArray
    private lateinit var rgbBitmap: Bitmap
    private lateinit var frameToCropTransform: Matrix


    override fun analyze(image: ImageProxy, rotationDegrees: Int) {
        val img = image.image ?: return
        val planes = img.planes
        if (img.width == 0 || img.height == 0) return
        if (!::rgb.isInitialized) { rgb = IntArray(img.width * img.height) }
        if (!::rgbBitmap.isInitialized) { initBitmap(img.width, img.height, rotationDegrees) }
        if (isProcessingFrame || !lock.tryAcquire()) return
        isProcessingFrame = true
        fillBytes(planes, yuv)
        yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride
        convertImage(img.width, img.height, uvRowStride, uvPixelStride)
        processImage(img.width, img.height)
        release()
    }

    private fun initBitmap(width: Int, height: Int, rotation: Int) {
        rgbBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        frameToCropTransform = getTransformationMatrix(
            width, height,
            classifier.getImageSizeX(), classifier.getImageSizeY(),
            rotation
        ).also { transform -> transform.invert(cropToFrameTransform) }
    }
    private fun fillBytes(planes: Array<out Image.Plane>, yuvBytes: Array<ByteArray?>) {
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer.get(yuvBytes[i]!!)
        }
    }
    private fun convertImage(width: Int, height: Int, uvRowStride: Int, uvPixelStride: Int) {
        convertYUV420ToARGB8888(
            yuv[0]!!, yuv[1]!!, yuv[2]!!,
            width, height,
            yRowStride, uvRowStride, uvPixelStride, rgb
        )
    }
    private fun processImage(width: Int, height: Int) {
        rgbBitmap.setPixels(rgb, 0, width, 0, 0, width, height)
        Canvas(analysisBitmap).apply {
            drawBitmap(rgbBitmap, frameToCropTransform, null)
        }
        callback?.onFrameClassified(classifier.performRecognition(analysisBitmap))
    }
    private fun release() {
        lock.release()
        isProcessingFrame = false
    }
}