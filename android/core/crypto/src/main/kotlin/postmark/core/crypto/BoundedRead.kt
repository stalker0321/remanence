package postmark.core.crypto

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads [this] stream fully into memory but refuses to buffer more than
 * [limitBytes] bytes (docs/protocol.md: normalized photos are capped at
 * 8 MiB of plaintext). The first read that would push the buffered total
 * past the limit aborts before allocating for it, so a lying or endless
 * source can never grow the buffer beyond one fixed chunk past the budget.
 *
 * Hostile streams fail closed:
 * - a zero-length read from a non-empty request is rejected as broken
 *   rather than spun on forever;
 * - `available()` is never trusted, so streams that lie about it are read
 *   correctly as long as they stay inside the budget.
 *
 * Closing the stream stays the caller's job (`use { }`) so exactly one
 * source is ever open at a time.
 */
fun InputStream.readBoundedBytes(limitBytes: Int): ByteArray {
    require(limitBytes >= 0) { "negative plaintext budget: $limitBytes" }
    val out = ByteArrayOutputStream(minOf(limitBytes, 64 * 1024))
    val chunk = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val read = read(chunk)
        if (read < 0) break
        if (read == 0) {
            throw IOException("hostile stream returned a zero-length read")
        }
        total += read
        if (total > limitBytes) {
            throw IllegalArgumentException("source exceeds $limitBytes-byte plaintext budget")
        }
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}
