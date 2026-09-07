package dev.hryshyn.rv01probe.probe

enum class CandidateFamily {
    BLOCK_STORE,
    PRF,
}

enum class CapabilityStatus {
    AVAILABLE,
    UNAVAILABLE,
    INDETERMINATE,
}

enum class CapabilityPlacement {
    SYNCED_PROVIDER,
    D2D_TRANSFERRED,
    DEVICE_BOUND,
    UNKNOWN,
    NOT_APPLICABLE,
}

enum class BackupEligibility {
    ELIGIBLE,
    INELIGIBLE,
    UNSUPPORTED,
    UNKNOWN,
    NOT_APPLICABLE,
}

enum class ScreenLockState {
    PIN,
    PATTERN,
    PASSWORD,
    BIOMETRIC_ONLY,
    ABSENT,
    UNKNOWN,
    NOT_APPLICABLE,
}

enum class E2eeState {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
    NOT_APPLICABLE,
}

enum class RestorePath {
    BLOCK_STORE_CLOUD,
    PRF_PROVIDER_SYNC_CLOUD,
    D2D,
    LOCAL_DRY_RUN,
    NOT_APPLICABLE,
}

enum class EvidenceClass {
    LOCAL_DRY_RUN_NOT_EVIDENCE,
}

enum class ProbeTuple {
    P1_CAPABILITY,
    P2_WRAP,
    P3_LOCAL_UNWRAP,
    P4_FRESH_INSTALL,
    P5_BS_CLOUD,
    P5_PRF_CLOUD,
    P5_D2D,
    P6_ACCOUNT_A_TO_B,
    P7_LOCAL_AUTHENTICATED_PACKAGE_TAMPER,
    P8_BS_CLOUD,
    P8_PRF_CLOUD,
}

enum class ProbeResult {
    PASS,
    FAIL_CLOSED,
    UNEXPECTED_SUCCESS,
    UNAVAILABLE,
    INDETERMINATE,
    NOT_CLAIMED,
    NOT_APPLICABLE,
}

data class TestPackageContext(
    val version: Int = 1,
    val purpose: String = "rv01-canary",
)

data class CapabilityDetection(
    val status: CapabilityStatus,
    val placement: CapabilityPlacement,
    val backupEligibility: BackupEligibility,
    val screenLock: ScreenLockState,
    val e2ee: E2eeState,
    val restorePath: RestorePath,
)

data class RetrievedOpaquePackage(
    val bytes: ByteArray,
    val version: Int,
    val context: String,
) {
    fun withBytes(replacement: ByteArray): RetrievedOpaquePackage = copy(bytes = replacement)
}

sealed interface WrapOutcome {
    data class Stored(val value: RetrievedOpaquePackage) : WrapOutcome

    data object Unavailable : WrapOutcome

    data object Failed : WrapOutcome
}

sealed interface RetrieveOutcome {
    data class Retrieved(val value: RetrievedOpaquePackage) : RetrieveOutcome

    data object Unavailable : RetrieveOutcome

    data object Failed : RetrieveOutcome
}

sealed interface UnwrapOutcome {
    data class Success(val plaintext: ByteArray) : UnwrapOutcome

    data object Rejected : UnwrapOutcome

    data object Unavailable : UnwrapOutcome

    data object Failed : UnwrapOutcome
}

data class EvidenceRecord(
    val tuple: ProbeTuple,
    val candidate: CandidateFamily,
    val result: ProbeResult,
    val capability: CapabilityStatus,
    val placement: CapabilityPlacement,
    val backupEligibility: BackupEligibility,
    val screenLock: ScreenLockState,
    val e2ee: E2eeState,
    val restorePath: RestorePath,
    val evidenceClass: EvidenceClass = EvidenceClass.LOCAL_DRY_RUN_NOT_EVIDENCE,
)
