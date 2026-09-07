package dev.hryshyn.rv01probe.probe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProbeControllerTest {
    @Test
    fun `happy pre-wipe path stores exports verifies and cleans exact key`() {
        val u = ImmediateUStorePort()
        val p = ImmediatePTransportPort()
        val controller = controller(u, p)

        assertEquals(ProbeControllerStatus.NOT_RUN, controller.state.status)
        assertTrue(controller.begin())
        assertEquals(ProbeControllerPhase.WAITING_FOR_SAF_EXPORT, controller.state.phase)
        assertTrue(controller.state.canExport)
        val key = u.lastStoredKey
        assertNotNull(key)

        assertTrue(controller.exportSidecar())
        assertEquals(ProbeControllerPhase.READY_TO_VERIFY, controller.state.phase)
        val sidecar = p.lastExported
        assertNotNull(sidecar)
        assertTrue(controller.verifyBeforeWipe())
        assertEquals(ProbeControllerStatus.PASS, controller.state.status)
        assertEquals(ProbeControllerPhase.READY_FOR_CLEANUP, controller.state.phase)
        assertTrue(controller.state.evidence.uRetrievedBeforeWipe)
        assertTrue(controller.state.evidence.pAuthenticatedBeforeWipe)
        assertTrue(controller.state.evidence.canaryMatchedBeforeWipe)

        val evidence = controller.state.evidenceJson
        assertTrue(evidence.contains("LOCAL_DRY_RUN_NOT_EVIDENCE"))
        assertTrue(evidence.contains("P1_CAPABILITY"))
        assertTrue(evidence.contains("P2_WRAP"))
        assertTrue(evidence.contains("P3_LOCAL_UNWRAP"))
        assertFalse(evidence.contains(checkNotNull(key)))
        assertFalse(evidence.contains(java.util.Base64.getEncoder().encodeToString(checkNotNull(sidecar))))
        listOf("email", "token", "uri", "exception", "message").forEach {
            assertFalse("evidence leaked $it", evidence.contains(it, ignoreCase = true))
        }

        assertTrue(controller.cleanup())
        assertEquals(ProbeControllerPhase.TERMINAL, controller.state.phase)
        assertEquals(ProbeControllerStatus.PASS, controller.state.status)
        assertTrue(controller.state.evidence.cleanupAttempted)
        assertTrue(controller.state.evidence.cleanupCompleted)
        assertEquals(listOf(key), u.deletedKeys)
        controller.close()
    }

    @Test
    fun `provider unavailability blocks before fresh material or provider store`() {
        val u = ImmediateUStorePort()
        val eligibility = ImmediateEligibilityPort(
            TaskResult.Completed(
                ProbeEligibility(
                    capability = CapabilityStatus.UNAVAILABLE,
                    placement = CapabilityPlacement.SYNCED_PROVIDER,
                    backupEligibility = BackupEligibility.UNKNOWN,
                    screenLock = ScreenLockState.UNKNOWN,
                    e2ee = E2eeState.UNKNOWN,
                    restorePath = RestorePath.BLOCK_STORE_CLOUD,
                ),
            ),
        )
        val controller = controller(u, ImmediatePTransportPort(), eligibility)

        assertTrue(controller.begin())
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
        assertEquals(ProbeControllerReason.PLAY_SERVICES_UNAVAILABLE, controller.state.reason)
        assertEquals(0, u.storeCalls)
        assertFalse(controller.state.canCleanup)
    }

    @Test
    fun `unqualified lock and false E2EE are precise blocked states`() {
        val cases = listOf(
            ProbeEligibility(
                CapabilityStatus.UNAVAILABLE,
                CapabilityPlacement.SYNCED_PROVIDER,
                BackupEligibility.UNKNOWN,
                ScreenLockState.ABSENT,
                E2eeState.UNKNOWN,
                RestorePath.BLOCK_STORE_CLOUD,
            ) to ProbeControllerReason.LOCK_NOT_QUALIFIED,
            ProbeEligibility(
                CapabilityStatus.UNAVAILABLE,
                CapabilityPlacement.SYNCED_PROVIDER,
                BackupEligibility.INELIGIBLE,
                ScreenLockState.PIN,
                E2eeState.UNAVAILABLE,
                RestorePath.BLOCK_STORE_CLOUD,
            ) to ProbeControllerReason.E2EE_UNAVAILABLE,
            ProbeEligibility(
                CapabilityStatus.INDETERMINATE,
                CapabilityPlacement.SYNCED_PROVIDER,
                BackupEligibility.UNKNOWN,
                ScreenLockState.UNKNOWN,
                E2eeState.UNKNOWN,
                RestorePath.BLOCK_STORE_CLOUD,
            ) to ProbeControllerReason.LOCK_NOT_QUALIFIED,
        )
        cases.forEach { (eligibility, expectedReason) ->
            val u = ImmediateUStorePort()
            val controller = controller(
                u,
                ImmediatePTransportPort(),
                ImmediateEligibilityPort(TaskResult.Completed(eligibility)),
            )
            assertTrue(controller.begin())
            assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
            assertEquals(expectedReason, controller.state.reason)
            assertEquals(0, u.storeCalls)
        }
    }

    @Test
    fun `malformed SAF input fails closed and leaves exact cleanup available`() {
        val u = ImmediateUStorePort()
        val p = ImmediatePTransportPort()
        val controller = controller(u, p)
        assertTrue(controller.begin())
        assertTrue(controller.exportSidecar())
        p.nextImport = TaskResult.Completed(ByteArray(ProbeSidecar.TOTAL_BYTES))

        assertTrue(controller.verifyBeforeWipe())
        assertEquals(ProbeControllerStatus.FAIL, controller.state.status)
        assertEquals(ProbeControllerReason.SAF_INVALID, controller.state.reason)
        assertTrue(controller.state.canCleanup)
        assertFalse(controller.state.evidence.canaryMatchedBeforeWipe)
        assertTrue(controller.cleanup())
        assertEquals(ProbeControllerStatus.FAIL, controller.state.status)
        assertTrue(controller.state.evidence.cleanupCompleted)
    }

    @Test
    fun `cancelled eligibility ignores late callback and cannot become success`() {
        val eligibility = ManualEligibilityPort()
        val u = ImmediateUStorePort()
        val controller = controller(u, ImmediatePTransportPort(), eligibility)
        assertTrue(controller.begin())
        assertTrue(controller.cancel())
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
        assertEquals(ProbeControllerReason.CANCELLED, controller.state.reason)
        eligibility.complete(TaskResult.Completed(eligible()))
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
        assertEquals(0, u.storeCalls)
    }

    @Test
    fun `timeout and duplicate callbacks are first settlement wins`() {
        val u = ImmediateUStorePort()
        val p = ManualPTransportPort()
        val scheduler = TestScheduler()
        val controller = controller(u, p, scheduler = scheduler)
        assertTrue(controller.begin())
        assertTrue(controller.exportSidecar())
        assertEquals(ProbeControllerPhase.EXPORT_P, controller.state.phase)
        scheduler.fireNext()
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
        assertEquals(ProbeControllerReason.RETRYABLE_UNAVAILABLE, controller.state.reason)
        assertTrue(controller.state.canRetry)
        p.completeExport(TaskResult.Completed(Unit))
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
        assertEquals(ProbeControllerPhase.EXPORT_P, controller.state.phase)

        val duplicateEligibility = ManualEligibilityPort()
        val duplicate = controller(
            ImmediateUStorePort(),
            ImmediatePTransportPort(),
            duplicateEligibility,
        )
        assertTrue(duplicate.begin())
        duplicateEligibility.complete(TaskResult.Completed(eligible()))
        duplicateEligibility.complete(TaskResult.Completed(eligible()))
        assertEquals(ProbeControllerPhase.WAITING_FOR_SAF_EXPORT, duplicate.state.phase)
        assertEquals(1, duplicate.state.evidence.events.count {
            it.phase == ProbeControllerPhase.CHECK_ENVIRONMENT
        })
    }

    @Test
    fun `process recreation discards case and only permits a fresh explicit run`() {
        val u = ImmediateUStorePort()
        val p = ImmediatePTransportPort()
        val controller = controller(u, p)
        assertTrue(controller.begin())
        assertEquals(ProbeControllerPhase.WAITING_FOR_SAF_EXPORT, controller.state.phase)
        val firstKey = u.lastStoredKey

        controller.processRecreated()
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
        assertEquals(ProbeControllerPhase.NOT_RUN, controller.state.phase)
        assertEquals(ProbeControllerReason.PROVIDER_ENTRY_MAY_REMAIN, controller.state.reason)
        assertTrue(controller.state.evidence.providerEntryMayRemain)
        assertFalse(controller.state.canCleanup)
        assertFalse(controller.state.evidenceJson.contains(checkNotNull(firstKey)))

        assertTrue(controller.begin())
        assertEquals(ProbeControllerPhase.WAITING_FOR_SAF_EXPORT, controller.state.phase)
        assertTrue(u.storeCalls >= 2)
    }

    @Test
    fun `illegal out of order actions and duplicate start are rejected`() {
        val controller = controller(ImmediateUStorePort(), ImmediatePTransportPort())
        assertFalse(controller.exportSidecar())
        assertFalse(controller.verifyBeforeWipe())
        assertFalse(controller.cleanup())
        assertFalse(controller.retry())
        assertFalse(controller.cancel())
        assertTrue(controller.begin())
        assertFalse(controller.begin())
        assertFalse(controller.verifyBeforeWipe())
        assertTrue(controller.exportSidecar())
        assertFalse(controller.exportSidecar())
    }

    @Test
    fun `concurrent duplicate export reserves one single flight`() {
        val p = BlockingPTransportPort()
        val controller = controller(ImmediateUStorePort(), p)
        assertTrue(controller.begin())

        val firstResult = AtomicReference<Boolean>()
        val first = Thread { firstResult.set(controller.exportSidecar()) }
        first.start()
        assertTrue(p.entered.await(2, TimeUnit.SECONDS))

        val secondResult = AtomicReference<Boolean>()
        val secondStarted = CountDownLatch(1)
        val second = Thread {
            secondStarted.countDown()
            secondResult.set(controller.exportSidecar())
        }
        second.start()
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS))

        p.release.countDown()
        first.join(2_000)
        second.join(2_000)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertTrue(firstResult.get())
        assertEquals(1, p.exportCalls)
        assertEquals(ProbeControllerPhase.EXPORT_P, controller.state.phase)
        controller.cancel()
    }

    @Test
    fun `recreation during accepted callback cannot resurrect a provider operation`() {
        val eligibility = ManualEligibilityPort()
        val u = ImmediateUStorePort()
        val controller = controller(u, ImmediatePTransportPort(), eligibility)
        var recreated = false
        controller.addListener { state ->
            if (!recreated && state.evidence.events.any {
                    it.phase == ProbeControllerPhase.CHECK_ENVIRONMENT
                }
            ) {
                recreated = true
                controller.processRecreated()
            }
        }

        assertTrue(controller.begin())
        eligibility.complete(TaskResult.Completed(eligible()))
        assertTrue(recreated)
        assertEquals(ProbeControllerReason.PROCESS_RECREATED_INCOMPLETE, controller.state.reason)
        assertEquals(0, u.storeCalls)
        assertFalse(controller.state.canCleanup)
    }

    @Test
    fun `process recreation after uncertain U store reports possible provider entry`() {
        val u = ManualStoreUStorePort()
        val controller = controller(u, ImmediatePTransportPort())
        assertTrue(controller.begin())
        assertEquals(ProbeControllerPhase.STORE_U, controller.state.phase)

        controller.processRecreated()
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
        assertEquals(ProbeControllerPhase.NOT_RUN, controller.state.phase)
        assertEquals(ProbeControllerReason.PROVIDER_ENTRY_MAY_REMAIN, controller.state.reason)
        assertTrue(controller.state.evidence.providerEntryMayRemain)
        assertFalse(controller.state.evidence.cleanupCompleted)
        assertFalse(controller.state.canCleanup)

        u.completeStore(TaskResult.Completed(Unit))
        assertEquals(ProbeControllerReason.PROVIDER_ENTRY_MAY_REMAIN, controller.state.reason)
    }

    @Test
    fun `E2EE availability alone never makes backup eligible`() {
        val mapped = BlockStoreResult.completed(true).toEligibilityTaskResult()
        assertTrue(mapped is TaskResult.Completed)
        assertEquals(
            BackupEligibility.UNKNOWN,
            (mapped as TaskResult.Completed).value.backupEligibility,
        )

        val eligibility = ProbeEligibility(
            capability = CapabilityStatus.AVAILABLE,
            placement = CapabilityPlacement.SYNCED_PROVIDER,
            backupEligibility = BackupEligibility.UNKNOWN,
            screenLock = ScreenLockState.PIN,
            e2ee = E2eeState.AVAILABLE,
            restorePath = RestorePath.BLOCK_STORE_CLOUD,
        )
        val u = ImmediateUStorePort()
        val controller = controller(
            u,
            ImmediatePTransportPort(),
            ImmediateEligibilityPort(TaskResult.Completed(eligibility)),
        )

        assertTrue(controller.begin())
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
        assertEquals(ProbeControllerReason.BACKUP_NOT_ELIGIBLE, controller.state.reason)
        assertEquals(0, u.storeCalls)
    }

    @Test
    fun `late retrieve and import values are scrubbed after timeout and cancellation`() {
        val retrieveU = ManualRetrieveUStorePort()
        val retrieveScheduler = TestScheduler()
        val retrieveController = controller(
            retrieveU,
            ImmediatePTransportPort(),
            scheduler = retrieveScheduler,
        )
        assertTrue(retrieveController.begin())
        assertTrue(retrieveController.exportSidecar())
        assertTrue(retrieveController.verifyBeforeWipe())
        val retrieveBytes = ByteArray(ProbeSidecar.KEY_BYTES) { 7 }
        retrieveScheduler.fireNext()
        retrieveU.completeRetrieve(TaskResult.Completed(retrieveBytes))
        assertArrayEquals(ByteArray(ProbeSidecar.KEY_BYTES), retrieveBytes)

        val importU = ImmediateUStorePort()
        val importP = ManualImportPTransportPort()
        val importController = controller(importU, importP)
        assertTrue(importController.begin())
        assertTrue(importController.exportSidecar())
        assertTrue(importController.verifyBeforeWipe())
        assertEquals(ProbeControllerPhase.IMPORT_P, importController.state.phase)
        val sidecar = ByteArray(ProbeSidecar.TOTAL_BYTES) { 9 }
        assertTrue(importController.cancel())
        importP.completeImport(TaskResult.Completed(sidecar))
        assertArrayEquals(ByteArray(ProbeSidecar.TOTAL_BYTES), sidecar)
        assertArrayEquals(ByteArray(ProbeSidecar.KEY_BYTES), checkNotNull(importU.lastRetrieved))
    }

    @Test
    fun `import timeout without callback wipes retrieved U`() {
        val u = ImmediateUStorePort()
        val p = ManualImportPTransportPort()
        val scheduler = TestScheduler()
        val controller = controller(u, p, scheduler = scheduler)

        assertTrue(controller.begin())
        assertTrue(controller.exportSidecar())
        assertTrue(controller.verifyBeforeWipe())
        val retrieved = checkNotNull(u.lastRetrieved)
        assertTrue(retrieved.any { it.toInt() != 0 })

        scheduler.fireNext()

        assertArrayEquals(ByteArray(ProbeSidecar.KEY_BYTES), retrieved)
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
        assertEquals(ProbeControllerReason.RETRYABLE_UNAVAILABLE, controller.state.reason)
    }

    @Test
    fun `import recreation without callback wipes retrieved U and ignores late value`() {
        val u = ImmediateUStorePort()
        val p = ManualImportPTransportPort()
        val controller = controller(u, p)

        assertTrue(controller.begin())
        assertTrue(controller.exportSidecar())
        assertTrue(controller.verifyBeforeWipe())
        val retrieved = checkNotNull(u.lastRetrieved)

        controller.processRecreated()

        assertArrayEquals(ByteArray(ProbeSidecar.KEY_BYTES), retrieved)
        val lateSidecar = ByteArray(ProbeSidecar.TOTAL_BYTES) { 9 }
        p.completeImport(TaskResult.Completed(lateSidecar))
        assertArrayEquals(ByteArray(ProbeSidecar.TOTAL_BYTES), lateSidecar)
        assertEquals(ProbeControllerReason.PROVIDER_ENTRY_MAY_REMAIN, controller.state.reason)
    }

    @Test
    fun `evidence remains bounded across repeated retries`() {
        val u = ImmediateUStorePort()
        val p = ImmediatePTransportPort()
        val controller = controller(u, p)
        assertTrue(controller.begin())
        p.nextExport = TaskResult.RetryableUnavailable
        assertTrue(controller.exportSidecar())
        repeat(ProbeEvidenceSnapshot.MAX_EVENTS + 16) {
            p.nextExport = TaskResult.RetryableUnavailable
            assertTrue(controller.retry())
        }
        assertEquals(ProbeEvidenceSnapshot.MAX_EVENTS, controller.state.evidence.events.size)
        assertTrue(controller.state.evidence.legacyRecords.size <= ProbeEvidenceSnapshot.MAX_LEGACY_RECORDS)
        assertTrue(controller.state.evidenceJson.length < 20_000)
    }

    @Test
    fun `rejected SAF submission wipes owned P copy`() {
        val source = ByteArray(ProbeSidecar.TOTAL_BYTES) { 3 }
        val owned = OwnedSafExportBytes(source)
        var callbackResult: TaskResult<Unit>? = null
        AsyncSafOperation<Unit>(
            executor = Executor { throw RejectedExecutionException() },
            work = { SafTransportResult.completed(Unit) },
            onAbandonBeforeStart = owned::wipe,
        ).then { callbackResult = it }

        assertArrayEquals(ByteArray(ProbeSidecar.TOTAL_BYTES), source)
        assertArrayEquals(ByteArray(ProbeSidecar.TOTAL_BYTES), owned.bytes)
        assertEquals(TaskResult.Indeterminate, callbackResult)
    }

    @Test
    fun `queued SAF export is wiped when cancellation abandons task`() {
        val executor = QueuedExecutor()
        val source = ByteArray(ProbeSidecar.TOTAL_BYTES) { 4 }
        val owned = OwnedSafExportBytes(source)
        var workRan = false
        var callbackResult: TaskResult<Unit>? = null
        val operation = AsyncSafOperation<Unit>(
            executor = executor,
            work = {
                workRan = true
                SafTransportResult.completed(Unit)
            },
            onAbandonBeforeStart = owned::wipe,
        ).then { callbackResult = it }

        assertTrue(operation.cancel())
        assertArrayEquals(ByteArray(ProbeSidecar.TOTAL_BYTES), owned.bytes)
        executor.run()
        assertFalse(workRan)
        assertEquals(TaskResult.RetryableUnavailable, callbackResult)
    }

    @Test
    fun `queued SAF export is wiped when process recreation abandons task`() {
        val executor = QueuedExecutor()
        val p = QueuedExportPTransportPort(executor)
        val controller = controller(ImmediateUStorePort(), p)

        assertTrue(controller.begin())
        assertTrue(controller.exportSidecar())
        assertTrue(p.owned.bytes.any { it.toInt() != 0 })

        controller.processRecreated()

        assertArrayEquals(ByteArray(ProbeSidecar.TOTAL_BYTES), p.owned.bytes)
        executor.run()
        assertFalse(p.workRan)
    }

    @Test
    fun `retryable cleanup preserves pass result and reuses exact key only`() {
        val u = ImmediateUStorePort()
        val p = ImmediatePTransportPort()
        val controller = controller(u, p)
        assertTrue(controller.begin())
        assertTrue(controller.exportSidecar())
        assertTrue(controller.verifyBeforeWipe())
        val key = u.lastStoredKey
        u.nextDelete = TaskResult.RetryableUnavailable
        assertTrue(controller.cleanup())
        assertEquals(ProbeControllerStatus.BLOCKED, controller.state.status)
        assertTrue(controller.state.canRetry)
        assertTrue(controller.retry())
        assertEquals(ProbeControllerStatus.PASS, controller.state.status)
        assertEquals(ProbeControllerPhase.TERMINAL, controller.state.phase)
        assertEquals(listOf(key), u.deleteKeys)
    }

    private fun controller(
        u: ProbeUStorePort,
        p: ProbePTransportPort,
        eligibility: ProbeEligibilityPort = ImmediateEligibilityPort(TaskResult.Completed(eligible())),
        scheduler: ProbeScheduler = TestScheduler(),
    ) = ProbeController(
        eligibilityPort = eligibility,
        uStore = u,
        pTransport = p,
        scheduler = scheduler,
        random = FixedRandomSource(),
    )

    private fun eligible() = ProbeEligibility(
        capability = CapabilityStatus.AVAILABLE,
        placement = CapabilityPlacement.SYNCED_PROVIDER,
        backupEligibility = BackupEligibility.ELIGIBLE,
        screenLock = ScreenLockState.PIN,
        e2ee = E2eeState.AVAILABLE,
        restorePath = RestorePath.BLOCK_STORE_CLOUD,
    )

    private class FixedRandomSource : ProbeRandomSource {
        override fun nextBytes(size: Int): ByteArray = ByteArray(size) { (it + 1).toByte() }
    }

    private class ImmediateOperation : ProbeControllerOperation {
        override fun timeout(): Boolean = false

        override fun cancel(): Boolean = false
    }

    private class ImmediateEligibilityPort(
        private val result: TaskResult<ProbeEligibility>,
    ) : ProbeEligibilityPort {
        override fun detect(onSettled: (TaskResult<ProbeEligibility>) -> Unit): ProbeControllerOperation {
            onSettled(result)
            return ImmediateOperation()
        }
    }

    private class ManualEligibilityPort : ProbeEligibilityPort {
        private var callback: ((TaskResult<ProbeEligibility>) -> Unit)? = null
        val operation = ManualOperation()

        override fun detect(onSettled: (TaskResult<ProbeEligibility>) -> Unit): ProbeControllerOperation {
            callback = onSettled
            return operation
        }

        fun complete(result: TaskResult<ProbeEligibility>) {
            callback?.invoke(result)
        }
    }

    private class ImmediateUStorePort : ProbeUStorePort {
        private val delegate = FakeUStore()
        var storeCalls = 0
        var lastStoredKey: String? = null
        var lastRetrieved: ByteArray? = null
        var nextDelete: TaskResult<Unit>? = null
        val deletedKeys: List<String>
            get() = delegate.deletedKeys
        val deleteKeys: List<String>
            get() = delegate.deletedKeys

        override fun storeU(
            key: String,
            value: ByteArray,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation {
            storeCalls += 1
            lastStoredKey = key
            val result = delegate.storeU(key, value)
            value.fill(0)
            onSettled(result)
            return ImmediateOperation()
        }

        override fun retrieveU(
            key: String,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation {
            val result = delegate.retrieveU(key)
            lastRetrieved = (result as? TaskResult.Completed)?.value
            onSettled(result)
            return ImmediateOperation()
        }

        override fun deleteU(
            key: String,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation {
            val scripted = nextDelete.also { nextDelete = null }
            if (scripted != null) {
                onSettled(scripted)
            } else {
                onSettled(delegate.deleteU(key))
            }
            return ImmediateOperation()
        }
    }

    private class ManualStoreUStorePort : ProbeUStorePort {
        private var storeCallback: ((TaskResult<Unit>) -> Unit)? = null
        val operation = ManualOperation()

        override fun storeU(
            key: String,
            value: ByteArray,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation {
            value.fill(0)
            storeCallback = onSettled
            return operation
        }

        override fun retrieveU(
            key: String,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation = ImmediateOperation()

        override fun deleteU(
            key: String,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = ImmediateOperation()

        fun completeStore(result: TaskResult<Unit>) {
            storeCallback?.invoke(result)
        }
    }

    private class ManualRetrieveUStorePort : ProbeUStorePort {
        private val delegate = FakeUStore()
        private var retrieveCallback: ((TaskResult<ByteArray>) -> Unit)? = null
        private val retrieveOperation = ManualOperation()

        override fun storeU(
            key: String,
            value: ByteArray,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation {
            val result = delegate.storeU(key, value)
            value.fill(0)
            onSettled(result)
            return ImmediateOperation()
        }

        override fun retrieveU(
            key: String,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation {
            retrieveCallback = onSettled
            return retrieveOperation
        }

        override fun deleteU(
            key: String,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation = ImmediateOperation()

        fun completeRetrieve(result: TaskResult<ByteArray>) {
            retrieveCallback?.invoke(result)
        }
    }

    private class ImmediatePTransportPort : ProbePTransportPort {
        private val delegate = FakePTransport()
        var lastExported: ByteArray? = null
        var nextExport: TaskResult<Unit>? = null
        var nextImport: TaskResult<ByteArray>? = null

        override fun exportP(
            opaqueP: ByteArray,
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation {
            lastExported = opaqueP.copyOf()
            val scripted = nextExport.also { nextExport = null }
            val result = scripted ?: delegate.storeP(opaqueP, expectedContext)
            opaqueP.fill(0)
            onSettled(result)
            return ImmediateOperation()
        }

        override fun importP(
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation {
            val scripted = nextImport.also { nextImport = null }
            onSettled(scripted ?: delegate.readP(expectedContext))
            return ImmediateOperation()
        }
    }

    private class ManualPTransportPort : ProbePTransportPort {
        private var callback: ((TaskResult<Unit>) -> Unit)? = null
        val operation = ManualOperation()

        override fun exportP(
            opaqueP: ByteArray,
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation {
            opaqueP.fill(0)
            callback = onSettled
            return operation
        }

        override fun importP(
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation = ImmediateOperation()

        fun completeExport(result: TaskResult<Unit>) {
            callback?.invoke(result)
        }
    }

    private class ManualImportPTransportPort : ProbePTransportPort {
        private val delegate = FakePTransport()
        private var importCallback: ((TaskResult<ByteArray>) -> Unit)? = null
        private val importOperation = ManualOperation()

        override fun exportP(
            opaqueP: ByteArray,
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation {
            val result = delegate.storeP(opaqueP, expectedContext)
            opaqueP.fill(0)
            onSettled(result)
            return ImmediateOperation()
        }

        override fun importP(
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation {
            importCallback = onSettled
            return importOperation
        }

        fun completeImport(result: TaskResult<ByteArray>) {
            importCallback?.invoke(result)
        }
    }

    private class BlockingPTransportPort : ProbePTransportPort {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var exportCalls = 0

        override fun exportP(
            opaqueP: ByteArray,
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation {
            exportCalls += 1
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            opaqueP.fill(0)
            return ImmediateOperation()
        }

        override fun importP(
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation = ImmediateOperation()
    }

    private class QueuedExportPTransportPort(
        private val executor: Executor,
    ) : ProbePTransportPort {
        lateinit var owned: OwnedSafExportBytes
        var workRan = false

        override fun exportP(
            opaqueP: ByteArray,
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<Unit>) -> Unit,
        ): ProbeControllerOperation {
            owned = OwnedSafExportBytes(opaqueP)
            return AsyncSafOperation(
                executor = executor,
                work = {
                    workRan = true
                    SafTransportResult.completed(Unit)
                },
                onAbandonBeforeStart = owned::wipe,
            ).then(onSettled)
        }

        override fun importP(
            expectedContext: ExpectedContext,
            onSettled: (TaskResult<ByteArray>) -> Unit,
        ): ProbeControllerOperation = ImmediateOperation()
    }

    private class ManualOperation : ProbeControllerOperation {
        var timeoutCalls = 0
        var cancelCalls = 0

        override fun timeout(): Boolean {
            timeoutCalls += 1
            return true
        }

        override fun cancel(): Boolean {
            cancelCalls += 1
            return true
        }
    }

    private class TestScheduler : ProbeScheduler {
        private data class Entry(
            val callback: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMs: Long, callback: () -> Unit): ProbeScheduledHandle {
            val entry = Entry(callback)
            entries += entry
            return ProbeScheduledHandle { entry.cancelled = true }
        }

        fun fireNext() {
            val entry = entries.firstOrNull { !it.cancelled }
                ?: error("no scheduled deadline")
            entry.cancelled = true
            entry.callback()
        }
    }

    private class QueuedExecutor : Executor {
        private var queued: Runnable? = null

        override fun execute(command: Runnable) {
            check(queued == null)
            queued = command
        }

        fun run() {
            val command = checkNotNull(queued)
            queued = null
            command.run()
        }
    }
}
