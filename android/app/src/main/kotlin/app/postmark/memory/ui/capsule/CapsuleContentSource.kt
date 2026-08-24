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
 * FIX-M1-007-13: the REAL capsule content source behind the presentation.
 * Every photo is decrypted on demand from its ciphertext file under the exact
 * artifact AAD; the note comes from the decrypted content manifest. Nothing
 * is written to disk in plaintext and nothing outlives the caller's page
 * cache (docs/security.md section 12).
 */
class CapsuleContentSource(
    private val database: PostmarkLocalDatabase,
    private val encryptionPrivateHandle: com.google.crypto.tink.KeysetHandle,
    private val ownUserId: UUID,
    private val recipientKeyBundleIdOf: suspend (capsuleId: String) -> UUID?,
) {

    /** Opens the envelope and returns the validated protocol-v1 keyset. */
    private suspend fun capsuleKeyset(capsuleId: String): com.google.crypto.tink.KeysetHandle =
        withContext(Dispatchers.IO) {
            val uuid = UUID.fromString(capsuleId)
            val row = requireNotNull(database.outboxCapsuleDao().getByCapsuleId(capsuleId)) {
                "unknown capsule"
            }
            val bundleId = requireNotNull(recipientKeyBundleIdOf(capsuleId))
            val envelopeBytes = java.io.File(requireNotNull(row.envelopePath)).readBytes()
            val opened = RecipientEnvelopeCryptor().open(
                encryptionPrivateHandle,
                RecipientEnvelopeContextInput(
                    CapsuleId(uuid),
                    UserId(ownUserId),
                    UserId(ownUserId),
                    KeyBundleId(bundleId),
                ),
                envelopeBytes,
            )
            CapsuleKeysetParser().parseExactAes256GcmTink(
                RecipientEnvelopePlaintext.parseFrom(opened).capsuleAeadKeyset.toByteArray(),
            )
        }

    private fun routing(capsuleUuid: UUID, blobId: BlobId, ordinal: Int) = ArtifactAadInput(
        capsuleId = CapsuleId(capsuleUuid),
        blobId = blobId,
        artifactKind = CapsuleArtifactKind.PHOTO,
        ordinal = ordinal,
        senderUserId = UserId(ownUserId),
        recipientUserId = UserId(ownUserId),
    )

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
                routing(uuid, BlobId(UUID.fromString(blobRow.blobId)), ordinal),
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
        ContentManifestCodec().decryptAndParse(
            keyset,
            postmark.core.crypto.RecognitionManifestCodec.RoutingContext(
                CapsuleId(uuid),
                BlobId(UUID.fromString(contentRow.blobId)),
                UserId(ownUserId),
                UserId(ownUserId),
            ),
            ciphertext,
        ).note
    }
}
