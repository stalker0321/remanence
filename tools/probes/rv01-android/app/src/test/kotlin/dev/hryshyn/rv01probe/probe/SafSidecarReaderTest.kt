package dev.hryshyn.rv01probe.probe

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafSidecarReaderTest {
    @Test
    fun `reads only selected exact sidecar and ignores unknown or lying length hint`() {
        val context = context()
        val material = bytes(32)
        val p = validSidecar(context, material)

        listOf(null, 0L, p.size.toLong(), 1025L).forEach { hint ->
            val document = FakeDocument(p, hint)
            val selection = SafDocumentSelection(
                document,
                SafGrantSelection.PERSISTABLE_EXPLICITLY_SELECTED,
            )
            val result = SafSidecarPTransport(selection).readPExact(context)

            assertEquals(SafTransportOutcome.COMPLETED, result.outcome)
            assertArrayEquals(p, result.value)
            result.value!!.fill(0)
            assertEquals(1, document.inputOpens)
            assertEquals(0, document.outputOpens)
        }
    }

    @Test
    fun `missing revoked security IO timeout cancel and indeterminate stay fixed`() {
        val context = context()
        val expected = validSidecar(context, bytes(32))
        val statuses = listOf(
            SafTransportOutcome.REVOKED,
            SafTransportOutcome.SECURITY_REJECTED,
            SafTransportOutcome.IO_ERROR,
            SafTransportOutcome.TIMEOUT,
            SafTransportOutcome.CANCELLED,
            SafTransportOutcome.INDETERMINATE,
        )

        statuses.forEach { status ->
            val document = FakeDocument(expected, null).also { it.inputOutcome = status }
            val result = SafSidecarPTransport(selection(document)).readPExact(context)
            assertEquals(status, result.outcome)
            assertNull(result.value)
        }

        val missing = SafSidecarPTransport(null).readPExact(context)
        assertEquals(SafTransportOutcome.MISSING, missing.outcome)
        assertNull(missing.value)
    }

    @Test
    fun `short trailing and overlong documents reject within fixed bound`() {
        val context = context()
        val p = validSidecar(context, bytes(32))
        val values = listOf(
            p.copyOf(ProbeSidecar.TOTAL_BYTES - 1),
            p + byteArrayOf(0),
            ByteArray(ProbeSidecar.MAX_SIZE + 1),
        )

        values.forEach { bytes ->
            val result = SafSidecarPTransport(selection(FakeDocument(bytes, null)))
                .readPExact(context)
            assertEquals(SafTransportOutcome.SECURITY_REJECTED, result.outcome)
            assertNull(result.value)
        }
    }

    @Test
    fun `zero progress and provider exceptions never become success`() {
        val context = context()

        val zeroProgress = FakeDocument(null, null).also {
            it.inputFactory = { ZeroProgressInputStream() }
        }
        assertEquals(
            SafTransportOutcome.INDETERMINATE,
            SafSidecarPTransport(selection(zeroProgress)).readPExact(context).outcome,
        )

        val revoked = FakeDocument(null, null).also {
            it.openInputException = SecurityException()
        }
        assertEquals(
            SafTransportOutcome.REVOKED,
            SafSidecarPTransport(selection(revoked)).readPExact(context).outcome,
        )

        val io = FakeDocument(null, null).also {
            it.openInputException = IOException()
        }
        assertEquals(
            SafTransportOutcome.IO_ERROR,
            SafSidecarPTransport(selection(io)).readPExact(context).outcome,
        )

        val unknown = FakeDocument(null, null).also {
            it.openInputException = IllegalStateException()
        }
        assertEquals(
            SafTransportOutcome.INDETERMINATE,
            SafSidecarPTransport(selection(unknown)).readPExact(context).outcome,
        )
    }

    @Test
    fun `well formed wrong trusted context is rejected before transport success`() {
        val trusted = context()
        val other = context(AccountBindingClass.B, ContextGeneration.G2, ContextTargetRole.D2_TARGET)
        val p = validSidecar(other, bytes(32))
        val result = SafSidecarPTransport(selection(FakeDocument(p, null))).readPExact(trusted)

        assertEquals(SafTransportOutcome.SECURITY_REJECTED, result.outcome)
        assertNull(result.value)
    }

    @Test
    fun `store requires exact frame then closes and verifies exact bytes`() {
        val context = context()
        val p = validSidecar(context, bytes(32))
        val document = FakeDocument(null, null)
        val transport = SafSidecarPTransport(selection(document))

        val result = transport.storePExact(p, context)

        assertEquals(SafTransportOutcome.COMPLETED, result.outcome)
        assertTrue(document.outputClosed)
        assertArrayEquals(p, document.bytes)
        assertEquals(1, document.inputOpens)
    }

    @Test
    fun `partial write and close ambiguity never report completed`() {
        val context = context()
        val p = validSidecar(context, bytes(32))

        val partial = FakeDocument(null, null).also { it.outputMode = OutputMode.PARTIAL }
        val partialResult = SafSidecarPTransport(selection(partial)).storePExact(p, context)
        assertEquals(SafTransportOutcome.SECURITY_REJECTED, partialResult.outcome)
        assertFalse(partialResult.outcome == SafTransportOutcome.COMPLETED)

        val closeFailure = FakeDocument(null, null).also { it.outputMode = OutputMode.CLOSE_IO }
        val closeResult = SafSidecarPTransport(selection(closeFailure)).storePExact(p, context)
        assertEquals(SafTransportOutcome.INDETERMINATE, closeResult.outcome)
        assertFalse(closeResult.outcome == SafTransportOutcome.COMPLETED)

        val writeFailure = FakeDocument(null, null).also { it.outputMode = OutputMode.WRITE_IO }
        val writeResult = SafSidecarPTransport(selection(writeFailure)).storePExact(p, context)
        assertEquals(SafTransportOutcome.IO_ERROR, writeResult.outcome)
        assertFalse(writeResult.outcome == SafTransportOutcome.COMPLETED)
    }

    @Test
    fun `timeout and cancellation close selected stream without success`() {
        val context = context()
        val p = validSidecar(context, bytes(32))

        val timeoutDocument = FakeDocument(p, null)
        val timeout = SafSidecarPTransport(selection(timeoutDocument)).readPExact(
            context,
            ScriptedControl(SafControlSignal.CONTINUE, SafControlSignal.TIMEOUT),
        )
        assertEquals(SafTransportOutcome.TIMEOUT, timeout.outcome)
        assertTrue(timeoutDocument.inputClosed)

        val cancelDocument = FakeDocument(p, null)
        val cancelled = SafSidecarPTransport(selection(cancelDocument)).readPExact(
            context,
            ScriptedControl(SafControlSignal.CONTINUE, SafControlSignal.CANCELLED),
        )
        assertEquals(SafTransportOutcome.CANCELLED, cancelled.outcome)
        assertTrue(cancelDocument.inputClosed)
    }

    @Test
    fun `store timeout before write does not open selected output`() {
        val context = context()
        val p = validSidecar(context, bytes(32))
        val document = FakeDocument(null, null)
        val result = SafSidecarPTransport(selection(document)).storePExact(
            p,
            context,
            ScriptedControl(SafControlSignal.TIMEOUT),
        )

        assertEquals(SafTransportOutcome.TIMEOUT, result.outcome)
        assertEquals(0, document.outputOpens)
        assertNull(document.bytes)
    }

    @Test
    fun `settlement ignores duplicate and late callbacks`() {
        val settlement = SafOperationSettlement<ByteArray>()
        val first = bytes(32)

        assertTrue(settlement.settle(SafTransportResult.completed(first)))
        assertFalse(settlement.fail(SafTransportOutcome.TIMEOUT))
        assertFalse(settlement.fail(SafTransportOutcome.CANCELLED))
        assertEquals(SafTransportOutcome.COMPLETED, settlement.snapshot()!!.outcome)
        assertArrayEquals(first, settlement.snapshot()!!.value)
        first.fill(0)
    }

    @Test
    fun `legacy PTransport view never maps a SAF failure to completion`() {
        val context = context()
        val document = FakeDocument(ByteArray(ProbeSidecar.TOTAL_BYTES - 1), null)
        val result = SafSidecarPTransport(selection(document)).readP(context)

        assertEquals(TaskResultKind.INDETERMINATE, result.kind)
        assertTrue(result !is TaskResult.Completed)
    }

    private fun selection(document: SafSelectedDocument) =
        SafDocumentSelection(document, SafGrantSelection.ONE_SHOT)

    private fun validSidecar(context: ExpectedContext, material: ByteArray): ByteArray =
        checkNotNull(
            ProbeSidecar.seal(
                canary = ByteArray(32) { (it + 1).toByte() },
                unwrapMaterial = material,
                expectedContext = context,
                random = FixedRandom(),
            ),
        )

    private fun context(
        account: AccountBindingClass = AccountBindingClass.A,
        generation: ContextGeneration = ContextGeneration.G1,
        role: ContextTargetRole = ContextTargetRole.D1_SOURCE,
    ) = ExpectedContext(
        accountBindingClass = account,
        runId = ByteArray(16) { (it + 1).toByte() },
        generation = generation,
        targetRole = role,
    )

    private fun bytes(start: Int) = ByteArray(32) { (start + it).toByte() }

    private enum class OutputMode {
        NORMAL,
        PARTIAL,
        WRITE_IO,
        CLOSE_IO,
    }

    private class FakeDocument(
        initialBytes: ByteArray?,
        override val providerLengthHintBytes: Long?,
    ) : SafSelectedDocument {
        var bytes: ByteArray? = initialBytes?.copyOf()
        var inputOutcome: SafTransportOutcome? = null
        var outputOutcome: SafTransportOutcome? = null
        var openInputException: Exception? = null
        var inputFactory: (() -> InputStream)? = null
        var outputMode = OutputMode.NORMAL
        var inputOpens = 0
        var outputOpens = 0
        var inputClosed = false
        var outputClosed = false

        override fun openInput(): SafTransportResult<InputStream> {
            inputOpens += 1
            openInputException?.let { throw it }
            inputOutcome?.let { return SafTransportResult.failure(it) }
            inputFactory?.let { return SafTransportResult.completed(it()) }
            val current = bytes ?: return SafTransportResult.failure(SafTransportOutcome.MISSING)
            return SafTransportResult.completed(
                object : ByteArrayInputStream(current.copyOf()) {
                    override fun close() {
                        inputClosed = true
                        super.close()
                    }
                },
            )
        }

        override fun openOutput(): SafTransportResult<OutputStream> {
            outputOpens += 1
            outputOutcome?.let { return SafTransportResult.failure(it) }
            return SafTransportResult.completed(
                RecordingOutputStream(this, outputMode),
            )
        }

        private class RecordingOutputStream(
            private val owner: FakeDocument,
            private val mode: OutputMode,
        ) : OutputStream() {
            private val buffer = ByteArrayOutputStream()

            override fun write(value: Int) {
                write(byteArrayOf(value.toByte()), 0, 1)
            }

            override fun write(source: ByteArray, offset: Int, length: Int) {
                when (mode) {
                    OutputMode.WRITE_IO -> throw IOException()
                    OutputMode.PARTIAL -> {
                        buffer.write(source, offset, minOf(length, 3))
                    }
                    OutputMode.NORMAL,
                    OutputMode.CLOSE_IO,
                    -> buffer.write(source, offset, length)
                }
            }

            override fun close() {
                owner.bytes = buffer.toByteArray()
                owner.outputClosed = true
                if (mode == OutputMode.CLOSE_IO) throw IOException()
            }
        }
    }

    private class ZeroProgressInputStream : InputStream() {
        override fun read(): Int = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
    }

    private class ScriptedControl(
        private vararg val signals: SafControlSignal,
    ) : SafOperationControl {
        private var index = 0

        override fun poll(): SafControlSignal = signals.getOrNull(index++) ?: SafControlSignal.CONTINUE
    }

    private class FixedRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.forEachIndexed { index, _ -> bytes[index] = (index + 1).toByte() }
        }
    }
}
