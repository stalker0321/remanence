package dev.hryshyn.remanence.ui.collage.analysis

import dev.hryshyn.remanence.ui.collage.NormBox
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Full-resolution EXIF-upright RGB source image (`0xAARRGGBB`), primitive so
 * the port stays JVM-safe.
 *
 * Ownership contract (slice B5): this MUST be the full-resolution EXIF-upright
 * original (see [FieldUprightDecoder]), symmetric with the oracle
 * `ImageOps.exif_transpose(Image.open(src)).convert("RGB")`
 * (`app/playground-infer.py:20`). The 2560 re-encoded normalized/preview bytes
 * are a different, unproven analysis contract version and must never be
 * silently substituted here.
 */
/**
 * Upright ARGB pixels. [sourceWidth]/[sourceHeight] are the upright original's
 * dimensions when the pixels were decoded at a reduced size; every analysis
 * output is normalized, so only the reported dimensions need the original.
 */
class FieldStageImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val sourceWidth: Int = width,
    val sourceHeight: Int = height,
) {
    init {
        require(width > 0 && height > 0) { "source dimensions must be positive" }
        require(sourceWidth >= width && sourceHeight >= height) { "source dimensions must cover the pixels" }
        require(argb.size == width * height) { "ARGB length must equal width*height" }
    }
}

/**
 * The four 48x48 row-major (`y * 48 + x`) pixel maps plus the contour-order
 * tip boxes produced by the frozen pixel stage
 * (`runtime/tools/legacy_features.py:50-73`): already-normalized `saliency`,
 * `edgeEnergy`, `contrast`, the already-merged `tips` map, and the
 * already-clamped, already-deduped structure-tip boxes. Faces/objects are NOT
 * part of this output; detectors stay unwired. Tests and the diagnostic probe
 * compose these with detector outputs into `ImportFieldPrimitives` for
 * [dev.hryshyn.remanence.ui.collage.FieldAggregator].
 */
data class FieldStageMaps(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val saliency: DoubleArray,
    val edgeEnergy: DoubleArray,
    val contrast: DoubleArray,
    val tips: DoubleArray,
    val tipBoxes: List<NormBox>,
)

/**
 * The native boundary. The only implementation that touches OpenCV is
 * [OpenCvFieldStage]; JVM tests inject a fake.
 */
interface FieldStageRuntime : AutoCloseable {
    fun analyze(image: FieldStageImage): FieldStageMaps

    override fun close()
}

sealed interface FieldStageResult {
    data class Maps(
        val maps: FieldStageMaps,
        val elapsedMs: Long,
    ) : FieldStageResult

    /** Redacted operational failure. */
    data class Unavailable(val reason: String) : FieldStageResult

    /** The runtime returned maps violating the 48x48 finite contract. */
    data class Invalid(val reason: String) : FieldStageResult
}

/**
 * Non-production orchestration around [FieldStageRuntime]: serialized
 * `analyze` calls on one reused worker, redacted typed failures, and the same
 * lifecycle as [NanoDetRunner]/[YuNetRunner] (short-held [ReentrantLock]
 * guarding `closed`/`active`, `close` waits for any active run then
 * `executor.shutdown()`, never `shutdownNow`). There is no model asset to
 * materialize: the pixel stage is pure OpenCV. It is never wired to
 * production `CollageCropSource`/Create; only tests and the diagnostic probe
 * drive it.
 *
 * Diagnostics carry only elapsed time, map shapes and tip count — never a
 * path, image bytes, or exception message.
 */
class FieldStageRunner(
    private val runtime: FieldStageRuntime,
) : AutoCloseable {

    private val workMutex = Mutex()
    private val lifecycle = ReentrantLock()
    private val idle = lifecycle.newCondition()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "collage-field-stage").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private var active = false
    private var closed = false

    suspend fun analyze(image: FieldStageImage): FieldStageResult = workMutex.withLock {
        lifecycle.lock()
        val alreadyClosed = try {
            if (closed) {
                true
            } else {
                active = true
                false
            }
        } finally {
            lifecycle.unlock()
        }
        if (alreadyClosed) return@withLock FieldStageResult.Unavailable("runner is closed")

        try {
            analyzeInternal(image)
        } finally {
            lifecycle.lock()
            try {
                active = false
                idle.signalAll()
            } finally {
                lifecycle.unlock()
            }
        }
    }

    private suspend fun analyzeInternal(image: FieldStageImage): FieldStageResult {
        currentCoroutineContext().ensureActive()

        val start = System.nanoTime()
        val maps = try {
            withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                runtime.analyze(image)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return FieldStageResult.Unavailable(simpleName(failure))
        }
        currentCoroutineContext().ensureActive()

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        if (!mapsAreValid(maps)) return FieldStageResult.Invalid("malformed field maps")
        return FieldStageResult.Maps(maps, elapsedMs)
    }

    private fun mapsAreValid(maps: FieldStageMaps): Boolean {
        if (maps.sourceWidth <= 0 || maps.sourceHeight <= 0) return false
        val cells = dev.hryshyn.remanence.ui.collage.CollageAnalysis.GRID_SIZE *
            dev.hryshyn.remanence.ui.collage.CollageAnalysis.GRID_SIZE
        for (field in listOf(maps.saliency, maps.edgeEnergy, maps.contrast, maps.tips)) {
            if (field.size != cells || !field.all { it.isFinite() }) return false
        }
        return maps.tipBoxes.all {
            it.x.isFinite() && it.y.isFinite() && it.width.isFinite() && it.height.isFinite()
        }
    }

    override fun close() {
        lifecycle.lock()
        try {
            if (closed) return
            closed = true
            var interrupted = false
            while (active) {
                try {
                    idle.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            runCatching { runtime.close() }
            runCatching { executor.shutdown() }
        } finally {
            lifecycle.unlock()
        }
    }

    private fun simpleName(failure: Exception): String = failure::class.simpleName ?: "Exception"
}
