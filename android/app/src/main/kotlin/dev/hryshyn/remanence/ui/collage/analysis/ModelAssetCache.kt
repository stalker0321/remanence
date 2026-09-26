package dev.hryshyn.remanence.ui.collage.analysis

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Bundled-model source with pinned integrity metadata (A1 assets). */
interface ModelAssetSource {
    val sha256: String
    val byteSize: Long
    val maxBytes: Long
    fun open(): InputStream
}

/** NanoDet-specific alias retained for existing call sites/probes. */
interface NanoDetModelAssetSource : ModelAssetSource

/**
 * Shared bounded asset → private-cache materialization: streams the asset into
 * a `.part` file, verifies the exact byte size and SHA-256, promotes atomically
 * via rename, deletes partials/target on any failure, and reuses the promoted
 * file across calls. [delete] removes the cached file.
 */
internal class ModelAssetCache(
    private val source: ModelAssetSource,
    private val cacheDir: File,
    private val fileName: String,
) {
    @Volatile
    private var materialized: File? = null

    fun materialize(): File {
        materialized?.let { return it }
        cacheDir.mkdirs()
        val target = File(cacheDir, fileName)
        val partial = File(cacheDir, "$fileName.part")
        try {
            source.open().use { input ->
                partial.outputStream().use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= source.maxBytes) { "model asset exceeds bound" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    check(total == source.byteSize) { "model asset size mismatch" }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    check(hex == source.sha256) { "model asset checksum mismatch" }
                }
            }
            check(partial.renameTo(target)) { "model promote failed" }
            materialized = target
            return target
        } catch (failure: Exception) {
            partial.delete()
            target.delete()
            throw failure
        }
    }

    fun delete() {
        materialized?.delete()
        materialized = null
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
