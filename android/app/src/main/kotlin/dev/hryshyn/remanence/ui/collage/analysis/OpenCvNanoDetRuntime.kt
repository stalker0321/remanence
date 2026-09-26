package dev.hryshyn.remanence.ui.collage.analysis

import dev.hryshyn.remanence.ui.collage.NanoDetDecoder
import dev.hryshyn.remanence.ui.collage.NanoDetHead
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc

/**
 * Deterministic ownership ledger for native resources, keyed by identity. The
 * real runtime registers every Mat header it creates (including `submat` ROI
 * views and `reshape` views) and releases each exactly once. The ledger is
 * OpenCV-free so the release policy is unit-testable without natives.
 */
internal class MatReleaseLedger {
    private val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    private val order = ArrayList<Any>()

    var releasedCount: Int = 0
        private set

    var doubleReleaseCount: Int = 0
        private set

    val liveCount: Int
        get() = live.size

    fun <T : Any> register(token: T): T {
        live.add(token)
        order += token
        return token
    }

    /** Releases in reverse registration order; a released token is a no-op. */
    fun releaseAll(release: (Any) -> Unit) {
        order.asReversed().forEach { token ->
            if (live.remove(token)) {
                release(token)
                releasedCount += 1
            } else {
                doubleReleaseCount += 1
            }
        }
        order.clear()
    }
}

/**
 * The single OpenCV touch point for the NanoDet runner. It reproduces the
 * frozen `import-field-3.3` preprocessing exactly — upright input, letterbox
 * resize (Python half-even), pad 114, RGB→BGR, per-channel mean/std — runs the
 * model once (one reused [Net]), and converts the named output tensors to
 * bounded [NanoDetHead]s.
 *
 * Ownership: the [MatOwner] registers every Mat header it creates (including
 * the `submat` ROI and every `reshape` view); the ROI is registered on
 * creation and each reshape header is released immediately in `finally` after
 * the bounded copy. Releasing a view only drops the header reference, so the
 * underlying `canvas`/output Mat stays owned until [MatOwner.releaseAll].
 *
 * Not production-wired; exercised only by the A3B androidTest probe.
 */
internal class OpenCvNanoDetRuntime : NanoDetRuntime {

    private var net: Net? = null

    override fun load(modelPath: String) {
        val read = Dnn.readNet(modelPath)
        check(!read.empty()) { "NanoDet net is empty" }
        net = read
    }

    override fun forward(image: NanoDetImage): List<NanoDetHead> {
        val net = this.net ?: error("NanoDet runtime is not loaded")
        val owner = MatOwner()
        try {
            val rgba = owner.own(Mat(image.height, image.width, CvType.CV_8UC4))
            rgba.put(0, 0, argbToRgbaBytes(image.argb))

            val bgr = owner.own(Mat())
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            val letterbox = NanoDetDecoder.letterbox(image.width, image.height)
            val canvas = owner.own(
                Mat(NanoDetDecoder.INPUT_SIZE, NanoDetDecoder.INPUT_SIZE, CvType.CV_8UC3, PAD_FILL),
            )
            val resized = owner.own(Mat())
            Imgproc.resize(
                bgr, resized,
                Size(letterbox.rw.toDouble(), letterbox.rh.toDouble()),
                0.0, 0.0, Imgproc.INTER_LINEAR,
            )
            // The submat ROI is a distinct Mat header: register it immediately.
            val roi = owner.own(
                canvas.submat(
                    letterbox.padY, letterbox.padY + letterbox.rh,
                    letterbox.padX, letterbox.padX + letterbox.rw,
                ),
            )
            resized.copyTo(roi)

            // Explicit float normalization: (x - mean) / std per channel. The
            // blobFromImage shortcut cannot express a per-channel std divisor.
            val normalized = owner.own(Mat())
            canvas.convertTo(normalized, CvType.CV_32FC3)
            Core.subtract(normalized, MEAN_BGR, normalized)
            Core.divide(normalized, STD_BGR, normalized)

            val blob = owner.own(
                Dnn.blobFromImage(
                    normalized, 1.0,
                    Size(NanoDetDecoder.INPUT_SIZE.toDouble(), NanoDetDecoder.INPUT_SIZE.toDouble()),
                    Scalar(0.0), false, false,
                ),
            )
            net.setInput(blob)

            val names = net.getUnconnectedOutLayersNames()
            val outputs = ArrayList<Mat>()
            net.forward(outputs, names)
            outputs.forEach { owner.own(it) }

            return normalizeHeads(names, outputs)
        } finally {
            owner.releaseAll()
        }
    }

    override fun close() {
        net = null
    }

    /** Exactly four cls (80ch) + four reg (32ch), paired by spatial count. */
    private fun normalizeHeads(names: List<String>, outputs: List<Mat>): List<NanoDetHead> {
        val cls = ArrayList<Pair<Int, NanoDetHead>>()
        val reg = HashMap<Int, NanoDetHead>()
        outputs.forEachIndexed { index, mat ->
            val shape = IntArray(mat.dims()) { mat.size(it) }
            val total = shape.fold(1L) { acc, v -> acc * v }
            check(total in 1..MAX_TENSOR_ELEMENTS) { "NanoDet head exceeds element bound" }
            val name = names.getOrNull(index) ?: "out$index"
            val values = FloatArray(total.toInt())
            // A reshape header shares the output data; release it immediately
            // after the bounded copy and never register it as an owned source.
            val flat = mat.reshape(1, 1)
            try {
                flat.get(0, 0, values)
            } finally {
                flat.release()
            }
            when (shape.last()) {
                NanoDetDecoder.CLASS_COUNT -> cls += shape[shape.size - 2] to NanoDetHead(name, shape, values)
                NanoDetDecoder.REG_CHANNELS -> {
                    check(reg.put(shape[shape.size - 2], NanoDetHead(name, shape, values)) == null) {
                        "duplicate NanoDet reg head"
                    }
                }
                else -> error("unexpected NanoDet head shape")
            }
        }
        check(cls.size == 4 && reg.size == 4) { "expected four cls and four reg heads" }
        val ordered = cls.sortedByDescending { it.first }
        val heads = ArrayList<NanoDetHead>(8)
        for ((spatial, clsHead) in ordered) {
            val regHead = reg[spatial] ?: error("unpaired NanoDet head")
            heads += clsHead
            heads += regHead
        }
        return heads
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

    /** Owner for the ROI/output Mat sources; each is released exactly once. */
    private class MatOwner {
        private val ledger = MatReleaseLedger()

        fun <T : Mat> own(mat: T): T {
            ledger.register(mat)
            return mat
        }

        fun releaseAll() {
            ledger.releaseAll { (it as Mat).release() }
        }
    }

    private companion object {
        const val MAX_TENSOR_ELEMENTS = 400_000L
        val PAD_FILL = Scalar(114.0, 114.0, 114.0)
        val MEAN_BGR = Scalar(103.53, 116.28, 123.675)
        val STD_BGR = Scalar(57.375, 57.12, 58.395)
    }
}
