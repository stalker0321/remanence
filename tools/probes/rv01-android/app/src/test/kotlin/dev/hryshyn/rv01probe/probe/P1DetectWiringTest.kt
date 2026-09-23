package dev.hryshyn.rv01probe.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 wiring tests: the app's detect decision is driven by ONE shared
 * [OperatorConfirmedP1Inputs] instance (as MainActivity wires it into
 * AndroidProbeEligibilityPort). These tests mutate a single shared instance
 * and assert the pure [decideP1Detect] decision the port reads on every
 * detect, plus blocked/unconfirmed cases and controller recheck without
 * restart. No Android framework, no provider, no cloud PASS.
 */
class P1DetectWiringTest {

    @Test
    fun `shared instance unconfirmed stays blocked unknown`() {
        val shared = OperatorConfirmedP1Inputs()
        val decision = decideP1Detect(
            keyguardPresent = true,
            isDeviceSecure = true,
            confirmation = shared.snapshot(),
        )
        assertEquals(BlockStoreLockState.UNKNOWN, decision.gatedState)
        assertEquals(ScreenLockState.UNKNOWN, decision.screenLock)
        assertEquals(BackupEligibility.UNKNOWN, decision.backupEligibility)
        assertFalse(decision.qualified)
    }

    @Test
    fun `mutating shared lock confirmation flips same decision to qualified`() {
        val shared = OperatorConfirmedP1Inputs()
        fun decide() = decideP1Detect(true, true, shared.snapshot())

        assertFalse(decide().qualified)
        assertTrue(shared.confirmLockKind(OperatorConfirmedLockKind.PIN))
        val confirmed = decide()
        assertTrue(confirmed.qualified)
        assertEquals(BlockStoreLockState.QUALIFIED, confirmed.gatedState)
        assertEquals(ScreenLockState.PIN, confirmed.screenLock)
        // Backup still unknown: no guessing from lock alone.
        assertEquals(BackupEligibility.UNKNOWN, confirmed.backupEligibility)

        shared.clearLockKind()
        assertFalse(decide().qualified)
    }

    @Test
    fun `shared backup needs eligible plus backup now on same instance`() {
        val shared = OperatorConfirmedP1Inputs()
        shared.confirmLockKind(OperatorConfirmedLockKind.PATTERN)
        fun backup() = decideP1Detect(true, true, shared.snapshot()).backupEligibility

        assertEquals(BackupEligibility.UNKNOWN, backup())
        shared.confirmBackupEligibility(BackupEligibility.ELIGIBLE)
        assertEquals(BackupEligibility.UNKNOWN, backup())
        shared.confirmBackupNowCompleted()
        assertEquals(BackupEligibility.ELIGIBLE, backup())
        shared.clearBackupNowCompleted()
        assertEquals(BackupEligibility.UNKNOWN, backup())
    }

    @Test
    fun `insecure physical dominates confirmed shared inputs`() {
        val shared = OperatorConfirmedP1Inputs()
        shared.confirmLockKind(OperatorConfirmedLockKind.PASSWORD)
        shared.confirmBackupEligibility(BackupEligibility.ELIGIBLE)
        shared.confirmBackupNowCompleted()
        val decision = decideP1Detect(true, false, shared.snapshot())
        assertEquals(BlockStoreLockState.INSECURE, decision.gatedState)
        assertEquals(ScreenLockState.ABSENT, decision.screenLock)
        assertEquals(BackupEligibility.UNKNOWN, decision.backupEligibility)
        assertFalse(decision.qualified)
    }

    @Test
    fun `absent keyguard stays unknown even when shared inputs confirm`() {
        val shared = OperatorConfirmedP1Inputs()
        shared.confirmLockKind(OperatorConfirmedLockKind.PIN)
        shared.confirmBackupEligibility(BackupEligibility.ELIGIBLE)
        shared.confirmBackupNowCompleted()
        val decision = decideP1Detect(false, true, shared.snapshot())
        assertEquals(BlockStoreLockState.UNKNOWN, decision.gatedState)
        assertFalse(decision.qualified)
    }

    @Test
    fun `qualified decision plus e2ee yields cloud eligible only with backup`() {
        val shared = OperatorConfirmedP1Inputs()
        shared.confirmLockKind(OperatorConfirmedLockKind.PIN)
        val withoutBackup = decideP1Detect(true, true, shared.snapshot())
        assertTrue(withoutBackup.qualified)
        assertFalse(
            ProbeEligibility(
                capability = CapabilityStatus.AVAILABLE,
                placement = CapabilityPlacement.SYNCED_PROVIDER,
                backupEligibility = withoutBackup.backupEligibility,
                screenLock = withoutBackup.screenLock,
                e2ee = E2eeState.AVAILABLE,
                restorePath = RestorePath.BLOCK_STORE_CLOUD,
            ).cloudEligible,
        )

        shared.confirmBackupEligibility(BackupEligibility.ELIGIBLE)
        shared.confirmBackupNowCompleted()
        val withBackup = decideP1Detect(true, true, shared.snapshot())
        assertTrue(
            ProbeEligibility(
                capability = CapabilityStatus.AVAILABLE,
                placement = CapabilityPlacement.SYNCED_PROVIDER,
                backupEligibility = withBackup.backupEligibility,
                screenLock = withBackup.screenLock,
                e2ee = E2eeState.AVAILABLE,
                restorePath = RestorePath.BLOCK_STORE_CLOUD,
            ).cloudEligible,
        )
    }

    @Test
    fun `blocked p1 can recheck without restart then blocks again`() {
        val eligibility = CountingEligibilityPort(blockedP1())
        val controller = ProbeController(
            eligibilityPort = eligibility,
            uStore = NoopUStore(),
            pTransport = NoopPTransport(),
            scheduler = NoopScheduler(),
            random = ProbeRandomSource { ByteArray(it) { (it + 1).toByte() } },
        )
        assertTrue(controller.begin())
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
        assertEquals(ProbeControllerPhase.CHECK_ENVIRONMENT, controller.state.phase)
        assertEquals(1, eligibility.detectCalls)

        assertTrue(controller.recheckEligibility())
        assertEquals(2, eligibility.detectCalls)
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
        assertEquals(ProbeControllerPhase.CHECK_ENVIRONMENT, controller.state.phase)
        controller.close()
    }

    @Test
    fun `recheck denied before start and while running`() {
        val manual = ManualEligibility()
        val controller = ProbeController(
            eligibilityPort = manual,
            uStore = NoopUStore(),
            pTransport = NoopPTransport(),
            scheduler = NoopScheduler(),
            random = ProbeRandomSource { ByteArray(it) },
        )
        assertFalse(controller.recheckEligibility())
        assertTrue(controller.begin())
        // Flight active: recheck must not stack a second detect.
        assertFalse(controller.recheckEligibility())
        assertEquals(1, manual.detectCalls)
        manual.complete(blockedP1())
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
        assertTrue(controller.recheckEligibility())
        assertEquals(2, manual.detectCalls)
        controller.close()
    }

    private fun blockedP1() = TaskResult.Completed(
        ProbeEligibility(
            capability = CapabilityStatus.INDETERMINATE,
            placement = CapabilityPlacement.SYNCED_PROVIDER,
            backupEligibility = BackupEligibility.UNKNOWN,
            screenLock = ScreenLockState.UNKNOWN,
            e2ee = E2eeState.UNKNOWN,
            restorePath = RestorePath.BLOCK_STORE_CLOUD,
        ),
    )

    private class CountingEligibilityPort(
        private val result: TaskResult<ProbeEligibility>,
    ) : ProbeEligibilityPort {
        var detectCalls = 0
        override fun detect(onSettled: (TaskResult<ProbeEligibility>) -> Unit): ProbeControllerOperation {
            detectCalls += 1
            onSettled(result)
            return NoopOperation()
        }
    }

    private class ManualEligibility : ProbeEligibilityPort {
        var detectCalls = 0
        private var callback: ((TaskResult<ProbeEligibility>) -> Unit)? = null
        override fun detect(onSettled: (TaskResult<ProbeEligibility>) -> Unit): ProbeControllerOperation {
            detectCalls += 1
            callback = onSettled
            return NoopOperation()
        }
        fun complete(result: TaskResult<ProbeEligibility>) {
            callback?.invoke(result)
        }
    }

    private class NoopUStore : ProbeUStorePort {
        override fun storeU(
            key: String,
            value: ByteArray,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = NoopOperation()

        override fun retrieveU(
            key: String,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation = NoopOperation()

        override fun deleteU(
            key: String,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = NoopOperation()
    }

    private class NoopPTransport : ProbePTransportPort {
        override fun exportP(
            opaqueP: ByteArray,
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = NoopOperation()

        override fun importP(
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation = NoopOperation()
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
