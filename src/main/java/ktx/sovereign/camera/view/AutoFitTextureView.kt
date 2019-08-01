package ktx.sovereign.camera.view

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {
    private var ratioWidth: Int = 0
    private var ratioHeight: Int = 0

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
}