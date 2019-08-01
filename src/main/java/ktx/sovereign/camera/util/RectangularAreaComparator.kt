package ktx.sovereign.camera.util

import android.util.Size
import java.lang.Long.signum

internal class RectangularAreaComparator : Comparator<Size> {
    override fun compare(o1: Size, o2: Size): Int =
        signum(o1.width.toLong() * o1.height.toLong() - o2.width.toLong() * o2.height.toLong())
}