package ktx.sovereign.camera.tflite.util

import ktx.sovereign.camera.tflite.classifier.Classifier

class ResultConfidenceComparator : Comparator<Classifier.Result> {
    override fun compare(lhs: Classifier.Result, rhs: Classifier.Result): Int = rhs.confidence.compareTo(lhs.confidence)
}