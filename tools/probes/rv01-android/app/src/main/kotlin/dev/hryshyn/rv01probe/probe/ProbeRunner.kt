package dev.hryshyn.rv01probe.probe

import java.security.SecureRandom

class ProbeRunMaterial private constructor(
    val canary: ByteArray,
    val unwrapMaterial: ByteArray,
) : AutoCloseable {
    companion object {
        private const val MATERIAL_BYTES = 32

        fun generate(random: SecureRandom = SecureRandom()): ProbeRunMaterial =
            ProbeRunMaterial(
                canary = ByteArray(MATERIAL_BYTES).also(random::nextBytes),
                unwrapMaterial = ByteArray(MATERIAL_BYTES).also(random::nextBytes),
            )
    }

    override fun close() {
        canary.fill(0)
        unwrapMaterial.fill(0)
    }
}

data class LocalDryRunResult(
    val records: List<EvidenceRecord>,
) {
    val evidenceJson: String
        get() = EvidenceJson.encode(records)
}

class ProbeRunner(
    private val adapter: CandidateAdapter,
    private val material: ProbeRunMaterial,
    private val context: TestPackageContext = TestPackageContext(),
) {
    fun run(): LocalDryRunResult {
        val records = mutableListOf<EvidenceRecord>()
        val detection = adapter.detect()
        records += detectionRecord(detection, ProbeTuple.P1_CAPABILITY, when (detection.status) {
            CapabilityStatus.AVAILABLE -> ProbeResult.PASS
            CapabilityStatus.UNAVAILABLE -> ProbeResult.UNAVAILABLE
            CapabilityStatus.INDETERMINATE -> ProbeResult.INDETERMINATE
        })
        if (detection.status != CapabilityStatus.AVAILABLE) {
            material.close()
            return LocalDryRunResult(records)
        }

        try {
            when (adapter.wrap(material.canary, context)) {
                is WrapOutcome.Stored -> {
                    records += detectionRecord(detection, ProbeTuple.P2_WRAP, ProbeResult.PASS)
                    runUnwrapChecks(detection, records)
                }

                WrapOutcome.Unavailable -> records += detectionRecord(
                    detection,
                    ProbeTuple.P2_WRAP,
                    ProbeResult.UNAVAILABLE,
                )

                WrapOutcome.Failed -> records += detectionRecord(
                    detection,
                    ProbeTuple.P2_WRAP,
                    ProbeResult.FAIL_CLOSED,
                )
            }
        } finally {
            material.close()
        }
        return LocalDryRunResult(records)
    }

    private fun runUnwrapChecks(
        detection: CapabilityDetection,
        records: MutableList<EvidenceRecord>,
    ) {
        when (val retrieved = adapter.retrieve()) {
            is RetrieveOutcome.Retrieved -> {
                val p3 = adapter.unwrap(retrieved.value, context)
                val p3Result = when (p3) {
                    is UnwrapOutcome.Success -> {
                        val matches = p3.plaintext.contentEquals(material.canary)
                        p3.plaintext.fill(0)
                        if (matches) ProbeResult.PASS else ProbeResult.FAIL_CLOSED
                    }

                    UnwrapOutcome.Rejected -> ProbeResult.FAIL_CLOSED
                    UnwrapOutcome.Unavailable -> ProbeResult.UNAVAILABLE
                    UnwrapOutcome.Failed -> ProbeResult.FAIL_CLOSED
                }
                records += detectionRecord(detection, ProbeTuple.P3_LOCAL_UNWRAP, p3Result)

                val tamperedBytes = retrieved.value.bytes.copyOf()
                if (tamperedBytes.isNotEmpty()) {
                    tamperedBytes[tamperedBytes.lastIndex] =
                        (tamperedBytes[tamperedBytes.lastIndex].toInt() xor 1).toByte()
                }
                val p7 = adapter.unwrap(retrieved.value.withBytes(tamperedBytes), context)
                if (p7 is UnwrapOutcome.Success) p7.plaintext.fill(0)
                val p7Result = when (p7) {
                    UnwrapOutcome.Rejected, UnwrapOutcome.Failed -> ProbeResult.FAIL_CLOSED

                    UnwrapOutcome.Unavailable -> ProbeResult.UNAVAILABLE
                    is UnwrapOutcome.Success -> ProbeResult.UNEXPECTED_SUCCESS
                }
                records += detectionRecord(detection, ProbeTuple.P7_LOCAL_AUTHENTICATED_PACKAGE_TAMPER, p7Result)
                tamperedBytes.fill(0)
            }

            RetrieveOutcome.Unavailable -> records += detectionRecord(
                detection,
                ProbeTuple.P3_LOCAL_UNWRAP,
                ProbeResult.UNAVAILABLE,
            )

            RetrieveOutcome.Failed -> records += detectionRecord(
                detection,
                ProbeTuple.P3_LOCAL_UNWRAP,
                ProbeResult.FAIL_CLOSED,
            )
        }

    }

    private fun detectionRecord(
        detection: CapabilityDetection,
        tuple: ProbeTuple,
        result: ProbeResult,
    ): EvidenceRecord = EvidenceRecord(
        tuple = tuple,
        candidate = adapter.family,
        result = result,
        capability = detection.status,
        placement = detection.placement,
        backupEligibility = detection.backupEligibility,
        screenLock = detection.screenLock,
        e2ee = detection.e2ee,
        restorePath = detection.restorePath,
    )
}

object LocalDryRunFactory {
    fun runBlockStoreDryRun(): LocalDryRunResult {
        val material = ProbeRunMaterial.generate()
        val backend = FakeCandidateBackend(material.unwrapMaterial)
        return ProbeRunner(BlockStoreExperimentalAdapter(backend), material).run()
    }
}
