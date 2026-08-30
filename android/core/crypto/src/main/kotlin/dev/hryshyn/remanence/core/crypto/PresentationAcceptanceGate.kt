package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.v1.ArtifactKind
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import java.security.GeneralSecurityException

/**
 * Identity-bound bundle for presentation acceptance (M2-P12,
 * docs/security.md section 6.7). The presentation plane delivers the
 * envelope plaintext, the canonical signed statement, the authoritative
 * sender signature, and the actual [DeliveredCiphertext] bytes for every
 * signed artifact (recognition, content manifest, each photo). The
 * transport identity (size, SHA-256) is always derived from the bytes
 * the caller holds; no caller-supplied size or digest is trusted.
 *
 * The control/index path and the presentation path share the same
 * canonical control fields and the same [CanonicalControlVerifier];
 * the presentation path additionally requires full material coverage
 * and a validated content manifest.
 */
data class PresentationAcceptanceInput(
    val expectedCapsuleId: CapsuleId,
    val authenticatedUserId: UserId,
    val senderVerifyingKeyset: KeysetHandle,
    val expectedSenderKeyBundleId: KeyBundleId,
    val envelopePlaintextBytes: ByteArray,
    val statementBytes: ByteArray,
    val signature: ByteArray,
    val deliveredCiphertexts: List<DeliveredCiphertext>,
)

sealed interface PresentationAcceptanceResult {
    /**
     * Presentation acceptance succeeded: the canonical signed statement
     * was verified, the delivered ciphertexts exactly cover every signed
     * binding, the capsule keyset parsed, the signed content manifest
     * decrypted and validated (including its inner capsule id binding
     * and structural checks), and the photo descriptors matched the
     * signed PHOTO bindings by blob id and ordinal. The verified
     * [PublishStatement] is the only output; note, photo descriptors,
     * the decrypted manifest, the keyset, and any photo plaintext are
     * never returned.
     */
    data class Verified(val statement: PublishStatement) : PresentationAcceptanceResult

    data class Rejected(val reason: RejectionReason) : PresentationAcceptanceResult
}

/** Verified material owned by the next local rendering checkpoint. */
class PreparedPresentationMaterial internal constructor(
    val statement: PublishStatement,
    capsuleKeyset: KeysetHandle,
    manifest: ContentManifestContent,
    deliveredCiphertexts: List<DeliveredCiphertext>,
) : AutoCloseable {
    private var capsuleKeysetValue: KeysetHandle? = capsuleKeyset
    private var manifestValue: ContentManifestContent? = manifest
    private val ciphertexts = LinkedHashMap<BlobId, ByteArray>(deliveredCiphertexts.size)
    private var closed = false

    init {
        deliveredCiphertexts.forEach { delivered ->
            check(ciphertexts.put(delivered.blobId, delivered.ciphertext.copyOf()) == null) {
                "duplicate prepared ciphertext"
            }
        }
    }

    val photoCount: Int
        get() = requireOpen().photos.size

    fun noteText(): String? = requireOpen().note

    /** Decrypts one verified photo from the exact snapshot captured at prepare time. */
    fun loadPhoto(ordinal: Int): ByteArray {
        val manifest = requireOpen()
        val photo = manifest.photos.firstOrNull { it.ordinal == ordinal }
            ?: throw IllegalArgumentException("photo ordinal is not in the verified manifest")
        val capsuleKeyset = capsuleKeysetValue ?: error("prepared material is closed")
        val sender = UserId.fromProtoBytes(statement.senderUserId)
        val recipient = UserId.fromProtoBytes(statement.recipientUserId)
        val capsuleId = CapsuleId.fromProtoBytes(statement.capsuleId)
        val blobId = BlobId(photo.blobId)
        val ciphertext = ciphertexts[blobId]?.copyOf()
            ?: throw IllegalStateException("verified photo ciphertext is unavailable")
        return try {
            CapsuleArtifactCryptor().decrypt(
                capsuleKeyset,
                ArtifactAadInput(
                    capsuleId = capsuleId,
                    blobId = blobId,
                    artifactKind = CapsuleArtifactKind.PHOTO,
                    ordinal = ordinal,
                    senderUserId = sender,
                    recipientUserId = recipient,
                ),
                ciphertext,
            )
        } finally {
            ciphertext.fill(0)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        ciphertexts.values.forEach { it.fill(0) }
        ciphertexts.clear()
        manifestValue = null
        capsuleKeysetValue = null
    }

    override fun toString(): String = "PreparedPresentationMaterial(<redacted>)"

    internal fun isClosedForTesting(): Boolean = closed && ciphertexts.isEmpty() &&
        manifestValue == null && capsuleKeysetValue == null

    private fun requireOpen(): ContentManifestContent {
        check(!closed) { "prepared presentation material is closed" }
        return manifestValue ?: error("prepared presentation material is closed")
    }
}

sealed interface PresentationAcceptancePreparationResult {
    class Prepared(val material: PreparedPresentationMaterial) :
        PresentationAcceptancePreparationResult {
        override fun toString(): String =
            "PresentationAcceptancePreparationResult.Prepared(<redacted>)"
    }

    data class Rejected(val reason: RejectionReason) : PresentationAcceptancePreparationResult
}

/**
 * M2-P12 presentation gate (docs/security.md section 6.7). Verifies the
 * canonical control plane, then the exact full ciphertext coverage, then
 * decrypts and validates only the signed content manifest, and finally
 * requires the photo descriptors to match the signed PHOTO bindings
 * by blob id and ordinal. The full [CapsuleAcceptanceGate] and the
 * [ControlIndexAcceptanceGate] are untouched; the presentation gate is
 * a strictly separate capability and never decrypts photo ciphertexts.
 *
 * The gate ALWAYS returns a result — it never throws. Malformed signed
 * IDs, binding/layout mismatches, content AEAD failures, wrong inner
 * capsule ids, and descriptor mismatches all reject closed with a
 * reason and perform no cryptographic or storage side effects beyond
 * the [CapsuleArtifactCryptor] and [ContentManifestCodec] calls they
 * are routed through. [prepare] is the private-material handoff for the
 * later local renderer; [verify] retains its statement-only result surface.
 */
class PresentationAcceptanceGate(
    signer: PublishStatementSigner = PublishStatementSigner(),
    private val capsuleKeysetParser: CapsuleKeysetParser = CapsuleKeysetParser(),
) {

    private val controlVerifier: CanonicalControlVerifier = CanonicalControlVerifier(
        signer = signer,
        capsuleKeysetParser = capsuleKeysetParser,
    )

    private val blobBindingVerifier: DeliveredBlobBindingVerifier = DeliveredBlobBindingVerifier()

    private val contentManifestCodec: ContentManifestCodec = ContentManifestCodec()

    fun verify(input: PresentationAcceptanceInput): PresentationAcceptanceResult =
        when (val prepared = prepare(input)) {
            is PresentationAcceptancePreparationResult.Prepared -> {
                val statement = prepared.material.statement
                prepared.material.close()
                PresentationAcceptanceResult.Verified(statement)
            }
            is PresentationAcceptancePreparationResult.Rejected ->
                PresentationAcceptanceResult.Rejected(prepared.reason)
        }

    /** Verifies once and retains the exact ciphertext snapshots for local rendering. */
    fun prepare(input: PresentationAcceptanceInput): PresentationAcceptancePreparationResult {
        val control = controlVerifier.verify(
            CanonicalControlInput(
                expectedCapsuleId = input.expectedCapsuleId,
                authenticatedUserId = input.authenticatedUserId,
                senderVerifyingKeyset = input.senderVerifyingKeyset,
                expectedSenderKeyBundleId = input.expectedSenderKeyBundleId,
                envelopePlaintextBytes = input.envelopePlaintextBytes,
                statementBytes = input.statementBytes,
                signature = input.signature,
            ),
        )
        val verified = when (control) {
            is CanonicalControlResult.Rejected ->
                return PresentationAcceptancePreparationResult.Rejected(control.reason)
            is CanonicalControlResult.Verified -> control.control
        }

        if (!blobBindingVerifier.matchesFullCoverage(
                bindings = verified.statement.artifactsList,
                delivered = input.deliveredCiphertexts,
            )
        ) {
            return PresentationAcceptancePreparationResult.Rejected(RejectionReason.BLOB_SUBSTITUTION)
        }

        val contentBinding = verified.statement.artifactsList.firstOrNull { binding ->
            binding.kind == ArtifactKind.CONTENT_MANIFEST && binding.ordinal == NON_PHOTO_ORDINAL
        } ?: return PresentationAcceptancePreparationResult.Rejected(RejectionReason.LAYOUT_INVALID)

        val keyset = try {
            capsuleKeysetParser.parseExactAes256GcmTink(
                verified.envelope.capsuleAeadKeyset.toByteArray(),
            )
        } catch (_: GeneralSecurityException) {
            return PresentationAcceptancePreparationResult.Rejected(RejectionReason.CONTENT_AEAD_INVALID)
        }

        val contentCiphertext = input.deliveredCiphertexts
            .firstOrNull { it.blobId.toProtoBytes() == contentBinding.blobId }
            ?.ciphertext
            ?: return PresentationAcceptancePreparationResult.Rejected(RejectionReason.BLOB_SUBSTITUTION)

        val routing = try {
            RecognitionManifestCodec.RoutingContext(
                capsuleId = input.expectedCapsuleId,
                blobId = BlobId.fromProtoBytes(contentBinding.blobId),
                senderUserId = UserId.fromProtoBytes(verified.statement.senderUserId),
                recipientUserId = UserId.fromProtoBytes(verified.statement.recipientUserId),
            )
        } catch (_: IllegalArgumentException) {
            return PresentationAcceptancePreparationResult.Rejected(RejectionReason.ID_MISMATCH)
        }

        val manifest = try {
            contentManifestCodec.decryptAndParse(keyset, routing, contentCiphertext)
        } catch (_: GeneralSecurityException) {
            return PresentationAcceptancePreparationResult.Rejected(RejectionReason.CONTENT_AEAD_INVALID)
        } catch (_: Exception) {
            return PresentationAcceptancePreparationResult.Rejected(RejectionReason.CONTENT_AEAD_INVALID)
        }

        val photoBindings = verified.statement.artifactsList.filter { binding ->
            binding.kind == ArtifactKind.PHOTO
        }
        if (manifest.photos.size != photoBindings.size) {
            return PresentationAcceptancePreparationResult.Rejected(RejectionReason.CONTENT_DESCRIPTORS_MISMATCH)
        }
        val manifestByOrdinal = manifest.photos.sortedBy { it.ordinal }
        for ((index, descriptor) in manifestByOrdinal.withIndex()) {
            if (descriptor.ordinal != index) {
                return PresentationAcceptancePreparationResult.Rejected(RejectionReason.CONTENT_DESCRIPTORS_MISMATCH)
            }
            val signedBinding = photoBindings[index]
            if (signedBinding.ordinal != index) {
                return PresentationAcceptancePreparationResult.Rejected(RejectionReason.CONTENT_DESCRIPTORS_MISMATCH)
            }
            val descriptorBytes = java.nio.ByteBuffer.allocate(16)
                .putLong(descriptor.blobId.mostSignificantBits)
                .putLong(descriptor.blobId.leastSignificantBits)
                .array()
            if (!java.security.MessageDigest.isEqual(signedBinding.blobId.toByteArray(), descriptorBytes)) {
                return PresentationAcceptancePreparationResult.Rejected(RejectionReason.CONTENT_DESCRIPTORS_MISMATCH)
            }
        }

        return PresentationAcceptancePreparationResult.Prepared(
            PreparedPresentationMaterial(
                statement = verified.statement,
                capsuleKeyset = keyset,
                manifest = manifest,
                deliveredCiphertexts = input.deliveredCiphertexts,
            ),
        )
    }

    private companion object {
        const val NON_PHOTO_ORDINAL = -1
    }
}
