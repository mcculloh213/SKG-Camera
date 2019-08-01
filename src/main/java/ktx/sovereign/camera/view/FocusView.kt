package ktx.sovereign.camera.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.RelativeLayout
import ktx.sovereign.camera.R


class FocusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RelativeLayout(context, attrs) {
    private lateinit var focus: ImageView

    init { init(context) }

    private fun init(context: Context) {
        val view = View.inflate(context, R.layout.view_focus, this)
        focus = view.findViewById(R.id.focus)
    }

    fun showFocus(x: Int, y: Int) {
        val w = measuredWidth
        val h = measuredHeight

        val margin = MarginLayoutParams(layoutParams)
        val left = x - (w / 2f).toInt()
        val top = y - (h / 2f).toInt()
        margin.setMargins(left, top, 0, 0)
        layoutParams = LayoutParams(margin)

        visibility = View.VISIBLE
        val scale = ScaleAnimation(
            1.3f, 1.0f, 1.3f, 1.0f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).also { it.duration = 200L }
        focus.animation = scale
        postDelayed({ visibility = View.GONE }, 1000L)
    }
}