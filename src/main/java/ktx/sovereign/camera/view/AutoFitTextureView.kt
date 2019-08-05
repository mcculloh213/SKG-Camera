package ktx.sovereign.camera.view

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.SparseArray
import android.util.SparseIntArray
import android.view.TextureView
import kotlin.random.Random

class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {
    private var ratioWidth: Int = 0
    private var ratioHeight: Int = 0

    private val paint: Paint = Paint().also {
        it.style = Paint.Style.STROKE
        it.strokeWidth = 5f
    }
    private val rects: SparseArray<Rect> = SparseArray()
    private val colors: SparseIntArray = SparseIntArray()

    fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0) {
            throw IllegalArgumentException("Size cannot be negative ($width, $height)")
        }
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(w, h)
        } else {
            if (w < h * ratioWidth / ratioHeight) {
                setMeasuredDimension(w, w * ratioHeight / ratioWidth)
            } else {
                setMeasuredDimension(h * ratioWidth / ratioHeight, h)
            }
        }
    }
    private fun nextColor(): Int =
        Color.argb(255, Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
}