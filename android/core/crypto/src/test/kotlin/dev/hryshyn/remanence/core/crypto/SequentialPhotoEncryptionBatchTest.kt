package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId

class SequentialPhotoEncryptionBatchTest {

    private lateinit var keyset: KeysetHandle
    private val routing = RecognitionManifestCodec.RoutingContext(
        capsuleId = CapsuleId(UUID.fromString("1f0a1234-5678-4abc-9def-aabbccdd1001")),
        blobId = BlobId(UUID.fromString("bf0a1234-5678-4abc-9def-aabbccdd000b")),
        senderUserId = UserId(UUID.fromString("3f0a1234-5678-4abc-9def-aabbccdd3003")),
        recipientUserId = UserId(UUID.fromString("4f0a1234-5678-4abc-9def-aabbccdd4004")),
    )

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        keyset = CapsuleKeysetGenerator().generate()
    }

    private fun bytes(n: Int, size: Int = 512): ByteArray =
        ByteArray(size) { (it + n).toByte() }

    private fun photos(n: Int): List<OrdinalPhoto> =
        (0 until n).map { ordinal -> OrdinalPhoto(ordinal) { ByteArrayInputStream(bytes(ordinal)) } }

    /** Counts concurrent opens and records open/close order to prove single-source streaming. */
    private class CountingSources(count: Int) {
        private val size = count
        val events = mutableListOf<String>()
        var currentlyOpen = 0
            private set
        var maxConcurrentOpen = 0
            private set
        var totalOpens = 0
            private set
        var totalCloses = 0
            private set

        fun list(): List<OrdinalPhoto> =
            (0 until size).map { ordinal ->
                OrdinalPhoto(ordinal) {
                    currentlyOpen += 1
                    maxConcurrentOpen = maxOf(maxConcurrentOpen, currentlyOpen)
                    totalOpens += 1
                    events += "open$ordinal"
                    object : InputStream() {
                        private val delegate = ByteArrayInputStream(payload(ordinal))
                        override fun read(): Int = delegate.read()
                        override fun close() {
                            currentlyOpen -= 1
                            totalCloses += 1
                            events += "close$ordinal"
                        }
                    }
                }
            }

        private fun payload(ordinal: Int): ByteArray =
            ByteArray(512) { (it + ordinal).toByte() }
    }

    @Test
    fun encryptsInAscendingOrderWithProgressAndRoundtrips() {
        val batch = SequentialPhotoEncryptionBatch(PhotoArtifactEncryptor())
        val progress = mutableListOf<Pair<Int, Int>>()
        val results = batch.encryptInOrder(keyset, routing, photos(5)) { index, total ->
            progress += index to total
            assertEquals(index + 1, progress.size) // strictly sequential callbacks
        }
        assertEquals((0 until 5).toList(), progress.map { it.first })
        assertEquals(List(5) { 5 }, progress.map { it.second })
        results.forEachIndexed { ordinal, encrypted ->
            assertContentEquals(
                bytes(ordinal),
                PhotoArtifactEncryptor().decryptPhoto(keyset, routing, ordinal, encrypted),
            )
        }
    }

    @Test
    fun atMostOnePlaintextSourceIsOpenAtAnyMomentAndEachClosesBeforeNext() {
        val batch = SequentialPhotoEncryptionBatch(PhotoArtifactEncryptor())
        val counting = CountingSources(4)

        val results = batch.encryptInOrder(keyset, routing, counting.list())

        assertTrue(counting.maxConcurrentOpen == 1, "staged sources must be read one at a time")
        assertTrue(
            counting.totalOpens == counting.totalCloses,
            "every opened source must be closed",
        )
        assertEquals(
            listOf("open0", "close0", "open1", "close1", "open2", "close2", "open3", "close3"),
            counting.events,
        )
        assertEquals(4, results.size)
    }

    @Test
    fun failureAtThirdPhotoDiscardsPartialResultsAndStopsProgress() {
        val oversized = ByteArrayInputStream(bytes(2, PhotoArtifactEncryptor.MAX_PLAINTEXT_BYTES + 1))
        val photos = photos(2) + OrdinalPhoto(2) { oversized } + OrdinalPhoto(3) { ByteArrayInputStream(bytes(3)) }
        val batch = SequentialPhotoEncryptionBatch(PhotoArtifactEncryptor())
        var progressEvents = 0
        try {
            batch.encryptInOrder(keyset, routing, photos) { _, _ -> progressEvents++ }
            throw AssertionError("expected failure")
        } catch (expected: IllegalArgumentException) {
            // oversized photo rejected before AEAD invocation
        }
        assertEquals(2, progressEvents) // first two succeeded, third aborted the run
    }

    @Test
    fun sourceReadFailureDiscardsPartialResults() {
        val photos = photos(2) + OrdinalPhoto(2) { throw java.io.IOException("staged file vanished") }
        val batch = SequentialPhotoEncryptionBatch(PhotoArtifactEncryptor())
        var progressEvents = 0
        try {
            batch.encryptInOrder(keyset, routing, photos) { _, _ -> progressEvents++ }
            throw AssertionError("expected failure")
        } catch (expected: java.io.IOException) {
            assertEquals("staged file vanished", expected.message)
        }
        assertEquals(2, progressEvents)
    }

    @Test
    fun stagedSourceAtExactlyThePlaintextBudgetEncryptsAndRoundtrips() {
        val batch = SequentialPhotoEncryptionBatch(PhotoArtifactEncryptor())
        val sources = photos(3).toMutableList()
        val exactBudget = bytes(1, PhotoArtifactEncryptor.MAX_PLAINTEXT_BYTES)
        sources[1] = OrdinalPhoto(1) { ByteArrayInputStream(exactBudget) }

        val results = batch.encryptInOrder(keyset, routing, sources)

        assertContentEquals(
            exactBudget,
            PhotoArtifactEncryptor().decryptPhoto(keyset, routing, 1, results[1]),
        )
    }

    @Test
    fun stagedSourceOneByteUnderThePlaintextBudgetEncryptsAndRoundtrips() {
        val batch = SequentialPhotoEncryptionBatch(PhotoArtifactEncryptor())
        val sources = photos(3).toMutableList()
        val underBudget = bytes(1, PhotoArtifactEncryptor.MAX_PLAINTEXT_BYTES - 1)
        sources[1] = OrdinalPhoto(1) { ByteArrayInputStream(underBudget) }

        val results = batch.encryptInOrder(keyset, routing, sources)

        assertContentEquals(
            underBudget,
            PhotoArtifactEncryptor().decryptPhoto(keyset, routing, 1, results[1]),
        )
    }

    @Test
    fun stagedSourceOneByteOverThePlaintextBudgetIsRejectedDuringReadAndStillClosed() {
        val batch = SequentialPhotoEncryptionBatch(PhotoArtifactEncryptor())
        val oversized = object : java.io.ByteArrayInputStream(
            bytes(1, PhotoArtifactEncryptor.MAX_PLAINTEXT_BYTES + 1),
        ) {
            var closed = false
                private set
            override fun close() {
                closed = true
                super.close()
            }
        }
        val sources = photos(3).toMutableList()
        sources[1] = OrdinalPhoto(1) { oversized }
        var progressEvents = 0

        val failure = assertFailsWith<IllegalArgumentException> {
            batch.encryptInOrder(keyset, routing, sources) { _, _ -> progressEvents++ }
        }

        assertTrue(failure.message!!.contains("plaintext budget"), failure.message)
        assertEquals(1, progressEvents, "rejection must happen while reading, before any AEAD work")
        assertTrue(oversized.closed, "the rejected source must still be closed")
    }

    @Test
    fun endlessHostileStagedSourceIsCutOffAtTheBudgetAndClosed() {
        val batch = SequentialPhotoEncryptionBatch(PhotoArtifactEncryptor())
        val endless = object : java.io.InputStream() {
            var closed = false
                private set
            private val chunk = ByteArray(64 * 1024)
            override fun read(): Int = 0x41
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                System.arraycopy(chunk, 0, b, off, len)
                return len
            }
            override fun close() {
                closed = true
                super.close()
            }
        }
        val sources = photos(3).toMutableList()
        sources[2] = OrdinalPhoto(2) { endless }

        assertFailsWith<IllegalArgumentException> {
            batch.encryptInOrder(keyset, routing, sources)
        }
        assertTrue(endless.closed, "the cut-off source must still be closed")
    }

    @Test
    fun nonSequentialOrdinalsRejectedBeforeAnySourceIsOpened() {
        val bad = listOf(
            OrdinalPhoto(0) { ByteArrayInputStream(bytes(0)) },
            OrdinalPhoto(2) { error("source must never be opened") },
            OrdinalPhoto(3) { error("source must never be opened") },
        )
        assertFailsWith<IllegalArgumentException> {
            SequentialPhotoEncryptionBatch(PhotoArtifactEncryptor()).encryptInOrder(keyset, routing, bad)
        }
    }

    @Test
    fun countOutsideWindowRejectedBeforeAnySourceIsOpened() {
        val batch = SequentialPhotoEncryptionBatch(PhotoArtifactEncryptor())
        for (count in intArrayOf(2, 6)) {
            val counting = CountingSources(count)
            assertFailsWith<IllegalArgumentException> {
                batch.encryptInOrder(keyset, routing, counting.list())
            }
            assertTrue(counting.totalOpens == 0, "bounds rejection must not open any source")
        }
    }
}
