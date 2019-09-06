package ktx.sovereign.camera.tflite.util

class LabelValueComparator : Comparator<Map.Entry<String, Float>> {
    override fun compare(
        o1: Map.Entry<String, Float>,
        o2: Map.Entry<String, Float>
    ): Int = o1.value.compareTo(o2.value)
}