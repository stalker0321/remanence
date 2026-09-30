package dev.hryshyn.remanence.core.recognition

import kotlin.math.floor

/**
 * Frozen S1 frame geometry: the exact pure 2:3 portrait frame contract from
 * the authoritative matcher freeze.
 *
 * Line-for-line port of `evidence/frame_first_v2/run.py::frame_geometry` and
 * `::frame_bounds` (`PRODUCTION_FREEZE_SPEC.md` §2: corrected universal UI
 * frame; v1 applied the X/Y percentages independently and is superseded):
 *
 * ```python
 * max_width = width * MAX_WIDTH_FRACTION          # run.py frame_geometry
 * max_height = height * MAX_HEIGHT_FRACTION
 * frame_width = min(max_width, max_height * ASPECT_WIDTH / ASPECT_HEIGHT)
 * frame_height = frame_width * ASPECT_HEIGHT / ASPECT_WIDTH
 * x0 = (width - frame_width) / 2.0               # centred
 * y0 = (height - frame_height) / 2.0
 * # frame_bounds rounds each of x0, y0, frame_width, frame_height with
 * # Python round() (banker's rounding) and returns
 * # (x0, y0, x0 + width, y0 + height).
 * ```
 *
 * Pure integer/float arithmetic only: no image pixels, no ONNX, no index, no
 * UI. [Bounds] is the S2 localizer wiring input (crop window in source image
 * pixels); the S2 crop itself is `image[y0:y1, x0:x1]` per `run.py::frame_crop`
 * and is intentionally not implemented here.
 */
object FrameGeometry {

    /** run.py `MAX_WIDTH_FRACTION`. */
    const val MAX_WIDTH_FRACTION = 0.6934

    /** run.py `MAX_HEIGHT_FRACTION`. */
    const val MAX_HEIGHT_FRACTION = 0.78

    /** run.py `ASPECT_WIDTH` / `ASPECT_HEIGHT`: the frame is always 2:3 portrait. */
    const val ASPECT_WIDTH = 2.0
    const val ASPECT_HEIGHT = 3.0

    /** Floating-point contract values: run.py `frame_geometry` dict. */
    data class Geometry(
        val imageWidth: Int,
        val imageHeight: Int,
        val maxWidth: Double,
        val maxHeight: Double,
        val frameWidth: Double,
        val frameHeight: Double,
        val x0: Double,
        val y0: Double,
    ) {
        /** run.py `aspect` field: always exactly 2/3. */
        val aspect: Double get() = ASPECT_WIDTH / ASPECT_HEIGHT
    }

    /** Integer pixel bounds: run.py `frame_bounds` tuple `(x0, y0, x1, y1)`. */
    data class Bounds(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width: Int get() = x1 - x0
        val height: Int get() = y1 - y0
    }

    /**
     * run.py `frame_geometry(shape)`: corrected 2:3 frame for one image size.
     * Requires positive dimensions; zero/negative sizes have no frame.
     */
    fun geometry(imageWidth: Int, imageHeight: Int): Geometry {
        require(imageWidth > 0) { "imageWidth must be positive, was $imageWidth" }
        require(imageHeight > 0) { "imageHeight must be positive, was $imageHeight" }
        val maxWidth = imageWidth * MAX_WIDTH_FRACTION
        val maxHeight = imageHeight * MAX_HEIGHT_FRACTION
        val frameWidth = minOf(maxWidth, maxHeight * ASPECT_WIDTH / ASPECT_HEIGHT)
        val frameHeight = frameWidth * ASPECT_HEIGHT / ASPECT_WIDTH
        val x0 = (imageWidth - frameWidth) / 2.0
        val y0 = (imageHeight - frameHeight) / 2.0
        return Geometry(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            x0 = x0,
            y0 = y0,
        )
    }

    /**
     * run.py `frame_bounds(shape)`: integer pixel bounds. Each of `x0`, `y0`,
     * `frame_width`, `frame_height` is rounded with Python `round()` semantics
     * (banker's rounding, [bankersRound]), then `x1 = x0 + width`,
     * `y1 = y0 + height` — the corner is never re-rounded.
     */
    fun bounds(imageWidth: Int, imageHeight: Int): Bounds {
        val g = geometry(imageWidth, imageHeight)
        val x0 = bankersRound(g.x0)
        val y0 = bankersRound(g.y0)
        val width = bankersRound(g.frameWidth)
        val height = bankersRound(g.frameHeight)
        return Bounds(x0, y0, x0 + width, y0 + height)
    }

    /**
     * Python `round()` parity for non-negative values: halves round to the
     * even integer (`round(0.5) == 0`, `round(1.5) == 2`, `round(2.5) == 2`),
     * unlike Kotlin `round()`, which rounds halves up. Exact halves are
     * exactly representable in binary floating point, so the equality branch
     * is exact.
     */
    internal fun bankersRound(value: Double): Int {
        require(value >= 0.0) { "bankersRound requires a non-negative value, was $value" }
        val floored = floor(value)
        val fraction = value - floored
        return when {
            fraction < 0.5 -> floored.toInt()
            fraction > 0.5 -> (floored + 1.0).toInt()
            floored.toLong() % 2L == 0L -> floored.toInt()
            else -> (floored + 1.0).toInt()
        }
    }
}
