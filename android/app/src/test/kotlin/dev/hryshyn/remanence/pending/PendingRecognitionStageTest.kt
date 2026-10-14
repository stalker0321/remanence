package dev.hryshyn.remanence.pending

import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.data.network.PendingBlobBytesResult
import dev.hryshyn.remanence.core.data.network.PendingMaterialFailure
import dev.hryshyn.remanence.protocol.delivery_v2.ArtifactBindingV2
import dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * T186 M14d-scan: reception-time kind-1 staging — selection from
 * verified S is exact (single kind-1/ordinal-1, bounded, 32-byte
 * digest), and fetch verifies size + SHA-256 with best-effort
 * failure semantics (only cancellation propagates).
 */
class PendingRecognitionStageTest {

    private val blobId = "cccccccc-dddd-eeee-ffff-000000000001"

    private fun uuidBytes(raw: String): ByteArray {
        val uuid = UUID.fromString(raw)
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private fun binding(
        kind: Int,
        ordinal: Int,
        size: Long,
        digest: ByteArray,
        id: String = blobId,
    ): ArtifactBindingV2 = ArtifactBindingV2.newBuilder()
        .setBlobId(ByteString.copyFrom(uuidBytes(id)))
        .setKind(kind)
        .setOrdinal(ordinal)
        .setCiphertextSize(size)
        .setCiphertextSha256(ByteString.copyFrom(digest))
        .build()

    private fun statement(vararg artifacts: ArtifactBindingV2): ByteArray =
        PendingPublishStatementV2.newBuilder()
            .setProtocolVersion(2)
            .addAllArtifacts(artifacts.toList())
            .build().toByteArray()

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test
    fun selectsSingleKind1() {
        val bytes = ByteArray(64) { it.toByte() }
        val selected = PendingRecognitionStage.selectKind1(
            statement(binding(1, -1, bytes.size.toLong(), sha256(bytes))),
        )
        assertEquals(blobId, selected?.blobId)
        assertEquals(bytes.size.toLong(), selected?.ciphertextSize)
        assertArrayEquals(sha256(bytes), selected?.ciphertextSha256)
    }

    @Test
    fun ignoresPhotosAndManifests() {
        val photo = ByteArray(64) { 1 }
        val manifest = ByteArray(64) { 2 }
        assertNull(
            PendingRecognitionStage.selectKind1(
                statement(
                    binding(2, -1, manifest.size.toLong(), sha256(manifest)),
                    binding(3, 0, photo.size.toLong(), sha256(photo)),
                ),
            ),
        )
    }

    @Test
    fun rejectsAmbiguousKind1() {
        val bytes = ByteArray(64) { it.toByte() }
        assertNull(
            PendingRecognitionStage.selectKind1(
                statement(
                    binding(1, -1, bytes.size.toLong(), sha256(bytes)),
                    binding(1, -1, bytes.size.toLong(), sha256(bytes), "dddddddd-dddd-eeee-ffff-000000000002"),
                ),
            ),
        )
    }

    @Test
    fun rejectsWrongOrdinalAndBounds() {
        val bytes = ByteArray(64) { it.toByte() }
        // kind-1 with a photo ordinal is not the FRONT binding.
        assertNull(
            PendingRecognitionStage.selectKind1(
                statement(binding(1, 0, bytes.size.toLong(), sha256(bytes))),
            ),
        )
        // Below tag + framing: implausible.
        assertNull(
            PendingRecognitionStage.selectKind1(
                statement(binding(1, -1, 10L, sha256(bytes))),
            ),
        )
        // Above the frozen fingerprint limit + tag margin: refused.
        assertNull(
            PendingRecognitionStage.selectKind1(
                statement(binding(1, -1, 4L * 1_024L * 1_024L, sha256(bytes))),
            ),
        )
        // Digest must be exactly 32 bytes.
        assertNull(
            PendingRecognitionStage.selectKind1(
                statement(binding(1, -1, bytes.size.toLong(), ByteArray(31))),
            ),
        )
        // Malformed statement bytes: no selection, never a throw.
        assertNull(PendingRecognitionStage.selectKind1(ByteArray(10)))
    }

    @Test
    fun fetchVerifiesSizeAndDigest() = runTest {
        val bytes = ByteArray(64) { it.toByte() }
        val binding = PendingRecognitionStage.Kind1Binding(blobId, bytes.size.toLong(), sha256(bytes))
        val port = PendingRecognitionStage.BlobFetchPort { deliveryId, id, _ ->
            assertEquals("delivery-1", deliveryId)
            assertEquals(blobId, id)
            PendingBlobBytesResult.Bytes(id, bytes)
        }
        assertArrayEquals(
            bytes,
            PendingRecognitionStage.fetchVerified("delivery-1", "token", binding, port),
        )
    }

    @Test
    fun fetchFailuresDegradeToNull() = runTest {
        val bytes = ByteArray(64) { it.toByte() }
        val binding = PendingRecognitionStage.Kind1Binding(blobId, bytes.size.toLong(), sha256(bytes))
        // Size mismatch.
        assertNull(
            PendingRecognitionStage.fetchVerified(
                "delivery-1", "token", binding,
                PendingRecognitionStage.BlobFetchPort { _, id, _ ->
                    PendingBlobBytesResult.Bytes(id, ByteArray(65))
                },
            ),
        )
        // Digest mismatch.
        assertNull(
            PendingRecognitionStage.fetchVerified(
                "delivery-1", "token", binding,
                PendingRecognitionStage.BlobFetchPort { _, id, _ ->
                    PendingBlobBytesResult.Bytes(id, ByteArray(64) { 9 })
                },
            ),
        )
        // Port failure.
        assertNull(
            PendingRecognitionStage.fetchVerified(
                "delivery-1", "token", binding,
                PendingRecognitionStage.BlobFetchPort { _, _, _ ->
                    PendingBlobBytesResult.Failure(PendingMaterialFailure.NETWORK, 500, true)
                },
            ),
        )
        // Port throw (non-cancellation).
        assertNull(
            PendingRecognitionStage.fetchVerified(
                "delivery-1", "token", binding,
                PendingRecognitionStage.BlobFetchPort { _, _, _ -> throw IllegalStateException("down") },
            ),
        )
        // Blank token: the port is never consulted.
        var called = false
        assertNull(
            PendingRecognitionStage.fetchVerified(
                "delivery-1", "", binding,
                PendingRecognitionStage.BlobFetchPort { _, id, _ ->
                    called = true
                    PendingBlobBytesResult.Bytes(id, bytes)
                },
            ),
        )
        assertEquals(false, called)
    }

    @Test
    fun fetchCancellationPropagates() = runTest {
        val bytes = ByteArray(64) { it.toByte() }
        val binding = PendingRecognitionStage.Kind1Binding(blobId, bytes.size.toLong(), sha256(bytes))
        try {
            PendingRecognitionStage.fetchVerified(
                "delivery-1", "token", binding,
                PendingRecognitionStage.BlobFetchPort { _, _, _ ->
                    throw CancellationException("scope torn down")
                },
            )
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun keepVerifiedPriorKeepsOnlyCurrentBytes() {
        val bytes = ByteArray(64) { it.toByte() }
        val statementBytes = statement(binding(1, -1, bytes.size.toLong(), sha256(bytes)))
        val prior = java.util.Base64.getEncoder().encodeToString(bytes)
        // Exact match against the current S: kept verbatim.
        assertEquals(
            prior,
            PendingRecognitionStage.keepVerifiedPrior(prior, statementBytes),
        )
        // Stale sizes/digests, blanks, garbage, and kind-1-less S: "".
        assertEquals(
            "",
            PendingRecognitionStage.keepVerifiedPrior(
                java.util.Base64.getEncoder().encodeToString(ByteArray(65)),
                statementBytes,
            ),
        )
        assertEquals(
            "",
            PendingRecognitionStage.keepVerifiedPrior(
                java.util.Base64.getEncoder().encodeToString(ByteArray(64) { 9 }),
                statementBytes,
            ),
        )
        assertEquals("", PendingRecognitionStage.keepVerifiedPrior("", statementBytes))
        assertEquals("", PendingRecognitionStage.keepVerifiedPrior("!!!", statementBytes))
        assertEquals(
            "",
            PendingRecognitionStage.keepVerifiedPrior(
                prior,
                statement(binding(3, 0, bytes.size.toLong(), sha256(bytes))),
            ),
        )
    }
}
