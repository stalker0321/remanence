package postmark.core.crypto

import com.google.crypto.tink.KeysetHandle
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import postmark.core.model.BlobId
import postmark.core.model.CapsuleId
import postmark.core.model.UserId

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

    private fun photos(n: Int): List<OrdinalPhoto> =
        (0 until n).map { OrdinalPhoto(it, ByteArray(512) { (it + n).toByte() }) }

    @Test
    fun encryptsInAscendingOrderWithProgress() {
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
                photos(5)[ordinal].normalizedJpeg,
                PhotoArtifactEncryptor().decryptPhoto(keyset, routing, ordinal, encrypted),
            )
        }
    }

    @Test
    fun failureAtThirdPhotoDiscardsPartialResultsAndStopsProgress() {
        val oversized = ByteArray(PhotoArtifactEncryptor.MAX_PLAINTEXT_BYTES + 1)
        val photos = photos(2) + OrdinalPhoto(2, oversized) + photos(1).map { it.copy(ordinal = 3) }
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
    fun nonSequentialOrdinalsRejectedBeforeFirstEncryption() {
        val bad = listOf(
            OrdinalPhoto(0, ByteArray(16)),
            OrdinalPhoto(2, ByteArray(16)),
            OrdinalPhoto(3, ByteArray(16)),
        )
        assertFailsWith<IllegalArgumentException> {
            SequentialPhotoEncryptionBatch(PhotoArtifactEncryptor()).encryptInOrder(keyset, routing, bad)
        }
    }

    @Test
    fun countOutsideWindowRejected() {
        val batch = SequentialPhotoEncryptionBatch(PhotoArtifactEncryptor())
        for (count in intArrayOf(2, 6)) {
            assertFailsWith<IllegalArgumentException> {
                batch.encryptInOrder(keyset, routing, (0 until count).map { OrdinalPhoto(it, ByteArray(8)) })
            }
        }
    }
}
