package dev.hryshyn.remanence.ui.collage.analysis

import dev.hryshyn.remanence.ui.collage.AnalysisObject
import dev.hryshyn.remanence.ui.collage.NanoDetDecoder
import dev.hryshyn.remanence.ui.collage.NanoDetHead
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
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

/** Bundled-model source with pinned integrity metadata (A1 assets). */
interface NanoDetModelAssetSource {
    val sha256: String
    val byteSize: Long
    val maxBytes: Long
    fun open(): InputStream
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
 * Diagnostics carry only elapsed time, head shapes and detection count — never
 * a path, image bytes, or exception message.
 */
class NanoDetRunner(
    private val assetSource: NanoDetModelAssetSource,
    private val runtime: NanoDetRuntime,
    private val cacheDir: File,
) : AutoCloseable {

    private val mutex = Mutex()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "collage-nanodet").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private var modelFile: File? = null
    private var loaded = false
    private var closed = false

    suspend fun detect(image: NanoDetImage): NanoDetResult = mutex.withLock {
        if (closed) return@withLock NanoDetResult.Unavailable("runner is closed")
        currentCoroutineContext().ensureActive()

        val file = try {
            ensureModel()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return@withLock NanoDetResult.Unavailable(simpleName(failure))
        }
        currentCoroutineContext().ensureActive()

        if (!loaded) {
            try {
                runtime.load(file.absolutePath)
                loaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return@withLock NanoDetResult.Unavailable(simpleName(failure))
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
            return@withLock NanoDetResult.Unavailable(simpleName(failure))
        }
        currentCoroutineContext().ensureActive()

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        return@withLock when (val decoded = NanoDetDecoder.decode(heads, letterbox)) {
            is NanoDetDecoder.Result.Detections ->
                NanoDetResult.Detections(decoded.objects, elapsedMs, heads.map { it.shape.toList() })

            is NanoDetDecoder.Result.Invalid ->
                NanoDetResult.Invalid("malformed NanoDet heads")
        }
    }

    private fun ensureModel(): File {
        modelFile?.let { return it }
        cacheDir.mkdirs()
        val target = File(cacheDir, MODEL_FILE_NAME)
        val partial = File(cacheDir, "$MODEL_FILE_NAME.part")
        try {
            assetSource.open().use { input ->
                partial.outputStream().use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= assetSource.maxBytes) { "model asset exceeds bound" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    check(total == assetSource.byteSize) { "model asset size mismatch" }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    check(hex == assetSource.sha256) { "model asset checksum mismatch" }
                }
            }
            check(partial.renameTo(target)) { "model promote failed" }
            modelFile = target
            return target
        } catch (failure: Exception) {
            partial.delete()
            target.delete()
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { runtime.close() }
        modelFile?.delete()
        modelFile = null
        loaded = false
        runCatching { executor.shutdownNow() }
    }

    private fun simpleName(failure: Exception): String = failure::class.simpleName ?: "Exception"

    private companion object {
        const val MODEL_FILE_NAME = "nanodet.onnx"
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
