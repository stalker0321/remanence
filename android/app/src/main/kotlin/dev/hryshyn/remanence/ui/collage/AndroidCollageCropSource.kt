package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageImage
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageResult
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageRunner
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetImage
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetResult
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetRunner
import dev.hryshyn.remanence.ui.collage.analysis.YuNetImage
import dev.hryshyn.remanence.ui.collage.analysis.YuNetResult
import dev.hryshyn.remanence.ui.collage.analysis.YuNetRunner
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Suspend source of full-resolution EXIF-upright JPEG bytes per content ID.
 * Returns `null` when the bytes are unavailable. The returned array is owned
 * by the caller after return; the seam zeroizes it once decoded. Never wired
 * to a production picker/source.
 */
fun interface UprightBytesSource {
    suspend fun bytesFor(contentId: String): ByteArray?
}

/**
 * Host-neutral decode of upright JPEG bytes to upright ARGB. Returns `null`
 * when undecodable. Must apply the EXIF transpose at full resolution (the
 * probe uses `FieldUprightDecoder::decodeUpright`); the 2560 re-encoded
 * normalized/preview bytes must never be substituted.
 */
fun interface UprightImageDecoder {
    fun decode(jpeg: ByteArray): FieldStageImage?
}

/**
 * Non-production [CollageCropSource] seam over the existing suspend runners.
 * For each content ID it decodes the full-resolution upright bytes once per
 * instance (in-memory cache), runs the field pixel stage plus the NanoDet and
 * YuNet runners, folds everything through the frozen [FieldAggregator], and
 * solves [Envelope50CropSolver] separately for each requested style slot
 * (slots differ per style, so crops are never a single reused per-photo map).
 *
 * Failure taxonomy (provider contract preserved exactly): an unavailable
 * source, undecodable bytes, or any runner `Unavailable`/`Invalid` yields
 * `null` (the provider reports `Unavailable`); a slot the solver rejects is
 * omitted from the map (the provider reports `Rejected` on the missing key
 * or invalid crop). No new result type is added.
 *
 * Cancellation propagates untouched and never caches: only a fully computed
 * analysis is stored. [close] is idempotent and releases all three owned
 * runners plus the cache; analyze-after-close fails closed to `null`. No
 * `runBlocking`; the Compose host calls this from its `LaunchedEffect`.
 * Never mounted in production: no Create wiring, no capability change.
 */
class AndroidCollageCropSource(
    private val bytesSource: UprightBytesSource,
    private val imageDecoder: UprightImageDecoder,
    private val fieldRunner: FieldStageRunner,
    private val nanoDetRunner: NanoDetRunner,
    private val yuNetRunner: YuNetRunner,
) : CollageCropSource, AutoCloseable {

    private val mutex = Mutex()
    private val analyses = LinkedHashMap<String, CollageAnalysis>()

    @Volatile
    private var closed = false

    override suspend fun cropsFor(requests: List<CollageCropRequest>): Map<String, GeneratorExpression.CropWindow>? =
        mutex.withLock {
            if (closed) return@withLock null
            val crops = LinkedHashMap<String, GeneratorExpression.CropWindow>(requests.size)
            for (request in requests) {
                val analysis = analysisFor(request.contentId) ?: return@withLock null
                if (closed) return@withLock null
                val crop = Envelope50CropSolver.solve(
                    analysis,
                    Envelope50CropSolver.Slot(
                        width = request.slotWidth.toDouble(),
                        height = request.slotHeight.toDouble(),
                        shape = request.shape,
                        zooms = request.zooms,
                    ),
                )?.toCropWindow() ?: continue
                crops[request.contentId] = crop
            }
            crops
        }

    private suspend fun analysisFor(contentId: String): CollageAnalysis? {
        analyses[contentId]?.let { return it }
        currentCoroutineContext().ensureActive()
        try {
            val bytes = bytesSource.bytesFor(contentId) ?: return null
            try {
                currentCoroutineContext().ensureActive()
                val image = imageDecoder.decode(bytes) ?: return null
                currentCoroutineContext().ensureActive()
                val maps = when (val fields = fieldRunner.analyze(image)) {
                    is FieldStageResult.Maps -> fields.maps
                    is FieldStageResult.Unavailable,
                    is FieldStageResult.Invalid,
                    -> return null
                }
                currentCoroutineContext().ensureActive()
                val objects = when (
                    val detected = nanoDetRunner.detect(NanoDetImage(image.width, image.height, image.argb))
                ) {
                    is NanoDetResult.Detections -> detected.objects
                    is NanoDetResult.Unavailable,
                    is NanoDetResult.Invalid,
                    -> return null
                }
                currentCoroutineContext().ensureActive()
                val faces = when (
                    val detected = yuNetRunner.detect(YuNetImage(image.width, image.height, image.argb))
                ) {
                    is YuNetResult.Faces -> detected.faces
                    is YuNetResult.Unavailable,
                    is YuNetResult.Invalid,
                    -> return null
                }
                currentCoroutineContext().ensureActive()
                val analysis = FieldAggregator.aggregate(
                    ImportFieldPrimitives(
                        sourceWidth = image.width,
                        sourceHeight = image.height,
                        faces = faces,
                        objects = objects,
                        saliency = maps.saliency,
                        edgeEnergy = maps.edgeEnergy,
                        contrast = maps.contrast,
                        structureField = maps.tips,
                        tipBoxes = maps.tipBoxes,
                    ),
                )
                if (!closed) analyses[contentId] = analysis
                return analysis
            } finally {
                bytes.fill(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
    }

    override fun close() {
        closed = true
        runCatching { fieldRunner.close() }
        runCatching { nanoDetRunner.close() }
        runCatching { yuNetRunner.close() }
        analyses.clear()
    }
}
