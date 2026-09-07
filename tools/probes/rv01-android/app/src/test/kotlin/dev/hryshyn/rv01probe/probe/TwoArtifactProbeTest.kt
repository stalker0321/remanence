package dev.hryshyn.rv01probe.probe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoArtifactProbeTest {
    @Test
    fun `never supplied fixture is incomplete but disappeared supplied U is unavailable`() {
        val material = bytes(33)
        val canary = bytes(1)
        val context = context(AccountBindingClass.A, ContextGeneration.G1, ContextTargetRole.D1_SOURCE)
        val uStore = FakeUStore()
        val pTransport = FakePTransport()
        val runner = TwoArtifactProbeRunner(uStore, pTransport)
        val case = TwoArtifactCase(validKey(), material, context)
        val p = checkNotNull(ProbeSidecar.seal(canary, material, context, FixedRandom()))

        assertEquals(TwoArtifactOutcome.INCOMPLETE, runner.run(null).outcome)
        val fixture = suppliedFixture(runner.prepare(case, p))
        uStore.discardValueForTest(case.key)
        assertEquals(TwoArtifactOutcome.UNAVAILABLE, runner.run(fixture).outcome)
    }

    @Test
    fun `runner keeps setup separate and labels only post-wipe harness success`() {
        val material = bytes(33)
        val canary = bytes(1)
        val context = context(AccountBindingClass.A, ContextGeneration.G1, ContextTargetRole.D1_SOURCE)
        val uStore = FakeUStore()
        val pTransport = FakePTransport()
        val runner = TwoArtifactProbeRunner(uStore, pTransport)
        val case = TwoArtifactCase(validKey(), material, context)
        val p = checkNotNull(ProbeSidecar.seal(canary, material, context, FixedRandom()))

        val fixture = suppliedFixture(runner.prepare(case, p))
        val result = runner.run(fixture)

        assertEquals(TwoArtifactOutcome.PASS, result.outcome)
        assertEquals(TwoArtifactSuccessLabel.U_DURABILITY_PLUS_HARNESS_P, result.successLabel)
        assertTrue(uStore.storedKeys.contains(case.key))
        assertTrue(p.isNotEmpty())
    }

    @Test
    fun `well formed account run generation and role mismatches are rejected before AEAD`() {
        val material = bytes(33)
        val canary = bytes(1)
        val trusted = context(AccountBindingClass.A, ContextGeneration.G1, ContextTargetRole.D1_SOURCE)
        val p = checkNotNull(ProbeSidecar.seal(canary, material, trusted, FixedRandom()))
        val mismatches = listOf(
            trusted.copy(runId = trusted.runId.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }),
            trusted.copy(accountBindingClass = AccountBindingClass.B),
            trusted.copy(generation = ContextGeneration.G2),
            trusted.copy(targetRole = ContextTargetRole.D2_TARGET),
        )

        mismatches.forEach { changed ->
            val canonical = changed.canonicalBytes()
            val decoded = ExpectedContext.decode(canonical)
            assertNotNull(decoded)
            assertEquals(changed, decoded)
            val runner = TwoArtifactProbeRunner(FakeUStore(), FakePTransport())
            val fixture = suppliedFixture(
                runner.prepare(TwoArtifactCase(validKey(), material, changed), p),
            )
            assertEquals(TwoArtifactOutcome.REJECTED, runner.run(fixture).outcome)
        }
    }

    @Test
    fun `missing tampered and truncated artifacts fail with distinct bounded outcomes`() {
        val material = bytes(33)
        val canary = bytes(1)
        val context = context(AccountBindingClass.A, ContextGeneration.G1, ContextTargetRole.D1_SOURCE)
        val case = TwoArtifactCase(validKey(), material, context)
        val p = checkNotNull(ProbeSidecar.seal(canary, material, context, FixedRandom()))

        val missingUStore = FakeUStore()
        val missingURunner = TwoArtifactProbeRunner(missingUStore, FakePTransport())
        val missingUFixture = suppliedFixture(missingURunner.prepare(case, p))
        missingUStore.discardValueForTest(case.key)
        assertEquals(TwoArtifactOutcome.UNAVAILABLE, missingURunner.run(missingUFixture).outcome)

        val tamperedUStore = FakeUStore().also {
            it.respondToNextRetrieve(TaskResult.Completed(material.changed(0, 0x7f)))
        }
        val tamperedURunner = TwoArtifactProbeRunner(tamperedUStore, FakePTransport())
        val tamperedUFixture = suppliedFixture(tamperedURunner.prepare(case, p))
        assertEquals(TwoArtifactOutcome.FAIL_CLOSED, tamperedURunner.run(tamperedUFixture).outcome)

        val missingPTransport = FakePTransport()
        val missingPRunner = TwoArtifactProbeRunner(FakeUStore(), missingPTransport)
        val missingPFixture = suppliedFixture(missingPRunner.prepare(case, p))
        missingPTransport.discardForTest()
        assertEquals(TwoArtifactOutcome.UNAVAILABLE, missingPRunner.run(missingPFixture).outcome)

        val truncatedRunner = TwoArtifactProbeRunner(FakeUStore(), FakePTransport())
        val truncatedFixture = suppliedFixture(
            truncatedRunner.prepare(case, p.copyOf(ProbeSidecar.TOTAL_BYTES - 1)),
        )
        assertEquals(TwoArtifactOutcome.FAIL_CLOSED, truncatedRunner.run(truncatedFixture).outcome)

        val tamperedP = p.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }
        val tamperedPRunner = TwoArtifactProbeRunner(FakeUStore(), FakePTransport())
        val tamperedPFixture = suppliedFixture(tamperedPRunner.prepare(case, tamperedP))
        assertEquals(TwoArtifactOutcome.FAIL_CLOSED, tamperedPRunner.run(tamperedPFixture).outcome)
    }

    @Test
    fun `invalid and wrong K_U values fail closed without cross-key access`() {
        val material = bytes(33)
        val store = FakeUStore()
        val invalidKeys = listOf("short", "!" + validKey().drop(1), validKey() + "x")

        invalidKeys.forEach { key ->
            assertEquals(TaskResultKind.INCOMPLETE, store.storeU(key, material).kind)
            assertEquals(TaskResultKind.INCOMPLETE, store.retrieveU(key).kind)
            assertEquals(TaskResultKind.INCOMPLETE, store.deleteU(key).kind)
        }

        val key = validKey()
        val otherKey = "Bbcdefghijklmnopqrstuv"
        assertEquals(TaskResultKind.COMPLETED, store.storeU(key, material).kind)
        assertEquals(TaskResultKind.INCOMPLETE, store.retrieveU(otherKey).kind)
        assertEquals(TaskResultKind.COMPLETED, store.deleteU(otherKey).kind)
        assertTrue(store.storedKeys.contains(key))
        assertFalse(store.deletedKeys.contains(key))
    }

    @Test
    fun `unavailable indeterminate and P timeout or cancellation never become success`() {
        val material = bytes(33)
        val canary = bytes(1)
        val context = context(AccountBindingClass.A, ContextGeneration.G1, ContextTargetRole.D1_SOURCE)
        val case = TwoArtifactCase(validKey(), material, context)
        val p = checkNotNull(ProbeSidecar.seal(canary, material, context, FixedRandom()))

        val unavailableStore = FakeUStore().also { it.respondToNextStore(TaskResult.Unavailable) }
        assertEquals(
            TwoArtifactOutcome.UNAVAILABLE,
            TwoArtifactProbeRunner(unavailableStore, FakePTransport()).prepare(case, p).failureResult().outcome,
        )

        val indeterminateStore = FakeUStore().also {
            it.respondToNextRetrieve(TaskResult.Indeterminate)
        }
        val indeterminateRunner = TwoArtifactProbeRunner(indeterminateStore, FakePTransport())
        val indeterminateFixture = suppliedFixture(indeterminateRunner.prepare(case, p))
        assertEquals(TwoArtifactOutcome.INDETERMINATE, indeterminateRunner.run(indeterminateFixture).outcome)

        val retryableP = FakePTransport().also {
            it.respondToNextRead(TaskResult.RetryableUnavailable)
        }
        val retryableRunner = TwoArtifactProbeRunner(FakeUStore(), retryableP)
        val retryableFixture = suppliedFixture(retryableRunner.prepare(case, p))
        assertEquals(TwoArtifactOutcome.RETRYABLE_UNAVAILABLE, retryableRunner.run(retryableFixture).outcome)

        val unavailableP = FakePTransport().also {
            it.respondToNextRead(TaskResult.Unavailable)
        }
        val unavailableRunner = TwoArtifactProbeRunner(FakeUStore(), unavailableP)
        val unavailableFixture = suppliedFixture(unavailableRunner.prepare(case, p))
        assertEquals(TwoArtifactOutcome.UNAVAILABLE, unavailableRunner.run(unavailableFixture).outcome)

        val timeoutP = FakePTransport().also { it.timeoutNextRead() }
        val timeoutRunner = TwoArtifactProbeRunner(FakeUStore(), timeoutP)
        val timeoutFixture = suppliedFixture(timeoutRunner.prepare(case, p))
        assertEquals(TwoArtifactOutcome.RETRYABLE_UNAVAILABLE, timeoutRunner.run(timeoutFixture).outcome)

        val cancelP = FakePTransport().also { it.cancelNextRead() }
        val cancelRunner = TwoArtifactProbeRunner(FakeUStore(), cancelP)
        val cancelFixture = suppliedFixture(cancelRunner.prepare(case, p))
        assertEquals(TwoArtifactOutcome.RETRYABLE_UNAVAILABLE, cancelRunner.run(cancelFixture).outcome)
    }

    @Test
    fun `unresolved delete quarantines K_U and token cannot remove successor value`() {
        val firstMaterial = bytes(33)
        val secondMaterial = bytes(77)
        val canary = bytes(1)
        val context = context(AccountBindingClass.A, ContextGeneration.G1, ContextTargetRole.D1_SOURCE)
        val firstCase = TwoArtifactCase(validKey(), firstMaterial, context)
        val firstP = checkNotNull(ProbeSidecar.seal(canary, firstMaterial, context, FixedRandom()))
        val uStore = FakeUStore()
        val pTransport = FakePTransport()
        val runner = TwoArtifactProbeRunner(uStore, pTransport)
        val fixture = suppliedFixture(runner.prepare(firstCase, firstP))

        uStore.respondToNextDelete(TaskResult.RetryableUnavailable)
        assertEquals(TwoArtifactOutcome.RETRYABLE_UNAVAILABLE, runner.delete(fixture).toOutcome())
        val token = uStore.pendingDeleteToken(firstCase.key)
        assertNotNull(token)
        assertTrue(uStore.isDeleteQuarantined(firstCase.key))
        assertEquals(TaskResultKind.RETRYABLE_UNAVAILABLE, runner.delete(fixture).kind)

        val secondCase = firstCase.copy(unwrapMaterial = secondMaterial)
        val secondP = checkNotNull(ProbeSidecar.seal(canary, secondMaterial, context, FixedRandom()))
        val blocked = runner.prepare(secondCase, secondP).failureResult()
        assertEquals(TwoArtifactOutcome.RETRYABLE_UNAVAILABLE, blocked.outcome)
        val current = completedValue(uStore.retrieveU(firstCase.key))
        assertArrayEquals(firstMaterial, current)
        current.fill(0)

        assertTrue(uStore.reconcileDelete(token!!, DeleteResolution.DELETED))
        assertFalse(uStore.isDeleteQuarantined(firstCase.key))
        val supplied = suppliedFixture(runner.prepare(secondCase, secondP))
        assertEquals(TwoArtifactOutcome.PASS, runner.run(supplied).outcome)
        assertFalse(uStore.reconcileDelete(token, DeleteResolution.DELETED))
        val successor = completedValue(uStore.retrieveU(firstCase.key))
        assertArrayEquals(secondMaterial, successor)
        successor.fill(0)
    }

    @Test
    fun `owned task settlement is first-settlement-wins`() {
        val gate = OwnedTaskSettlement<String>()
        assertTrue(gate.timeout())
        assertFalse(gate.complete("late-success"))
        assertFalse(gate.unavailable())
        assertFalse(gate.retryableUnavailable())
        assertEquals(TaskResultKind.RETRYABLE_UNAVAILABLE, gate.snapshot().kind)

        val cancelled = OwnedTaskSettlement<String>()
        assertTrue(cancelled.cancel())
        assertFalse(cancelled.complete("late-success"))
        assertEquals(TaskResultKind.RETRYABLE_UNAVAILABLE, cancelled.snapshot().kind)

        val duplicate = OwnedTaskSettlement<String>()
        assertTrue(duplicate.complete("first"))
        assertFalse(duplicate.complete("second"))
        assertEquals(TaskResult.Completed("first"), duplicate.snapshot())
    }

    @Test
    fun `cleanup deletes only exact K_U and preserves another key`() {
        val material = bytes(33)
        val otherMaterial = bytes(77)
        val canary = bytes(1)
        val context = context(AccountBindingClass.A, ContextGeneration.G1, ContextTargetRole.D1_SOURCE)
        val case = TwoArtifactCase(validKey(), material, context)
        val p = checkNotNull(ProbeSidecar.seal(canary, material, context, FixedRandom()))
        val uStore = FakeUStore()
        val runner = TwoArtifactProbeRunner(uStore, FakePTransport())
        val fixture = suppliedFixture(runner.prepare(case, p))
        val otherKey = "Bbcdefghijklmnopqrstuv"

        assertEquals(TwoArtifactOutcome.PASS, runner.run(fixture).outcome)
        assertEquals(TaskResultKind.COMPLETED, uStore.storeU(otherKey, otherMaterial).kind)
        assertEquals(TaskResultKind.COMPLETED, runner.delete(fixture).kind)
        assertEquals(listOf(case.key), uStore.deletedKeys)
        assertEquals(setOf(otherKey), uStore.storedKeys)
    }

    private fun suppliedFixture(result: FixtureSetupResult): TwoArtifactFixture {
        assertTrue(result is FixtureSetupResult.Supplied)
        return (result as FixtureSetupResult.Supplied).fixture
    }

    private fun FixtureSetupResult.failureResult(): TwoArtifactRunResult {
        assertTrue(this is FixtureSetupResult.Failed)
        return (this as FixtureSetupResult.Failed).result
    }

    private fun TaskResult<Unit>.toOutcome(): TwoArtifactOutcome = when (this) {
        is TaskResult.Completed -> TwoArtifactOutcome.PASS
        TaskResult.Unavailable -> TwoArtifactOutcome.UNAVAILABLE
        TaskResult.RetryableUnavailable -> TwoArtifactOutcome.RETRYABLE_UNAVAILABLE
        TaskResult.Incomplete -> TwoArtifactOutcome.INCOMPLETE
        TaskResult.Indeterminate -> TwoArtifactOutcome.INDETERMINATE
    }

    private fun completedValue(result: TaskResult<ByteArray>): ByteArray {
        assertTrue(result is TaskResult.Completed)
        return (result as TaskResult.Completed).value
    }

    private fun bytes(start: Int) = ByteArray(32) { (start + it).toByte() }

    private fun validKey() = "Abcdefghijklmnopqrstuv"

    private fun context(
        account: AccountBindingClass,
        generation: ContextGeneration,
        role: ContextTargetRole,
    ) = ExpectedContext(
        accountBindingClass = account,
        runId = ByteArray(16) { (it + 1).toByte() },
        generation = generation,
        targetRole = role,
    )

    private fun ByteArray.changed(index: Int, value: Int): ByteArray = copyOf().also {
        it[index] = value.toByte()
    }

    private class FixedRandom : java.security.SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.forEachIndexed { index, _ -> bytes[index] = (index + 1).toByte() }
        }
    }
}
