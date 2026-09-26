package dev.hryshyn.remanence.ui.collage.analysis

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN

/**
 * The single OpenCV touch point for the YuNet runner. It reproduces the frozen
 * detection semantics — upright input, RGB→BGR, Python half-even resize to the
 * pass's long edge, `FaceDetectorYN` at the freeze config — and converts the
 * detector rows to a bounded [YuNetPass]. The score/NMS/topK values are applied
 * by `create`, matching the frozen `score=0.65, nms=0.3, topK=5000`.
 *
 * Ownership: the [MatOwner] registers every Mat header it creates (including the
 * `reshape` view, released in `finally` right after the bounded copy) and
 * releases each exactly once. Not production-wired; exercised only by the A3C
 * androidTest probe.
 */
internal class OpenCvYuNetRuntime : YuNetRuntime {

    private var detector: FaceDetectorYN? = null

    override fun load(modelPath: String, config: YuNetConfig) {
        val created = FaceDetectorYN.create(
            modelPath,
            "",
            Size(INITIAL_INPUT.toDouble(), INITIAL_INPUT.toDouble()),
            config.scoreThreshold,
            config.nmsThreshold,
            config.topK,
        )
        detector = created
    }

    override fun detect(image: YuNetImage, longEdge: Int): YuNetPass {
        val detector = this.detector ?: error("YuNet runtime is not loaded")
        val owner = MatOwner()
        try {
            val rgba = owner.own(Mat(image.height, image.width, CvType.CV_8UC4))
            rgba.put(0, 0, argbToRgbaBytes(image.argb))

            val bgr = owner.own(Mat())
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            val (rw, rh) = YuNetFacePostProcessor.letterboxSize(image.width, image.height, longEdge)
            val resized = owner.own(Mat())
            Imgproc.resize(bgr, resized, Size(rw.toDouble(), rh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

            detector.setInputSize(Size(rw.toDouble(), rh.toDouble()))
            val faces = owner.own(Mat())
            detector.detect(resized, faces)

            val rows = faces.rows()
            val columns = faces.cols()
            val total = rows.toLong() * columns
            check(total <= MAX_TENSOR_ELEMENTS) { "YuNet output exceeds element bound" }
            if (rows == 0) return YuNetPass(rw, rh, columns, emptyList())
            check(columns >= YuNetFacePostProcessor.MIN_COLUMNS) { "YuNet output has too few columns" }

            val values = FloatArray(total.toInt())
            // A reshape header shares the output data; release it immediately
            // after the bounded copy and never register it as an owned source.
            val flat = faces.reshape(1, 1)
            try {
                flat.get(0, 0, values)
            } finally {
                flat.release()
            }
            val rowsOut = ArrayList<FloatArray>(rows)
            for (r in 0 until rows) {
                rowsOut += values.copyOfRange(r * columns, (r + 1) * columns)
            }
            return YuNetPass(rw, rh, columns, rowsOut)
        } finally {
            owner.releaseAll()
        }
    }

    override fun close() {
        detector = null
    }

    private fun argbToRgbaBytes(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            val c = argb[i]
            out[i * 4] = ((c shr 16) and 0xFF).toByte() // R
            out[i * 4 + 1] = ((c shr 8) and 0xFF).toByte() // G
            out[i * 4 + 2] = (c and 0xFF).toByte() // B
            out[i * 4 + 3] = ((c ushr 24) and 0xFF).toByte() // A
        }
        return out
    }

    private companion object {
        const val INITIAL_INPUT = 640
        const val MAX_TENSOR_ELEMENTS = 200_000L
    }
}
