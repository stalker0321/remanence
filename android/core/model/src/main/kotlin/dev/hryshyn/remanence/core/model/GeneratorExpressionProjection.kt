package dev.hryshyn.remanence.core.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * ADR-018 step 1: versioned, receiver-recomputable projection hash for the
 * BER1 v2 expression artifact (`BEXPR01`); V5 collage: `BEXPR02`.
 *
 * Unlike [GeneratorExpression.canonicalHash] (which hashes the full G1 input,
 * including sender `ownerId`/`epoch`), this projection EXCLUDES owner/epoch
 * and instead binds the authored content ids, original content hashes,
 * upright dims, the exact encrypted photo blob ids, frozen geometry
 * (placements + contentRects + noteRegion), grammar/branch/canvas/font/palette
 * versions, note treatment, and the candidate id. A receiver can recompute it
 * from the sealed artifact alone.
 *
 * The v3 path (`BEXPR02`, projection version 2) additionally binds the
 * per-placement crop (0..10000 integers) and mask id, plus the global
 * style binding (id, version, 32-byte STYD01 digest), the frozen note
 * type, and the optional music region with its nested type. The v1/v2
 * path is frozen byte-identical.
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
 *
 * v3 trailer (magic "BEXPR02", projectionVersion:i32=2; same prefix through
 * the per-placement contentRect, then per placement):
 * crop present:byte [x:y:w:h:i32×4] | maskId present:byte [str]
 * then global: style { styleId:str styleVersion:str digest:32 } |
 * noteType { family:str size:i32 weight:i32 italic:byte line:i32 } |
 * music present:byte [x:y:w:h:i32×4 mode:str
 *   artwork present:byte [provider:str url:str sha256:32 mime:str w:i32 h:i32]
 *   type { family:str size:i32 weight:i32 italic:byte line:i32 }]
 * The artwork slot is byte-for-byte the GENEX03 shape; no-art seals as
 * mode "text" with artwork present:byte 0. The descriptor rides here;
 * artwork bytes never do — the projection never carries image content.
 *
 * v4 trailer (magic "BEXPR03", projectionVersion:i32=3; full frozen V5):
 * the v3 shape through the music trailer, then per placement a trailing
 * shape:str, then provenance present:byte [count:i32 { kind:str text:str
 * x:y:w:h:i32×4 family:str size:i32 weight:i32 italic:byte line:i32
 * orientation:str align:str }] and decor present:byte [count:i32 {
 * kind:str x:y:w:h:i32×4 }]. Mark texts join the projection digest
 * (lead checkpoint); the v1/v2/v3 paths stay frozen byte-identical.
 */
object GeneratorExpressionProjection {

    const val PROJECTION_VERSION = 1
    private const val MAGIC = "BEXPR01"

    const val PROJECTION_VERSION_2 = 2
    private const val MAGIC_2 = "BEXPR02"

    const val PROJECTION_VERSION_3 = 3
    private const val MAGIC_3 = "BEXPR03"
    private val UTF8 = Charsets.UTF_8

    fun canonicalBytes(
        expression: GeneratorExpression.ResolvedExpression,
        candidateId: String,
        blobIdByContentId: Map<String, ByteArray>,
    ): ByteArray {
        require(candidateId.isNotBlank()) { "candidateId must not be blank" }
        return when (expression.expressionContractVersion) {
            GeneratorExpression.EXPRESSION_CONTRACT_V1,
            GeneratorExpression.EXPRESSION_CONTRACT_V2
            -> v1ProjectionBytes(expression, candidateId, blobIdByContentId)
            GeneratorExpression.EXPRESSION_CONTRACT_V3 ->
                v3ProjectionBytes(expression, candidateId, blobIdByContentId)
            GeneratorExpression.EXPRESSION_CONTRACT_V4 ->
                v4ProjectionBytes(expression, candidateId, blobIdByContentId)
            else -> throw IllegalArgumentException(
                "unsupported expressionContractVersion: ${expression.expressionContractVersion}",
            )
        }
    }

    /** Frozen v1/v2 projection encoding (do not modify). */
    private fun v1ProjectionBytes(
        expression: GeneratorExpression.ResolvedExpression,
        candidateId: String,
        blobIdByContentId: Map<String, ByteArray>,
    ): ByteArray {
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

    /**
     * v3 projection encoding (finished collage). The prefix is identical
     * through the per-placement contentRect; each placement then gains its
     * crop and mask trailers, followed by the global style / note-type /
     * music trailer. Fail-closed: a v3 expression missing its required
     * style, note type, or per-placement crop throws instead of emitting
     * bytes that alias a weaker binding.
     */
    private fun v3ProjectionBytes(
        expression: GeneratorExpression.ResolvedExpression,
        candidateId: String,
        blobIdByContentId: Map<String, ByteArray>,
    ): ByteArray {
        val out = Writer()
        out.ascii(MAGIC_2)
        out.int(PROJECTION_VERSION_2)
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
        out.int(expression.placements.size)
        for (placement in expression.placements) {
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
            val crop = placement.crop
                ?: throw IllegalArgumentException("v3 expression must carry crop on ${placement.contentId}")
            out.byte(1)
            out.int(crop.x); out.int(crop.y); out.int(crop.width); out.int(crop.height)
            val mask = placement.maskId
            if (mask == null) {
                out.byte(0)
            } else {
                out.byte(1)
                out.str(mask)
            }
        }
        val style = expression.style
            ?: throw IllegalArgumentException("v3 expression must carry style")
        out.str(style.styleId)
        out.str(style.styleVersion)
        require(style.digest.size == 32) { "v3 style.digest must be exactly 32 bytes" }
        out.bytes(style.digest)
        val noteType = expression.noteType
            ?: throw IllegalArgumentException("v3 expression must carry noteType")
        out.writeTextType(noteType)
        val music = expression.musicRegion
        if (music == null) {
            out.byte(0)
        } else {
            out.byte(1)
            out.int(music.x); out.int(music.y); out.int(music.width); out.int(music.height)
            out.str(music.mode)
            out.writeArtwork(music.artwork)
            out.writeTextType(music.type)
        }
        return out.toByteArray()
    }

    /**
     * v4 projection encoding (full frozen V5). The v3 shape is preserved
     * through the music trailer; each placement gains its trailing shape,
     * then the authored provenance marks (texts join the digest) and decor
     * planes. Fail-closed: a v4 expression missing its required style,
     * note type, or per-placement crop throws instead of emitting bytes
     * that alias a weaker binding.
     */
    private fun v4ProjectionBytes(
        expression: GeneratorExpression.ResolvedExpression,
        candidateId: String,
        blobIdByContentId: Map<String, ByteArray>,
    ): ByteArray {
        val out = Writer()
        out.ascii(MAGIC_3)
        out.int(PROJECTION_VERSION_3)
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
        out.int(expression.placements.size)
        for (placement in expression.placements) {
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
            val crop = placement.crop
                ?: throw IllegalArgumentException("v4 expression must carry crop on ${placement.contentId}")
            out.byte(1)
            out.int(crop.x); out.int(crop.y); out.int(crop.width); out.int(crop.height)
            val mask = placement.maskId
            if (mask == null) {
                out.byte(0)
            } else {
                out.byte(1)
                out.str(mask)
            }
            out.str(placement.shape.name.lowercase())
        }
        val style = expression.style
            ?: throw IllegalArgumentException("v4 expression must carry style")
        out.str(style.styleId)
        out.str(style.styleVersion)
        require(style.digest.size == 32) { "v4 style.digest must be exactly 32 bytes" }
        out.bytes(style.digest)
        val noteType = expression.noteType
            ?: throw IllegalArgumentException("v4 expression must carry noteType")
        out.writeTextType(noteType)
        val music = expression.musicRegion
        if (music == null) {
            out.byte(0)
        } else {
            out.byte(1)
            out.int(music.x); out.int(music.y); out.int(music.width); out.int(music.height)
            out.str(music.mode)
            out.writeArtwork(music.artwork)
            out.writeTextType(music.type)
        }
        val provenance = expression.provenance
        if (provenance == null) {
            out.byte(0)
        } else {
            out.byte(1)
            out.int(provenance.size)
            for (mark in provenance) {
                out.str(mark.kind.name.lowercase())
                out.str(mark.text)
                out.int(mark.x); out.int(mark.y); out.int(mark.width); out.int(mark.height)
                out.str(mark.family)
                out.int(mark.size)
                out.int(mark.weight)
                out.byte(if (mark.italic) 1 else 0)
                out.int(mark.line)
                out.str(mark.orientation.name.lowercase().replace('_', '-'))
                out.str(mark.align.name.lowercase())
            }
        }
        val decor = expression.decor
        if (decor == null) {
            out.byte(0)
        } else {
            out.byte(1)
            out.int(decor.size)
            for (plane in decor) {
                out.str(plane.kind.name.lowercase().replace('_', '-'))
                out.int(plane.x); out.int(plane.y); out.int(plane.width); out.int(plane.height)
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

        /** Frozen resolved type: family, size, weight, italic byte, line. */
        fun writeTextType(type: GeneratorExpression.TextType) {
            str(type.family)
            int(type.size)
            int(type.weight)
            byte(if (type.italic) 1 else 0)
            int(type.line)
        }

        /** Cover-art descriptor after the music mode, before the type. */
        fun writeArtwork(artwork: GeneratorExpression.ArtworkRef?) {
            if (artwork == null) {
                byte(0)
                return
            }
            byte(1)
            str(artwork.provider)
            str(artwork.url)
            require(artwork.sha256.size == GeneratorExpression.ARTWORK_SHA256_BYTES) {
                "artwork.sha256 must be exactly 32 bytes"
            }
            bytes(artwork.sha256)
            str(artwork.mimeType)
            int(artwork.width)
            int(artwork.height)
        }

        fun toByteArray(): ByteArray {
            val total = parts.sumOf { it.size }
            val buffer = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            for (part in parts) buffer.put(part)
            return buffer.array()
        }
    }
}
