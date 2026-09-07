package dev.hryshyn.rv01probe.probe

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TwoArtifactCase(
    val key: String,
    val unwrapMaterial: ByteArray,
    val expectedContext: ExpectedContext,
)

/** Post-wipe fixture: only K_U and independently trusted context survive setup. */
data class TwoArtifactFixture(
    val key: String,
    val expectedContext: ExpectedContext,
)

sealed interface FixtureSetupResult {
    data class Supplied(val fixture: TwoArtifactFixture) : FixtureSetupResult

    data class Failed(
        val result: TwoArtifactRunResult,
        val cleanupFixture: TwoArtifactFixture?,
    ) : FixtureSetupResult
}

/**
 * Fake/local post-wipe orchestrator. Setup and recovery are intentionally
 * separate: run() never stores or recreates either artifact.
 */
class TwoArtifactProbeRunner(
    private val uStore: UStore,
    private val pTransport: PTransport,
) {
    /**
     * Supplies the provider U and opaque P fixture. P construction remains
     * outside PTransport, in the already-reviewed ProbeSidecar helper.
     */
    fun prepare(case: TwoArtifactCase, opaqueP: ByteArray): FixtureSetupResult {
        val fixture = case.fixtureOrNull()
        if (fixture == null || case.unwrapMaterial.size != ProbeSidecar.KEY_BYTES) {
            return FixtureSetupResult.Failed(
                result = TwoArtifactRunResult(TwoArtifactOutcome.FAIL_CLOSED),
                cleanupFixture = fixture,
            )
        }
        if (opaqueP.size > ProbeSidecar.MAX_SIZE) {
            return FixtureSetupResult.Failed(
                result = TwoArtifactRunResult(TwoArtifactOutcome.FAIL_CLOSED),
                cleanupFixture = fixture,
            )
        }

        val materialCopy = case.unwrapMaterial.copyOf()
        val packageCopy = opaqueP.copyOf()
        return try {
            when (val stored = uStore.storeU(case.key, materialCopy)) {
                is TaskResult.Completed -> Unit
                else -> return FixtureSetupResult.Failed(taskOutcome(stored, setup = true), fixture)
            }
            when (val stored = pTransport.storeP(packageCopy, case.expectedContext)) {
                is TaskResult.Completed -> Unit
                else -> return FixtureSetupResult.Failed(taskOutcome(stored, setup = true), fixture)
            }
            FixtureSetupResult.Supplied(fixture)
        } finally {
            materialCopy.fill(0)
            packageCopy.fill(0)
        }
    }

    /** Runs read/unwrap only; null means no fixture was ever supplied. */
    fun run(fixture: TwoArtifactFixture?): TwoArtifactRunResult {
        if (fixture == null) return TwoArtifactRunResult(TwoArtifactOutcome.INCOMPLETE)
        if (!ProbeKey.isValid(fixture.key)) {
            return TwoArtifactRunResult(TwoArtifactOutcome.FAIL_CLOSED)
        }

        var retrievedU: ByteArray? = null
        var retrievedP: ByteArray? = null
        var plaintext: ByteArray? = null
        return try {
            val exactU = when (val retrieved = uStore.retrieveU(fixture.key)) {
                is TaskResult.Completed -> retrieved.value
                else -> return taskOutcome(retrieved, setup = false)
            }
            if (exactU.size != ProbeSidecar.KEY_BYTES) {
                exactU.fill(0)
                return TwoArtifactRunResult(TwoArtifactOutcome.FAIL_CLOSED)
            }
            retrievedU = exactU

            val exactP = when (val read = pTransport.readP(fixture.expectedContext)) {
                is TaskResult.Completed -> read.value
                else -> return taskOutcome(read, setup = false)
            }
            if (exactP.size > ProbeSidecar.MAX_SIZE) {
                exactP.fill(0)
                return TwoArtifactRunResult(TwoArtifactOutcome.FAIL_CLOSED)
            }
            retrievedP = exactP

            if (hasWellFormedContextMismatch(exactP, fixture.expectedContext)) {
                return TwoArtifactRunResult(TwoArtifactOutcome.REJECTED)
            }

            val opened = ProbeSidecar.open(exactP, exactU, fixture.expectedContext)
                ?: return TwoArtifactRunResult(TwoArtifactOutcome.FAIL_CLOSED)
            if (opened.size != ProbeSidecar.CANARY_BYTES) {
                opened.fill(0)
                return TwoArtifactRunResult(TwoArtifactOutcome.FAIL_CLOSED)
            }
            plaintext = opened
            TwoArtifactRunResult(
                outcome = TwoArtifactOutcome.PASS,
                successLabel = TwoArtifactSuccessLabel.U_DURABILITY_PLUS_HARNESS_P,
            )
        } finally {
            retrievedU?.fill(0)
            retrievedP?.fill(0)
            plaintext?.fill(0)
        }
    }

    /** Explicit cleanup is exact-key only and is intentionally separate from run(). */
    fun delete(fixture: TwoArtifactFixture?): TaskResult<Unit> =
        fixture?.let { uStore.deleteU(it.key) } ?: TaskResult.Incomplete

    private fun TwoArtifactCase.fixtureOrNull(): TwoArtifactFixture? =
        if (ProbeKey.isValid(key)) TwoArtifactFixture(key, expectedContext) else null

    private fun taskOutcome(result: TaskResult<*>, setup: Boolean): TwoArtifactRunResult =
        TwoArtifactRunResult(
            outcome = when (result) {
                is TaskResult.Completed -> TwoArtifactOutcome.INDETERMINATE
                TaskResult.Unavailable -> TwoArtifactOutcome.UNAVAILABLE
                TaskResult.RetryableUnavailable -> TwoArtifactOutcome.RETRYABLE_UNAVAILABLE
                TaskResult.Incomplete -> if (setup) {
                    TwoArtifactOutcome.INCOMPLETE
                } else {
                    TwoArtifactOutcome.UNAVAILABLE
                }
                TaskResult.Indeterminate -> TwoArtifactOutcome.INDETERMINATE
            },
        )

    /**
     * Distinguishes a canonical claimed context mismatch before AEAD. It
     * checks the complete fixed RVP1 framing but never uses the claim as AAD.
     */
    private fun hasWellFormedContextMismatch(
        encoded: ByteArray,
        expectedContext: ExpectedContext,
    ): Boolean {
        if (encoded.size != ProbeSidecar.TOTAL_BYTES) return false

        val expectedBytes = expectedContext.canonicalBytes()
        var claimedContext: ByteArray? = null
        var decodedClaim: ExpectedContext? = null
        return try {
            val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
            if (buffer.int != SIDEcar_MAGIC) return false
            if ((buffer.short.toInt() and 0xffff) != SIDEcar_VERSION) return false
            if ((buffer.short.toInt() and 0xffff) != ExpectedContext.CANONICAL_BYTES) return false
            claimedContext = ByteArray(ExpectedContext.CANONICAL_BYTES).also(buffer::get)
            decodedClaim = ExpectedContext.decode(claimedContext!!)
            if (decodedClaim == null) return false
            if ((buffer.get().toInt() and 0xff) != SIDEcar_NONCE_BYTES) return false
            if (buffer.remaining() < SIDEcar_NONCE_BYTES + 2 + SIDEcar_CIPHERTEXT_BYTES) return false
            buffer.position(buffer.position() + SIDEcar_NONCE_BYTES)
            if ((buffer.short.toInt() and 0xffff) != SIDEcar_CIPHERTEXT_BYTES) return false
            if (buffer.remaining() != SIDEcar_CIPHERTEXT_BYTES) return false
            !claimedContext!!.contentEquals(expectedBytes)
        } catch (_: RuntimeException) {
            false
        } finally {
            expectedBytes.fill(0)
            claimedContext?.fill(0)
            decodedClaim?.wipeRunId()
        }
    }

    private companion object {
        private const val SIDEcar_MAGIC = 0x52565031 // ASCII RVP1
        private const val SIDEcar_VERSION = 1
        private const val SIDEcar_NONCE_BYTES = 12
        private const val SIDEcar_CIPHERTEXT_BYTES = 48
    }
}
