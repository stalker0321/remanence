package app.postmark.memory.create

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import postmark.core.data.fingerprints.DuplicateFingerprintException
import postmark.core.data.db.FingerprintOrigin
import postmark.core.data.db.FingerprintSide
import postmark.core.data.fingerprints.SealedFingerprintPersistence

private class RecordingPersistence : SealedFingerprintPersistence {
        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

    val persisted = mutableListOf<Triple<FingerprintSide, FingerprintOrigin, ByteArray>>()
    val profiles = mutableListOf<String>()
    var duplicateNext = false
    var frontExists = false

    override suspend fun persist(
        capsuleId: String,
        side: FingerprintSide,
        origin: FingerprintOrigin,
        profileId: String,
        plaintextBytes: ByteArray,
    ): String {
        if (duplicateNext) {
            duplicateNext = false
            throw DuplicateFingerprintException(capsuleId, side)
        }
        persisted += Triple(side, origin, plaintextBytes)
        profiles += profileId
        return "fp-${persisted.size}"
    }

    override suspend fun hasBaseline(
        capsuleId: String,
        side: FingerprintSide,
        origin: FingerprintOrigin,
    ): Boolean = persisted.any { it.first == side && it.second == origin } || (side == FingerprintSide.FRONT && frontExists)

    val preferredPairCalls = mutableListOf<Pair<String, FingerprintOrigin>>()
    val deletedBaselines = mutableListOf<Triple<String, FingerprintSide, FingerprintOrigin>>()

    override suspend fun setPreferredPair(capsuleId: String, origin: FingerprintOrigin) {
        preferredPairCalls += capsuleId to origin
    }

    override suspend fun deleteBaseline(
        capsuleId: String,
        side: FingerprintSide,
        origin: FingerprintOrigin,
    ) {
        deletedBaselines += Triple(capsuleId, side, origin)
    }
}

private class StubExtractor : SideFingerprintExtractor {
    val extractions = AtomicInteger()
    val requested = mutableListOf<FingerprintSide>()

    override fun extract(side: FingerprintSide): StagedSideFingerprint {
        extractions.incrementAndGet()
        requested += side
        return StagedSideFingerprint("mvp-orb-v1", side, "serialized-fingerprint".toByteArray())
    }
}

/** Misbehaving adapter that always reports the wrong side back to the repo. */
private class LyingExtractor(private val reported: FingerprintSide) : SideFingerprintExtractor {
    override fun extract(side: FingerprintSide): StagedSideFingerprint =
        StagedSideFingerprint("mvp-orb-v1", reported, "serialized-fingerprint".toByteArray())
}

class CreateSessionFingerprintRepositoryTest {

    private val capsuleId = "0198f0a0-0000-7000-8000-00000000ca01"

    @Test
    fun frontCaptureExtractsOnceAndPersistsSealedSenderFront() = runBlocking {
        val persistence = RecordingPersistence()
        val extractor = StubExtractor()
        val sut = CreateSessionFingerprintRepository(persistence, extractor)

        val id = sut.captureFront(capsuleId)

        assertEquals("fp-1", id)
        assertEquals(1, extractor.extractions.get())
        assertEquals(1, persistence.persisted.size)
        val (side, origin, bytes) = persistence.persisted.single()
        assertEquals(FingerprintSide.FRONT, side)
        assertEquals(FingerprintOrigin.SENDER, origin)
        assertTrue(bytes.isNotEmpty())
        assertEquals("mvp-orb-v1", persistence.profiles.single())
    }

    @Test
    fun duplicateFrontMapsToSafeFailureWithoutSecondPersist() {
        val persistence = RecordingPersistence().apply { duplicateNext = true }
        val sut = CreateSessionFingerprintRepository(persistence, StubExtractor())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { sut.captureFront(capsuleId) }
        }
        assertEquals(0, persistence.persisted.size)
    }

    @Test
    fun nonUuidCapsuleRejectedBeforeAnyExtractionOrPersist() {
        val persistence = RecordingPersistence()
        val extractor = StubExtractor()
        val sut = CreateSessionFingerprintRepository(persistence, extractor)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { sut.captureFront("not-a-uuid") }
        }
        assertEquals(0, extractor.extractions.get())
        assertEquals(0, persistence.persisted.size)
    }

    @Test
    fun uppercaseCapsuleRejected() {
        val sut = CreateSessionFingerprintRepository(RecordingPersistence(), StubExtractor())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { sut.captureFront(capsuleId.uppercase()) }
        }
    }

    @Test
    fun backWithoutFrontIsRejectedBeforeAnyExtractionOrPersist() {
        val persistence = RecordingPersistence()
        val extractor = StubExtractor()
        val sut = CreateSessionFingerprintRepository(persistence, extractor)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { sut.captureBack(capsuleId) }
        }
        assertEquals(0, extractor.extractions.get())
        assertEquals(0, persistence.persisted.size)
    }

    @Test
    fun backCaptureRequiresFrontThenPersistsSenderBack() = runBlocking {
        val persistence = RecordingPersistence()
        val extractor = StubExtractor()
        val sut = CreateSessionFingerprintRepository(persistence, extractor)

        sut.captureFront(capsuleId)
        val backId = sut.captureBack(capsuleId)

        assertEquals("fp-2", backId)
        assertEquals(2, extractor.extractions.get())
        assertEquals(
            listOf(FingerprintSide.FRONT, FingerprintSide.BACK),
            extractor.requested,
        )
        assertEquals(
            listOf(FingerprintSide.FRONT, FingerprintSide.BACK),
            persistence.persisted.map { it.first },
        )
        assertTrue(persistence.persisted.all { it.second == FingerprintOrigin.SENDER })
    }

    @Test
    fun failedBackAttemptLeavesFrontBaselineIntact() = runBlocking {
        val persistence = RecordingPersistence()
        val extractor = StubExtractor()
        val sut = CreateSessionFingerprintRepository(persistence, extractor)

        sut.captureFront(capsuleId)
        persistence.duplicateNext = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { sut.captureBack(capsuleId) }
        }
        // Front record untouched; only the front baseline exists.
        assertEquals(1, persistence.persisted.size)
        assertEquals(FingerprintSide.FRONT, persistence.persisted.single().first)
        assertTrue(persistence.hasBaseline(capsuleId, FingerprintSide.FRONT, FingerprintOrigin.SENDER))
    }

    @Test
    fun wrongSideFromExtractorRejectedWithoutPersist() = runBlocking {
        val persistence = RecordingPersistence().apply { frontExists = true }
        val extractor = LyingExtractor(reported = FingerprintSide.FRONT)
        val sut = CreateSessionFingerprintRepository(persistence, extractor)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { sut.captureBack(capsuleId) }
        }
        assertEquals(0, persistence.persisted.size)
    }
}
