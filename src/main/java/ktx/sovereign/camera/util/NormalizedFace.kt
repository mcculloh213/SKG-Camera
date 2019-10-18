package ktx.sovereign.camera.util

import android.graphics.RectF
import android.graphics.PointF
import android.hardware.camera2.params.Face


class NormalizedFace(face: Face, dx: Int, dy: Int, offX: Int, offY: Int) {
    val bounds: RectF = RectF()
    val leftEye: PointF = PointF()
    val rightEye: PointF = PointF()
    val mouth: PointF = PointF()

    init {
        with (face) {
            if (leftEyePosition != null) {
                leftEye.apply {
                    x = (leftEyePosition.x - offX) / dx.toFloat()
                    y = (leftEyePosition.y - offY) / dy.toFloat()
                }
            }
            if (rightEyePosition != null) {
                rightEye.apply {
                    x = (rightEyePosition.x - offX) / dx.toFloat()
                    y = (rightEyePosition.y - offY) / dy.toFloat()
                }
            }
            if (mouthPosition != null) {
                mouth.apply {
                    x = (mouthPosition.x - offX) / dx.toFloat()
                    y = (mouthPosition.y - offY) / dy.toFloat()
                }
            }
            if (bounds != null) {
                this@NormalizedFace.bounds.apply {
                    left   = (bounds.left   - offX) / dx.toFloat()
                    top    = (bounds.top    - offY) / dy.toFloat()
                    right  = (bounds.right  - offX) / dx.toFloat()
                    bottom = (bounds.bottom - offY) / dy.toFloat()
                }
            }
        }
    }
    fun mirrorInX() {
        leftEye.x = 1f - leftEye.x
        rightEye.x = 1f - rightEye.x
        mouth.x = 1f - mouth.x
        val oldLeft = bounds.left
        bounds.left = 1f - bounds.right
        bounds.right = 1f - oldLeft
    }
    /**
     * Typically required for front camera
     */
    fun mirrorInY() {
        leftEye.y = 1f - leftEye.y
        rightEye.y = 1f - rightEye.y
        mouth.y = 1f - mouth.y
        val oldTop = bounds.top
        bounds.top = 1f - bounds.bottom
        bounds.bottom = 1f - oldTop
    }
}