package ktx.sovereign.camera.tflite.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import java.util.LinkedList

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {
    private val callbacks: MutableList<DrawCallback> = LinkedList()
    fun addCallback(callback: DrawCallback) = callbacks.add(callback)
    @Synchronized
    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        callbacks.forEach { callback -> callback.onDraw(canvas) }
    }
    interface DrawCallback {
        fun onDraw(canvas: Canvas)
    }
}