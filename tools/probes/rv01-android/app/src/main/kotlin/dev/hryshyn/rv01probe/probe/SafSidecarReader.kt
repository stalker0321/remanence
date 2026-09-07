package dev.hryshyn.rv01probe.probe

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fixed, redaction-safe results for one selected SAF document operation. */
enum class SafTransportOutcome {
    COMPLETED,
    MISSING,
    REVOKED,
    SECURITY_REJECTED,
    IO_ERROR,
    TIMEOUT,
    CANCELLED,
    INDETERMINATE,
}

/**
 * Probe-owned result. It deliberately retains no exception, URI, provider, or
 * path data. Only COMPLETED may carry a value.
 */
class SafTransportResult<T> private constructor(
    val outcome: SafTransportOutcome,
    val value: T?,
) {
    init {
        require((outcome == SafTransportOutcome.COMPLETED) == (value != null))
    }

    companion object {
        fun <T> completed(value: T): SafTransportResult<T> =
            SafTransportResult(SafTransportOutcome.COMPLETED, value)

        fun <T> failure(outcome: SafTransportOutcome): SafTransportResult<T> {
            require(outcome != SafTransportOutcome.COMPLETED)
            return SafTransportResult(outcome, null)
        }
    }
}

/** The operator-selected grant mode; the transport never persists a grant. */
enum class SafGrantSelection {
    ONE_SHOT,
    PERSISTABLE_EXPLICITLY_SELECTED,
}

/**
 * Opaque selected-document port. An Android SAF bridge may implement this
 * around one user-selected URI. No URI or provider metadata enters this seam.
 */
interface SafSelectedDocument {
    /** Untrusted optimization hint only; the reader never uses it as a bound. */
    val providerLengthHintBytes: Long?

    fun openInput(): SafTransportResult<InputStream>

    fun openOutput(): SafTransportResult<OutputStream>
}

/** One selected document and the grant mode explicitly chosen by the operator. */
class SafDocumentSelection(
    val document: SafSelectedDocument,
    val grantSelection: SafGrantSelection,
)

/** A narrow deterministic control port for timeout/cancellation testing. */
enum class SafControlSignal {
    CONTINUE,
    TIMEOUT,
    CANCELLED,
}

fun interface SafOperationControl {
    fun poll(): SafControlSignal
}

private val NO_SAF_CONTROL = SafOperationControl { SafControlSignal.CONTINUE }

/**
 * First-settlement-wins guard for a SAF callback/operation owner. Late provider
 * callbacks are ignored by the owner and cannot replace a timeout/cancellation.
 */
class SafOperationSettlement<T> {
    private val lock = Any()
    private var settled: SafTransportResult<T>? = null

    fun settle(result: SafTransportResult<T>): Boolean = synchronized(lock) {
        if (settled != null) return false
        settled = result
        true
    }

    fun complete(value: T): Boolean = settle(SafTransportResult.completed(value))

    fun fail(outcome: SafTransportOutcome): Boolean =
        settle(SafTransportResult.failure(outcome))

    fun snapshot(): SafTransportResult<T>? = synchronized(lock) { settled }
}

/**
 * Probe-only SAF P transport. It reads/writes one already-selected document,
 * never scans a directory, and never accepts U, K_U, canary, plaintext, or a
 * provider identity. A persistable grant is metadata supplied by the chooser;
 * this class neither requests nor stores it and makes no uninstall-survival
 * claim. After reinstall, the operator must supply a new selection.
 */
class SafSidecarPTransport(
    private val selection: SafDocumentSelection?,
) : PTransport {
    /** Detailed status used by the future real composition. */
    fun storePExact(
        opaqueP: ByteArray,
        expectedContext: ExpectedContext,
        control: SafOperationControl = NO_SAF_CONTROL,
    ): SafTransportResult<Unit> {
        if (opaqueP.size > MAX_BYTES || !isStrictSidecar(opaqueP, expectedContext)) {
            return SafTransportResult.failure(SafTransportOutcome.SECURITY_REJECTED)
        }
        val selected = selection ?: return SafTransportResult.failure(SafTransportOutcome.MISSING)
        when (val signal = control.poll()) {
            SafControlSignal.CONTINUE -> Unit
            SafControlSignal.TIMEOUT ->
                return SafTransportResult.failure(SafTransportOutcome.TIMEOUT)
            SafControlSignal.CANCELLED ->
                return SafTransportResult.failure(SafTransportOutcome.CANCELLED)
        }

        val output = try {
            selected.document.openOutput()
        } catch (_: SecurityException) {
            SafTransportResult.failure(SafTransportOutcome.REVOKED)
        } catch (_: IOException) {
            SafTransportResult.failure(SafTransportOutcome.IO_ERROR)
        } catch (_: RuntimeException) {
            SafTransportResult.failure(SafTransportOutcome.INDETERMINATE)
        }
        if (output.outcome != SafTransportOutcome.COMPLETED) {
            return SafTransportResult.failure(output.outcome)
        }

        val stream = output.value ?: return SafTransportResult.failure(
            SafTransportOutcome.INDETERMINATE,
        )
        var writeStarted = false
        var closeAttempted = false
        var result = SafTransportResult.failure<Unit>(SafTransportOutcome.INDETERMINATE)
        try {
            stream.write(opaqueP)
            writeStarted = true
            when (val signal = control.poll()) {
                SafControlSignal.CONTINUE -> Unit
                SafControlSignal.TIMEOUT -> {
                    result = SafTransportResult.failure(SafTransportOutcome.TIMEOUT)
                    return result
                }
                SafControlSignal.CANCELLED -> {
                    result = SafTransportResult.failure(SafTransportOutcome.CANCELLED)
                    return result
                }
            }
            stream.flush()
            closeAttempted = true
            stream.close()
            result = verifyWritten(opaqueP, expectedContext, control)
        } catch (_: SecurityException) {
            result = if (writeStarted) {
                SafTransportResult.failure(SafTransportOutcome.INDETERMINATE)
            } else {
                SafTransportResult.failure(SafTransportOutcome.REVOKED)
            }
        } catch (_: IOException) {
            result = if (writeStarted) {
                SafTransportResult.failure(SafTransportOutcome.INDETERMINATE)
            } else {
                SafTransportResult.failure(SafTransportOutcome.IO_ERROR)
            }
        } catch (_: RuntimeException) {
            result = SafTransportResult.failure(SafTransportOutcome.INDETERMINATE)
        } finally {
            if (!closeAttempted) {
                closeAttempted = true
                try {
                    stream.close()
                } catch (_: Exception) {
                    result = SafTransportResult.failure(SafTransportOutcome.INDETERMINATE)
                }
            }
        }
        return result
    }

    /** Detailed bounded read of exactly one selected sidecar document. */
    fun readPExact(
        expectedContext: ExpectedContext,
        control: SafOperationControl = NO_SAF_CONTROL,
    ): SafTransportResult<ByteArray> {
        val selected = selection ?: return SafTransportResult.failure(SafTransportOutcome.MISSING)
        when (val signal = control.poll()) {
            SafControlSignal.CONTINUE -> Unit
            SafControlSignal.TIMEOUT ->
                return SafTransportResult.failure(SafTransportOutcome.TIMEOUT)
            SafControlSignal.CANCELLED ->
                return SafTransportResult.failure(SafTransportOutcome.CANCELLED)
        }
        val input = try {
            selected.document.openInput()
        } catch (_: SecurityException) {
            return SafTransportResult.failure(SafTransportOutcome.REVOKED)
        } catch (_: IOException) {
            return SafTransportResult.failure(SafTransportOutcome.IO_ERROR)
        } catch (_: RuntimeException) {
            return SafTransportResult.failure(SafTransportOutcome.INDETERMINATE)
        }
        if (input.outcome != SafTransportOutcome.COMPLETED) {
            return SafTransportResult.failure(input.outcome)
        }
        val stream = input.value ?: return SafTransportResult.failure(
            SafTransportOutcome.INDETERMINATE,
        )
        return readBounded(stream, expectedContext, control)
    }

    /**
     * Compatibility view for the earlier PTransport seam. Its older result
     * type cannot represent SECURITY_REJECTED, so that status is conservatively
     * collapsed to INDETERMINATE; callers requiring exact SAF status use the
     * detailed methods above. No failure maps to Completed.
     */
    override fun storeP(opaqueP: ByteArray, expectedContext: ExpectedContext): TaskResult<Unit> =
        storePExact(opaqueP, expectedContext).asTaskResult()

    override fun readP(expectedContext: ExpectedContext): TaskResult<ByteArray> =
        readPExact(expectedContext).asTaskResult()

    private fun verifyWritten(
        expected: ByteArray,
        expectedContext: ExpectedContext,
        control: SafOperationControl,
    ): SafTransportResult<Unit> {
        val read = readPExact(expectedContext, control)
        if (read.outcome != SafTransportOutcome.COMPLETED) {
            return SafTransportResult.failure(read.outcome)
        }
        val actual = read.value ?: return SafTransportResult.failure(
            SafTransportOutcome.INDETERMINATE,
        )
        return try {
            if (actual.contentEquals(expected)) {
                SafTransportResult.completed(Unit)
            } else {
                SafTransportResult.failure(SafTransportOutcome.SECURITY_REJECTED)
            }
        } finally {
            actual.fill(0)
        }
    }

    private fun readBounded(
        stream: InputStream,
        expectedContext: ExpectedContext,
        control: SafOperationControl,
    ): SafTransportResult<ByteArray> {
        val bounded = ByteArray(MAX_BYTES)
        var count = 0
        var aborted = false
        var result = SafTransportResult.failure<ByteArray>(SafTransportOutcome.INDETERMINATE)
        try {
            loop@ while (count < MAX_BYTES) {
                when (val signal = control.poll()) {
                    SafControlSignal.CONTINUE -> Unit
                    SafControlSignal.TIMEOUT -> {
                        result = SafTransportResult.failure(SafTransportOutcome.TIMEOUT)
                        aborted = true
                        break@loop
                    }
                    SafControlSignal.CANCELLED -> {
                        result = SafTransportResult.failure(SafTransportOutcome.CANCELLED)
                        aborted = true
                        break@loop
                    }
                }
                if (aborted) break@loop
                val read = stream.read(bounded, count, MAX_BYTES - count)
                when {
                    read < 0 -> break
                    read == 0 -> {
                        result = SafTransportResult.failure(SafTransportOutcome.INDETERMINATE)
                        aborted = true
                        break@loop
                    }
                    read > MAX_BYTES - count -> {
                        result = SafTransportResult.failure(SafTransportOutcome.INDETERMINATE)
                        aborted = true
                        break@loop
                    }
                    else -> count += read
                }
            }

            if (!aborted) {
                if (count == MAX_BYTES && stream.read() >= 0) {
                    result = SafTransportResult.failure(SafTransportOutcome.SECURITY_REJECTED)
                } else if (count != ProbeSidecar.TOTAL_BYTES) {
                    result = SafTransportResult.failure(SafTransportOutcome.SECURITY_REJECTED)
                } else {
                    val exact = bounded.copyOf(count)
                    if (!isStrictSidecar(exact, expectedContext)) {
                        exact.fill(0)
                        result = SafTransportResult.failure(SafTransportOutcome.SECURITY_REJECTED)
                    } else {
                        result = SafTransportResult.completed(exact)
                    }
                }
            }
        } catch (_: SecurityException) {
            result = SafTransportResult.failure(SafTransportOutcome.REVOKED)
        } catch (_: IOException) {
            result = SafTransportResult.failure(SafTransportOutcome.IO_ERROR)
        } catch (_: RuntimeException) {
            result = SafTransportResult.failure(SafTransportOutcome.INDETERMINATE)
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
                result.value?.fill(0)
                result = SafTransportResult.failure(SafTransportOutcome.INDETERMINATE)
            }
            bounded.fill(0)
        }
        return result
    }

    /** Structural validation only; AEAD remains the later U-bound operation. */
    private fun isStrictSidecar(encoded: ByteArray, expectedContext: ExpectedContext): Boolean {
        if (encoded.size > MAX_BYTES || encoded.size != ProbeSidecar.TOTAL_BYTES) return false
        val expectedBytes = expectedContext.canonicalBytes()
        var claimedContext: ByteArray? = null
        return try {
            val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
            if (buffer.int != SIDECAR_MAGIC) return false
            if ((buffer.short.toInt() and 0xffff) != SIDECAR_VERSION) return false
            if ((buffer.short.toInt() and 0xffff) != ExpectedContext.CANONICAL_BYTES) return false
            claimedContext = ByteArray(ExpectedContext.CANONICAL_BYTES).also(buffer::get)
            if (!ExpectedContext.isCanonical(claimedContext!!)) return false
            if (!claimedContext!!.contentEquals(expectedBytes)) return false
            if ((buffer.get().toInt() and 0xff) != SIDECAR_NONCE_BYTES) return false
            buffer.position(buffer.position() + SIDECAR_NONCE_BYTES)
            if ((buffer.short.toInt() and 0xffff) != SIDECAR_CIPHERTEXT_BYTES) return false
            buffer.remaining() == SIDECAR_CIPHERTEXT_BYTES
        } catch (_: RuntimeException) {
            false
        } finally {
            expectedBytes.fill(0)
            claimedContext?.fill(0)
        }
    }

    private fun <T> SafTransportResult<T>.asTaskResult(): TaskResult<T> = when (outcome) {
        SafTransportOutcome.COMPLETED -> TaskResult.Completed(checkNotNull(value))
        SafTransportOutcome.MISSING,
        SafTransportOutcome.REVOKED,
        -> TaskResult.Unavailable
        SafTransportOutcome.IO_ERROR,
        SafTransportOutcome.TIMEOUT,
        SafTransportOutcome.CANCELLED,
        -> TaskResult.RetryableUnavailable
        SafTransportOutcome.SECURITY_REJECTED,
        SafTransportOutcome.INDETERMINATE,
        -> TaskResult.Indeterminate
    }

    private companion object {
        const val MAX_BYTES = 1024
        const val SIDECAR_MAGIC = 0x52565031 // ASCII RVP1
        const val SIDECAR_VERSION = 1
        const val SIDECAR_NONCE_BYTES = 12
        const val SIDECAR_CIPHERTEXT_BYTES = 48
    }
}
