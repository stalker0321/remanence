package dev.hryshyn.rv01probe.probe

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeRunnerTest {
    @Test
    fun `fake backend deterministically exercises local tuples and exact retrieval tamper`() {
        val material = ProbeRunMaterial.generate(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7) })
        val backend = FakeCandidateBackend(material.unwrapMaterial)
        val recordingAdapter = RecordingAdapter(BlockStoreExperimentalAdapter(backend))
        val result = ProbeRunner(recordingAdapter, material).run()

        assertEquals(
            listOf(
                ProbeTuple.P1_CAPABILITY,
                ProbeTuple.P2_WRAP,
                ProbeTuple.P3_LOCAL_UNWRAP,
                ProbeTuple.P7_LOCAL_AUTHENTICATED_PACKAGE_TAMPER,
            ),
            result.records.map(EvidenceRecord::tuple),
        )
        assertEquals(ProbeResult.PASS, result.records[0].result)
        assertEquals(ProbeResult.PASS, result.records[1].result)
        assertEquals(ProbeResult.PASS, result.records[2].result)
        assertEquals(ProbeResult.FAIL_CLOSED, result.records[3].result)
        assertTrue(result.records.all { it.evidenceClass == EvidenceClass.LOCAL_DRY_RUN_NOT_EVIDENCE })
        assertEquals(2, recordingAdapter.unwrapInputs.size)
        assertEquals(recordingAdapter.unwrapInputs[0].size, recordingAdapter.unwrapInputs[1].size)
        val changedOffsets = recordingAdapter.unwrapInputs[0].indices.filter { index ->
            recordingAdapter.unwrapInputs[0][index] != recordingAdapter.unwrapInputs[1][index]
        }
        assertEquals(listOf(recordingAdapter.unwrapInputs[0].lastIndex), changedOffsets)
        assertEquals(
            recordingAdapter.unwrapInputs[0].last().toInt() xor 1,
            recordingAdapter.unwrapInputs[1].last().toInt(),
        )
        assertTrue(material.canary.all { it == 0.toByte() })
        assertTrue(material.unwrapMaterial.all { it == 0.toByte() })
    }

    @Test
    fun `provider adapters remain unavailable without an injected capability`() {
        val blockStore = ProbeRunner(
            BlockStoreExperimentalAdapter(),
            ProbeRunMaterial.generate(),
        ).run()
        val prf = ProbeRunner(
            PrfExperimentalAdapter(),
            ProbeRunMaterial.generate(),
        ).run()

        assertEquals(listOf(ProbeResult.UNAVAILABLE), blockStore.records.map(EvidenceRecord::result))
        assertEquals(listOf(ProbeResult.UNAVAILABLE), prf.records.map(EvidenceRecord::result))
        assertTrue(blockStore.evidenceJson.contains("LOCAL_DRY_RUN_NOT_EVIDENCE"))
        assertTrue(prf.evidenceJson.contains("LOCAL_DRY_RUN_NOT_EVIDENCE"))
    }

    @Test
    fun `tamper accepting harness is reported as unexpected success never pass`() {
        val material = ProbeRunMaterial.generate()
        val result = ProbeRunner(TamperAcceptingHarness(), material).run()

        val p7 = result.records.single { it.tuple == ProbeTuple.P7_LOCAL_AUTHENTICATED_PACKAGE_TAMPER }
        assertEquals(ProbeResult.UNEXPECTED_SUCCESS, p7.result)
        assertTrue(p7.result != ProbeResult.PASS)
    }

    private class RecordingAdapter(
        private val delegate: CandidateAdapter,
    ) : CandidateAdapter {
        override val family: CandidateFamily
            get() = delegate.family

        val unwrapInputs = mutableListOf<ByteArray>()

        override fun detect(): CapabilityDetection = delegate.detect()

        override fun wrap(canary: ByteArray, context: TestPackageContext): WrapOutcome =
            delegate.wrap(canary, context)

        override fun retrieve(): RetrieveOutcome = delegate.retrieve()

        override fun unwrap(value: RetrievedOpaquePackage, expectedContext: TestPackageContext): UnwrapOutcome {
            unwrapInputs += value.bytes.copyOf()
            return delegate.unwrap(value, expectedContext)
        }
    }

    /** Deliberately accepts every opaque input so the runner's negative mapping is exercised. */
    private class TamperAcceptingHarness : CandidateAdapter {
        override val family: CandidateFamily = CandidateFamily.BLOCK_STORE

        private var canary: ByteArray? = null
        private var retrieved: RetrievedOpaquePackage? = null

        override fun detect(): CapabilityDetection = CapabilityDetection(
            status = CapabilityStatus.AVAILABLE,
            placement = CapabilityPlacement.DEVICE_BOUND,
            backupEligibility = BackupEligibility.NOT_APPLICABLE,
            screenLock = ScreenLockState.NOT_APPLICABLE,
            e2ee = E2eeState.NOT_APPLICABLE,
            restorePath = RestorePath.LOCAL_DRY_RUN,
        )

        override fun wrap(canary: ByteArray, context: TestPackageContext): WrapOutcome {
            this.canary = canary.copyOf()
            return WrapOutcome.Stored(
                RetrievedOpaquePackage(
                    bytes = byteArrayOf(1),
                    version = context.version,
                    context = context.purpose,
                ).also { retrieved = it },
            )
        }

        override fun retrieve(): RetrieveOutcome =
            RetrieveOutcome.Retrieved(retrieved!!.copy(bytes = retrieved!!.bytes.copyOf()))

        override fun unwrap(value: RetrievedOpaquePackage, expectedContext: TestPackageContext): UnwrapOutcome =
            UnwrapOutcome.Success(canary!!.copyOf())
    }
}
