package dev.hryshyn.rv01probe.probe

/**
 * G1 bounded P1 input: operator-confirmed device qualifiers.
 *
 * Android cannot truthfully distinguish PIN vs pattern vs password from
 * [android.app.KeyguardManager.isDeviceSecure] alone, and E2EE/backup
 * eligibility cannot be inferred from lock presence. Every value here stays
 * UNKNOWN unless the operator explicitly confirms it on the physical device.
 * Evidence class is always [EvidenceClass.LOCAL_DRY_RUN_NOT_EVIDENCE]; this
 * file never claims a cloud PASS and never touches D2/K_U/resume flow.
 */
enum class OperatorConfirmedLockKind {
    UNCONFIRMED,
    PIN,
    PATTERN,
    PASSWORD,
}

/** Explicit operator snapshot; defaults are all unconfirmed/unknown. */
data class OperatorP1Confirmation(
    val lockKind: OperatorConfirmedLockKind = OperatorConfirmedLockKind.UNCONFIRMED,
    val backupEligibility: BackupEligibility = BackupEligibility.UNKNOWN,
    val backupNowCompleted: Boolean = false,
)

/**
 * Mutable holder for operator confirmations. Only PIN/PATTERN/PASSWORD lock
 * kinds and ELIGIBLE/INELIGIBLE backup states are accepted; everything else
 * is rejected and the holder stays UNKNOWN. Thread-safe for UI + probe use.
 */
class OperatorConfirmedP1Inputs {
    @Volatile private var lockKind: OperatorConfirmedLockKind =
        OperatorConfirmedLockKind.UNCONFIRMED
    @Volatile private var backupEligibility: BackupEligibility = BackupEligibility.UNKNOWN
    @Volatile private var backupNowCompleted: Boolean = false

    /** Accepts only PIN/PATTERN/PASSWORD; returns false and keeps UNCONFIRMED otherwise. */
    fun confirmLockKind(kind: OperatorConfirmedLockKind): Boolean = when (kind) {
        OperatorConfirmedLockKind.PIN,
        OperatorConfirmedLockKind.PATTERN,
        OperatorConfirmedLockKind.PASSWORD,
        -> {
            lockKind = kind
            true
        }
        OperatorConfirmedLockKind.UNCONFIRMED -> false
    }

    fun clearLockKind() {
        lockKind = OperatorConfirmedLockKind.UNCONFIRMED
    }

    /** Accepts only ELIGIBLE/INELIGIBLE; returns false and keeps UNKNOWN otherwise. */
    fun confirmBackupEligibility(value: BackupEligibility): Boolean = when (value) {
        BackupEligibility.ELIGIBLE,
        BackupEligibility.INELIGIBLE,
        -> {
            backupEligibility = value
            true
        }
        BackupEligibility.UNSUPPORTED,
        BackupEligibility.UNKNOWN,
        BackupEligibility.NOT_APPLICABLE,
        -> false
    }

    fun clearBackupEligibility() {
        backupEligibility = BackupEligibility.UNKNOWN
    }

    fun confirmBackupNowCompleted() {
        backupNowCompleted = true
    }

    fun clearBackupNowCompleted() {
        backupNowCompleted = false
    }

    fun clearAll() {
        lockKind = OperatorConfirmedLockKind.UNCONFIRMED
        backupEligibility = BackupEligibility.UNKNOWN
        backupNowCompleted = false
    }

    fun snapshot(): OperatorP1Confirmation = OperatorP1Confirmation(
        lockKind = lockKind,
        backupEligibility = backupEligibility,
        backupNowCompleted = backupNowCompleted,
    )
}

/**
 * Physical-only classification. Secure-lock presence alone never yields
 * QUALIFIED: a secure device without operator-confirmed kind is UNKNOWN.
 */
fun classifyPhysicalLockState(
    keyguardPresent: Boolean,
    isDeviceSecure: Boolean,
): BlockStoreLockState = when {
    !keyguardPresent -> BlockStoreLockState.UNKNOWN
    !isDeviceSecure -> BlockStoreLockState.INSECURE
    else -> BlockStoreLockState.UNKNOWN
}

/**
 * Truthful screen-lock mapping. Returns the confirmed PIN/PATTERN/PASSWORD
 * only when the device reports secure AND the operator confirmed that exact
 * kind. Insecure maps to ABSENT; everything else stays UNKNOWN.
 */
fun resolveConfirmedScreenLock(
    isDeviceSecure: Boolean,
    confirmed: OperatorConfirmedLockKind,
): ScreenLockState {
    if (!isDeviceSecure) return ScreenLockState.ABSENT
    return when (confirmed) {
        OperatorConfirmedLockKind.PIN -> ScreenLockState.PIN
        OperatorConfirmedLockKind.PATTERN -> ScreenLockState.PATTERN
        OperatorConfirmedLockKind.PASSWORD -> ScreenLockState.PASSWORD
        OperatorConfirmedLockKind.UNCONFIRMED -> ScreenLockState.UNKNOWN
    }
}

/**
 * Truthful backup mapping. ELIGIBLE requires both operator-confirmed eligible
 * AND operator-confirmed Backup Now completion; INELIGIBLE passes through
 * when explicitly confirmed; everything else stays UNKNOWN.
 */
fun resolveConfirmedBackupEligibility(
    confirmed: BackupEligibility,
    backupNowCompleted: Boolean,
): BackupEligibility = when {
    confirmed == BackupEligibility.INELIGIBLE -> BackupEligibility.INELIGIBLE
    confirmed == BackupEligibility.ELIGIBLE && backupNowCompleted ->
        BackupEligibility.ELIGIBLE
    else -> BackupEligibility.UNKNOWN
}

/**
 * Gated qualifying state for the P1 eligibility port only. QUALIFIED requires
 * physical secure + operator-confirmed PIN/PATTERN/PASSWORD. INSECURE is
 * physical insecure regardless of confirmation; all else is UNKNOWN.
 */
fun resolveGatedQualifyingState(
    keyguardPresent: Boolean,
    isDeviceSecure: Boolean?,
    confirmed: OperatorConfirmedLockKind,
): BlockStoreLockState {
    if (keyguardPresent && isDeviceSecure == false) return BlockStoreLockState.INSECURE
    if (!keyguardPresent || isDeviceSecure == null) return BlockStoreLockState.UNKNOWN
    if (!isDeviceSecure) return BlockStoreLockState.INSECURE
    return when (confirmed) {
        OperatorConfirmedLockKind.PIN,
        OperatorConfirmedLockKind.PATTERN,
        OperatorConfirmedLockKind.PASSWORD,
        -> BlockStoreLockState.QUALIFIED
        OperatorConfirmedLockKind.UNCONFIRMED -> BlockStoreLockState.UNKNOWN
    }
}

/**
 * Pure P1 detect decision mirroring [AndroidProbeEligibilityPort.detect] pre-E2EE
 * logic. The port calls this with live Keyguard values plus the shared
 * [OperatorConfirmedP1Inputs.snapshot]; unit tests drive the same function
 * through shared-instance mutations, so helper-only coverage cannot drift
 * from app behavior.
 */
data class P1DetectDecision(
    val gatedState: BlockStoreLockState,
    val screenLock: ScreenLockState,
    val backupEligibility: BackupEligibility,
    val qualified: Boolean,
)

fun decideP1Detect(
    keyguardPresent: Boolean,
    isDeviceSecure: Boolean?,
    confirmation: OperatorP1Confirmation,
): P1DetectDecision {
    val gated = resolveGatedQualifyingState(
        keyguardPresent = keyguardPresent,
        isDeviceSecure = isDeviceSecure,
        confirmed = confirmation.lockKind,
    )
    if (gated == BlockStoreLockState.INSECURE) {
        return P1DetectDecision(
            gatedState = gated,
            screenLock = ScreenLockState.ABSENT,
            backupEligibility = BackupEligibility.UNKNOWN,
            qualified = false,
        )
    }
    val secure = keyguardPresent && isDeviceSecure == true
    val screenLock = if (!secure) {
        ScreenLockState.UNKNOWN
    } else {
        resolveConfirmedScreenLock(
            isDeviceSecure = true,
            confirmed = confirmation.lockKind,
        )
    }
    val backup = resolveConfirmedBackupEligibility(
        confirmed = confirmation.backupEligibility,
        backupNowCompleted = confirmation.backupNowCompleted,
    )
    return P1DetectDecision(
        gatedState = gated,
        screenLock = screenLock,
        backupEligibility = backup,
        qualified = gated == BlockStoreLockState.QUALIFIED,
    )
}
