package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * M2-P05 (architecture.md section 11) naming/tag contract for account-scoped
 * WorkManager chains. Pure JVM tests, no WorkManager on the classpath yet.
 *
 * Coverage:
 *  - exhaustive deterministic shape of every name and tag for both factories;
 *  - A/B non-collision across both unique names, both account tags, and both
 *    capsule tags, including the capsule tag that two accounts share when
 *    they happen to refer to the same capsule UUID;
 *  - malformed [UserId] / [CapsuleId] inputs fail fast at the typed wrapper
 *    boundary, never reaching a string composition site.
 */
class AccountWorkIdentityTest {

    @Test
    fun incomingSyncShapeIsExactlyTheCanonicalNameAndTagSet() {
        val identity = AccountWorkIdentity.incomingSync(USER_A)

        assertEquals(
            "remanence.account.${USER_A.value}.incoming-sync",
            identity.uniqueName,
        )
        assertEquals(
            listOf(
                "remanence",
                "remanence.account.${USER_A.value}",
            ),
            identity.tags,
        )
        assertEquals(identity.tags[1], identity.accountTag)
    }

    @Test
    fun outboxShapeIsExactlyTheCanonicalNameAndFullTagSet() {
        val identity = AccountWorkIdentity.outbox(USER_A, CAPSULE_A)

        assertEquals(
            "remanence.account.${USER_A.value}.outbox.${CAPSULE_A.value}",
            identity.uniqueName,
        )
        assertEquals(
            listOf(
                "remanence",
                "remanence.account.${USER_A.value}",
                "remanence.capsule.${CAPSULE_A.value}",
            ),
            identity.tags,
        )
        assertEquals(identity.tags[1], identity.accountTag)
    }

    @Test
    fun factoriesAreDeterministicAndDistinctInputsYieldDistinctIdentities() {
        val firstA = AccountWorkIdentity.incomingSync(USER_A)
        val secondA = AccountWorkIdentity.incomingSync(USER_A)
        assertEquals(firstA, secondA)
        assertEquals(firstA.uniqueName, secondA.uniqueName)
        assertEquals(firstA.tags, secondA.tags)

        val firstOutboxA = AccountWorkIdentity.outbox(USER_A, CAPSULE_A)
        val secondOutboxA = AccountWorkIdentity.outbox(USER_A, CAPSULE_A)
        assertEquals(firstOutboxA, secondOutboxA)

        // A different capsule under the same account is a distinct chain.
        val outboxAOtherCapsule = AccountWorkIdentity.outbox(USER_A, CAPSULE_B)
        assertNotEquals(firstOutboxA.uniqueName, outboxAOtherCapsule.uniqueName)
        assertNotEquals(firstOutboxA.tags, outboxAOtherCapsule.tags)
    }

    @Test
    fun aAndBIncomingSyncChainsDoNotCollideOnAnyField() {
        val a = AccountWorkIdentity.incomingSync(USER_A)
        val b = AccountWorkIdentity.incomingSync(USER_B)

        assertNotEquals(a.uniqueName, b.uniqueName)
        assertNotEquals(a.tags, b.tags)
        assertNotEquals(a.accountTag, b.accountTag)

        // The shared `remanence` global tag is the only thing tag sets of two
        // accounts may have in common. Anything else (the account tag) being
        // shared would mean a coarse cancellation over one tag could reach
        // the other account's chains.
        val aTags = a.tags.toSet()
        val bTags = b.tags.toSet()
        val shared = aTags.intersect(bTags)
        assertEquals(
            "incoming-sync tag sets for distinct accounts may only share the global remanence tag " +
                "(A=$aTags B=$bTags shared=$shared)",
            setOf("remanence"),
            shared,
        )
    }

    @Test
    fun aAndBOutboxChainsDoNotCollideEvenWhenTheyTargetTheSameCapsule() {
        val a = AccountWorkIdentity.outbox(USER_A, CAPSULE_A)
        val b = AccountWorkIdentity.outbox(USER_B, CAPSULE_A)

        // A and B may legitimately refer to the same capsule UUID; the
        // account tag is the only thing that keeps their chains separate.
        assertNotEquals(a.uniqueName, b.uniqueName)
        assertNotEquals(a.accountTag, b.accountTag)
        assertEquals(a.tags[2], b.tags[2]) // shared capsule tag is the point

        val aTags = a.tags.toSet()
        val bTags = b.tags.toSet()
        val shared = aTags.intersect(bTags)
        // The remanence global tag and the shared capsule tag may overlap;
        // the account tag must not.
        assertTrue(
            "only the global and capsule tags may be shared between accounts (got $shared)",
            shared.subtract(setOf("remanence", "remanence.capsule.${CAPSULE_A.value}")).isEmpty(),
        )
        assertTrue(
            "account tag must never be shared between A and B",
            "remanence.account.${USER_A.value}" !in bTags &&
                "remanence.account.${USER_B.value}" !in aTags,
        )
    }

    @Test
    fun outboxChainsForDifferentAccountsButDifferentCapsulesDoNotCollide() {
        val a = AccountWorkIdentity.outbox(USER_A, CAPSULE_A)
        val b = AccountWorkIdentity.outbox(USER_B, CAPSULE_B)

        assertNotEquals(a.uniqueName, b.uniqueName)
        assertNotEquals(a.tags, b.tags)
        val aTags = a.tags.toSet()
        val bTags = b.tags.toSet()
        val shared = aTags.intersect(bTags)
        assertEquals(
            "outbox tag sets for fully disjoint (account, capsule) pairs may only share the global tag " +
                "(A=$aTags B=$bTags shared=$shared)",
            setOf("remanence"),
            shared,
        )
    }

    @Test
    fun factoryRejectsMalformedUserAndCapsuleStringsAtTheTypedBoundary() {
        val malformedStrings = listOf(
            "not-a-uuid",
            "00112233445566778899AABBCCDDEEFF", // no dashes
            "00112233-4455-6677-8899-aabbccddeegg", // non-hex
            "00112233-4455-6677-8899-aabbccddeef", // truncated
            "00112233-4455-6677-8899-aabbccddeefff", // too long
            "",
            "00112233-4455-6677-8899-AABBCCDDEEFF", // upper-case hex
        )
        // A whitespace-padded canonical form: the wrapper rejects the input,
        // and the resulting error must not echo the surrounding whitespace
        // or the exact (still recognisable) substring back to a log.
        val whitespacePadded = " ${CANONICAL_UUID} "

        for (raw in malformedStrings) {
            assertMalformed("user", raw) { UserId.parseRest(raw) }
            assertMalformed("capsule", raw) { CapsuleId.parseRest(raw) }
        }
        // The padded sample uses a recognisable inner UUID, so the no-echo
        // check is meaningful: the message must not contain the whole padded
        // form (the entire input string).
        assertMalformed("user", whitespacePadded) { UserId.parseRest(whitespacePadded) }
        assertMalformed("capsule", whitespacePadded) { CapsuleId.parseRest(whitespacePadded) }
    }

    @Test
    fun accountAndCapsuleTagHelpersMatchTheFactoryOutput() {
        assertEquals(
            "remanence.account.${USER_A.value}",
            AccountWorkIdentity.accountTag(USER_A),
        )
        assertEquals(
            "remanence.capsule.${CAPSULE_A.value}",
            AccountWorkIdentity.capsuleTag(CAPSULE_A),
        )
    }

    @Test
    fun uniqueNamesNeverEmbedHandleEmailOrPathSegments() {
        val names = listOf(
            AccountWorkIdentity.incomingSync(USER_A).uniqueName,
            AccountWorkIdentity.outbox(USER_A, CAPSULE_A).uniqueName,
            AccountWorkIdentity.incomingSync(USER_B).uniqueName,
            AccountWorkIdentity.outbox(USER_B, CAPSULE_B).uniqueName,
        )
        val allTags = listOf(
            AccountWorkIdentity.incomingSync(USER_A).tags,
            AccountWorkIdentity.outbox(USER_A, CAPSULE_A).tags,
            AccountWorkIdentity.incomingSync(USER_B).tags,
            AccountWorkIdentity.outbox(USER_B, CAPSULE_B).tags,
        ).flatten()

        for (forbidden in FORBIDDEN_NAME_FRAGMENTS) {
            for (name in names) {
                assertTrue(
                    "unique name '$name' must not embed '$forbidden'",
                    !name.contains(forbidden, ignoreCase = false),
                )
            }
            for (tag in allTags) {
                assertTrue(
                    "tag '$tag' must not embed '$forbidden'",
                    !tag.contains(forbidden, ignoreCase = false),
                )
            }
        }
    }

    private fun assertMalformed(kind: String, sample: String, block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException for malformed $kind '$sample'")
        } catch (expected: IllegalArgumentException) {
            if (sample.isNotEmpty()) {
                assertTrue(
                    "malformed $kind '$sample' error must not echo the input (got '${expected.message}')",
                    expected.message?.contains(sample) != true,
                )
            }
        }
    }

    private companion object {
        val USER_A: UserId = UserId(UUID.fromString("11111111-1111-4111-8111-111111111111"))
        val USER_B: UserId = UserId(UUID.fromString("22222222-2222-4222-8222-222222222222"))
        val CAPSULE_A: CapsuleId = CapsuleId(UUID.fromString("33333333-3333-4333-8333-333333333333"))
        val CAPSULE_B: CapsuleId = CapsuleId(UUID.fromString("44444444-4444-4444-8444-444444444444"))
        const val CANONICAL_UUID: String = "00112233-4455-6677-8899-aabbccddeeff"

        // A handle, an email, an absolute path, and a relative path segment
        // that the architecture (section 11) forbids inside any work name or
        // tag. Any appearance of one of these would mean we leaked PII or a
        // filesystem location into a work identifier.
        val FORBIDDEN_NAME_FRAGMENTS: List<String> = listOf(
            "@",
            "mykola",
            "private@example.com",
            "/storage/emulated/0/",
            "files/",
            "blobs/",
        )
    }
}
