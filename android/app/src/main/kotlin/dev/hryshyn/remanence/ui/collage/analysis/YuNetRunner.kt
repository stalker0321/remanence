package dev.hryshyn.remanence.ui.collage.analysis

import dev.hryshyn.remanence.ui.collage.AnalysisFace
import java.io.File
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

/** Upright ARGB source image (`0xAARRGGBB`), primitive so the port stays JVM-safe. */
class YuNetImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "source dimensions must be positive" }
        require(argb.size == width * height) { "ARGB length must equal width*height" }
    }
}

/**
 * The native boundary. The only implementation that touches OpenCV is
 * [OpenCvYuNetRuntime]; JVM tests inject a fake.
 */
interface YuNetRuntime : AutoCloseable {
    fun load(modelPath: String, config: YuNetConfig)

    fun detect(image: YuNetImage, longEdge: Int): YuNetPass

    override fun close()
}

sealed interface YuNetResult {
    data class Faces(
        val faces: List<AnalysisFace>,
        val elapsedMs: Long,
        val passShapes: List<List<Int>>,
    ) : YuNetResult

    /** Redacted operational failure (asset/load/forward). */
    data class Unavailable(val reason: String) : YuNetResult

    /** Post-processing rejected the detector rows. */
    data class Invalid(val reason: String) : YuNetResult
}

/**
 * Non-production orchestration around [YuNetRuntime]: bounded asset →
 * private-cache materialization with exact size/SHA-256 verification and an
 * atomic promote, one model load and one `FaceDetectorYN` reused, the
 * deterministic two-pass long-edge schedule (640 then 960) over the upright
 * source, serialized `detect` calls, and redacted typed failures. It is never
 * wired to production `CollageCropSource`/Create.
 *
 * Lifecycle matches [NanoDetRunner]: a short-held [ReentrantLock] guards the
 * `closed`/`active` state; `detect` takes it only to register/deregister itself
 * (never across a native pass), and `close` marks closed, waits for any active
 * detect to finish, then clears the runtime/cache and gracefully
 * `executor.shutdown()` (never `shutdownNow`). A [Mutex] serializes the work of
 * concurrent `detect` calls. Diagnostics carry only elapsed time, per-pass
 * output shapes and face count — never a path, image bytes, or exception
 * message.
 */
class YuNetRunner(
    assetSource: ModelAssetSource,
    private val runtime: YuNetRuntime,
    cacheDir: File,
    private val config: YuNetConfig = YuNetConfig(),
) : AutoCloseable {

    private val cache = ModelAssetCache(assetSource, cacheDir, MODEL_FILE_NAME)
    private val workMutex = Mutex()
    private val lifecycle = ReentrantLock()
    private val idle = lifecycle.newCondition()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "collage-yunet").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private var loaded = false
    private var active = false
    private var closed = false

    suspend fun detect(image: YuNetImage): YuNetResult = workMutex.withLock {
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
        if (alreadyClosed) return@withLock YuNetResult.Unavailable("runner is closed")

        try {
            detectInternal(image)
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

    private suspend fun detectInternal(image: YuNetImage): YuNetResult {
        currentCoroutineContext().ensureActive()

        val file = try {
            cache.materialize()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return YuNetResult.Unavailable(simpleName(failure))
        }
        currentCoroutineContext().ensureActive()

        if (!loaded) {
            try {
                runtime.load(file.absolutePath, config)
                loaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return YuNetResult.Unavailable(simpleName(failure))
            }
        }
        currentCoroutineContext().ensureActive()

        val passShapes = ArrayList<List<Int>>(config.longEdges.size)
        val passes = ArrayList<YuNetPass>(config.longEdges.size)
        val start = System.nanoTime()
        try {
            for (longEdge in config.longEdges) {
                currentCoroutineContext().ensureActive()
                val pass = withContext(dispatcher) {
                    currentCoroutineContext().ensureActive()
                    runtime.detect(image, longEdge)
                }
                passes += pass
                passShapes += listOf(pass.passHeight, pass.passWidth, pass.columns)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return YuNetResult.Unavailable(simpleName(failure))
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        currentCoroutineContext().ensureActive()

        val faces = try {
            YuNetFacePostProcessor.merge(passes, config.dedupIou)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return YuNetResult.Invalid("malformed YuNet rows")
        }
        return YuNetResult.Faces(faces, elapsedMs, passShapes)
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
            cache.delete()
            loaded = false
            runCatching { executor.shutdown() }
        } finally {
            lifecycle.unlock()
        }
    }

    private fun simpleName(failure: Exception): String = failure::class.simpleName ?: "Exception"

    private companion object {
        const val MODEL_FILE_NAME = "yunet.onnx"
    }
}
