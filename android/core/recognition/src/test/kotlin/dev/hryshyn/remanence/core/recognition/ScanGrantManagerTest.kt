package dev.hryshyn.remanence.core.recognition

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fake-clock proof for M1-M10 scan grants. */
class ScanGrantManagerTest {

    private var nowMillis = 1_000_000L
    private val clock: () -> Long = { nowMillis }

    private fun manager(lifetime: Long = ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS) =
        ScanGrantManager(clock, grantLifetimeMillis = lifetime)

    @Test
    fun issuedGrantResolvesToItsCapsuleInsideTheWindow() {
        val grants = manager()
        val capsuleId = UUID.randomUUID()
        val grant = grants.issue(capsuleId)

        assertNotEquals(capsuleId, grant.grantId)
        assertEquals(
            ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS,
            grant.expiresAtEpochMillis - grant.issuedAtEpochMillis,
        )
        assertEquals(capsuleId, grants.resolveCapsuleId(grant.grantId))
    }

    @Test
    fun grantExpiresExactlyAtTheDeadline() {
        val grants = manager()
        val grant = grants.issue(UUID.randomUUID())

        nowMillis += ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS - 1
        assertTrue(grants.resolveCapsuleId(grant.grantId) != null, "valid one ms before expiry")

        nowMillis += 1 // exactly at expiresAtEpochMillis
        assertNull(grants.resolveCapsuleId(grant.grantId))
        // The expired grant is invalidated, not merely unreadable.
        assertNull(grants.resolveCapsuleId(grant.grantId))
    }

    @Test
    fun consumeInvalidatesAndReportsUnknownGrantsHonestly() {
        val grants = manager()
        val grant = grants.issue(UUID.randomUUID())
        assertTrue(grants.consume(grant.grantId))

        assertNull(grants.resolveCapsuleId(grant.grantId))
        assertFalse(grants.consume(grant.grantId), "double consume must fail")
        assertFalse(grants.consume(UUID.randomUUID()), "unknown ids must not consume anything")
    }

    @Test
    fun issuingANewGrantInvalidatesThePreviousOne() {
        val grants = manager()
        val first = grants.issue(UUID.randomUUID())
        val second = grants.issue(UUID.randomUUID())

        assertNotEquals(first.grantId, second.grantId)
        assertNull(grants.resolveCapsuleId(first.grantId))
        assertEquals(second.capsuleId, grants.resolveCapsuleId(second.grantId))
    }

    @Test
    fun clearAllWipesEverythingImmediately() {
        val grants = manager()
        val grant = grants.issue(UUID.randomUUID())

        grants.clearAll()

        assertNull(grants.resolveCapsuleId(grant.grantId))
        assertFalse(grants.consume(grant.grantId))
    }

    @Test
    fun customLifetimeIsHonoredFromTheSingleConfigurationSite() {
        val short = ScanGrantManager(clock, grantLifetimeMillis = 5_000)
        val grant = short.issue(UUID.randomUUID())

        assertEquals(5_000, grant.expiresAtEpochMillis - grant.issuedAtEpochMillis)

        nowMillis += 4_999
        assertTrue(short.resolveCapsuleId(grant.grantId) != null)
        nowMillis += 2
        assertNull(short.resolveCapsuleId(grant.grantId))
    }

    @Test
    fun nonPositiveLifetimeIsRejected() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ScanGrantManager(clock, grantLifetimeMillis = 0)
        }
    }

    @Test
    fun expiresAtMillisReportsTheExactDeadlineOfTheLiveGrantOnly() {
        val grants = manager()
        val grant = grants.issue(UUID.randomUUID())

        assertEquals(grant.expiresAtEpochMillis, grants.expiresAtMillis(grant.grantId))

        // A foreign ID never reads another grant's deadline.
        assertNull(grants.expiresAtMillis(UUID.randomUUID()))

        // After consumption there is no deadline left to read.
        assertTrue(grants.consume(grant.grantId))
        assertNull(grants.expiresAtMillis(grant.grantId))
    }

    @Test
    fun expiresAtMillisInvalidatesAnAlreadyExpiredGrant() {
        val grants = manager()
        val grant = grants.issue(UUID.randomUUID())

        nowMillis += ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS + 1

        assertNull(grants.expiresAtMillis(grant.grantId))
        assertNull(grants.resolveCapsuleId(grant.grantId))
    }
}
