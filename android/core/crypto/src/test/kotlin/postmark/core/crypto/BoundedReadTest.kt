package postmark.core.crypto

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Boundary and hostile-stream proof for [readBoundedBytes]. */
class BoundedReadTest {

    private open class FixedStream(private val payload: ByteArray) : InputStream() {
        private var position = 0
        override fun read(): Int =
            if (position >= payload.size) -1 else payload[position++].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= payload.size) return -1
            val n = minOf(len, payload.size - position)
            System.arraycopy(payload, position, b, off, n)
            position += n
            return n
        }
    }

    /** Never ends; every buffered read hands back fresh nonzero bytes. */
    private object EndlessStream : InputStream() {
        override fun read(): Int = 0x41
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            java.util.Arrays.fill(b, off, off + len, 0x41)
            return len
        }
    }

    /** Violates the InputStream contract by returning zero-length reads forever. */
    private object ZeroLengthHostileStream : InputStream() {
        override fun read(): Int = 0x41
        override fun read(b: ByteArray, off: Int, len: Int): Int = 0
    }

    /** Reports an absurd availability while holding ordinary finite content. */
    private class LyingAvailableStream(payload: ByteArray) : FixedStream(payload) {
        override fun available(): Int = Int.MAX_VALUE
    }

    @Test
    fun readsPayloadSmallerThanLimit() {
        val payload = ByteArray(LIMIT - 1) { it.toByte() }
        assertContentEquals(payload, FixedStream(payload).readBoundedBytes(LIMIT))
    }

    @Test
    fun readsPayloadOfExactlyTheLimit() {
        val payload = ByteArray(LIMIT) { it.toByte() }
        assertContentEquals(payload, FixedStream(payload).readBoundedBytes(LIMIT))
    }

    @Test
    fun rejectsPayloadOneByteOverTheLimitWithoutBufferingIt() {
        val payload = ByteArray(LIMIT + 1) { it.toByte() }
        val failure = assertFailsWith<IllegalArgumentException> {
            FixedStream(payload).readBoundedBytes(LIMIT)
        }
        assertEquals("source exceeds $LIMIT-byte plaintext budget", failure.message)
    }

    @Test
    fun endlessHostileStreamIsCutOffAtTheBudgetInsteadOfGrowingForever() {
        assertFailsWith<IllegalArgumentException> {
            EndlessStream.readBoundedBytes(LIMIT)
        }
    }

    @Test
    fun zeroLengthReadsAreRejectedAsBrokenInsteadOfSpinningForever() {
        assertFailsWith<IOException> {
            ZeroLengthHostileStream.readBoundedBytes(LIMIT)
        }
    }

    @Test
    fun availableIsNeverTrustedSoALyingStreamIsReadCorrectlyInsideTheBudget() {
        val payload = "jpeg-payload".toByteArray()
        assertContentEquals(payload, LyingAvailableStream(payload).readBoundedBytes(LIMIT))
    }

    @Test
    fun emptyStreamYieldsEmptyBytes() {
        assertEquals(0, FixedStream(ByteArray(0)).readBoundedBytes(LIMIT).size)
    }

    @Test
    fun negativeLimitIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ByteArrayInputStream(ByteArray(1)).readBoundedBytes(-1)
        }
    }

    companion object {
        private const val LIMIT: Int = 4096
    }
}
