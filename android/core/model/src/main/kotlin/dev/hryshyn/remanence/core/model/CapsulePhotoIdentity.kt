package dev.hryshyn.remanence.core.model

import com.google.protobuf.ByteString

/**
 * ADR-018 critical fix: the ONE deterministic, opaque photo-identity contract
 * shared by the preview loader, the publish bridge and the receiver so the
 * sender's frozen expression and the sealed artifact use identical source
 * descriptors.
 *
 * - `contentId` is derived from the ORIGINAL bytes' SHA-256 (opaque, URI-free,
 *   never a picker URI). The normalized/preview JPEG bytes are NEVER hashed.
 * - the encrypted photo blob id is the capsule UUID with the ordinal tag in
 *   byte 0 (identical to `CapsulePublisher`'s layout).
 */
object CapsulePhotoIdentity {

    /** Photo blob tag base; must match `CapsulePublisher.PHOTO_BLOB_BASE`. */
    const val PHOTO_BLOB_TAG_BASE = 0x10

    private val HEX64 = Regex("[0-9a-f]{64}")

    /** Opaque, deterministic content id from the ORIGINAL-bytes SHA-256. */
    fun contentIdFor(originalContentHash: String): String {
        require(HEX64.matches(originalContentHash)) {
            "content hash must be 64 lowercase hex chars"
        }
        return "c$originalContentHash"
    }

    /** Deterministic encrypted photo blob id for `ordinal` (0..4). */
    fun photoBlobId(capsuleId: CapsuleId, ordinal: Int): BlobId {
        require(ordinal in 0..4) { "photo ordinal out of range" }
        val base = capsuleId.toProtoBytes().toByteArray()
        base[0] = (PHOTO_BLOB_TAG_BASE + ordinal).toByte()
        return BlobId.fromProtoBytes(ByteString.copyFrom(base))
    }

    fun photoBlobBytes(capsuleId: CapsuleId, ordinal: Int): ByteArray =
        photoBlobId(capsuleId, ordinal).toProtoBytes().toByteArray()

    /** Authored-order contentId -> exact encrypted photo blob bytes. */
    fun blobIdByContentId(capsuleId: CapsuleId, contentIds: List<String>): Map<String, ByteArray> =
        contentIds.mapIndexed { index, contentId ->
            contentId to photoBlobBytes(capsuleId, index)
        }.toMap()

    /** The exact BEXPR01 hash the sealed artifact will declare. */
    fun projectedHash(
        expression: GeneratorExpression.ResolvedExpression,
        candidateId: String,
        capsuleId: CapsuleId,
        contentIds: List<String>,
    ): String = GeneratorExpressionProjection.hash(
        expression,
        candidateId,
        blobIdByContentId(capsuleId, contentIds),
    )
}
