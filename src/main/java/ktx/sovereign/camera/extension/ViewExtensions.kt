@file:JvmName("ViewUtils")
package ktx.sovereign.camera.extension

import android.graphics.Point
import android.util.DisplayMetrics
import android.util.Rational
import android.view.View
import com.google.android.material.floatingactionbutton.FloatingActionButton

const val ANIM_FAST_MILLIS = 50L
const val ANIM_SLOW_MILLIS = 100L

fun FloatingActionButton.simulateClick(delay: Long = ANIM_FAST_MILLIS) {
    performClick()
    isPressed = true
    invalidate()
    postDelayed({
        invalidate()
        isPressed = false
    }, delay)
}

fun View.getSize(): Point = Point().also { display.getSize(it) }
fun View.getRealSize(): Point = Point().also { display.getRealSize(it) }
fun View.getRealMetrics(): DisplayMetrics = DisplayMetrics().also { display.getRealMetrics(it) }
fun View.getDisplayRotation(): Int = display.rotation
fun View.getAspectRatio(): Rational = with(getRealMetrics()) {
    Rational(widthPixels, heightPixels)
}