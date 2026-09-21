package dev.hryshyn.remanence.core.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * G1 — provider-independent canonical generator/artifact contract.
 *
 * Slice G1 covers ONLY the frozen data contract and its deterministic
 * identity: versioned expression/snapshot identity, hash/equality
 * semantics, authored order, the optional note, mixed aspect metadata,
 * and invalidation boundaries. There is deliberately NO generator
 * provider, NO branch/layout implementation, NO UI, NO publishing,
 * NO receive presentation, and NO M4 wiring here.
 *
 * Authority:
 * - `docs/hold/generator-boundary.md` — Generate/Select/Correct-crop/
 *   Freeze/Publish/Receive semantics; the adapter entry sits between
 *   validated CONTENT and `CreateViewModel.startPublishing()`.
 * - `CANONICAL_PRODUCT_RENDERER_ARCHITECTURE.md` §1–§3 — capsule input
 *   (3–5 ordered photos, optional music reference, one short note),
 *   bounded canvas, ResolvedExpression shape, freeze rule.
 * - Design handoff `README.md` §37.5 — generator metadata is an
 *   unimplemented seam; production must expose note/music and authored
 *   reading order from actual metadata.
 *
 * Resolved protocol choices (no guessing):
 * - Note ceiling is the v1 wire rule `NOTE_MAX_UTF8_BYTES = 1000`
 *   ([ProtocolV1Limits]); the generator study's provisional 200–300
 *   character guidance is NOT adopted as a backend rule, exactly as the
 *   boundary document requires. Footprint classes (absent/short/regular)
 *   are a render concern and stay out of identity.
 * - Absent note (`null`) and present-but-empty note (`""`) are
 *   canonically DISTINCT: absence closes the composition (arch §5),
 *   while an empty string is explicit input. The renderer decides
 *   treatment; identity never conflates them.
 * - Music is input-level reference only (`trackId`, optional
 *   `artworkId`); v1 forbids track attachment
 *   (`MVP_TRACK_ATTACHMENT_ALLOWED = false`), and music rendering/
 *   applicability belongs to grammars, not to G1. Presence/absence of
 *   the reference participates in identity so it can never change
 *   silently.
 * - Authored order IS list order: every photo carries an `ordinal` that
 *   must equal its index (0..n-1 exactly once). Anything else is invalid.
 * - Reference canvas is 360×640 units (arch §2, provisional): placements
 *   are recorded in these units for identity; shell fitting is out of
 *   scope and never affects the hash.
 *
 * Canonical serialization (all integers big-endian, all strings as
 * int32-BE length + UTF-8 bytes, fields in declaration order):
 * ```
 * magic      "GENEX01" (7 bytes, no length prefix)
 * versions   canvasVersion:i32 grammarId:str grammarVersion:i32 branchId:str
 * photos     count:i32 then per photo:
 *              contentId:str ordinal:i32 width:i32 height:i32 contentHash:str
 * note       present:byte(0/1) then value:str iff present
 * music      present:byte(0/1) then trackId:str artworkPresent:byte
 *              then artworkId:str iff present
 * mapping    count:i32 then per placement (authored-photo order):
 *              contentId:str x:i32 y:i32 w:i32 h:i32
 *              cropPresent:byte then cx:cy:cw:ch:i32×4 iff present
 *              maskPresent:byte then maskId:str iff present
 * noteTreatment:str diagnosticsCount:i32 then each:str
 * deps       fontVersion:i32 paletteVersion:i32
 * ```
 * `canonicalHash()` is lowercase-hex SHA-256 over exactly these bytes.
 */
object GeneratorExpression {

    /** Canonical contract version pinned by every hash in this file. */
    const val CONTRACT_VERSION = 1

    /** Reference canvas units (arch §2, provisional 9:16 360×640). */
    const val CANVAS_WIDTH_UNITS = 360
    const val CANVAS_HEIGHT_UNITS = 640

    /** Normalized crop window units (0..1000 per axis). */
    const val CROP_UNIT_MAX = 1000

    private const val MAGIC = "GENEX01"
    private val UTF8 = Charsets.UTF_8

    /** One ordered original photograph (authored input side). */
    data class PhotoRef(
        val contentId: String,
        val ordinal: Int,
        val widthPx: Int,
        val heightPx: Int,
        /** Lowercase-hex SHA-256 of the original bytes (64 chars). */
        val contentHash: String,
    )

    /** Input-level music reference (no attachment, no rendering). */
    data class MusicRef(
        val trackId: String,
        val artworkId: String?,
    )

    /** Canonical generator input (arch §3 `CapsuleInput`). */
    data class GeneratorInput(
        val ownerId: String,
        /** Owner/session epoch: older-epoch callbacks are rejected. */
        val epoch: Long,
        /** Authored order; `photos[i].ordinal` must equal `i`. */
        val photos: List<PhotoRef>,
        /** `null` = absent (closes the composition); `""` = explicit empty. */
        val note: String?,
        val music: MusicRef?,
    )

    /** Normalized crop window in [CROP_UNIT_MAX] units. */
    data class CropWindow(val x: Int, val y: Int, val width: Int, val height: Int)

    /** Resolved placement of one authored photo on the reference canvas. */
    data class Placement(
        val contentId: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val crop: CropWindow?,
        val maskId: String?,
    )

    /** Frozen resolved expression (arch §2–§3 `ResolvedExpression`). */
    data class ResolvedExpression(
        val canvasVersion: Int,
        val grammarId: String,
        val grammarVersion: Int,
        val branchId: String,
        val input: GeneratorInput,
        /** Placements in authored-photo order, one per photo. */
        val placements: List<Placement>,
        val noteTreatment: String,
        val diagnostics: List<String>,
        val fontVersion: Int,
        val paletteVersion: Int,
    )

    /** Immutable frozen snapshot (boundary `Freeze`). */
    data class FrozenSnapshot(
        val expression: ResolvedExpression,
        /** SHA-256 over the expression canonical bytes. */
        val expressionHash: String,
        val contentRevision: Long,
        val frozenAtEpoch: Long,
    )

    /** Structural validation outcome for [GeneratorInput]. */
    sealed interface InputValidation {
        data object Valid : InputValidation
        data class Invalid(val reasons: List<String>) : InputValidation
    }

    /** Enumerates the invalidation boundary (boundary §Evidence). */
    sealed interface ExpressionChange {
        data object PhotosChanged : ExpressionChange
        data object PhotoMetadataChanged : ExpressionChange
        data object NoteChanged : ExpressionChange
        data object MusicChanged : ExpressionChange
        data object VersionsChanged : ExpressionChange
        data object OwnerOrEpochChanged : ExpressionChange

        /**
         * Shell/viewer-only change (fitting, scrolling, safe-area
         * handling): deliberately does NOT invalidate — the composition
         * is the canvas, the shell accommodates the device (arch §2).
         */
        data object ShellOnlyChange : ExpressionChange
    }

    /** Validates structural boundaries; never touches bytes or providers. */
    fun validate(input: GeneratorInput): InputValidation {
        val reasons = mutableListOf<String>()
        if (input.ownerId.isBlank()) reasons += "ownerId must not be blank"
        if (input.epoch < 0) reasons += "epoch must be >= 0"
        if (input.photos.size !in ProtocolV1Limits.PHOTO_COUNT_MIN..ProtocolV1Limits.PHOTO_COUNT_MAX) {
            reasons += "photos.size must be 3..5, got ${input.photos.size}"
        }
        input.photos.forEachIndexed { index, photo ->
            if (photo.ordinal != index) reasons += "photos[$index].ordinal must equal $index, got ${photo.ordinal}"
            if (photo.contentId.isBlank()) reasons += "photos[$index].contentId must not be blank"
            if (photo.widthPx <= 0 || photo.heightPx <= 0) {
                reasons += "photos[$index] dimensions must be positive, got ${photo.widthPx}x${photo.heightPx}"
            }
            if (!photo.contentHash.matches(Regex("[0-9a-f]{64}"))) {
                reasons += "photos[$index].contentHash must be 64 lowercase hex chars"
            }
        }
        if (input.photos.map { it.contentId }.toSet().size != input.photos.size) {
            reasons += "photos.contentId must be unique"
        }
        if (input.photos.map { it.contentHash }.toSet().size != input.photos.size) {
            reasons += "photos.contentHash must be unique per original"
        }
        val noteBytes = input.note?.toByteArray(UTF8)?.size ?: 0
        if (noteBytes > ProtocolV1Limits.NOTE_MAX_UTF8_BYTES) {
            reasons += "note must be <= ${ProtocolV1Limits.NOTE_MAX_UTF8_BYTES} UTF-8 bytes, got $noteBytes"
        }
        val music = input.music
        if (music != null) {
            if (music.trackId.isBlank()) reasons += "music.trackId must not be blank"
            if (music.artworkId != null && music.artworkId.isBlank()) reasons += "music.artworkId must not be blank"
        }
        return if (reasons.isEmpty()) InputValidation.Valid else InputValidation.Invalid(reasons)
    }

    /** Validates a resolved expression against its input and the canvas. */
    fun validateResolved(expression: ResolvedExpression): InputValidation {
        val reasons = (validate(expression.input) as? InputValidation.Invalid)?.reasons.orEmpty().toMutableList()
        if (expression.canvasVersion <= 0) reasons += "canvasVersion must be positive"
        if (expression.grammarId.isBlank()) reasons += "grammarId must not be blank"
        if (expression.branchId.isBlank()) reasons += "branchId must not be blank"
        if (expression.noteTreatment.isBlank()) reasons += "noteTreatment must not be blank"
        val photoIds = expression.input.photos.map { it.contentId }.toSet()
        val placedIds = expression.placements.map { it.contentId }
        if (placedIds != expression.input.photos.map { it.contentId }) {
            reasons += "placements must cover every authored photo exactly once, in authored order"
        }
        expression.placements.forEachIndexed { index, placement ->
            if (placement.contentId !in photoIds) reasons += "placements[$index].contentId is unknown"
            if (placement.width <= 0 || placement.height <= 0) {
                reasons += "placements[$index] size must be positive"
            }
            if (placement.x < 0 || placement.y < 0 ||
                placement.x + placement.width > CANVAS_WIDTH_UNITS ||
                placement.y + placement.height > CANVAS_HEIGHT_UNITS
            ) {
                reasons += "placements[$index] must fit 360x640, got (${placement.x},${placement.y},${placement.width},${placement.height})"
            }
            val crop = placement.crop
            if (crop != null) {
                if (crop.x < 0 || crop.y < 0 || crop.width <= 0 || crop.height <= 0 ||
                    crop.x + crop.width > CROP_UNIT_MAX || crop.y + crop.height > CROP_UNIT_MAX
                ) {
                    reasons += "placements[$index].crop must fit 0..1000"
                }
            }
            if (placement.maskId != null && placement.maskId.isBlank()) {
                reasons += "placements[$index].maskId must not be blank"
            }
        }
        return if (reasons.isEmpty()) InputValidation.Valid else InputValidation.Invalid(reasons)
    }

    /** Late callbacks from older content/owner epochs are rejected. */
    fun rejectsLateCallback(frozenAtEpoch: Long, callerEpoch: Long): Boolean = callerEpoch < frozenAtEpoch

    /** Every listed change invalidates except [ExpressionChange.ShellOnlyChange]. */
    fun ResolvedExpression.invalidatedBy(change: ExpressionChange): Boolean =
        change != ExpressionChange.ShellOnlyChange

    /** Deterministic canonical bytes of an input (field order fixed). */
    fun canonicalBytes(input: GeneratorInput): ByteArray {
        val out = CanonicalWriter()
        out.putAscii(MAGIC)
        out.putInt(CONTRACT_VERSION)
        out.writeInputBody(input)
        return out.toByteArray()
    }

    /** Deterministic canonical bytes of a resolved expression. */
    fun canonicalBytes(expression: ResolvedExpression): ByteArray {
        val out = CanonicalWriter()
        out.putAscii(MAGIC)
        out.putInt(CONTRACT_VERSION)
        out.putInt(expression.canvasVersion)
        out.putString(expression.grammarId)
        out.putInt(expression.grammarVersion)
        out.putString(expression.branchId)
        out.writeInputBody(expression.input)
        out.putInt(expression.placements.size)
        for (placement in expression.placements) {
            out.putString(placement.contentId)
            out.putInt(placement.x)
            out.putInt(placement.y)
            out.putInt(placement.width)
            out.putInt(placement.height)
            val crop = placement.crop
            if (crop == null) {
                out.putByte(0)
            } else {
                out.putByte(1)
                out.putInt(crop.x)
                out.putInt(crop.y)
                out.putInt(crop.width)
                out.putInt(crop.height)
            }
            val mask = placement.maskId
            if (mask == null) {
                out.putByte(0)
            } else {
                out.putByte(1)
                out.putString(mask)
            }
        }
        out.putString(expression.noteTreatment)
        out.putInt(expression.diagnostics.size)
        for (diagnostic in expression.diagnostics) out.putString(diagnostic)
        out.putInt(expression.fontVersion)
        out.putInt(expression.paletteVersion)
        return out.toByteArray()
    }

    /** Lowercase-hex SHA-256 over [canonicalBytes] of an input. */
    fun canonicalHash(input: GeneratorInput): String = sha256Hex(canonicalBytes(input))

    /** Lowercase-hex SHA-256 over [canonicalBytes] of an expression. */
    fun canonicalHash(expression: ResolvedExpression): String = sha256Hex(canonicalBytes(expression))

    /** Freezes a validated expression; callers must check validity first. */
    fun freeze(expression: ResolvedExpression, contentRevision: Long, frozenAtEpoch: Long): FrozenSnapshot {
        require(contentRevision >= 0) { "contentRevision must be >= 0" }
        require(frozenAtEpoch >= 0) { "frozenAtEpoch must be >= 0" }
        return FrozenSnapshot(
            expression = expression,
            expressionHash = canonicalHash(expression),
            contentRevision = contentRevision,
            frozenAtEpoch = frozenAtEpoch,
        )
    }

    internal fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private class CanonicalWriter {
        private val parts = mutableListOf<ByteArray>()

        fun writeInputBody(input: GeneratorInput) {
            putString(input.ownerId)
            putLong(input.epoch)
            putInt(input.photos.size)
            for (photo in input.photos) {
                putString(photo.contentId)
                putInt(photo.ordinal)
                putInt(photo.widthPx)
                putInt(photo.heightPx)
                putString(photo.contentHash)
            }
            val note = input.note
            if (note == null) {
                putByte(0)
            } else {
                putByte(1)
                putString(note)
            }
            val music = input.music
            if (music == null) {
                putByte(0)
            } else {
                putByte(1)
                putString(music.trackId)
                val artwork = music.artworkId
                if (artwork == null) {
                    putByte(0)
                } else {
                    putByte(1)
                    putString(artwork)
                }
            }
        }

        fun putAscii(value: String) {
            parts += value.toByteArray(Charsets.US_ASCII)
        }

        fun putByte(value: Int) {
            parts += byteArrayOf(value.toByte())
        }

        fun putInt(value: Int) {
            parts += ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
        }

        fun putLong(value: Long) {
            parts += ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
        }

        fun putString(value: String) {
            val bytes = value.toByteArray(UTF8)
            putInt(bytes.size)
            parts += bytes
        }

        fun toByteArray(): ByteArray {
            val total = parts.sumOf { it.size }
            val buffer = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            for (part in parts) buffer.put(part)
            return buffer.array()
        }
    }
}
