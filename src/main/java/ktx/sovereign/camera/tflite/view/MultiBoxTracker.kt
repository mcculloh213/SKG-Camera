package ktx.sovereign.camera.tflite.view

import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.TypedValue
import ktx.sovereign.camera.tflite.classifier.Classifier
import ktx.sovereign.camera.tflite.util.getTransformationMatrix
import java.util.*
import kotlin.math.min

class MultiBoxTracker(context: Context) {
    data class TrackedResult (
        val location: RectF,
        val confidence: Float,
        val color: Int,
        val title: String
    )
    data class ScreenRect (
        val confidence: Float,
        val location: RectF
    )
    class BorderedText @JvmOverloads constructor (
        val textSize: Float,
        interior: Int = Color.WHITE,
        exterior: Int = Color.BLACK
    ) {
        private val interiorPaint: Paint by lazy {
            Paint().apply {
                textSize = this@BorderedText.textSize
                color = interior
                style = Paint.Style.FILL
                isAntiAlias = false
                alpha = 255
            }
        }
        private val exteriorPaint: Paint by lazy {
            Paint().apply {
                textSize = this@BorderedText.textSize
                color = exterior
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = this@BorderedText.textSize / 8
                isAntiAlias = false
                alpha = 255
            }
        }
        fun setTypeface(typeface: Typeface) {
            interiorPaint.typeface = typeface
            exteriorPaint.typeface = typeface
        }
        fun drawText(canvas: Canvas, x: Float, y: Float, text: String) {
            canvas.drawText(text, x, y, exteriorPaint)
            canvas.drawText(text, x, y, interiorPaint)
        }
        fun drawText(canvas: Canvas, x: Float, y: Float, text: String, background: Paint) {
            val w = exteriorPaint.measureText(text)
            val size = exteriorPaint.textSize
            val paint = Paint(background).apply {
                style = Paint.Style.FILL
                alpha = 160
            }
            canvas.drawRect(x, (y + size), (x + w), y, paint)
            canvas.drawText(text, x, (y + size), interiorPaint)
        }
        fun drawLines(canvas: Canvas, x: Float, y: Float, lines: List<String>) {
            lines.forEachIndexed { idx, line ->
                drawText(canvas, x, y - textSize * (lines.size - (idx - 1)), line)
            }
        }
        fun setInteriorColor(color: Int) {
            interiorPaint.color = color
        }
        fun setExteriorColor(color: Int) {
            exteriorPaint.color = color
        }
        fun setAlpha(alpha: Int) {
            interiorPaint.alpha = alpha
            exteriorPaint.alpha = alpha
        }
        fun getTextBounds(line: String, index: Int, count: Int, bounds: Rect) {
            interiorPaint.getTextBounds(line, index, count, bounds)
        }
        fun setTextAlign(align: Paint.Align) {
            interiorPaint.textAlign = align
            exteriorPaint.textAlign = align
        }
    }

    private val textSizePx: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.resources.displayMetrics
    )
    private val boarderedText: BorderedText = BorderedText(textSizePx)
    private val tracked: MutableList<TrackedResult> = LinkedList()
    private val rects: MutableList<ScreenRect> = LinkedList()
    private val colors: Queue<Int> by lazy {
        LinkedList<Int>().apply { addAll(COLORS.toList()) }
    }
    private val paint: Paint by lazy {
        Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 10.0f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeMiter = 100f
        }
    }

    private var frameWidth: Int = -1
    private var frameHeight: Int = -1
    private var sensorOrientation: Int = -1
    private lateinit var frameToCanvasMatrix: Matrix

    @Synchronized
    fun setFrameConfiguration(width: Int, height: Int, orientation: Int) {
        frameWidth = width
        frameHeight = height
        sensorOrientation = orientation
    }
    @Synchronized
    fun drawDebug(canvas: Canvas) {
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 60.0f
        }
        val boxPaint = Paint().apply {
            color = Color.RED
            alpha = 200
            style = Paint.Style.STROKE
        }
        rects.forEach { rect ->
            canvas.drawRect(rect.location, boxPaint)
            canvas.drawText("${rect.confidence}", rect.location.left, rect.location.top, textPaint)
            boarderedText.drawText(canvas, rect.location.centerX(), rect.location.centerY(), "${rect.confidence}")
        }
    }
    @Synchronized
    fun trackResults(results: List<Classifier.Result>) = processResults(results)
    @Synchronized
    fun draw(canvas: Canvas) {
        val isRotated = sensorOrientation % 180 == 90
        val multiplier = if (isRotated) {
            min(canvas.height / frameWidth.toFloat(), canvas.width / frameHeight.toFloat())
        } else {
            min(canvas.height / frameHeight.toFloat(), canvas.width / frameWidth.toFloat())
        }
        frameToCanvasMatrix = getTransformationMatrix(
            frameWidth,
            frameHeight,
            (multiplier * if (isRotated) frameHeight else frameWidth).toInt(),
            (multiplier * if (isRotated) frameWidth else frameHeight).toInt(),
            sensorOrientation,
            false
        )
        tracked.forEach { result ->
            val pos = result.location

            frameToCanvasMatrix.mapRect(pos)
            paint.color = result.color

            val cornerSize = min(pos.width(), pos.height()) / 8.0f
            canvas.drawRoundRect(pos, cornerSize, cornerSize, paint)

            val label = if (result.title.isNotEmpty()) {
                "${result.title} ${(result.confidence * 100).format(2)}"
            } else {
                (result.confidence * 100).format(2)
            }
            boarderedText.drawText(canvas, pos.left + cornerSize, pos.top, "$label%", paint)
        }
    }
    private fun processResults(results: List<Classifier.Result>) {
        if (!::frameToCanvasMatrix.isInitialized) return
        val processed = LinkedList<Pair<Float, Classifier.Result>>()

        rects.clear()
        val rgbFrameToScreen = Matrix(frameToCanvasMatrix)

        results.forEach { result ->
            result.location?.let { location ->
                val detectFrameRect = RectF(location)
                val detectScreenRect = RectF()
                rgbFrameToScreen.mapRect(detectScreenRect, detectFrameRect)
                rects.add(ScreenRect(result.confidence, detectScreenRect))
                if (detectFrameRect.width() < MIN_SIZE || detectFrameRect.height() < MIN_SIZE) {
                    Log.w("Tracker", "Degenerate Rectangle!\t$detectFrameRect")
                    false
                } else {
                    processed.add(Pair(result.confidence, result))
                }
            }
        }

        if (processed.isEmpty()) {
            Log.w("Tracker", "Nothing to track.")
            return
        }

        tracked.clear()
        for (potential in processed) {
            val tracking = TrackedResult(
                RectF(potential.second.location),
                potential.first,
                COLORS[tracked.size],
                potential.second.title
            )
            tracked.add(tracking)
            if (tracked.size >= COLORS.size) break
        }
        Log.v("Tracker", "Tracking Results: ${tracked.size}")
    }

    private fun Float.format(digits: Int) = java.lang.String.format("%.${digits}f", this)
    companion object {
        private const val TEXT_SIZE_DIP: Float = 18f
        private const val MIN_SIZE: Float = 16.0f
        private val COLORS: IntArray = intArrayOf(
            Color.BLUE,
            Color.RED,
            Color.GREEN,
            Color.YELLOW,
            Color.CYAN,
            Color.MAGENTA,
            Color.WHITE,
            Color.parseColor("#55ff55"),
            Color.parseColor("#ffa500"),
            Color.parseColor("#ff8888"),
            Color.parseColor("#aaaaff"),
            Color.parseColor("#ffffaa"),
            Color.parseColor("#55aaaa"),
            Color.parseColor("#aa33aa"),
            Color.parseColor("#0d0068")
        )
    }
}