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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * when undecodable. Decodes the original bytes (never the 2560 re-encoded
 * normalized/preview bytes) and applies the EXIF transpose; the pixels may be
 * subsampled as long as the image reports the original upright size
 * (production uses `FieldUprightDecoder::decodeForAnalysis`).
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
 * or invalid crop) and its dominant frozen reason is additionally recorded in
 * [lastCropRejections] (diagnostic only, never a substitute crop). No success
 * or `null` behavior changes.
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
    /**
     * Lane for the decode/analysis stages (JPEG decode, model
     * materialize/load, detector passes, aggregation). Production IO:
     * the stages mix disk reads with native inference. The bytes fetch
     * stays on the caller lane (see below), and cache publication stays
     * on the caller lane after the hop; the shared [mutex] serialization
     * spans the hop unchanged. Injectable for hermetic lane tests.
     */
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Parity seam defaults strict; installed legacy source explicitly selects balanced. */
    private val cropPolicy: CropAdmissionPolicy = CropAdmissionPolicy.STRICT,
) : CollageCropSource, CollageCropDiagnostics, AutoCloseable {

    private val mutex = Mutex()
    private val analyses = java.util.concurrent.ConcurrentHashMap<String, CollageAnalysis>()

    /** Cached-only seam: telemetry never triggers detectors or image reads. */
    internal fun cachedAnalysisFor(contentId: String, width: Int, height: Int): CollageAnalysis? =
        if (closed) null else analyses[contentId]?.takeIf { it.width == width && it.height == height }

    @Volatile
    private var closed = false

    @Volatile
    private var rejections: List<CollageSlotRejection> = emptyList()

    /** Dominant frozen solver reason per slot rejected by the last call. */
    override val lastCropRejections: List<CollageSlotRejection>
        get() = rejections

    override suspend fun cropsFor(requests: List<CollageCropRequest>): Map<String, GeneratorExpression.CropWindow>? =
        mutex.withLock {
            rejections = emptyList()
            if (closed) return@withLock null
            val crops = LinkedHashMap<String, GeneratorExpression.CropWindow>(requests.size)
            val rejected = ArrayList<CollageSlotRejection>()
            for (request in requests) {
                val analysis = analysisFor(request.contentId) ?: return@withLock null
                if (closed) return@withLock null
                when (
                    val solved = Envelope50CropSolver.solveDetailed(
                        analysis,
                        Envelope50CropSolver.Slot(
                            width = request.slotWidth.toDouble(),
                            height = request.slotHeight.toDouble(),
                            shape = request.shape,
                            zooms = request.zooms,
                        ),
                        cropPolicy,
                    )
                ) {
                    is CropSolveResult.Solved -> crops[request.contentId] = solved.crop.toCropWindow()
                    is CropSolveResult.Rejected -> rejected += CollageSlotRejection(
                        contentId = request.contentId,
                        slotWidth = request.slotWidth,
                        slotHeight = request.slotHeight,
                        shape = request.shape,
                        reason = solved.reason,
                    )
                }
            }
            rejections = rejected.toList()
            crops
        }

    private suspend fun analysisFor(contentId: String): CollageAnalysis? {
        analyses[contentId]?.let { return it }
        currentCoroutineContext().ensureActive()
        // Holder-safety: the bytes holder (`RetainedOriginalHolder`) guards
        // a plain map with no lock, so the fetch stays on the caller lane
        // with the cache lookup/publication above — only decode/analysis,
        // whose inputs are pure values or self-synchronizing runners, move.
        val bytes = try {
            bytesSource.bytesFor(contentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } ?: return null
        try {
            val analysis = withContext(workerDispatcher) {
                analyzeBytesOnWorker(contentId, bytes)
            } ?: return null
            // Caller-lane close recheck + publication (existing ownership):
            // a close landing mid-worker must never publish a stale row.
            if (!closed) analyses[contentId] = analysis
            return analysis
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Worker-lane decode/analysis product (no cache publication here).
     * Failure taxonomy and cancellation behavior match the historical
     * inline version exactly: decode/runner failures fail closed to
     * `null`; cancellation propagates untouched (never converted).
     * Partial originals are zeroized by the caller-side finally; this
     * function never retains anything.
     */
    private suspend fun analyzeBytesOnWorker(contentId: String, bytes: ByteArray): CollageAnalysis? {
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
        return FieldAggregator.aggregate(
            ImportFieldPrimitives(
                sourceWidth = image.sourceWidth,
                sourceHeight = image.sourceHeight,
                faces = faces,
                objects = objects,
                saliency = maps.saliency,
                edgeEnergy = maps.edgeEnergy,
                contrast = maps.contrast,
                structureField = maps.tips,
                tipBoxes = maps.tipBoxes,
            ),
        )
    }

    /**
     * Shared authoritative-analysis feeder for the T151 all-family producer:
     * the same contentId-keyed cache under the same [mutex], so the assignment
     * matrix and the placement solves share one detector run per photo.
     */
    fun analysisSource(): CollageAnalysisSource =
        CollageAnalysisSource { contentId -> mutex.withLock { analysisFor(contentId) } }

    override fun close() {
        closed = true
        runCatching { fieldRunner.close() }
        runCatching { nanoDetRunner.close() }
        runCatching { yuNetRunner.close() }
        analyses.clear()
        rejections = emptyList()
    }
}
