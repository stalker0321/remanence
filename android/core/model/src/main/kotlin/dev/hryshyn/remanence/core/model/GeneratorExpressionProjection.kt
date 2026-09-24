package dev.hryshyn.remanence.core.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * ADR-018 step 1: versioned, receiver-recomputable projection hash for the
 * BER1 v2 expression artifact (`BEXPR01`).
 *
 * Unlike [GeneratorExpression.canonicalHash] (which hashes the full G1 input,
 * including sender `ownerId`/`epoch`), this projection EXCLUDES owner/epoch
 * and instead binds the authored content ids, original content hashes,
 * upright dims, the exact encrypted photo blob ids, frozen geometry
 * (placements + contentRects + noteRegion), grammar/branch/canvas/font/palette
 * versions, note treatment, and the candidate id. A receiver can recompute it
 * from the sealed artifact alone.
 *
 * Byte grammar (all integers big-endian; strings int32-BE length + UTF-8):
 * magic "BEXPR01" | projectionVersion:i32=1 | canvasVersion:i32 |
 * grammarId:str | grammarVersion:i32 | branchId:str | noteTreatment:str |
 * fontVersion:i32 | paletteVersion:i32 |
 * noteRegion present:byte [x:y:w:h:i32×4] | candidateId:str |
 * sources count:i32 { ordinal:i32 contentId:str contentHash:str widthPx:i32
 *   heightPx:i32 blobId:16 } |
 * placements count:i32 { contentId:str x:i32 y:i32 w:i32 h:i32
 *   contentRect present:byte [x:y:w:h:i32×4] }
 */
object GeneratorExpressionProjection {

    const val PROJECTION_VERSION = 1
    private const val MAGIC = "BEXPR01"
    private val UTF8 = Charsets.UTF_8

    fun canonicalBytes(
        expression: GeneratorExpression.ResolvedExpression,
        candidateId: String,
        blobIdByContentId: Map<String, ByteArray>,
    ): ByteArray {
        require(candidateId.isNotBlank()) { "candidateId must not be blank" }
        val out = Writer()
        out.ascii(MAGIC)
        out.int(PROJECTION_VERSION)
        out.int(expression.canvasVersion)
        out.str(expression.grammarId)
        out.int(expression.grammarVersion)
        out.str(expression.branchId)
        out.str(expression.noteTreatment)
        out.int(expression.fontVersion)
        out.int(expression.paletteVersion)
        val region = expression.noteRegion
        if (region == null) {
            out.byte(0)
        } else {
            out.byte(1)
            out.int(region.x); out.int(region.y); out.int(region.width); out.int(region.height)
        }
        out.str(candidateId)
        val photos = expression.input.photos
        out.int(photos.size)
        for (photo in photos) {
            val blobId = blobIdByContentId[photo.contentId]
                ?: throw IllegalArgumentException("missing blob id for ${photo.contentId}")
            require(blobId.size == PHOTO_BLOB_ID_BYTES) { "blob id must be 16 bytes" }
            out.int(photo.ordinal)
            out.str(photo.contentId)
            out.str(photo.contentHash)
            out.int(photo.widthPx)
            out.int(photo.heightPx)
            out.bytes(blobId)
        }
        val placements = expression.placements
        out.int(placements.size)
        for (placement in placements) {
            out.str(placement.contentId)
            out.int(placement.x); out.int(placement.y)
            out.int(placement.width); out.int(placement.height)
            val rect = placement.contentRect
            if (rect == null) {
                out.byte(0)
            } else {
                out.byte(1)
                out.int(rect.x); out.int(rect.y); out.int(rect.width); out.int(rect.height)
            }
        }
        return out.toByteArray()
    }

    fun hash(
        expression: GeneratorExpression.ResolvedExpression,
        candidateId: String,
        blobIdByContentId: Map<String, ByteArray>,
    ): String = sha256Hex(canonicalBytes(expression, candidateId, blobIdByContentId))

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private const val PHOTO_BLOB_ID_BYTES = 16

    private class Writer {
        private val parts = mutableListOf<ByteArray>()

        fun ascii(value: String) {
            parts += value.toByteArray(Charsets.US_ASCII)
        }

        fun byte(value: Int) {
            parts += byteArrayOf(value.toByte())
        }

        fun int(value: Int) {
            parts += ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
        }

        fun str(value: String) {
            val bytes = value.toByteArray(UTF8)
            int(bytes.size)
            parts += bytes
        }

        fun bytes(value: ByteArray) {
            parts += value
        }

        fun toByteArray(): ByteArray {
            val total = parts.sumOf { it.size }
            val buffer = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            for (part in parts) buffer.put(part)
            return buffer.array()
        }
    }
}
