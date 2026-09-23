package dev.hryshyn.rv01probe.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 store-gate wiring tests: the SAME shared [OperatorConfirmedP1Inputs]
 * instance that drives the UI and the P1 eligibility decision also gates the
 * real storeU path. Unconfirmed/insecure/null stay blocked before any provider
 * E2EE or client creation; the confirmed path reaches the existing E2EE gate
 * (no fake physical PASS is claimed here). Exact-key/tombstone/E2EE behavior
 * is unchanged and covered by BlockStoreProviderBackendTest.
 */
class P2StoreGateWiringTest {

    @Test
    fun `shared unconfirmed blocks store before provider E2EE or client creation`() {
        val shared = OperatorConfirmedP1Inputs()
        val client = FakeClient()
        var factoryCalls = 0
        val gated = GoogleBlockStoreUStore(
            BlockStoreClientFactory {
                factoryCalls += 1
                client
            },
            { true },
            { resolveGatedQualifyingState(true, true, shared.snapshot().lockKind) },
            BlockStoreDeleteTombstone.inMemoryForTest(),
        )
        val results = mutableListOf<BlockStoreResult<Unit>>()
        gated.storeU(validKey(), bytes(32)) { results += it }
        assertEquals(BlockStoreOutcome.UNAVAILABLE, results.single().outcome)
        assertEquals(0, factoryCalls)
        assertEquals(0, client.e2eeCalls)
        assertTrue(client.storeRequests.isEmpty())
    }

    @Test
    fun `shared confirmed PIN reaches existing E2EE gate with lazy client`() {
        val shared = OperatorConfirmedP1Inputs()
        assertTrue(shared.confirmLockKind(OperatorConfirmedLockKind.PIN))
        val client = FakeClient()
        var factoryCalls = 0
        val bridge = GoogleBlockStoreUStore(
            BlockStoreClientFactory {
                factoryCalls += 1
                client
            },
            { true },
            { resolveGatedQualifyingState(true, true, shared.snapshot().lockKind) },
            BlockStoreDeleteTombstone.inMemoryForTest(),
        )
        val results = mutableListOf<BlockStoreResult<Unit>>()
        bridge.storeU(validKey(), bytes(32)) { results += it }
        // Gate passed: exactly one lazy client and one E2EE check, no store yet.
        assertEquals(1, factoryCalls)
        assertEquals(1, client.e2eeCalls)
        assertTrue(client.storeRequests.isEmpty())
        assertTrue(results.isEmpty())

        // Existing E2EE gate proceeds to a one-key cloud-backup request.
        client.e2eeTask.succeed(true)
        val request = client.storeRequests.single()
        assertEquals(validKey(), request.key)
        assertTrue(request.shouldBackupToCloud)
        // No completion claimed here: the provider store task is still pending,
        // so this test proves gate reachability only, not a physical PASS.
        assertTrue(results.isEmpty())
    }

    @Test
    fun `insecure stays blocked even with confirmed shared inputs`() {
        val shared = OperatorConfirmedP1Inputs()
        shared.confirmLockKind(OperatorConfirmedLockKind.PASSWORD)
        shared.confirmBackupEligibility(BackupEligibility.ELIGIBLE)
        shared.confirmBackupNowCompleted()
        val client = FakeClient()
        var factoryCalls = 0
        val bridge = GoogleBlockStoreUStore(
            BlockStoreClientFactory {
                factoryCalls += 1
                client
            },
            { true },
            { resolveGatedQualifyingState(true, false, shared.snapshot().lockKind) },
            BlockStoreDeleteTombstone.inMemoryForTest(),
        )
        val results = mutableListOf<BlockStoreResult<Unit>>()
        bridge.storeU(validKey(), bytes(32)) { results += it }
        assertEquals(BlockStoreOutcome.UNAVAILABLE, results.single().outcome)
        assertEquals(0, factoryCalls)
        assertEquals(0, client.e2eeCalls)
        assertTrue(client.storeRequests.isEmpty())
    }

    @Test
    fun `null keyguard stays blocked even when shared inputs confirm`() {
        val shared = OperatorConfirmedP1Inputs()
        shared.confirmLockKind(OperatorConfirmedLockKind.PIN)
        shared.confirmBackupEligibility(BackupEligibility.ELIGIBLE)
        shared.confirmBackupNowCompleted()
        val client = FakeClient()
        var factoryCalls = 0
        val bridge = GoogleBlockStoreUStore(
            BlockStoreClientFactory {
                factoryCalls += 1
                client
            },
            { true },
            { resolveGatedQualifyingState(false, null, shared.snapshot().lockKind) },
            BlockStoreDeleteTombstone.inMemoryForTest(),
        )
        val results = mutableListOf<BlockStoreResult<Unit>>()
        bridge.storeU(validKey(), bytes(32)) { results += it }
        assertEquals(BlockStoreOutcome.UNAVAILABLE, results.single().outcome)
        assertEquals(0, factoryCalls)
        assertEquals(0, client.e2eeCalls)
    }

    @Test
    fun `one shared instance drives eligibility and store decisions together`() {
        val shared = OperatorConfirmedP1Inputs()
        fun eligibilityQualified() = decideP1Detect(true, true, shared.snapshot()).qualified
        fun storeAllows() =
            resolveGatedQualifyingState(true, true, shared.snapshot().lockKind) ==
                BlockStoreLockState.QUALIFIED

        assertFalse(eligibilityQualified())
        assertFalse(storeAllows())

        shared.confirmLockKind(OperatorConfirmedLockKind.PATTERN)
        assertTrue(eligibilityQualified())
        assertTrue(storeAllows())

        shared.clearLockKind()
        assertFalse(eligibilityQualified())
        assertFalse(storeAllows())
    }

    @Test
    fun `cleared confirmation re-blocks store without provider calls`() {
        val shared = OperatorConfirmedP1Inputs()
        shared.confirmLockKind(OperatorConfirmedLockKind.PIN)
        shared.clearLockKind()
        val client = FakeClient()
        var factoryCalls = 0
        val bridge = GoogleBlockStoreUStore(
            BlockStoreClientFactory {
                factoryCalls += 1
                client
            },
            { true },
            { resolveGatedQualifyingState(true, true, shared.snapshot().lockKind) },
            BlockStoreDeleteTombstone.inMemoryForTest(),
        )
        val results = mutableListOf<BlockStoreResult<Unit>>()
        bridge.storeU(validKey(), bytes(32)) { results += it }
        assertEquals(BlockStoreOutcome.UNAVAILABLE, results.single().outcome)
        assertEquals(0, factoryCalls)
        assertEquals(0, client.e2eeCalls)
    }

    private fun validKey() = "Abcdefghijklmnopqrstuv"

    private fun bytes(size: Int) = ByteArray(size) { (it + 1).toByte() }

    private class FakeClient : BlockStoreClientPort {
        val e2eeTask = FakeTask<Boolean>()
        var storeTask = FakeTask<Int>()
        var e2eeCalls = 0
        val storeRequests = mutableListOf<BlockStoreStoreRequest>()

        override fun isEndToEndEncryptionAvailable(): BlockStoreTaskPort<Boolean> {
            e2eeCalls += 1
            return e2eeTask
        }

        override fun storeBytes(request: BlockStoreStoreRequest): BlockStoreTaskPort<Int> {
            storeRequests += request
            return storeTask
        }

        override fun retrieveBytes(
            request: BlockStoreRetrieveRequest,
        ): BlockStoreTaskPort<BlockStoreRetrieveResponse> = FakeTask()

        override fun deleteBytes(request: BlockStoreDeleteRequest): BlockStoreTaskPort<Boolean> =
            FakeTask()
    }

    private class FakeTask<T> : BlockStoreTaskPort<T> {
        private var success: ((T) -> Unit)? = null
        private var failure: ((BlockStoreTaskFailure) -> Unit)? = null
        private var cancelled: (() -> Unit)? = null

        override fun addOnSuccessListener(listener: (T) -> Unit) {
            success = listener
        }

        override fun addOnFailureListener(listener: (BlockStoreTaskFailure) -> Unit) {
            failure = listener
        }

        override fun addOnCanceledListener(listener: () -> Unit) {
            cancelled = listener
        }

        fun succeed(value: T) {
            success?.invoke(value)
        }

        fun fail(value: BlockStoreTaskFailure) {
            failure?.invoke(value)
        }

        fun cancel() {
            cancelled?.invoke()
        }
    }
}
