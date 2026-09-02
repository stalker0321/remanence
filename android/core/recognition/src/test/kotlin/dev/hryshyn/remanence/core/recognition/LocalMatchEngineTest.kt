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
        side = FingerprintSide.FRONT,
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
        val universe = listOf(candidate("other-card", preferred = false, seedFront = 91))

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

    private fun assertFalse(value: Boolean) = kotlin.test.assertFalse(value)
}
