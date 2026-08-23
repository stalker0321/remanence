package postmark.core.model

enum class CapsuleArtifactKind {
    RECOGNITION_MANIFEST,
    CONTENT_MANIFEST,
    PHOTO,
}

data class ArtifactSlot(
    val blobId: BlobId,
    val kind: CapsuleArtifactKind,
    val ordinal: Int,
)

enum class ArtifactLayoutError {
    DUPLICATE_BLOB_ID,
    RECOGNITION_MANIFEST_CARDINALITY,
    CONTENT_MANIFEST_CARDINALITY,
    PHOTO_CARDINALITY,
    NON_PHOTO_ORDINAL,
    PHOTO_ORDINAL_SEQUENCE,
}

sealed interface ArtifactLayoutValidation {
    data class Valid(val slots: List<ArtifactSlot>) : ArtifactLayoutValidation

    data class Invalid(val reason: ArtifactLayoutError) : ArtifactLayoutValidation
}

object ArtifactLayoutValidator {
    fun validate(slots: List<ArtifactSlot>): ArtifactLayoutValidation {
        val uniqueIds = HashSet<BlobId>(slots.size)
        for (slot in slots) {
            if (!uniqueIds.add(slot.blobId)) {
                return ArtifactLayoutValidation.Invalid(ArtifactLayoutError.DUPLICATE_BLOB_ID)
            }
        }
        val recognition = slots.filter { it.kind == CapsuleArtifactKind.RECOGNITION_MANIFEST }
        if (recognition.size != ProtocolV1Limits.RECOGNITION_MANIFEST_COUNT) {
            return ArtifactLayoutValidation.Invalid(ArtifactLayoutError.RECOGNITION_MANIFEST_CARDINALITY)
        }
        val content = slots.filter { it.kind == CapsuleArtifactKind.CONTENT_MANIFEST }
        if (content.size != ProtocolV1Limits.CONTENT_MANIFEST_COUNT) {
            return ArtifactLayoutValidation.Invalid(ArtifactLayoutError.CONTENT_MANIFEST_CARDINALITY)
        }
        val photos = slots.filter { it.kind == CapsuleArtifactKind.PHOTO }
        if (photos.size !in ProtocolV1Limits.PHOTO_COUNT_MIN..ProtocolV1Limits.PHOTO_COUNT_MAX) {
            return ArtifactLayoutValidation.Invalid(ArtifactLayoutError.PHOTO_CARDINALITY)
        }
        if (recognition.any { it.ordinal != ProtocolV1Limits.NON_PHOTO_ORDINAL } ||
            content.any { it.ordinal != ProtocolV1Limits.NON_PHOTO_ORDINAL }
        ) {
            return ArtifactLayoutValidation.Invalid(ArtifactLayoutError.NON_PHOTO_ORDINAL)
        }
        val expectedOrdinals = (ProtocolV1Limits.PHOTO_ORDINAL_MIN until photos.size).toSet()
        if (photos.map { it.ordinal }.toSet() != expectedOrdinals) {
            return ArtifactLayoutValidation.Invalid(ArtifactLayoutError.PHOTO_ORDINAL_SEQUENCE)
        }
        return ArtifactLayoutValidation.Valid(slots.toList())
    }
}
