package app.postmark.memory.ui.capsule

import app.postmark.protocol.v1.RecipientEnvelopePlaintext
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import postmark.core.crypto.CapsuleArtifactCryptor
import postmark.core.crypto.CapsuleKeysetParser
import postmark.core.crypto.ContentManifestCodec
import postmark.core.crypto.RecipientEnvelopeCryptor
import postmark.core.data.db.PostmarkLocalDatabase
import postmark.core.data.outbox.OutboxArtifactKind
import postmark.core.model.ArtifactAadInput
import postmark.core.model.BlobId
import postmark.core.model.CapsuleArtifactKind
import postmark.core.model.CapsuleId
import postmark.core.model.KeyBundleId
import postmark.core.model.RecipientEnvelopeContextInput
import postmark.core.model.UserId

/**
 * FIX-M1-007-13 / FIX-REVIEW-04: the REAL capsule content source behind the
 * presentation. Every photo is decrypted on demand from its ciphertext file
 * under the exact artifact AAD built from the SEPARATE persisted sender and
 * recipient identities; the note comes from the decrypted content manifest.
 * Nothing is written to disk in plaintext and nothing outlives the caller's
 * page cache (docs/security.md section 12).
 */
class CapsuleContentSource(
    private val database: PostmarkLocalDatabase,
    private val encryptionPrivateHandle: com.google.crypto.tink.KeysetHandle,
    private val ownUserId: UUID,
    private val recipientKeyBundleIdOf: suspend (capsuleId: String) -> UUID?,
) {

    /** Separate routing identities resolved from the persisted capsule row. */
    private data class Routing(
        val senderUserId: UserId,
        val recipientUserId: UserId,
    )

    /**
     * FIX-REVIEW-04: sender and recipient are distinct persisted identities.
     * Legacy same-account rows (NULL sender columns) fall back to the
     * authenticated account - self-send works naturally, never by conflation.
     */
    private fun routing(row: postmark.core.data.db.OutboxCapsuleEntity): Routing {
        val recipient = runCatching { UserId(UUID.fromString(row.recipientUserId)) }
            .getOrElse { UserId(ownUserId) }
        val sender = row.senderUserId?.let { id ->
            runCatching { UserId(UUID.fromString(id)) }.getOrNull()
        } ?: recipient
        return Routing(senderUserId = sender, recipientUserId = recipient)
    }

    private suspend fun routing(capsuleId: String): Routing =
        routing(requireNotNull(database.outboxCapsuleDao().getByCapsuleId(capsuleId)) {
            "unknown capsule"
        })

    /** Opens the envelope and returns the validated protocol-v1 keyset. */
    private suspend fun capsuleKeyset(capsuleId: String): com.google.crypto.tink.KeysetHandle =
        withContext(Dispatchers.IO) {
            val uuid = UUID.fromString(capsuleId)
            val row = requireNotNull(database.outboxCapsuleDao().getByCapsuleId(capsuleId)) {
                "unknown capsule"
            }
            val routing = routing(row)
            val bundleId = requireNotNull(recipientKeyBundleIdOf(capsuleId))
            val envelopeBytes = java.io.File(requireNotNull(row.envelopePath)).readBytes()
            val opened = RecipientEnvelopeCryptor().open(
                encryptionPrivateHandle,
                RecipientEnvelopeContextInput(
                    CapsuleId(uuid),
                    routing.senderUserId,
                    routing.recipientUserId,
                    KeyBundleId(bundleId),
                ),
                envelopeBytes,
            )
            CapsuleKeysetParser().parseExactAes256GcmTink(
                RecipientEnvelopePlaintext.parseFrom(opened).capsuleAeadKeyset.toByteArray(),
            )
        }

    private suspend fun photoRouting(capsuleUuid: UUID, blobId: BlobId, ordinal: Int): ArtifactAadInput {
        val routing = routing(capsuleUuid.toString())
        return ArtifactAadInput(
            capsuleId = CapsuleId(capsuleUuid),
            blobId = blobId,
            artifactKind = CapsuleArtifactKind.PHOTO,
            ordinal = ordinal,
            senderUserId = routing.senderUserId,
            recipientUserId = routing.recipientUserId,
        )
    }

    /** Number of encrypted photo blobs declared for this capsule. */
    suspend fun photoCount(capsuleId: String): Int =
        database.outboxBlobDao().getAllByCapsuleId(capsuleId)
            .count { it.kind == OutboxArtifactKind.PHOTO.name }

    /** Decrypts one photo page strictly on demand. */
    suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto =
        withContext(Dispatchers.IO) {
            val uuid = UUID.fromString(capsuleId)
            val blobs = database.outboxBlobDao().getAllByCapsuleId(capsuleId)
            val blobRow = blobs.first {
                it.kind == OutboxArtifactKind.PHOTO.name && it.ordinal == ordinal
            }
            val keyset = capsuleKeyset(capsuleId)
            val ciphertext = java.io.File(blobRow.localCiphertextPath).readBytes()
            val plaintext = CapsuleArtifactCryptor().decrypt(
                keyset,
                photoRouting(uuid, BlobId(UUID.fromString(blobRow.blobId)), ordinal),
                ciphertext,
            )
            DecryptedPhoto(ordinal = ordinal, jpegBytes = plaintext)
        }

    /** Decrypts the optional note from the content manifest. */
    suspend fun noteText(capsuleId: String): String? = withContext(Dispatchers.IO) {
        val uuid = UUID.fromString(capsuleId)
        val blobs = database.outboxBlobDao().getAllByCapsuleId(capsuleId)
        val contentRow = blobs.firstOrNull { it.kind == OutboxArtifactKind.CONTENT_MANIFEST.name }
            ?: return@withContext null
        val keyset = capsuleKeyset(capsuleId)
        val ciphertext = java.io.File(contentRow.localCiphertextPath).readBytes()
        val manifestRouting = routing(capsuleId)
        ContentManifestCodec().decryptAndParse(
            keyset,
            postmark.core.crypto.RecognitionManifestCodec.RoutingContext(
                CapsuleId(uuid),
                BlobId(UUID.fromString(contentRow.blobId)),
                manifestRouting.senderUserId,
                manifestRouting.recipientUserId,
            ),
            ciphertext,
        ).note
    }
}
