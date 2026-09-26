package dev.hryshyn.remanence.ui.collage.analysis

import dev.hryshyn.remanence.ui.collage.NormBox
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The single OpenCV touch point for the A4b pixel field stage. It reproduces
 * the frozen `import-field-3.3` pixel boundary
 * (`runtime/tools/legacy_features.py:50-73`) over the full-resolution
 * EXIF-upright original and returns the four 48x48 maps plus contour-order
 * tip boxes for [FieldStageRunner]; detectors, aggregation, and any
 * production wiring stay out.
 *
 * Line-by-line mapping (all kernel/sigma/threshold choices are the freeze
 * defaults, never tuned):
 * - 51: `resize(rgb, 96x96)` default linear, `/ 255` float, `RGB2GRAY`.
 * - 53-55: spectral-residual saliency. `dft` forward unscaled with
 *   [Core.DFT_COMPLEX_OUTPUT] (numpy `fft2` convention), `log(|F| + 1e-8)`,
 *   `blur((3, 3))`, residual `log - smooth`, `exp`, phase, `polarToCart`
 *   reconstruction, `idft` with [Core.DFT_SCALE] (numpy `ifft2` 1/N
 *   convention), `|.|^2`, `GaussianBlur((7, 7), 0)`, then `normal()` at 96x96.
 * - 56: local contrast. `GaussianBlur((0, 0), 5)`, per-pixel channel L2 norm
 *   (`split`/square/add/`sqrt`, exactly `linalg.norm(..., axis=2)`),
 *   `normal()` at 96x96.
 * - 57-58: Sobel `CV_32F` `dx`/`dy` (ksize 3), `magnitude`, `normal()` at
 *   96x96.
 * - 60-70: `resize(rgb, 192x192)` default linear, `RGB2GRAY`, `Canny(20, 60)`,
 *   `findContours(RETR_LIST, CHAIN_APPROX_SIMPLE)`, `boundingRect` filter
 *   `ch > 12 && cw > 2 && ch > cw * 1.2 && y > 4 && y < 140`, topmost point
 *   (`argmin` first-wins on ties), tip box + `0.025` dedup via
 *   [FieldPixelMath], and the per-contour `tips = maximum(...)` merge with the
 *   analytic 48x48 gaussian (`expand = 0.4`). The merge runs for EVERY
 *   qualifying contour while only novel boxes become tip regions — exactly as
 *   lines 68-69 order the statements.
 * - 71-72: the already-normalized `edge`/`sal`/`contrast` maps down to
 *   48x48 with `INTER_AREA` (`tips` is already 48x48), with NO second
 *   normalization after the resize. `normal()` is evaluated on the JVM
 *   through [FieldPixelMath.normalizeField] so the quantile contract is
 *   unit-tested.
 * - 11: `setNumThreads(2)` once per instance.
 *
 * Known, explicitly non-bit-exact points (functional equivalence only; the
 * Python/cv2/device parity gate stays OPEN, see the probe):
 * - the spectral/contrast/edge chain accumulates in `CV_32F` while numpy
 *   holds float64 until the line-55 `astype(float32)`;
 * - `phase` reports `[0, 2pi)` where `np.angle` reports `(-pi, pi]`; only
 *   `cos`/`sin` (via `polarToCart`) consume it, so the reconstruction is
 *   identical;
 * - `findContours` order is an OpenCV-version input to tip insertion order;
 *   this pins OpenCV 4.10.0 and treats order as device-observed, not proven.
 *
 * Ownership: the [MatOwner] registers every Mat header this stage creates
 * (including `split` outputs, contour Mats, the hierarchy, and every
 * `reshape` readback view, released in `finally` right after the bounded
 * copy) and releases each exactly once. Not production-wired; exercised only
 * by the A4b androidTest probe.
 */
internal class OpenCvFieldStage : FieldStageRuntime {

    init {
        Core.setNumThreads(THREAD_COUNT)
    }

    override fun analyze(image: FieldStageImage): FieldStageMaps {
        val owner = MatOwner()
        try {
            val rgba = owner.own(Mat(image.height, image.width, CvType.CV_8UC4))
            rgba.put(0, 0, argbToRgbaBytes(image.argb))

            val rgb = owner.own(Mat())
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)

            val small96 = owner.own(Mat())
            Imgproc.resize(rgb, small96, Size(96.0, 96.0), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val small96f = owner.own(Mat())
            small96.convertTo(small96f, CvType.CV_32FC3, 1.0 / 255.0)
            val grey = owner.own(Mat())
            Imgproc.cvtColor(small96f, grey, Imgproc.COLOR_RGB2GRAY)

            val saliency = spectralSaliency48(owner, grey)
            val contrast = localContrast48(owner, small96f)
            val edges = edgeEnergy48(owner, grey)
            val (tips, tipBoxes) = contourTips(owner, rgb)

            return FieldStageMaps(
                sourceWidth = image.width,
                sourceHeight = image.height,
                saliency = saliency,
                edgeEnergy = edges,
                contrast = contrast,
                tips = tips,
                tipBoxes = tipBoxes,
            )
        } finally {
            owner.releaseAll()
        }
    }

    override fun close() {
        // No native model state; every Mat is per-call owned above.
    }

    /** Lines 53-55 + 71: spectral-residual saliency down to normalized 48x48. */
    private fun spectralSaliency48(owner: MatOwner, grey: Mat): DoubleArray {
        val freq = owner.own(Mat())
        Core.dft(grey, freq, Core.DFT_COMPLEX_OUTPUT, 0)
        val parts = ArrayList<Mat>()
        Core.split(freq, parts)
        parts.forEach { owner.own(it) }
        val re = parts[0]
        val im = parts[1]

        val mag = owner.own(Mat())
        Core.magnitude(re, im, mag)
        Core.add(mag, Scalar(LOG_EPSILON), mag)
        val logMag = owner.own(Mat())
        Core.log(mag, logMag)
        val logSmooth = owner.own(Mat())
        Imgproc.blur(logMag, logSmooth, Size(3.0, 3.0))
        val residual = owner.own(Mat())
        Core.subtract(logMag, logSmooth, residual)
        val expResidual = owner.own(Mat())
        Core.exp(residual, expResidual)
        val angle = owner.own(Mat())
        Core.phase(re, im, angle, false)
        val recX = owner.own(Mat())
        val recY = owner.own(Mat())
        Core.polarToCart(expResidual, angle, recX, recY, false)
        val complex = owner.own(Mat())
        Core.merge(listOf(recX, recY), complex)
        val spatial = owner.own(Mat())
        Core.idft(complex, spatial, Core.DFT_SCALE, 0)
        val spatialParts = ArrayList<Mat>()
        Core.split(spatial, spatialParts)
        spatialParts.forEach { owner.own(it) }
        val salMag = owner.own(Mat())
        Core.magnitude(spatialParts[0], spatialParts[1], salMag)
        val salPow = owner.own(Mat())
        Core.multiply(salMag, salMag, salPow)
        val salBlur = owner.own(Mat())
        Imgproc.GaussianBlur(salPow, salBlur, Size(7.0, 7.0), 0.0)
        val salNormal96 = normalize96ToOwned(owner, salBlur)
        val sal48 = owner.own(Mat())
        Imgproc.resize(salNormal96, sal48, Size(48.0, 48.0), 0.0, 0.0, Imgproc.INTER_AREA)
        return readback48(sal48)
    }

    /** Line 56 + 72: local-contrast norm down to normalized 48x48. */
    private fun localContrast48(owner: MatOwner, small96f: Mat): DoubleArray {
        val blur5 = owner.own(Mat())
        Imgproc.GaussianBlur(small96f, blur5, Size(0.0, 0.0), 5.0)
        val diff = owner.own(Mat())
        Core.subtract(small96f, blur5, diff)
        val channels = ArrayList<Mat>()
        Core.split(diff, channels)
        channels.forEach { owner.own(it) }
        val acc = owner.own(Mat())
        Core.multiply(channels[0], channels[0], acc)
        val tmp = owner.own(Mat())
        Core.multiply(channels[1], channels[1], tmp)
        Core.add(acc, tmp, acc)
        Core.multiply(channels[2], channels[2], tmp)
        Core.add(acc, tmp, acc)
        val norm = owner.own(Mat())
        Core.sqrt(acc, norm)
        val conNormal96 = normalize96ToOwned(owner, norm)
        val con48 = owner.own(Mat())
        Imgproc.resize(conNormal96, con48, Size(48.0, 48.0), 0.0, 0.0, Imgproc.INTER_AREA)
        return readback48(con48)
    }

    /** Lines 57-58 + 72: Sobel edge energy down to normalized 48x48. */
    private fun edgeEnergy48(owner: MatOwner, grey: Mat): DoubleArray {
        val gx = owner.own(Mat())
        Imgproc.Sobel(grey, gx, CvType.CV_32F, 1, 0, 3, 1.0, 0.0)
        val gy = owner.own(Mat())
        Imgproc.Sobel(grey, gy, CvType.CV_32F, 0, 1, 3, 1.0, 0.0)
        val edgeMag = owner.own(Mat())
        Core.magnitude(gx, gy, edgeMag)
        val edgeNormal96 = normalize96ToOwned(owner, edgeMag)
        val edge48 = owner.own(Mat())
        Imgproc.resize(edgeNormal96, edge48, Size(48.0, 48.0), 0.0, 0.0, Imgproc.INTER_AREA)
        return readback48(edge48)
    }

    /**
     * Lines 60-70: Canny contour endpoints. Returns the merged 48x48 tips map
     * with the contour-order tip boxes. The gaussian merge runs for every
     * qualifying contour; only novel boxes are collected.
     */
    private fun contourTips(owner: MatOwner, rgb: Mat): Pair<DoubleArray, List<NormBox>> {
        val structural = owner.own(Mat())
        Imgproc.resize(rgb, structural, Size(192.0, 192.0), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val structGrey = owner.own(Mat())
        Imgproc.cvtColor(structural, structGrey, Imgproc.COLOR_RGB2GRAY)
        val canny = owner.own(Mat())
        Imgproc.Canny(structGrey, canny, CANNY_LOW, CANNY_HIGH)
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = owner.own(Mat())
        Imgproc.findContours(canny, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        contours.forEach { owner.own(it) }

        val cells = 48 * 48
        val tips = DoubleArray(cells)
        val tipBoxes = ArrayList<NormBox>()
        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            val cw = rect.width
            val ch = rect.height
            if (!(ch > 12 && cw > 2 && ch > cw * 1.2 && rect.y > 4 && rect.y < 140)) continue
            var topX = 0
            var topY = Int.MAX_VALUE
            for (point in contour.toArray()) {
                val py = point.y.toInt()
                if (py < topY) {
                    topY = py
                    topX = point.x.toInt()
                }
            }
            val box = FieldPixelMath.tipBoxForPixel(topX, topY)
            val gaussian = FieldPixelMath.tipGaussian48(box)
            for (c in 0 until cells) {
                if (gaussian[c] > tips[c]) tips[c] = gaussian[c]
            }
            if (FieldPixelMath.isNovelTip(tipBoxes, box.x, box.y)) {
                tipBoxes += box
            }
        }
        return tips to tipBoxes.toList()
    }

    /**
     * Freeze lines 55-58 order: `normal()` runs on the 96x96 float32 map.
     * Bounded readback preserves the float32 source values, the JVM-tested
     * [FieldPixelMath.normalizeField] normalizes, and the normalized map is
     * written to an owned `CV_32F` Mat for the 48x48 `INTER_AREA` step.
     */
    private fun normalize96ToOwned(owner: MatOwner, map96: Mat): Mat {
        check(map96.rows() == 96 && map96.cols() == 96 && map96.channels() == 1) {
            "field map is not 96x96"
        }
        val floats = readbackFloats(map96)
        val normalized = FieldPixelMath.normalizeField(DoubleArray(floats.size) { floats[it].toDouble() })
        val out = owner.own(Mat(96, 96, CvType.CV_32FC1))
        out.put(0, 0, FloatArray(normalized.size) { normalized[it].toFloat() })
        return out
    }

    /** Bounded 48x48 readback with NO second normalization (lines 71-72). */
    private fun readback48(map48: Mat): DoubleArray {
        check(map48.rows() == 48 && map48.cols() == 48 && map48.channels() == 1) {
            "field map is not 48x48"
        }
        val floats = readbackFloats(map48)
        return DoubleArray(floats.size) { floats[it].toDouble() }
    }

    /** Bounded float readback via an immediately-released reshape header. */
    private fun readbackFloats(mat: Mat): FloatArray {
        val floats = FloatArray(mat.rows() * mat.cols() * mat.channels())
        // A reshape header shares the map data; release it immediately after
        // the bounded copy and never register it as an owned source.
        val flat = mat.reshape(1, 1)
        try {
            flat.get(0, 0, floats)
        } finally {
            flat.release()
        }
        return floats
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
        const val THREAD_COUNT = 2
        const val LOG_EPSILON = 1e-8
        const val CANNY_LOW = 20.0
        const val CANNY_HIGH = 60.0
    }
}
