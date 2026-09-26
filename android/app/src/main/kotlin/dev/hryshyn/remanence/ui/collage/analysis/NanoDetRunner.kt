package dev.hryshyn.remanence.ui.collage.analysis

import dev.hryshyn.remanence.ui.collage.AnalysisObject
import dev.hryshyn.remanence.ui.collage.NanoDetDecoder
import dev.hryshyn.remanence.ui.collage.NanoDetHead
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
class NanoDetImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "source dimensions must be positive" }
        require(argb.size == width * height) { "ARGB length must equal width*height" }
    }
}

/**
 * The native boundary. The only implementation that touches OpenCV is
 * [OpenCvNanoDetRuntime]; JVM tests inject a fake.
 */
interface NanoDetRuntime : AutoCloseable {
    fun load(modelPath: String)

    fun forward(image: NanoDetImage): List<NanoDetHead>

    override fun close()
}

sealed interface NanoDetResult {
    data class Detections(
        val objects: List<AnalysisObject>,
        val elapsedMs: Long,
        val headShapes: List<List<Int>>,
    ) : NanoDetResult

    /** Redacted operational failure (asset/load/forward). */
    data class Unavailable(val reason: String) : NanoDetResult

    /** Decoder rejected the head tensors. */
    data class Invalid(val reason: String) : NanoDetResult
}

/**
 * Non-production orchestration around [NanoDetRuntime]: bounded asset →
 * private-cache materialization with exact size/SHA-256 verification and an
 * atomic promote, one model load and one `Net` reused, serialized `detect`
 * calls, and redacted typed failures. It is never wired to production
 * `CollageCropSource`/Create.
 *
 * Lifecycle: a short-held [ReentrantLock] guards the `closed`/`active` state.
 * `detect` takes it only to register/deregister itself (never across the
 * forward pass), and `close` takes it to mark closed, wait for any active
 * detect to finish, then clear the runtime/cache and gracefully
 * `executor.shutdown()` (never `shutdownNow`). A [Mutex] still serializes the
 * work of concurrent `detect` calls. Diagnostics carry only elapsed time, head
 * shapes and detection count — never a path, image bytes, or exception message.
 */
class NanoDetRunner(
    assetSource: ModelAssetSource,
    private val runtime: NanoDetRuntime,
    cacheDir: File,
) : AutoCloseable {

    private val cache = ModelAssetCache(assetSource, cacheDir, MODEL_FILE_NAME)
    private val workMutex = Mutex()
    private val lifecycle = ReentrantLock()
    private val idle = lifecycle.newCondition()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "collage-nanodet").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private var loaded = false
    private var active = false
    private var closed = false

    suspend fun detect(image: NanoDetImage): NanoDetResult = workMutex.withLock {
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
        if (alreadyClosed) return@withLock NanoDetResult.Unavailable("runner is closed")

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

    private suspend fun detectInternal(image: NanoDetImage): NanoDetResult {
        currentCoroutineContext().ensureActive()

        val file = try {
            cache.materialize()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return NanoDetResult.Unavailable(simpleName(failure))
        }
        currentCoroutineContext().ensureActive()

        if (!loaded) {
            try {
                runtime.load(file.absolutePath)
                loaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return NanoDetResult.Unavailable(simpleName(failure))
            }
        }
        currentCoroutineContext().ensureActive()

        val letterbox = NanoDetDecoder.letterbox(image.width, image.height)
        val start = System.nanoTime()
        val heads = try {
            withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                runtime.forward(image)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return NanoDetResult.Unavailable(simpleName(failure))
        }
        currentCoroutineContext().ensureActive()

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        return when (val decoded = NanoDetDecoder.decode(heads, letterbox)) {
            is NanoDetDecoder.Result.Detections ->
                NanoDetResult.Detections(decoded.objects, elapsedMs, heads.map { it.shape.toList() })

            is NanoDetDecoder.Result.Invalid ->
                NanoDetResult.Invalid("malformed NanoDet heads")
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
            cache.delete()
            loaded = false
            runCatching { executor.shutdown() }
        } finally {
            lifecycle.unlock()
        }
    }

    private fun simpleName(failure: Exception): String = failure::class.simpleName ?: "Exception"

    private companion object {
        const val MODEL_FILE_NAME = "nanodet.onnx"
    }
}
