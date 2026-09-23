package dev.hryshyn.rv01probe.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G1 focused P1 input tests: physical secure/insecure/unknown plus
 * operator-unconfirmed/confirmed gates. No Android framework, no provider,
 * no cloud PASS; evidence class stays LOCAL_DRY_RUN_NOT_EVIDENCE.
 */
class P1OperatorConfirmationTest {

    @Test
    fun `physical secure alone never qualifies`() {
        assertEquals(
            BlockStoreLockState.UNKNOWN,
            classifyPhysicalLockState(keyguardPresent = true, isDeviceSecure = true),
        )
    }

    @Test
    fun `physical insecure and absent keyguard stay non-qualified`() {
        assertEquals(
            BlockStoreLockState.INSECURE,
            classifyPhysicalLockState(keyguardPresent = true, isDeviceSecure = false),
        )
        assertEquals(
            BlockStoreLockState.UNKNOWN,
            classifyPhysicalLockState(keyguardPresent = false, isDeviceSecure = true),
        )
        assertEquals(
            BlockStoreLockState.UNKNOWN,
            classifyPhysicalLockState(keyguardPresent = false, isDeviceSecure = false),
        )
    }

    @Test
    fun `insecure device reports absent regardless of confirmation`() {
        listOf(
            OperatorConfirmedLockKind.UNCONFIRMED,
            OperatorConfirmedLockKind.PIN,
            OperatorConfirmedLockKind.PATTERN,
            OperatorConfirmedLockKind.PASSWORD,
        ).forEach { kind ->
            assertEquals(
                ScreenLockState.ABSENT,
                resolveConfirmedScreenLock(isDeviceSecure = false, confirmed = kind),
            )
        }
    }

    @Test
    fun `secure unconfirmed stays unknown`() {
        assertEquals(
            ScreenLockState.UNKNOWN,
            resolveConfirmedScreenLock(
                isDeviceSecure = true,
                confirmed = OperatorConfirmedLockKind.UNCONFIRMED,
            ),
        )
    }

    @Test
    fun `secure confirmed exposes only pin pattern password`() {
        assertEquals(
            ScreenLockState.PIN,
            resolveConfirmedScreenLock(true, OperatorConfirmedLockKind.PIN),
        )
        assertEquals(
            ScreenLockState.PATTERN,
            resolveConfirmedScreenLock(true, OperatorConfirmedLockKind.PATTERN),
        )
        assertEquals(
            ScreenLockState.PASSWORD,
            resolveConfirmedScreenLock(true, OperatorConfirmedLockKind.PASSWORD),
        )
    }

    @Test
    fun `backup eligible requires eligible plus backup now`() {
        assertEquals(
            BackupEligibility.UNKNOWN,
            resolveConfirmedBackupEligibility(BackupEligibility.UNKNOWN, backupNowCompleted = true),
        )
        assertEquals(
            BackupEligibility.UNKNOWN,
            resolveConfirmedBackupEligibility(BackupEligibility.UNKNOWN, backupNowCompleted = false),
        )
        assertEquals(
            BackupEligibility.UNKNOWN,
            resolveConfirmedBackupEligibility(BackupEligibility.ELIGIBLE, backupNowCompleted = false),
        )
        assertEquals(
            BackupEligibility.ELIGIBLE,
            resolveConfirmedBackupEligibility(BackupEligibility.ELIGIBLE, backupNowCompleted = true),
        )
        assertEquals(
            BackupEligibility.INELIGIBLE,
            resolveConfirmedBackupEligibility(BackupEligibility.INELIGIBLE, backupNowCompleted = false),
        )
        assertEquals(
            BackupEligibility.INELIGIBLE,
            resolveConfirmedBackupEligibility(BackupEligibility.INELIGIBLE, backupNowCompleted = true),
        )
    }

    @Test
    fun `confirmation holder rejects guesses and keeps unknown by default`() {
        val inputs = OperatorConfirmedP1Inputs()
        assertEquals(OperatorP1Confirmation(), inputs.snapshot())

        assertFalse(inputs.confirmLockKind(OperatorConfirmedLockKind.UNCONFIRMED))
        assertEquals(OperatorConfirmedLockKind.UNCONFIRMED, inputs.snapshot().lockKind)
        assertFalse(inputs.confirmBackupEligibility(BackupEligibility.UNKNOWN))
        assertFalse(inputs.confirmBackupEligibility(BackupEligibility.UNSUPPORTED))
        assertFalse(inputs.confirmBackupEligibility(BackupEligibility.NOT_APPLICABLE))
        assertEquals(BackupEligibility.UNKNOWN, inputs.snapshot().backupEligibility)

        assertTrue(inputs.confirmLockKind(OperatorConfirmedLockKind.PIN))
        assertTrue(inputs.confirmBackupEligibility(BackupEligibility.ELIGIBLE))
        inputs.confirmBackupNowCompleted()
        assertEquals(
            OperatorP1Confirmation(
                lockKind = OperatorConfirmedLockKind.PIN,
                backupEligibility = BackupEligibility.ELIGIBLE,
                backupNowCompleted = true,
            ),
            inputs.snapshot(),
        )

        inputs.clearAll()
        assertEquals(OperatorP1Confirmation(), inputs.snapshot())
    }

    @Test
    fun `gated qualifying state needs secure plus confirmed kind`() {
        assertEquals(
            BlockStoreLockState.UNKNOWN,
            resolveGatedQualifyingState(true, true, OperatorConfirmedLockKind.UNCONFIRMED),
        )
        assertEquals(
            BlockStoreLockState.QUALIFIED,
            resolveGatedQualifyingState(true, true, OperatorConfirmedLockKind.PIN),
        )
        assertEquals(
            BlockStoreLockState.QUALIFIED,
            resolveGatedQualifyingState(true, true, OperatorConfirmedLockKind.PATTERN),
        )
        assertEquals(
            BlockStoreLockState.QUALIFIED,
            resolveGatedQualifyingState(true, true, OperatorConfirmedLockKind.PASSWORD),
        )
        assertEquals(
            BlockStoreLockState.INSECURE,
            resolveGatedQualifyingState(true, false, OperatorConfirmedLockKind.PIN),
        )
        assertEquals(
            BlockStoreLockState.UNKNOWN,
            resolveGatedQualifyingState(false, true, OperatorConfirmedLockKind.PIN),
        )
        assertEquals(
            BlockStoreLockState.UNKNOWN,
            resolveGatedQualifyingState(true, null, OperatorConfirmedLockKind.PIN),
        )
    }

    @Test
    fun `cloud eligible only when lock backup and e2ee all qualify`() {
        fun eligibility(
            screenLock: ScreenLockState,
            backup: BackupEligibility,
        ) = ProbeEligibility(
            capability = CapabilityStatus.AVAILABLE,
            placement = CapabilityPlacement.SYNCED_PROVIDER,
            backupEligibility = backup,
            screenLock = screenLock,
            e2ee = E2eeState.AVAILABLE,
            restorePath = RestorePath.BLOCK_STORE_CLOUD,
        )

        assertTrue(eligibility(ScreenLockState.PIN, BackupEligibility.ELIGIBLE).cloudEligible)
        assertTrue(eligibility(ScreenLockState.PATTERN, BackupEligibility.ELIGIBLE).cloudEligible)
        assertTrue(eligibility(ScreenLockState.PASSWORD, BackupEligibility.ELIGIBLE).cloudEligible)

        // Unconfirmed gates stay non-eligible: no guessing.
        assertFalse(eligibility(ScreenLockState.UNKNOWN, BackupEligibility.ELIGIBLE).cloudEligible)
        assertFalse(eligibility(ScreenLockState.PIN, BackupEligibility.UNKNOWN).cloudEligible)
        assertFalse(eligibility(ScreenLockState.UNKNOWN, BackupEligibility.UNKNOWN).cloudEligible)
        assertFalse(eligibility(ScreenLockState.ABSENT, BackupEligibility.UNKNOWN).cloudEligible)
        assertFalse(eligibility(ScreenLockState.PIN, BackupEligibility.INELIGIBLE).cloudEligible)
    }

    @Test
    fun `evidence class stays local dry run`() {
        assertEquals(
            EvidenceClass.LOCAL_DRY_RUN_NOT_EVIDENCE,
            EvidenceRecord(
                tuple = ProbeTuple.P1_CAPABILITY,
                candidate = CandidateFamily.BLOCK_STORE,
                result = ProbeResult.PASS,
                capability = CapabilityStatus.AVAILABLE,
                placement = CapabilityPlacement.SYNCED_PROVIDER,
                backupEligibility = BackupEligibility.ELIGIBLE,
                screenLock = ScreenLockState.PIN,
                e2ee = E2eeState.AVAILABLE,
                restorePath = RestorePath.BLOCK_STORE_CLOUD,
            ).evidenceClass,
        )
    }
}
