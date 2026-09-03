package dev.hryshyn.remanence.create

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.hryshyn.remanence.core.data.fingerprints.DuplicateFingerprintException
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence

private class RecordingPersistence : SealedFingerprintPersistence {
        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

    val persisted = mutableListOf<Triple<String, FingerprintOrigin, ByteArray>>()
    val profiles = mutableListOf<String>()
    var duplicateNext = false
    var frontExists = false

    override suspend fun persist(
        capsuleId: String,
        origin: FingerprintOrigin,
        profileId: String,
        plaintextBytes: ByteArray,
    ): String {
        if (duplicateNext) {
            duplicateNext = false
            throw DuplicateFingerprintException(capsuleId, origin)
        }
        persisted += Triple(capsuleId, origin, plaintextBytes)
        profiles += profileId
        return "fp-${persisted.size}"
    }

    override suspend fun hasBaseline(
        capsuleId: String,
        origin: FingerprintOrigin,
    ): Boolean = persisted.any { it.second == origin } || frontExists

    override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit

    override suspend fun deleteBaseline(capsuleId: String, origin: FingerprintOrigin) = Unit
}

private class StubExtractor : SideFingerprintExtractor {
    val extractions = AtomicInteger()
    val requested = mutableListOf<CaptureFingerprintSide>()

    override fun extract(side: CaptureFingerprintSide): StagedSideFingerprint {
        extractions.incrementAndGet()
        requested += side
        return StagedSideFingerprint("mvp-orb-v1", side, "serialized-fingerprint".toByteArray())
    }
}

/** Misbehaving adapter that always reports the wrong side back to the repo. */
private class LyingExtractor(private val reported: CaptureFingerprintSide) : SideFingerprintExtractor {
    override fun extract(side: CaptureFingerprintSide): StagedSideFingerprint =
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
        val (storedCapsule, origin, bytes) = persistence.persisted.single()
        assertEquals(capsuleId, storedCapsule)
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
    fun backCaptureRequiresFrontThenStagesCaptureLocalBack() = runBlocking {
        val persistence = RecordingPersistence()
        val extractor = StubExtractor()
        val captureStore = CreateCaptureSessionStore()
        val sut = CreateSessionFingerprintRepository(persistence, extractor, captureStore)

        sut.captureFront(capsuleId)
        val backId = sut.captureBack(capsuleId)

        assertTrue(backId.startsWith("capture-"))
        assertEquals(2, extractor.extractions.get())
        assertEquals(
            listOf(CaptureFingerprintSide.FRONT, CaptureFingerprintSide.BACK),
            extractor.requested,
        )
        assertEquals(
            listOf(capsuleId),
            persistence.persisted.map { it.first },
        )
        assertTrue(persistence.persisted.all { it.second == FingerprintOrigin.SENDER })
        assertArrayEquals("serialized-fingerprint".toByteArray(), captureStore.read(backId))
        captureStore.clear()
        assertNull(captureStore.read(backId))
    }

    @Test
    fun wrongSideFromExtractorRejectedWithoutPersist() = runBlocking {
        val persistence = RecordingPersistence().apply { frontExists = true }
        val extractor = LyingExtractor(reported = CaptureFingerprintSide.FRONT)
        val sut = CreateSessionFingerprintRepository(persistence, extractor)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { sut.captureBack(capsuleId) }
        }
        assertEquals(0, persistence.persisted.size)
    }
}
