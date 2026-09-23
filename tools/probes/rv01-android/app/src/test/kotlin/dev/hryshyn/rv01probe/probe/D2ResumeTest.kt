package dev.hryshyn.rv01probe.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2 resume tests: strict handoff parsing, real retrieveU-to-open wiring with
 * no store/delete calls, wrong runId/role/tamper fail-closed, missing U/P
 * contract results, late-callback wipe with timeout/cancel/close cleanup, and
 * redacted enum-only state. No D2 persistence, no Remanence, no
 * physical/cloud PASS.
 */
class D2ResumeTest {

    @Test
    fun `handoff paste parses exact key runId and d2 binding`() {
        val fixture = d1Fixture()
        val handoff = checkNotNull(sealD2Handoff(fixture.key, fixture.canary, fixture.u, fixture.d1))
        val parsed = checkNotNull(ParsedD2Handoff.parse(handoff.display.copyText()))
        try {
            assertEquals(fixture.key, parsed.key)
            assertEquals(ContextTargetRole.D2_TARGET, parsed.context.targetRole)
            assertEquals(ContextTargetRole.D2_TARGET, parsed.fixture.expectedContext.targetRole)
            assertEquals(fixture.key, parsed.fixture.key)
            assertTrue(parsed.context.runId.contentEquals(fixture.d1.runId))
        } finally {
            parsed.close()
        }
    }

    @Test
    fun `handoff parse rejects wrong binding garbage and extras`() {
        val fixture = d1Fixture()
        val handoff = checkNotNull(sealD2Handoff(fixture.key, fixture.canary, fixture.u, fixture.d1))
        val valid = handoff.display.copyText()
        assertNull(ParsedD2Handoff.parse(withToken(valid, "role", "D1_SOURCE")))
        assertNull(ParsedD2Handoff.parse(withToken(valid, "account", "B")))
        assertNull(ParsedD2Handoff.parse(withToken(valid, "generation", "G2")))
        assertNull(ParsedD2Handoff.parse(withToken(valid, "key", "short")))
        assertNull(ParsedD2Handoff.parse(withToken(valid, "runIdHex", "zz")))
        assertNull(ParsedD2Handoff.parse(withToken(valid, "runIdHex", "abcd")))
        assertNull(ParsedD2Handoff.parse("garbage"))
        assertNull(ParsedD2Handoff.parse(valid.substringBeforeLast(" ")))
        assertNull(ParsedD2Handoff.parse("$valid extra=1"))
        assertNull(ParsedD2Handoff.parse(""))
    }

    @Test
    fun `happy path wires real retrieveU to open with durability label only`() {
        val fixture = d1Fixture()
        val handoff = checkNotNull(sealD2Handoff(fixture.key, fixture.canary, fixture.u, fixture.d1))
        val providerU = fixture.u.copyOf()
        val client = FakeBlockStoreClient()
        val realPort = RealUStorePort(client)
        val transport = ScriptedPTransport(TaskResult.Completed(handoff.sidecar.copyOf()))
        val controller = D2ResumeController(realPort, transport, NoopScheduler())

        client.retrieveTask = SucceedingTask(
            BlockStoreRetrieveResponse(mapOf(fixture.key to providerU)),
        )
        assertTrue(controller.resume(handoff.display.copyText()))

        val state = controller.state
        assertEquals(D2ResumeStatus.PASS, state.status)
        assertEquals(D2ResumeReason.NONE, state.reason)
        assertEquals(TwoArtifactSuccessLabel.U_DURABILITY_PLUS_HARNESS_P, state.successLabel)
        assertEquals(EvidenceClass.LOCAL_DRY_RUN_NOT_EVIDENCE, state.evidenceClass)
        // No store or delete touched the provider.
        assertTrue(client.storeRequests.isEmpty())
        assertTrue(client.deleteRequests.isEmpty())
        // Provider and plaintext material wiped; state carries no secrets.
        assertTrue(providerU.all { it == 0.toByte() })
        assertFalse(state.toString().contains(fixture.key))
        assertFalse(state.toString().contains(handoff.display.runIdHex))
        controller.close()
    }

    @Test
    fun `wrong runId fails closed and wipes provider bytes`() {
        val fixture = d1Fixture()
        val handoff = checkNotNull(sealD2Handoff(fixture.key, fixture.canary, fixture.u, fixture.d1))
        val wrongHex = ByteArray(16) { 0xAA.toByte() }.toHex()
        val wrongText = withToken(handoff.display.copyText(), "runIdHex", wrongHex)
        val providerU = fixture.u.copyOf()
        val client = FakeBlockStoreClient()
        val realPort = RealUStorePort(client)
        val transport = ScriptedPTransport(TaskResult.Completed(handoff.sidecar.copyOf()))
        val controller = D2ResumeController(realPort, transport, NoopScheduler())

        client.retrieveTask = SucceedingTask(
            BlockStoreRetrieveResponse(mapOf(fixture.key to providerU)),
        )
        assertTrue(controller.resume(wrongText))
        assertEquals(D2ResumeStatus.FAIL, controller.state.status)
        assertEquals(D2ResumeReason.FAIL_CLOSED, controller.state.reason)
        assertTrue(providerU.all { it == 0.toByte() })
        controller.close()
    }

    @Test
    fun `tampered p_d2 and d1 sidecar as p_d2 fail closed`() {
        val fixture = d1Fixture()
        val handoff = checkNotNull(sealD2Handoff(fixture.key, fixture.canary, fixture.u, fixture.d1))
        val text = handoff.display.copyText()

        val tampered = handoff.sidecar.copyOf()
        tampered[tampered.size - 1] = (tampered.last() + 1).toByte()
        assertEquals(
            D2ResumeStatus.FAIL,
            runToTerminal(text, fixture.u, TaskResult.Completed(tampered)).status,
        )

        val d1sidecar = checkNotNull(ProbeSidecar.seal(fixture.canary, fixture.u, fixture.d1))
        assertEquals(
            D2ResumeStatus.FAIL,
            runToTerminal(text, fixture.u, TaskResult.Completed(d1sidecar)).status,
        )
    }

    @Test
    fun `missing u is unavailable and never reads p`() {
        val fixture = d1Fixture()
        val handoff = checkNotNull(sealD2Handoff(fixture.key, fixture.canary, fixture.u, fixture.d1))
        val client = FakeBlockStoreClient()
        val realPort = RealUStorePort(client)
        val transport = CountingPTransport()
        val controller = D2ResumeController(realPort, transport, NoopScheduler())

        client.retrieveTask = SucceedingTask(BlockStoreRetrieveResponse(emptyMap()))
        assertTrue(controller.resume(handoff.display.copyText()))
        assertEquals(D2ResumeStatus.BLOCKED, controller.state.status)
        assertEquals(D2ResumeReason.PROVIDER_UNAVAILABLE, controller.state.reason)
        assertEquals(0, transport.importCalls)
        controller.close()
    }

    @Test
    fun `missing p file is incomplete`() {
        val fixture = d1Fixture()
        val handoff = checkNotNull(sealD2Handoff(fixture.key, fixture.canary, fixture.u, fixture.d1))
        val providerU = fixture.u.copyOf()
        val client = FakeBlockStoreClient()
        val realPort = RealUStorePort(client)
        val transport = ScriptedPTransport(TaskResult.Incomplete)
        val controller = D2ResumeController(realPort, transport, NoopScheduler())

        client.retrieveTask = SucceedingTask(
            BlockStoreRetrieveResponse(mapOf(fixture.key to providerU)),
        )
        assertTrue(controller.resume(handoff.display.copyText()))
        assertEquals(D2ResumeStatus.BLOCKED, controller.state.status)
        assertEquals(D2ResumeReason.INCOMPLETE, controller.state.reason)
        controller.close()
    }

    @Test
    fun `bad handoff makes no provider calls`() {
        val retrieveCalls = CountingUStore()
        val transport = CountingPTransport()
        val controller = D2ResumeController(retrieveCalls, transport, NoopScheduler())
        assertTrue(controller.resume("not a handoff line"))
        assertEquals(D2ResumeStatus.FAIL, controller.state.status)
        assertEquals(D2ResumeReason.INVALID_HANDOFF, controller.state.reason)
        assertEquals(0, retrieveCalls.retrieveCalls)
        assertEquals(0, transport.importCalls)
        controller.close()
    }

    @Test
    fun `late retrieve after cancel is ignored wiped and reusable`() {
        val fixture = d1Fixture()
        val handoff = checkNotNull(sealD2Handoff(fixture.key, fixture.canary, fixture.u, fixture.d1))
        val text = handoff.display.copyText()
        val uStore = ManualUStore()
        val transport = ScriptedPTransport(TaskResult.Completed(handoff.sidecar.copyOf()))
        val controller = D2ResumeController(uStore, transport, NoopScheduler())

        assertTrue(controller.resume(text))
        assertEquals(D2ResumeStatus.RUNNING, controller.state.status)
        assertTrue(controller.cancel())
        assertEquals(D2ResumeStatus.BLOCKED, controller.state.status)
        assertEquals(D2ResumeReason.CANCELLED, controller.state.reason)

        val lateU = fixture.u.copyOf()
        uStore.completeRetrieve(TaskResult.Completed(lateU))
        assertEquals(D2ResumeStatus.BLOCKED, controller.state.status)
        assertEquals(D2ResumeReason.CANCELLED, controller.state.reason)
        assertTrue(lateU.all { it == 0.toByte() })

        // Fresh run after cancel still works.
        assertTrue(controller.resume(text))
        uStore.completeRetrieve(TaskResult.Completed(fixture.u.copyOf()))
        assertEquals(D2ResumeStatus.PASS, controller.state.status)
        controller.close()
    }

    @Test
    fun `timeout settles retryable and late import is ignored`() {
        val fixture = d1Fixture()
        val handoff = checkNotNull(sealD2Handoff(fixture.key, fixture.canary, fixture.u, fixture.d1))
        val uStore = ManualUStore()
        val transport = ManualPTransport()
        val scheduler = ManualScheduler()
        val controller = D2ResumeController(uStore, transport, scheduler)

        assertTrue(controller.resume(handoff.display.copyText()))
        uStore.completeRetrieve(TaskResult.Completed(fixture.u.copyOf()))
        scheduler.fire()
        assertEquals(D2ResumeStatus.BLOCKED, controller.state.status)
        assertEquals(D2ResumeReason.RETRYABLE_UNAVAILABLE, controller.state.reason)

        transport.completeImport(TaskResult.Completed(handoff.sidecar.copyOf()))
        assertEquals(D2ResumeStatus.BLOCKED, controller.state.status)
        assertEquals(D2ResumeReason.RETRYABLE_UNAVAILABLE, controller.state.reason)
        controller.close()
    }

    @Test
    fun `close wipes retained u and never settles pass`() {
        val fixture = d1Fixture()
        val handoff = checkNotNull(sealD2Handoff(fixture.key, fixture.canary, fixture.u, fixture.d1))
        val uStore = ManualUStore()
        val transport = ManualPTransport()
        val controller = D2ResumeController(uStore, transport, NoopScheduler())

        assertTrue(controller.resume(handoff.display.copyText()))
        val retained = fixture.u.copyOf()
        uStore.completeRetrieve(TaskResult.Completed(retained))
        controller.close()
        assertTrue(retained.all { it == 0.toByte() })

        transport.completeImport(TaskResult.Completed(handoff.sidecar.copyOf()))
        assertTrue(controller.state.status != D2ResumeStatus.PASS)
        controller.close()
    }

    private data class D1Fixture(
        val key: String,
        val canary: ByteArray,
        val u: ByteArray,
        val d1: ExpectedContext,
    )

    private fun d1Fixture(): D1Fixture {
        val key = "Abcdefghijklmnopqrstuv"
        val canary = ByteArray(ProbeSidecar.CANARY_BYTES) { (it + 1).toByte() }
        val u = ByteArray(ProbeSidecar.KEY_BYTES) { (it + 0x21).toByte() }
        val d1 = ExpectedContext(
            accountBindingClass = AccountBindingClass.A,
            runId = ByteArray(ExpectedContext.RUN_ID_BYTES) { (it + 0x41).toByte() },
            generation = ContextGeneration.G1,
            targetRole = ContextTargetRole.D1_SOURCE,
        )
        return D1Fixture(key, canary, u, d1)
    }

    private fun withToken(text: String, name: String, value: String): String =
        text.split(" ").joinToString(" ") { token ->
            if (token.startsWith("$name=")) "$name=$value" else token
        }

    private fun runToTerminal(
        handoffText: String,
        u: ByteArray,
        importResult: TaskResult<ByteArray>,
    ): D2ResumeState {
        val key = "Abcdefghijklmnopqrstuv"
        val providerU = u.copyOf()
        val client = FakeBlockStoreClient()
        val realPort = RealUStorePort(client)
        val transport = ScriptedPTransport(importResult)
        val controller = D2ResumeController(realPort, transport, NoopScheduler())
        client.retrieveTask = SucceedingTask(
            BlockStoreRetrieveResponse(mapOf(key to providerU)),
        )
        controller.resume(handoffText)
        val state = controller.state
        controller.close()
        return state
    }

    /** Real exact-key retrieveU with a fake Task boundary; store/delete explode if called. */
    private class RealUStorePort(
        private val client: FakeBlockStoreClient,
    ) : ProbeUStorePort {
        private val delegate = GoogleBlockStoreUStore(
            client,
            { true },
            BlockStoreDeleteTombstone.inMemoryForTest(),
            { BlockStoreLockState.QUALIFIED },
        )

        override fun storeU(
            key: String,
            value: ByteArray,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = error("D2 resume never stores")

        override fun retrieveU(
            key: String,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation {
            val operation = delegate.retrieveU(key) { result ->
                onSettled(
                    when (result.outcome) {
                        BlockStoreOutcome.COMPLETED ->
                            TaskResult.Completed(checkNotNull(result.value))
                        BlockStoreOutcome.UNAVAILABLE -> TaskResult.Unavailable
                        BlockStoreOutcome.RETRYABLE_UNAVAILABLE ->
                            TaskResult.RetryableUnavailable
                        BlockStoreOutcome.FAIL_CLOSED,
                        BlockStoreOutcome.INDETERMINATE,
                        -> TaskResult.Indeterminate
                        BlockStoreOutcome.INCOMPLETE -> TaskResult.Incomplete
                    },
                )
            }
            return object : ProbeControllerOperation {
                override fun timeout(): Boolean = operation.timeout()
                override fun cancel(): Boolean = operation.cancel()
            }
        }

        override fun deleteU(
            key: String,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = error("D2 resume never deletes")
    }

    private class FakeBlockStoreClient : BlockStoreClientPort {
        var retrieveTask: BlockStoreTaskPort<BlockStoreRetrieveResponse> =
            SucceedingTask(BlockStoreRetrieveResponse(emptyMap()))
        val storeRequests = mutableListOf<BlockStoreStoreRequest>()
        val deleteRequests = mutableListOf<BlockStoreDeleteRequest>()

        override fun isEndToEndEncryptionAvailable(): BlockStoreTaskPort<Boolean> =
            SucceedingTask(true)

        override fun storeBytes(request: BlockStoreStoreRequest): BlockStoreTaskPort<Int> {
            storeRequests += request
            return SucceedingTask(0)
        }

        override fun retrieveBytes(
            request: BlockStoreRetrieveRequest,
        ): BlockStoreTaskPort<BlockStoreRetrieveResponse> = retrieveTask

        override fun deleteBytes(request: BlockStoreDeleteRequest): BlockStoreTaskPort<Boolean> {
            deleteRequests += request
            return SucceedingTask(false)
        }
    }

    private class SucceedingTask<T>(private val value: T) : BlockStoreTaskPort<T> {
        override fun addOnSuccessListener(listener: (T) -> Unit) {
            listener(value)
        }

        override fun addOnFailureListener(listener: (BlockStoreTaskFailure) -> Unit) = Unit

        override fun addOnCanceledListener(listener: () -> Unit) = Unit
    }

    private class ScriptedPTransport(
        private val importResult: TaskResult<ByteArray>,
    ) : ProbePTransportPort {
        override fun exportP(
            opaqueP: ByteArray,
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = error("D2 resume never exports")

        override fun importP(
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation {
            val result = importResult
            onSettled(
                when (result) {
                    is TaskResult.Completed -> TaskResult.Completed(result.value.copyOf())
                    TaskResult.Unavailable -> TaskResult.Unavailable
                    TaskResult.RetryableUnavailable -> TaskResult.RetryableUnavailable
                    TaskResult.Incomplete -> TaskResult.Incomplete
                    TaskResult.Indeterminate -> TaskResult.Indeterminate
                },
            )
            return NoopOperation()
        }
    }

    private class CountingUStore : ProbeUStorePort {
        var retrieveCalls = 0

        override fun storeU(
            key: String,
            value: ByteArray,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = error("D2 resume never stores")

        override fun retrieveU(
            key: String,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation {
            retrieveCalls += 1
            onSettled(TaskResult.Unavailable)
            return NoopOperation()
        }

        override fun deleteU(
            key: String,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = error("D2 resume never deletes")
    }

    private class CountingPTransport : ProbePTransportPort {
        var importCalls = 0

        override fun exportP(
            opaqueP: ByteArray,
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = error("D2 resume never exports")

        override fun importP(
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation {
            importCalls += 1
            onSettled(TaskResult.Unavailable)
            return NoopOperation()
        }
    }

    private class ManualUStore : ProbeUStorePort {
        private var callback: ((TaskResult<ByteArray>) -> Unit)? = null
        val operation = ManualOperation()

        override fun storeU(
            key: String,
            value: ByteArray,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = error("D2 resume never stores")

        override fun retrieveU(
            key: String,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation {
            callback = onSettled
            return operation
        }

        override fun deleteU(
            key: String,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = error("D2 resume never deletes")

        fun completeRetrieve(result: TaskResult<ByteArray>) {
            callback?.invoke(result)
        }
    }

    private class ManualPTransport : ProbePTransportPort {
        private var callback: ((TaskResult<ByteArray>) -> Unit)? = null
        val operation = ManualOperation()

        override fun exportP(
            opaqueP: ByteArray,
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = error("D2 resume never exports")

        override fun importP(
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation {
            callback = onSettled
            return operation
        }

        fun completeImport(result: TaskResult<ByteArray>) {
            callback?.invoke(result)
        }
    }

    private class ManualOperation : ProbeControllerOperation {
        override fun timeout(): Boolean = true
        override fun cancel(): Boolean = true
    }

    private class NoopOperation : ProbeControllerOperation {
        override fun timeout(): Boolean = false
        override fun cancel(): Boolean = false
    }

    private class NoopScheduler : ProbeScheduler {
        override fun schedule(delayMs: Long, callback: () -> Unit): ProbeScheduledHandle =
            ProbeScheduledHandle { }
    }

    private class ManualScheduler : ProbeScheduler {
        private var callback: (() -> Unit)? = null

        override fun schedule(delayMs: Long, callback: () -> Unit): ProbeScheduledHandle {
            this.callback = callback
            return ProbeScheduledHandle { this.callback = null }
        }

        fun fire() {
            val pending = callback
            callback = null
            checkNotNull(pending)()
        }
    }
}
