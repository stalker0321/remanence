package dev.hryshyn.remanence.core.recognition

import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FRONT-only integration proof (ADR-012, M2-F0-01): the hierarchy over synthetic
 * FRONT fingerprints — unique strong acceptance issues exactly one grant after
 * crypto verification, verification refusal never issues, and identical FRONT
 * designs fall to the chooser (design->0..N). Old two-sided payloads fail closed
 * at the codec before reaching the engine.
 */
class LocalMatchEngineTest {

    private var verifierResult = true
    private val issuedGrants = mutableListOf<UUID>()

    @BeforeTest
    fun setUp() {
        verifierResult = true
        issuedGrants.clear()
    }

    private fun keypoint(i: Int) = FingerprintKeypoint(
        xNormalized = (i % 8) / 8.0,
        yNormalized = (i / 8) / 8.0,
        scaleNormalized = 1.0,
        angleCentiDegrees = 0,
        responseQuantized = i,
        octave = 0,
    )

    private fun fingerprint(seed: Int, count: Int) = PostcardFingerprint(
        profileId = RecognitionProfile.MVP_ORB_V1_ID,
        canonicalWidthPx = 1600,
        canonicalHeightPx = 1000,
        coarseHash64 = seed.toLong(),
        keypoints = List(count) { keypoint(it) },
        descriptors = List(count) { i -> ByteArray(32) { ((it * 7 + i * 13 + seed * 29) and 0xFF).toByte() } },
        quality = ExtractionQuality(200.0, 90.0, 0.01, 0.85),
    )

    private fun candidate(id: String, preferred: Boolean, seedFront: Int) = IndexedCandidate(
        capsuleId = UUID.nameUUIDFromBytes(id.toByteArray()),
        front = fingerprint(seedFront, 64),
        recipientPreferred = preferred,
    )

    private fun engine(): Pair<LocalMatchEngine, ScanGrantIssuer> {
        val issuer = ScanGrantIssuer { capsuleId ->
            issuedGrants += capsuleId
            "grant-${capsuleId}"
        }
        return LocalMatchEngine(
            profile = RecognitionProfile.mvpOrbV1(),
            verifier = { verifierResult },
            grantIssuer = issuer,
        ) to issuer
    }

    @Test
    fun uniqueStrongRecipientMatchIssuesExactlyOneGrantAfterVerification() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64)
        val recipient = candidate("A", preferred = true, seedFront = 11)
        val universe = listOf(recipient, recipient.copy(recipientPreferred = false))

        val result = engine.run(queryFront, universe)

        val granted = result as ScanFlowResult.Granted
        assertEquals(recipient.capsuleId, granted.capsuleId)
        assertEquals(CandidateOrigin.RECIPIENT_PREFERRED, granted.origin)
        assertTrue(granted.grantId.startsWith("grant-"))
        assertEquals(1, issuedGrants.size)
    }

    @Test
    fun refusedCryptoVerificationNeverIssuesAGrant() = kotlinx.coroutines.runBlocking {
        verifierResult = false
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64)
        val universe = listOf(candidate("A", preferred = true, 11))

        val result = engine.run(queryFront, universe)

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertEquals(0, issuedGrants.size)
    }

    @Test
    fun identicalMassProducedDesignsFallToTheChooserInsteadOfAutoOpen() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64)
        val universe = listOf(
            candidate("dup-1", preferred = true, 11),
            candidate("dup-2", preferred = true, 11),
        )

        val result = engine.run(queryFront, universe)

        val ambiguous = result as ScanFlowResult.Ambiguous
        assertEquals(CandidateOrigin.RECIPIENT_PREFERRED, ambiguous.origin)
        assertEquals(2, ambiguous.rows.size)
        assertFalse(ambiguous.singleRecaptureFirst)
        kotlin.test.assertTrue(issuedGrants.isEmpty(), "no grant may exist for an ambiguous scan")
    }

    @Test
    fun senderFallbackRunsWhenRecipientRowsLackWeakEvidence() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64)
        // Recipient baselines EXIST but each holds so few features that no
        // FRONT can ever clear the weak-evidence gate.
        val starvedFront = fingerprint(11, 3)
        val universe = listOf(
            IndexedCandidate(
                capsuleId = UUID.nameUUIDFromBytes("rec-starved-1".toByteArray()),
                front = starvedFront,
                recipientPreferred = true,
            ),
            IndexedCandidate(
                capsuleId = UUID.nameUUIDFromBytes("rec-starved-2".toByteArray()),
                front = fingerprint(31, 5),
                recipientPreferred = true,
            ),
            candidate("sender-original", preferred = false, seedFront = 11),
        )

        val result = engine.run(queryFront, universe)

        val granted = result as ScanFlowResult.Granted
        assertEquals(UUID.nameUUIDFromBytes("sender-original".toByteArray()), granted.capsuleId)
        assertEquals(CandidateOrigin.SENDER_FALLBACK, granted.origin)
        assertEquals(1, issuedGrants.size)
    }

    @Test
    fun senderFallbackKeepsRecipientAndSenderPairsForTheSameCapsule() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64)
        val capsuleId = UUID.nameUUIDFromBytes("repeat-fallback".toByteArray())

        val result = engine.run(
            queryFront,
            listOf(
                IndexedCandidate(
                    capsuleId = capsuleId,
                    front = fingerprint(11, 3),
                    recipientPreferred = true,
                ),
                IndexedCandidate(
                    capsuleId = capsuleId,
                    front = fingerprint(11, 64),
                    recipientPreferred = false,
                ),
            ),
        )

        val granted = result as ScanFlowResult.Granted
        assertEquals(capsuleId, granted.capsuleId)
        assertEquals(CandidateOrigin.SENDER_FALLBACK, granted.origin)
        assertEquals(1, issuedGrants.size)
    }

    @Test
    fun unknownPostcardIsRecaptureRequiredWithoutAnyGrant() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        // Unknown postcard: candidate has too few features to ever pass weak gate.
        val starved = fingerprint(91, 3)
        val universe = listOf(
            IndexedCandidate(
                capsuleId = UUID.nameUUIDFromBytes("other-card".toByteArray()),
                front = starved,
                recipientPreferred = false,
            ),
        )

        val result = engine.run(fingerprint(55, 64), universe)

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertEquals(0, issuedGrants.size)
    }

    @Test
    fun emptyCandidateIndexIsNoMatchNotAnError() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()

        val result = engine.run(fingerprint(1, 64), emptyList())

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertEquals(0, issuedGrants.size)
    }

    @Test
    fun designToManyRequiresExplicitChoiceNeverAutoOpens() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        // Same printed FRONT design mapped to two capsules (design->N).
        val queryFront = fingerprint(11, 64)
        // other-design is starved so it cannot pass weak gate and is excluded.
        val universe = listOf(
            candidate("design-capsule-1", preferred = true, 11),
            candidate("design-capsule-2", preferred = true, 11),
            IndexedCandidate(
                capsuleId = UUID.nameUUIDFromBytes("other-design".toByteArray()),
                front = fingerprint(99, 3),
                recipientPreferred = true,
            ),
        )
        // Both design capsules are plausible; must not auto-open.
        val result = engine.run(queryFront, universe)
        val ambiguous = result as ScanFlowResult.Ambiguous
        assertEquals(2, ambiguous.rows.size)
        assertTrue(issuedGrants.isEmpty(), "design->N must never auto-open")
    }

    @Test
    fun identicalScoreDistinctPlausibleCandidatesReturnAmbiguousWithoutVerifier() = kotlinx.coroutines.runBlocking {
        var verifierInvoked = false
        val countingVerifier = CapsuleVerifier { id -> verifierInvoked = true; true }
        val issuer = ScanGrantIssuer { capsuleId -> issuedGrants += capsuleId; "grant-$capsuleId" }
        val engine = LocalMatchEngine(
            profile = RecognitionProfile.mvpOrbV1(),
            verifier = countingVerifier,
            grantIssuer = issuer,
        )
        val queryFront = fingerprint(11, 64)
        // Both candidates share the same seed (11) so their synthetic fingerprints are
        // identical; the engine sees identical scores and must not auto-open.  Real
        // score-separated cases (e.g. 0.85 vs 0.45) are exercised in
        // MatchCoordinatorTest; this test specifically proves the identical-score path.
        val universe = listOf(
            candidate("dup-a", preferred = true, 11),
            candidate("dup-b", preferred = true, 11),
        )
        val result = engine.run(queryFront, universe)
        assertTrue(result is ScanFlowResult.Ambiguous, "identical-score plausible candidates must be ambiguous")
        assertTrue(!verifierInvoked, "verifier must never be invoked for ambiguous")
        assertTrue(issuedGrants.isEmpty(), "no grant for ambiguous even with identical scores")
    }

    @Test
    fun multiplePlausibleInSenderFallbackAlsoReturnsAmbiguousWithoutVerifier() = kotlinx.coroutines.runBlocking {
        var verifierInvoked = false
        val countingVerifier = CapsuleVerifier { id -> verifierInvoked = true; true }
        val issuer = ScanGrantIssuer { capsuleId -> issuedGrants += capsuleId; "grant-$capsuleId" }
        val engine = LocalMatchEngine(
            profile = RecognitionProfile.mvpOrbV1(),
            verifier = countingVerifier,
            grantIssuer = issuer,
        )
        val queryFront = fingerprint(11, 64)
        // No recipient candidates, two sender candidates both plausible.
        val universe = listOf(
            candidate("sender-1", preferred = false, 11),
            candidate("sender-2", preferred = false, 11),
        )
        val result = engine.run(queryFront, universe)
        assertTrue(result is ScanFlowResult.Ambiguous, "sender-fallback multiple plausible must be ambiguous")
        assertEquals(CandidateOrigin.SENDER_FALLBACK, (result as ScanFlowResult.Ambiguous).origin)
        assertTrue(!verifierInvoked, "verifier must never be invoked for sender-fallback ambiguous")
        assertTrue(issuedGrants.isEmpty())
    }

    @Test
    fun singlePlausibleCandidateStillAutoOpens() = kotlinx.coroutines.runBlocking {
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64)
        val universe = listOf(candidate("single", preferred = true, 11))
        val result = engine.run(queryFront, universe)
        assertTrue(result is ScanFlowResult.Granted, "single plausible must grant")
        assertEquals(1, issuedGrants.size)
    }

    @Test
    fun crossOriginRecipientAndSenderBothPlausibleReturnsAmbiguousWithoutVerifier() = kotlinx.coroutines.runBlocking {
        var verifierInvoked = false
        val countingVerifier = CapsuleVerifier { id -> verifierInvoked = true; true }
        val issuer = ScanGrantIssuer { capsuleId -> issuedGrants += capsuleId; "grant-$capsuleId" }
        val engine = LocalMatchEngine(
            profile = RecognitionProfile.mvpOrbV1(),
            verifier = countingVerifier,
            grantIssuer = issuer,
        )
        val queryFront = fingerprint(11, 64)
        // Recipient capsule A (plausible) + Sender capsule B (plausible) distinct => cross-origin ambiguous
        val universe = listOf(
            candidate("recipient-A", preferred = true, 11),
            candidate("sender-B", preferred = false, 11),
        )
        val result = engine.run(queryFront, universe)
        assertTrue(result is ScanFlowResult.Ambiguous, "recipient A + sender B both plausible must be ambiguous")
        assertTrue(!verifierInvoked, "verifier must not be invoked for cross-origin ambiguous")
        assertTrue(issuedGrants.isEmpty(), "no grant for cross-origin ambiguous")
        val ambiguous = result as ScanFlowResult.Ambiguous
        assertEquals(2, ambiguous.rows.size)
    }

    @Test
    fun sameCapsuleInBothOriginsDedupsAndPrefersRecipient() = kotlinx.coroutines.runBlocking {
        var verifierInvoked = false
        var verifiedId: UUID? = null
        val countingVerifier = CapsuleVerifier { id -> verifierInvoked = true; verifiedId = id; true }
        val issuer = ScanGrantIssuer { capsuleId -> issuedGrants += capsuleId; "grant-$capsuleId" }
        val engine = LocalMatchEngine(
            profile = RecognitionProfile.mvpOrbV1(),
            verifier = countingVerifier,
            grantIssuer = issuer,
        )
        val queryFront = fingerprint(11, 64)
        val capsuleId = UUID.nameUUIDFromBytes("dedup-capsule".toByteArray())
        val universe = listOf(
            IndexedCandidate(capsuleId = capsuleId, front = fingerprint(11, 64), recipientPreferred = true),
            IndexedCandidate(capsuleId = capsuleId, front = fingerprint(11, 64), recipientPreferred = false),
        )
        val result = engine.run(queryFront, universe)
        assertTrue(result is ScanFlowResult.Granted, "same capsule in both origins must dedup to single and grant")
        assertEquals(capsuleId, (result as ScanFlowResult.Granted).capsuleId)
        assertEquals(CandidateOrigin.RECIPIENT_PREFERRED, result.origin)
        assertTrue(verifierInvoked, "verifier must be invoked for single deduped candidate")
        assertEquals(capsuleId, verifiedId)
        assertEquals(1, issuedGrants.size)
    }

    @Test
    fun recipientNonPlausibleSenderPlausibleFallsBackToSender() = kotlinx.coroutines.runBlocking {
        var verifierInvoked = false
        val countingVerifier = CapsuleVerifier { id -> verifierInvoked = true; true }
        val issuer = ScanGrantIssuer { capsuleId -> issuedGrants += capsuleId; "grant-$capsuleId" }
        val engine = LocalMatchEngine(
            profile = RecognitionProfile.mvpOrbV1(),
            verifier = countingVerifier,
            grantIssuer = issuer,
        )
        val queryFront = fingerprint(11, 64)
        // Recipient has no plausible (starved), sender has one plausible
        val universe = listOf(
            IndexedCandidate(
                capsuleId = UUID.nameUUIDFromBytes("rec-nonplausible".toByteArray()),
                front = fingerprint(11, 3),
                recipientPreferred = true,
            ),
            candidate("sender-plausible", preferred = false, 11),
        )
        val result = engine.run(queryFront, universe)
        assertTrue(result is ScanFlowResult.Granted, "recipient non-plausible + sender plausible must fallback")
        assertEquals(CandidateOrigin.SENDER_FALLBACK, (result as ScanFlowResult.Granted).origin)
        assertTrue(verifierInvoked, "verifier must be invoked for fallback")
        assertEquals(1, issuedGrants.size)
    }

    private fun assertFalse(value: Boolean) = kotlin.test.assertFalse(value)
}
