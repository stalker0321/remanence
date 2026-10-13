package dev.hryshyn.remanence.pending

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import dev.hryshyn.remanence.core.crypto.CapsuleKeysetGenerator
import dev.hryshyn.remanence.core.crypto.PendingCapsulePublisher
import dev.hryshyn.remanence.core.crypto.PendingPublishCrypto
import dev.hryshyn.remanence.core.data.network.PendingBlobBytesResult
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T186 M14c: admitted photo opening with REAL sealed bytes (v2
 * publisher output) — owner fence, sender-signature re-verification,
 * declaration-bound blob download, target-AAD decrypt. Manifest/note
 * decoding is the next slice.
 */
class PendingAdmittedContentTest {

    private val capsule = "11111111-2222-3333-4444-555555555555"
    private val sender = "01010101-0202-0303-0404-050505050505"
    private val senderBundle = "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f"
    private val target = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val serviceKey = "9f9f9f9f-8e8e-7d7d-6c6c-5b5b5b5b5b5b"
    private val delivery = "0198f0a0-0000-7000-8000-00000000d401"
    private val owner = "33333333-4444-5555-6666-777777777777"

    private data class Sealed(
        val publication: PendingCapsulePublisher.Publication,
        val signing: KeysetHandle,
        val capsuleKey: KeysetHandle,
        val jpeg: ByteArray,
    )

    private fun sealed(): Sealed {
        dev.hryshyn.remanence.core.crypto.TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val capsuleKey = CapsuleKeysetGenerator().generate()
        val jpeg = "photo-bytes".toByteArray()
        val publication = PendingCapsulePublisher.publishFull(
            capsuleId = capsule,
            senderUserId = sender,
            senderKeyBundleId = senderBundle,
            deliveryTargetId = target,
            serviceKeyId = serviceKey,
            channel = PendingPublishCrypto.CHANNEL_EMAIL,
            normalizedDestination = "a42@example.test",
            salt32 = ByteArray(32).also { SecureRandom().nextBytes(it) },
            claimExpiresAt = 1814317432L,
            createdAtEpochSeconds = 1798765432L,
            signingHandle = signing,
            capsuleKeyset = capsuleKey,
            senderHandleSnapshot = "mykola",
            frontFingerprintBytes = ByteArray(96) { (it * 3).toByte() },
            photos = listOf(
                PendingCapsulePublisher.PhotoInput(jpeg, 1000, 1000),
                PendingCapsulePublisher.PhotoInput("photo-two".toByteArray(), 1200, 800),
                PendingCapsulePublisher.PhotoInput("photo-three".toByteArray(), 800, 1200),
            ),
            noteUtf8 = "hello",
        )
        return Sealed(publication, signing, capsuleKey, jpeg)
    }

    private fun rowOf(sealed: Sealed) = PendingAdmittedGiftStore.AdmittedGift(
        ownerUserId = owner,
        capsuleId = capsule,
        deliveryId = delivery,
        deliveryTargetId = target,
        claimId = "55555555-6666-7777-8888-999999999999",
        availableAt = 1798765432L,
        envelopeB64 = "RW52",
        envelopeSha256Hex = "ab".repeat(32),
        statementB64 = Base64.getEncoder().encodeToString(sealed.publication.statement),
        statementSha256Hex = MessageDigest.getInstance("SHA-256")
            .digest(sealed.publication.statement).joinToString("") { "%02x".format(it) },
        publishSignature69B64 = Base64.getEncoder()
            .encodeToString(sealed.publication.signature),
        envelopeRef = "/v2/pending-deliveries/$delivery/claim",
        manifestRef = "/v2/pending-deliveries/$delivery/manifest",
        blobRefs = emptyList(),
        completedAt = null,
        admittedAtMillis = 1L,
    )

    private fun content(
        sealed: Sealed,
        blobs: Map<String, ByteArray> = sealed.publication.artifacts
            .associate { it.blobId to it.ciphertext },
    ) = PendingAdmittedContent(
        blobPort = PendingAdmittedContent.BlobPort { _, blobId, _ ->
            val bytes = blobs[blobId]
                ?: return@BlobPort PendingBlobBytesResult.Failure(
                    dev.hryshyn.remanence.core.data.network.PendingMaterialFailure.NOT_FOUND,
                    404, false,
                )
            PendingBlobBytesResult.Bytes(blobId, bytes)
        },
        senderVerifyPort = PendingAdmittedContent.SenderVerifyPort {
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
                sealed.signing.publicKeysetHandle,
            )
        },
    )

    private fun keyOf(sealed: Sealed): ByteArray =
        TinkProtoKeysetFormat.serializeKeyset(
            sealed.capsuleKey,
            InsecureSecretKeyAccess.get(),
        )

    @Test
    fun opensPhotosWithTargetAad() = runTest {
        val sealed = sealed()
        val opened = content(sealed).openPhotos(
            rowOf(sealed), owner, "token",
            PendingAdmittedContent.EnvelopeOpener { keyOf(sealed) },
        )
        assertEquals(3, opened.photosByOrdinal.size)
        assertEquals(
            "photo-bytes",
            opened.photosByOrdinal[0]?.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun foreignOwnerRejected() = runTest {
        val sealed = sealed()
        assertThrows(IllegalArgumentException::class.java) {
            content(sealed).openPhotos(
                rowOf(sealed), "99999999-2222-3333-4444-555555555555", "token",
                PendingAdmittedContent.EnvelopeOpener { keyOf(sealed) },
            )
        }
    }

    @Test
    fun tamperedBlobRejected() = runTest {
        val sealed = sealed()
        val first = sealed.publication.artifacts.first { it.kind == 3 }
        val tampered = first.ciphertext.copyOf()
            .also { it[0] = (it[0] + 1).toByte() }
        val opened = try {
            content(
                sealed,
                sealed.publication.artifacts.associate {
                    it.blobId to if (it.blobId == first.blobId) tampered else it.ciphertext
                },
            ).openPhotos(
                rowOf(sealed), owner, "token",
                PendingAdmittedContent.EnvelopeOpener { keyOf(sealed) },
            )
            null
        } catch (_: IllegalStateException) {
            "rejected"
        }
        assertEquals("rejected", opened)
    }

    @Test
    fun missingPhotoBlobFails() = runTest {
        val sealed = sealed()
        val result = try {
            content(sealed, emptyMap()).openPhotos(
                rowOf(sealed), owner, "token",
                PendingAdmittedContent.EnvelopeOpener { keyOf(sealed) },
            )
            null
        } catch (_: IllegalStateException) {
            "rejected"
        }
        assertEquals("rejected", result)
    }
}
