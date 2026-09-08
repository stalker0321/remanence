package dev.hryshyn.remanence.create

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.hryshyn.remanence.core.data.fingerprints.DuplicateFingerprintException
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture

private class RecordingPersistence : SealedFingerprintPersistence {
        override suspend fun decrypt(fingerprintId: String): ByteArray = ByteArray(0)

    val persisted = mutableListOf<Triple<String, FingerprintOrigin, ByteArray>>()
    val profiles = mutableListOf<String>()
    var duplicateNext = false

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
        // Persistence owns its sealed copy; the repository wipes its handoff.
        persisted += Triple(capsuleId, origin, plaintextBytes.copyOf())
        profiles += profileId
        return "fp-${persisted.size}"
    }

    override suspend fun hasBaseline(
        capsuleId: String,
        origin: FingerprintOrigin,
    ): Boolean = persisted.isNotEmpty()

    override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit

    override suspend fun deleteBaseline(capsuleId: String, origin: FingerprintOrigin) = Unit
}

private class StubExtractor : SideFingerprintExtractor {
    val extractions = AtomicInteger()
    var lastBytes: ByteArray? = null

    override fun extract(): StagedSideFingerprint {
        extractions.incrementAndGet()
        return StagedSideFingerprint(
            "postcard-sift-rootsift-v1",
            CanonicalSiftFingerprintFixture.bytes(seed = 1),
        ).also {
            lastBytes = it.serializedBytes
        }
    }
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
        assertEquals("postcard-sift-rootsift-v1", persistence.profiles.single())
        assertTrue(extractor.lastBytes!!.all { it == 0.toByte() })
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
    fun noncanonicalExtractionIsRejectedAndTheHandoffIsWiped() = runBlocking {
        val persistence = RecordingPersistence()
        var staged: StagedSideFingerprint? = null
        val extractor = SideFingerprintExtractor {
            StagedSideFingerprint(
                "postcard-sift-rootsift-v1",
                byteArrayOf(1, 2, 3),
            ).also { staged = it }
        }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { CreateSessionFingerprintRepository(persistence, extractor).captureFront(capsuleId) }
        }

        assertEquals(0, persistence.persisted.size)
        assertTrue(staged!!.serializedBytes.all { it == 0.toByte() })
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
}
