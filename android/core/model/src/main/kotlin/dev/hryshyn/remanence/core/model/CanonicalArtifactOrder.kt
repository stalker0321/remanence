package dev.hryshyn.remanence.core.model

import dev.hryshyn.remanence.protocol.v1.ArtifactKind

/** The protocol-v1 canonical order for artifact bindings. */
object CanonicalArtifactOrder {

    /** Compares `(protobuf kind number, ordinal, unsigned blob_id bytes)`. */
    fun compare(left: ArtifactSlot, right: ArtifactSlot): Int {
        val kindComparison = protobufKindNumber(left.kind).compareTo(protobufKindNumber(right.kind))
        if (kindComparison != 0) return kindComparison

        val ordinalComparison = left.ordinal.compareTo(right.ordinal)
        if (ordinalComparison != 0) return ordinalComparison

        return compareUnsignedBytes(left.blobId.toProtoBytes(), right.blobId.toProtoBytes())
    }

    /** Checks order without sorting or changing the supplied sequence. */
    fun isCanonical(slots: List<ArtifactSlot>): Boolean {
        for (index in 1 until slots.size) {
            if (compare(slots[index - 1], slots[index]) > 0) return false
        }
        return true
    }

    private fun protobufKindNumber(kind: CapsuleArtifactKind): Int = when (kind) {
        CapsuleArtifactKind.RECOGNITION_MANIFEST -> ArtifactKind.RECOGNITION_MANIFEST.number
        CapsuleArtifactKind.CONTENT_MANIFEST -> ArtifactKind.CONTENT_MANIFEST.number
        CapsuleArtifactKind.PHOTO -> ArtifactKind.PHOTO.number
    }

    private fun compareUnsignedBytes(left: com.google.protobuf.ByteString, right: com.google.protobuf.ByteString): Int {
        val length = minOf(left.size(), right.size())
        for (index in 0 until length) {
            val difference = (left.byteAt(index).toInt() and 0xff) - (right.byteAt(index).toInt() and 0xff)
            if (difference != 0) return difference
        }
        return left.size().compareTo(right.size())
    }
}
