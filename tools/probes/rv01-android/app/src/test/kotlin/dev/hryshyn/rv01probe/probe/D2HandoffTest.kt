package dev.hryshyn.rv01probe.probe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1→D2 handoff tests: role separation between P_D1/P_D2, exact handoff and
 * context consistency, no K_U/runId leakage into evidence, cleanup and
 * lifecycle invalidation, and no false PASS. P framing is unchanged: RVP1 P
 * carries its claimed context (including runId) by design; K_U never enters
 * P and the trusted D2 context is never derived from P. No D2 resume, no
 * Remanence integration, no physical/cloud claim.
 */
class D2HandoffTest {

    @Test
    fun `d1 sidecar opens under d1 context and fails under d2 context`() {
        val canary = bytes(ProbeSidecar.CANARY_BYTES, 0x11)
        val material = bytes(ProbeSidecar.KEY_BYTES, 0x22)
        val runId = bytes(ExpectedContext.RUN_ID_BYTES, 0x33)
        val d1 = ExpectedContext(
            accountBindingClass = AccountBindingClass.A,
            runId = runId,
            generation = ContextGeneration.G1,
            targetRole = ContextTargetRole.D1_SOURCE,
        )
        val d2 = ExpectedContext(
            accountBindingClass = AccountBindingClass.A,
            runId = runId.copyOf(),
            generation = ContextGeneration.G1,
            targetRole = ContextTargetRole.D2_TARGET,
        )
        val p1 = checkNotNull(ProbeSidecar.seal(canary, material, d1))
        assertArrayEquals(canary, checkNotNull(ProbeSidecar.open(p1, material, d1)))
        assertNull(ProbeSidecar.open(p1, material, d2))
    }

    @Test
    fun `sealed d2 handoff opens under d2 and fails under d1`() {
        val canary = bytes(ProbeSidecar.CANARY_BYTES, 0x44)
        val material = bytes(ProbeSidecar.KEY_BYTES, 0x55)
        val d1 = ExpectedContext(
            accountBindingClass = AccountBindingClass.A,
            runId = bytes(ExpectedContext.RUN_ID_BYTES, 0x66),
            generation = ContextGeneration.G1,
            targetRole = ContextTargetRole.D1_SOURCE,
        )
        val p1 = checkNotNull(ProbeSidecar.seal(canary, material, d1))
        val handoff = checkNotNull(sealD2Handoff(validKey(), canary, material, d1))

        assertEquals(ContextTargetRole.D2_TARGET, handoff.context.targetRole)
        assertEquals(ProbeSidecar.TOTAL_BYTES, handoff.sidecar.size)
        assertNotEquals(
            p1.toList(),
            handoff.sidecar.toList(),
        )
        assertArrayEquals(canary, checkNotNull(ProbeSidecar.open(handoff.sidecar, material, handoff.context)))
        assertNull(ProbeSidecar.open(handoff.sidecar, material, d1))
        // Same runId value, independent object: D2 is constructed, not copied from P.
        assertArrayEquals(d1.runId, handoff.context.runId)
        assertFalse(d1 == handoff.context)
    }

    @Test
    fun `handoff display carries exact key and runId fields only`() {
        val canary = bytes(ProbeSidecar.CANARY_BYTES, 0x77)
        val material = bytes(ProbeSidecar.KEY_BYTES, 0x88)
        val runId = bytes(ExpectedContext.RUN_ID_BYTES, 0x99.toByte())
        val d1 = ExpectedContext(
            accountBindingClass = AccountBindingClass.A,
            runId = runId,
            generation = ContextGeneration.G1,
            targetRole = ContextTargetRole.D1_SOURCE,
        )
        val handoff = checkNotNull(sealD2Handoff(validKey(), canary, material, d1))
        val display = handoff.display

        assertEquals(validKey(), display.key)
        assertTrue(ProbeKey.isValid(display.key))
        assertArrayEquals(runId, checkNotNull(display.runIdHex.hexToBytesOrNull()))
        assertEquals("A", display.accountBindingClass)
        assertEquals("G1", display.generation)
        assertEquals("D2_TARGET", display.targetRole)

        val text = display.copyText()
        assertTrue(text.startsWith(D2HandoffDisplay.SCHEMA_TAG))
        assertTrue(text.contains(validKey()))
        assertTrue(text.contains(display.runIdHex))
        // No U, canary, or plaintext material in the display value.
        assertFalse(text.contains(material.toHex()))
        assertFalse(text.contains(canary.toHex()))
    }

    @Test
    fun `seal rejects non-d1 source and invalid inputs`() {
        val canary = bytes(ProbeSidecar.CANARY_BYTES, 0x01)
        val material = bytes(ProbeSidecar.KEY_BYTES, 0x02)
        val d1 = ExpectedContext(
            accountBindingClass = AccountBindingClass.A,
            runId = bytes(ExpectedContext.RUN_ID_BYTES, 0x03),
            generation = ContextGeneration.G1,
            targetRole = ContextTargetRole.D1_SOURCE,
        )
        val d2Role = ExpectedContext(
            accountBindingClass = AccountBindingClass.A,
            runId = bytes(ExpectedContext.RUN_ID_BYTES, 0x03),
            generation = ContextGeneration.G1,
            targetRole = ContextTargetRole.D2_TARGET,
        )
        assertNull(sealD2Handoff(validKey(), canary, material, d2Role))
        assertNull(sealD2Handoff("short", canary, material, d1))
        assertNull(sealD2Handoff(validKey(), ByteArray(31), material, d1))
        assertNull(sealD2Handoff(validKey(), canary, ByteArray(33), d1))
        assertNull(
            sealD2Handoff(
                validKey(),
                canary,
                material,
                ExpectedContext(
                    accountBindingClass = AccountBindingClass.B,
                    runId = bytes(ExpectedContext.RUN_ID_BYTES, 0x03),
                    generation = ContextGeneration.G1,
                    targetRole = ContextTargetRole.D1_SOURCE,
                ),
            ),
        )
    }

    @Test
    fun `controller exposes handoff after p2 without pass and keeps evidence clean`() {
        val controller = handoffController()
        assertTrue(controller.begin())
        assertEquals(ProbeControllerPhase.WAITING_FOR_SAF_EXPORT, controller.state.phase)
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)

        val handoff = checkNotNull(controller.state.d2Handoff)
        assertTrue(controller.state.canExportD2)
        assertTrue(controller.state.canExport)
        assertTrue(ProbeKey.isValid(handoff.key))
        assertEquals(ExpectedContext.RUN_ID_BYTES * 2, handoff.runIdHex.length)

        val evidence = controller.state.evidenceJson
        assertTrue(evidence.contains("LOCAL_DRY_RUN_NOT_EVIDENCE"))
        assertFalse(evidence.contains(handoff.key))
        assertFalse(evidence.contains(handoff.runIdHex))
        assertFalse(evidence.contains("handoff"))
        assertFalse(evidence.contains("runIdHex"))
        controller.close()
    }

    @Test
    fun `d2 export is reachable after p2 and retains d1 for p3 pass`() {
        val transport = CapturingPTransport()
        val controller = handoffController(transport)
        assertTrue(controller.begin())

        assertTrue(controller.exportD2Sidecar())
        assertEquals(1, transport.exports.size)
        assertEquals(ProbeSidecar.TOTAL_BYTES, transport.exports.single().size)
        // Origin phase restored: P_D1 export still reachable.
        assertEquals(ProbeControllerPhase.WAITING_FOR_SAF_EXPORT, controller.state.phase)
        assertTrue(controller.state.canExportD2)

        assertTrue(controller.exportSidecar())
        assertEquals(2, transport.exports.size)
        assertEquals(ProbeControllerPhase.READY_TO_VERIFY, controller.state.phase)
        // Distinct opaque artifacts for distinct roles.
        assertNotEquals(transport.exports[0].toList(), transport.exports[1].toList())

        // P3 still runs on the retained P_D1 path and passes locally.
        transport.importBytes = transport.exports[1].copyOf()
        assertTrue(controller.verifyBeforeWipe())
        assertEquals(ProbeControllerStatus.PASS, controller.state.status)
        assertEquals(ProbeControllerPhase.READY_FOR_CLEANUP, controller.state.phase)

        assertTrue(controller.cleanup())
        assertEquals(ProbeControllerPhase.TERMINAL, controller.state.phase)
        assertNull(controller.state.d2Handoff)
        assertFalse(controller.state.canExportD2)
        assertFalse(controller.exportD2Sidecar())
        controller.close()
    }

    @Test
    fun `process recreation invalidates handoff and blocks d2 export`() {
        val controller = handoffController()
        assertTrue(controller.begin())
        checkNotNull(controller.state.d2Handoff)

        controller.processRecreated()
        assertNull(controller.state.d2Handoff)
        assertFalse(controller.state.canExportD2)
        assertFalse(controller.exportD2Sidecar())
        controller.close()
    }

    @Test
    fun `d2 export rejected before p2 and after terminal`() {
        val transport = CapturingPTransport()
        val controller = handoffController(transport)
        assertFalse(controller.exportD2Sidecar())
        assertTrue(controller.begin())
        assertTrue(controller.exportSidecar())
        transport.importBytes = transport.exports.single().copyOf()
        assertTrue(controller.verifyBeforeWipe())
        assertTrue(controller.cleanup())
        assertEquals(ProbeControllerPhase.TERMINAL, controller.state.phase)
        assertFalse(controller.exportD2Sidecar())
        controller.close()
    }

    private fun validKey() = "Abcdefghijklmnopqrstuv"

    private fun bytes(size: Int, fill: Byte): ByteArray = ByteArray(size) { fill }

    private fun bytes(size: Int, fill: Int): ByteArray = ByteArray(size) { fill.toByte() }

    private fun eligible() = ProbeEligibility(
        capability = CapabilityStatus.AVAILABLE,
        placement = CapabilityPlacement.SYNCED_PROVIDER,
        backupEligibility = BackupEligibility.ELIGIBLE,
        screenLock = ScreenLockState.PIN,
        e2ee = E2eeState.AVAILABLE,
        restorePath = RestorePath.BLOCK_STORE_CLOUD,
    )

    private fun handoffController(
        transport: CapturingPTransport = CapturingPTransport(),
    ): ProbeController = ProbeController(
        eligibilityPort = ImmediateEligibilityPort(TaskResult.Completed(eligible())),
        uStore = ImmediateUStorePort(),
        pTransport = transport,
        scheduler = NoopScheduler(),
        random = ProbeRandomSource { ByteArray(it) { (it + 1).toByte() } },
    )

    private class ImmediateEligibilityPort(
        private val result: TaskResult<ProbeEligibility>,
    ) : ProbeEligibilityPort {
        override fun detect(onSettled: (TaskResult<ProbeEligibility>) -> Unit): ProbeControllerOperation {
            onSettled(result)
            return NoopOperation()
        }
    }

    private class ImmediateUStorePort : ProbeUStorePort {
        private val delegate = FakeUStore()

        override fun storeU(
            key: String,
            value: ByteArray,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation {
            val result = delegate.storeU(key, value)
            value.fill(0)
            onSettled(result)
            return NoopOperation()
        }

        override fun retrieveU(
            key: String,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation {
            onSettled(delegate.retrieveU(key))
            return NoopOperation()
        }

        override fun deleteU(
            key: String,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation {
            onSettled(delegate.deleteU(key))
            return NoopOperation()
        }
    }

    private class CapturingPTransport : ProbePTransportPort {
        val exports = mutableListOf<ByteArray>()
        var importBytes: ByteArray? = null

        override fun exportP(
            opaqueP: ByteArray,
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation {
            exports += opaqueP.copyOf()
            opaqueP.fill(0)
            onSettled(TaskResult.Completed(Unit))
            return NoopOperation()
        }

        override fun importP(
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation {
            val bytes = importBytes?.copyOf()
            if (bytes == null) {
                onSettled(TaskResult.Incomplete)
            } else {
                onSettled(TaskResult.Completed(bytes))
            }
            return NoopOperation()
        }
    }

    private class NoopScheduler : ProbeScheduler {
        override fun schedule(delayMs: Long, callback: () -> Unit): ProbeScheduledHandle =
            ProbeScheduledHandle { }
    }

    private class NoopOperation : ProbeControllerOperation {
        override fun timeout(): Boolean = false
        override fun cancel(): Boolean = false
    }
}
