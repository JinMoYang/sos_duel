package com.example.activeperception.acquire

/** One detection: box in image pixel space, confidence, COCO class id. */
class Detection(val xyxy: FloatArray, val confidence: Float, val classId: Int)

/** Renders the given grid cells of the current frame. Generic over the image type so the
 *  controller is testable without Android (T = Bitmap on device). */
interface CandidateSource<T> {
    fun render(cells: IntArray): List<T>
}

/** Detects on a batch of candidates. May true-batch or loop internally — transparent to
 *  the controller, so batch-vs-loop is measurable without changing this code. */
interface Detector<T> {
    fun detectBatch(images: List<T>): List<List<Detection>>
}
