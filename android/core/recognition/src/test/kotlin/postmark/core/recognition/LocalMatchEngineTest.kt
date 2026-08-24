package postmark.core.recognition

import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I09 integration proof: the whole hierarchy over synthetic fingerprints -
 * unique strong acceptance issues exactly one grant after crypto verification,
 * verification refusal never issues, and identical designs fall to the chooser.
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

    private fun fingerprint(seed: Int, count: Int, side: FingerprintSide) = PostcardFingerprint(
        profileId = RecognitionProfile.MVP_ORB_V1_ID,
        side = side,
        canonicalWidthPx = 1600,
        canonicalHeightPx = 1000,
        coarseHash64 = seed.toLong(),
        keypoints = List(count) { keypoint(it) },
        descriptors = List(count) { i -> ByteArray(32) { ((it * 7 + i * 13 + seed * 29) and 0xFF).toByte() } },
        quality = ExtractionQuality(200.0, 90.0, 0.01, 0.85),
    )

    private fun candidate(id: String, preferred: Boolean, seedFront: Int, seedBack: Int) = IndexedCandidate(
        capsuleId = UUID.nameUUIDFromBytes(id.toByteArray()),
        front = fingerprint(seedFront, 64, FingerprintSide.FRONT),
        back = fingerprint(seedBack, 64, FingerprintSide.BACK),
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
        // suspend engine.run proof
        val (engine, _) = engine()
        // The scanned card IS candidate A: identical descriptors, grid keypoints.
        val queryFront = fingerprint(11, 64, FingerprintSide.FRONT)
        val queryBack = fingerprint(22, 64, FingerprintSide.BACK)
        val universe = listOf(candidate("A", preferred = true, seedFront = 11, seedBack = 22))

        val result = engine.run(queryFront, queryBack, universe)

        val granted = result as ScanFlowResult.Granted
        assertEquals(universe.single().capsuleId, granted.capsuleId)
        assertEquals(CandidateOrigin.RECIPIENT_PREFERRED, granted.origin)
        assertTrue(granted.grantId.startsWith("grant-"))
        assertEquals(1, issuedGrants.size)
    }

    @Test
    fun refusedCryptoVerificationNeverIssuesAGrant() = kotlinx.coroutines.runBlocking {
        // suspend engine.run proof
        verifierResult = false
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64, FingerprintSide.FRONT)
        val queryBack = fingerprint(22, 64, FingerprintSide.BACK)
        val universe = listOf(candidate("A", preferred = true, 11, 22))

        val result = engine.run(queryFront, queryBack, universe)

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertEquals(0, issuedGrants.size)
    }

    @Test
    fun identicalMassProducedDesignsFallToTheChooserInsteadOfAutoOpen() = kotlinx.coroutines.runBlocking {
        // suspend engine.run proof
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64, FingerprintSide.FRONT)
        val queryBack = fingerprint(22, 64, FingerprintSide.BACK)
        // Two recipients of the SAME printed design: indistinguishable fronts.
        val universe = listOf(
            candidate("dup-1", preferred = true, 11, 22),
            candidate("dup-2", preferred = true, 11, 22),
        )

        val result = engine.run(queryFront, queryBack, universe)

        val ambiguous = result as ScanFlowResult.Ambiguous
        assertEquals(CandidateOrigin.RECIPIENT_PREFERRED, ambiguous.origin)
        assertEquals(2, ambiguous.rows.size)
        assertFalse(ambiguous.singleRecaptureFirst)
        kotlin.test.assertTrue(issuedGrants.isEmpty(), "no grant may exist for an ambiguous scan")
    }

    @Test
    fun senderFallbackRunsWhenRecipientRowsLackWeakEvidence() = kotlinx.coroutines.runBlocking {
        // suspend engine.run proof
        val (engine, _) = engine()
        val queryFront = fingerprint(11, 64, FingerprintSide.FRONT)
        val queryBack = fingerprint(22, 64, FingerprintSide.BACK)
        // Recipient baselines EXIST but each holds so few features that no
        // side can ever clear the weak-evidence gate (which requires at least
        // the configured minimum ratio/mutual matches); the scanned card must
        // therefore fall back to its sender-era identity.
        val starvedFront = fingerprint(11, 3, FingerprintSide.FRONT)
        val starvedBack = fingerprint(22, 3, FingerprintSide.BACK)
        val universe = listOf(
            IndexedCandidate(
                capsuleId = UUID.nameUUIDFromBytes("rec-starved-1".toByteArray()),
                front = starvedFront,
                back = starvedBack,
                recipientPreferred = true,
            ),
            IndexedCandidate(
                capsuleId = UUID.nameUUIDFromBytes("rec-starved-2".toByteArray()),
                front = fingerprint(31, 5, FingerprintSide.FRONT),
                back = fingerprint(32, 5, FingerprintSide.BACK),
                recipientPreferred = true,
            ),
            candidate("sender-original", preferred = false, seedFront = 11, seedBack = 22),
        )

        val result = engine.run(queryFront, queryBack, universe)

        val granted = result as ScanFlowResult.Granted
        assertEquals(UUID.nameUUIDFromBytes("sender-original".toByteArray()), granted.capsuleId)
        assertEquals(CandidateOrigin.SENDER_FALLBACK, granted.origin)
        assertEquals(1, issuedGrants.size)
    }

    @Test
    fun unknownPostcardIsRecaptureRequiredWithoutAnyGrant() = kotlinx.coroutines.runBlocking {
        // suspend engine.run proof
        val (engine, _) = engine()
        // Query matches nothing in the index.
        val universe = listOf(candidate("other-card", preferred = false, seedFront = 91, seedBack = 92))

        val result = engine.run(fingerprint(55, 64, FingerprintSide.FRONT), fingerprint(66, 64, FingerprintSide.BACK), universe)

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertEquals(0, issuedGrants.size)
    }

    @Test
    fun emptyCandidateIndexIsNoMatchNotAnError() = kotlinx.coroutines.runBlocking {
        // suspend engine.run proof
        val (engine, _) = engine()

        val result = engine.run(fingerprint(1, 64, FingerprintSide.FRONT), fingerprint(2, 64, FingerprintSide.BACK), emptyList())

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertEquals(0, issuedGrants.size)
    }

    @Test
    fun candidateWithoutStoredBackNeverBorrowsTheFrontAsItsBack() = kotlinx.coroutines.runBlocking {
        // suspend engine.run proof
        val (engine, _) = engine()
        // Perfect front match but NO stored back fingerprint. If the engine
        // substituted the front as the missing back, this scan would wrongly
        // self-accept; instead the incomplete pair must be unusable evidence.
        val front = fingerprint(11, 64, FingerprintSide.FRONT)
        val brokenCandidate = IndexedCandidate(
            capsuleId = UUID.nameUUIDFromBytes("broken-pair".toByteArray()),
            front = front,
            back = null,
            recipientPreferred = true,
        )

        // The query back carries the FRONT's descriptors: exactly the forgery
        // the front-as-back substitution would have accepted.
        val queryBack = PostcardFingerprint(
            profileId = front.profileId,
            side = FingerprintSide.BACK,
            canonicalWidthPx = front.canonicalWidthPx,
            canonicalHeightPx = front.canonicalHeightPx,
            coarseHash64 = front.coarseHash64,
            keypoints = front.keypoints,
            descriptors = front.descriptors,
            quality = front.quality,
        )

        val result = engine.run(front, queryBack, listOf(brokenCandidate))

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertTrue(issuedGrants.isEmpty(), "an incomplete pair must never issue a grant")
    }

    private fun assertFalse(value: Boolean) = kotlin.test.assertFalse(value)
}
