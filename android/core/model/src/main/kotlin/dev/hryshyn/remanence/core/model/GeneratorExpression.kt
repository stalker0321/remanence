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
 * Canonical serialization v1 (expressionContractVersion == 1; all integers
 * big-endian, all strings as int32-BE length + UTF-8 bytes, fields in
 * declaration order):
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
 * The v1 path is frozen: its bytes, hashes, and goldens are BYTE-IDENTICAL
 * before and after the v2 addition. Canonical serialization v2
 * (expressionContractVersion == 2) is framed by the distinct magic
 * `"GENEX02"` (a v1 parser matching `"GENEX01"` rejects v2 bytes before it
 * could misread the version marker as `canvasVersion = 2`), followed by the
 * unchanged global CONTRACT_VERSION, then `expressionContractVersion:i32`,
 * then the v1 fields in v1 order except each placement gains a trailing
 * `contentRectPresent:byte then x:y:w:h:i32×4 iff present`, and a trailing
 * `noteRegionPresent:byte then x:y:w:h:i32×4 iff present` after
 * paletteVersion. v1 decoders reject any non-null new field; any version
 * other than 1, 2, or 3 fails closed in [validateResolved].
 * `canonicalHash()` is lowercase-hex SHA-256 over exactly these bytes.
 *
 * Canonical serialization v3 (expressionContractVersion == 3; finished
 * collage, V5 freeze 2026-09-25) is framed by the distinct magic
 * `"GENEX03"`, followed by the unchanged global CONTRACT_VERSION, then
 * `expressionContractVersion:i32 = 3`, then the v1 fields in v1 order
 * with v2-compatible per-placement `crop` / `maskId` / `contentRect`
 * slots, then the v3 global trailer (style, note type, optional music
 * region) before `noteTreatment`. The music slot nests the artwork
 * descriptor between mode and type:
 * `music present:byte [x y w h:i32×4 mode:str artwork present:byte
 * [provider:str url:str sha256:32 mime:str w:i32 h:i32]
 * type{family:str size:i32 weight:i32 italic:byte line:i32}]`;
 * no-art seals as mode `"text"` with artwork present:byte 0, music
 * absent as music present:byte 0. There is deliberately NO noteRegion
 * slot in GENEX03: the receiver-recomputable projection (`BEXPR02`)
 * binds it instead. Version axes (never confused):
 * contract 1 (GENEX01) and contract 2 (GENEX02) map to artifact
 * `expression_version` 1; contract 3 (GENEX03) maps to artifact
 * `expression_version` 2 and projection `BEXPR02`.
 */
object GeneratorExpression {

    /** Canonical contract version pinned by every hash in this file. */
    const val CONTRACT_VERSION = 1

    /**
     * Expression layout version: legacy frozen layout (v1 canonical bytes).
     * The default of every [ResolvedExpression]; existing constructors are
     * unaffected.
     */
    const val EXPRESSION_CONTRACT_V1 = 1

    /**
     * Expression layout version: BER1 noteRegion + per-placement contentRect
     * layout (ADR-017). Opt-in per expression; never the default.
     */
    const val EXPRESSION_CONTRACT_V2 = 2

    /**
     * Expression layout version: finished collage (V5 freeze 2026-09-25).
     * Opt-in per expression; never the default. v1 and v2 bytes, hashes,
     * and goldens stay byte-identical.
     */
    const val EXPRESSION_CONTRACT_V3 = 3

    /** Reference canvas units (arch §2, provisional 9:16 360×640). */
    const val CANVAS_WIDTH_UNITS = 360
    const val CANVAS_HEIGHT_UNITS = 640

    /** Normalized crop window units (0..1000 per axis) for v1/v2. */
    const val CROP_UNIT_MAX = 1000

    /**
     * Quantized crop window units (0..10000 per axis) for v3. The v1/v2
     * 0..1000 bound is frozen; 1000 is too coarse for the V5 envelope-50
     * policy, so v3 validates against this bound instead.
     */
    const val CROP_UNIT_MAX_V3 = 10000

    private const val MAGIC = "GENEX01"

    /**
     * v2 frame discriminator (ADR-017 Stage1-B): a v1 parser matching on
     * magic rejects v2 bytes before it can misread the version marker as
     * `canvasVersion = 2`. No v2 bytes exist on any wire, so this marker
     * is still cheap to own.
     */
    private const val MAGIC_V2 = "GENEX02"

    /**
     * v3 frame discriminator (finished collage): a v1/v2 parser matching on
     * its own magic rejects v3 bytes before misreading the version marker.
     * No v3 bytes exist on any wire, so this marker is still cheap to own.
     */
    private const val MAGIC_V3 = "GENEX03"
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

    /**
     * Frozen note band in canvas units (v2 only, ADR-017 BER1 noteRegion).
     * Null iff the note is absent or empty.
     */
    data class NoteRegion(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Letterboxed content rect inside its placement cell, in canvas units
     * (v2 only). Unlike [CropWindow], coordinates are canvas-absolute, not
     * normalized source units.
     */
    data class ContentRect(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Finished-collage style binding (v3 only): the resolved style id and
     * version plus the STYD01 digest (exactly 32 raw bytes) of the bundled
     * style record both phones ship. Custom equality: digest compares by
     * content, so equal bindings always hash equally.
     */
    data class StyleBinding(
        val styleId: String,
        val styleVersion: String,
        val digest: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is StyleBinding &&
                styleId == other.styleId &&
                styleVersion == other.styleVersion &&
                digest.contentEquals(other.digest)

        override fun hashCode(): Int {
            var result = styleId.hashCode()
            result = 31 * result + styleVersion.hashCode()
            result = 31 * result + digest.contentHashCode()
            return result
        }
    }

    /**
     * Frozen resolved type (v3 only): family, canvas-px size, weight, italic,
     * and frozen line-height px. Receivers must never re-derive the line
     * height (reference: `ceil(size * 1.12)`); they render these integers.
     */
    data class TextType(
        val family: String,
        val size: Int,
        val weight: Int,
        val italic: Boolean,
        val line: Int,
    )

    /**
     * Music rectangle inside the canvas (v3 only) with its resolved type
     * nested alongside the geometry, so neither can change silently. The
     * optional artwork descriptor rides nested after the mode and before
     * the type: `mode == "thumbnail"` iff artwork is present (rule A1);
     * no-art always seals as mode `"text"` with artwork absent.
     */
    data class MusicRegion(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val mode: String,
        val type: TextType,
        val artwork: ArtworkRef? = null,
    )

    /**
     * Cover-art descriptor (v3 only, nested in [MusicRegion]): provider key,
     * stable fetch URL, digest of the exact bytes, media type, and pixel
     * size. The descriptor travels inside the sealed expression so both
     * endpoints agree on what was fetched; the artwork BYTES travel
     * out-of-band (client <-> provider) only and never enter identity
     * bytes, storage, or logs. Custom equality: sha256 compares by content.
     */
    data class ArtworkRef(
        val provider: String,
        val url: String,
        val sha256: ByteArray,
        val mimeType: String,
        val width: Int,
        val height: Int,
    ) {
        override fun equals(other: Any?): Boolean =
            other is ArtworkRef &&
                provider == other.provider &&
                url == other.url &&
                sha256.contentEquals(other.sha256) &&
                mimeType == other.mimeType &&
                width == other.width &&
                height == other.height

        override fun hashCode(): Int {
            var result = provider.hashCode()
            result = 31 * result + url.hashCode()
            result = 31 * result + sha256.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + width
            result = 31 * result + height
            return result
        }
    }

    /** Type families the bundled font registry can render. */
    val TEXT_FAMILIES: Set<String> = setOf("StudySans", "StudySerif", "StudyMono", "Narrow", "Gothic")

    /** Music treatments a sealed collage may carry. */
    val MUSIC_MODES: Set<String> = setOf("text", "thumbnail")

    /** Cover-art media types a sealed descriptor may name. */
    val ARTWORK_MIME_TYPES: Set<String> = setOf("image/jpeg", "image/png", "image/webp")

    /**
     * Allowlisted artwork provider keys. Seeded with the S1 sample-catalog
     * key used by fixtures; the S1 catalog slice owns extending it. It must
     * never contain a backend host: cover bytes are fetched client <->
     * provider only. Host-allowlist enforcement for the fetcher (one fetch
     * host per descriptor) belongs to that later slice, not to seal-time
     * validation.
     */
    val ARTWORK_PROVIDERS: Set<String> = setOf("catalog-sample")

    /** Maximum artwork URL length in chars (rule A4). */
    const val ARTWORK_URL_MAX = 512

    /** Maximum artwork provider-key length in chars (rule A3). */
    const val ARTWORK_PROVIDER_MAX = 32

    /** Maximum artwork pixel dimension (rule A7). */
    const val ARTWORK_DIM_MAX = 4096

    /** Exact artwork digest length in bytes: SHA-256 (rule A5). */
    const val ARTWORK_SHA256_BYTES = 32

    /** Resolved placement of one authored photo on the reference canvas. */
    data class Placement(
        val contentId: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val crop: CropWindow?,
        val maskId: String?,
        /** v2/v3; v1 expressions must leave this null. */
        val contentRect: ContentRect? = null,
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
        /**
         * Expression layout version ([EXPRESSION_CONTRACT_V1] default).
         * Trailing default: every existing constructor call compiles
         * unchanged and keeps legacy v1 bytes/hashes.
         */
        val expressionContractVersion: Int = EXPRESSION_CONTRACT_V1,
        /** v2 only; v1 and v3 expressions leave this to their own rules. */
        val noteRegion: NoteRegion? = null,
        /** v3 only; v1/v2 expressions must leave this null. */
        val style: StyleBinding? = null,
        /** v3 only; v1/v2 expressions must leave this null. */
        val noteType: TextType? = null,
        /** v3 only; v1/v2 expressions must leave this null. */
        val musicRegion: MusicRegion? = null,
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

    /**
     * Rejects picker URIs / platform handles in authored content ids. Content
     * ids are opaque tokens only; no `content:`/`file:`/`android.resource:`
     * scheme, no `://`, no absolute path may ever enter G1 identity or the
     * BEXPR01 projection.
     */
    fun looksLikeUriOrHandle(value: String): Boolean =
        value.contains("://") ||
            value.startsWith("content:") ||
            value.startsWith("file:") ||
            value.startsWith("android.resource:") ||
            value.startsWith("/")

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
            if (looksLikeUriOrHandle(photo.contentId)) {
                reasons += "photos[$index].contentId must be an opaque id, not a URI/handle"
            }
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
        val version = expression.expressionContractVersion
        if (version != EXPRESSION_CONTRACT_V1 &&
            version != EXPRESSION_CONTRACT_V2 &&
            version != EXPRESSION_CONTRACT_V3
        ) {
            reasons += "expressionContractVersion must be 1, 2, or 3, got $version"
        }
        if (version == EXPRESSION_CONTRACT_V1) {
            if (expression.noteRegion != null) reasons += "v1 expression must not carry noteRegion"
            if (expression.style != null) reasons += "v1 expression must not carry style"
            if (expression.noteType != null) reasons += "v1 expression must not carry noteType"
            if (expression.musicRegion != null) reasons += "v1 expression must not carry musicRegion"
        }
        if (version == EXPRESSION_CONTRACT_V3) {
            val style = expression.style
            if (style == null) {
                reasons += "v3 requires style"
            } else {
                reasons += validateStyleBinding(style)
            }
            val noteType = expression.noteType
            if (noteType == null) {
                reasons += "v3 requires noteType"
            } else {
                reasons += validateTextType(noteType, "noteType")
            }
            // GENEX03 carries no noteRegion slot (the BEXPR02 projection
            // binds it instead), so v3 only requires a present region to
            // fit the canvas; presence is never tied to the note here.
            val v3region = expression.noteRegion
            if (v3region != null &&
                (v3region.x < 0 || v3region.y < 0 || v3region.width <= 0 || v3region.height <= 0 ||
                    v3region.x.toLong() + v3region.width > CANVAS_WIDTH_UNITS ||
                    v3region.y.toLong() + v3region.height > CANVAS_HEIGHT_UNITS)
            ) {
                reasons += "v3 noteRegion must fit 360x640 with positive size"
            }
            val musicRegion = expression.musicRegion
            if (musicRegion != null) {
                if (musicRegion.width <= 0 || musicRegion.height <= 0 ||
                    musicRegion.x < 0 || musicRegion.y < 0 ||
                    musicRegion.x.toLong() + musicRegion.width > CANVAS_WIDTH_UNITS ||
                    musicRegion.y.toLong() + musicRegion.height > CANVAS_HEIGHT_UNITS
                ) {
                    reasons += "v3 musicRegion must fit 360x640 with positive size"
                }
                if (musicRegion.mode !in MUSIC_MODES) {
                    reasons += "v3 musicRegion.mode must be text or thumbnail, got ${musicRegion.mode}"
                }
                reasons += validateTextType(musicRegion.type, "musicRegion.type")
                // A1: thumbnail iff artwork. A thumbnail without bytes cannot
                // render (placeholders forbidden); artwork under mode "text"
                // is dead weight inside a sealed artifact.
                if ((musicRegion.mode == "thumbnail") != (musicRegion.artwork != null)) {
                    reasons += "v3 musicRegion requires mode thumbnail iff artwork is present"
                }
                val artwork = musicRegion.artwork
                if (artwork != null) {
                    reasons += validateArtworkRef(artwork)
                }
            }
            // A8: single source of truth for v3. The input music reference
            // must not carry an artwork id alongside the expression
            // descriptor; the descriptor wins and the input byte then
            // deterministically emits artwork-absent for v3.
            if (expression.input.music?.artworkId != null) {
                reasons += "v3 input.music.artworkId must be null; the expression artwork descriptor wins"
            }
        }
        if (version == EXPRESSION_CONTRACT_V2) {
            if (expression.style != null) reasons += "v2 expression must not carry style"
            if (expression.noteType != null) reasons += "v2 expression must not carry noteType"
            if (expression.musicRegion != null) reasons += "v2 expression must not carry musicRegion"
            val note = expression.input.note
            val region = expression.noteRegion
            if (note.isNullOrEmpty()) {
                if (region != null) reasons += "v2 noteRegion must be absent when note is absent or empty"
            } else if (region == null) {
                reasons += "v2 requires noteRegion for non-empty note"
            } else if (region.x < 0 || region.y < 0 || region.width <= 0 || region.height <= 0 ||
                region.x.toLong() + region.width > CANVAS_WIDTH_UNITS ||
                region.y.toLong() + region.height > CANVAS_HEIGHT_UNITS
            ) {
                reasons += "v2 noteRegion must fit 360x640 with positive size"
            }
        }
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
                placement.x.toLong() + placement.width > CANVAS_WIDTH_UNITS ||
                placement.y.toLong() + placement.height > CANVAS_HEIGHT_UNITS
            ) {
                reasons += "placements[$index] must fit 360x640, got (${placement.x},${placement.y},${placement.width},${placement.height})"
            }
            val crop = placement.crop
            // The crop unit rides the contract: v1/v2 stay on the frozen
            // 0..1000 bound; v3 quantizes the V5 envelope-50 policy at
            // 0..10000 and requires a crop on every placement.
            val cropBound = if (version == EXPRESSION_CONTRACT_V3) CROP_UNIT_MAX_V3 else CROP_UNIT_MAX
            if (version == EXPRESSION_CONTRACT_V3 && crop == null) {
                reasons += "placements[$index] v3 requires crop"
            }
            if (crop != null) {
                if (crop.x < 0 || crop.y < 0 || crop.width <= 0 || crop.height <= 0 ||
                    crop.x.toLong() + crop.width > cropBound ||
                    crop.y.toLong() + crop.height > cropBound
                ) {
                    reasons += if (version == EXPRESSION_CONTRACT_V3) {
                        "placements[$index].crop must fit 0..10000"
                    } else {
                        "placements[$index].crop must fit 0..1000"
                    }
                }
            }
            if (placement.maskId != null && placement.maskId.isBlank()) {
                reasons += "placements[$index].maskId must not be blank"
            }
            if (version == EXPRESSION_CONTRACT_V1 && placement.contentRect != null) {
                reasons += "placements[$index] v1 must not carry contentRect"
            }
            if (version == EXPRESSION_CONTRACT_V2) {
                val rect = placement.contentRect
                if (rect == null) {
                    reasons += "placements[$index] v2 requires contentRect"
                } else {
                    reasons += checkContentRectInside(rect, placement, index, "v2")
                }
            }
            if (version == EXPRESSION_CONTRACT_V3) {
                // Unlike v2, v3 placements use the crop window — not a
                // second letterbox — so the rect stays optional here.
                val rect = placement.contentRect
                if (rect != null) {
                    reasons += checkContentRectInside(rect, placement, index, "v3")
                }
            }
        }
        return if (reasons.isEmpty()) InputValidation.Valid else InputValidation.Invalid(reasons)
    }

    /** Structural checks for a v3 style binding (STYD01 digest: 32 raw bytes). */
    private fun validateStyleBinding(style: StyleBinding): List<String> {
        val reasons = mutableListOf<String>()
        if (style.styleId.length !in 1..64 || !style.styleId.matches(Regex("[a-z0-9-]+"))) {
            reasons += "style.styleId must be 1..64 [a-z0-9-], got ${style.styleId}"
        }
        if (style.styleVersion.length !in 1..32) {
            reasons += "style.styleVersion must be 1..32 chars, got ${style.styleVersion}"
        }
        if (style.digest.size != 32) {
            reasons += "style.digest must be exactly 32 bytes, got ${style.digest.size}"
        }
        return reasons
    }

    /** Structural checks for a v3 frozen resolved type. */
    private fun validateTextType(type: TextType, prefix: String): List<String> {
        val reasons = mutableListOf<String>()
        if (type.family !in TEXT_FAMILIES) {
            reasons += "$prefix.family must be one of $TEXT_FAMILIES, got ${type.family}"
        }
        if (type.size !in 8..96) reasons += "$prefix.size must be 8..96, got ${type.size}"
        if (type.weight != 400 && type.weight != 700) {
            reasons += "$prefix.weight must be 400 or 700, got ${type.weight}"
        }
        if (type.italic && (type.family != "StudySerif" || type.weight != 400)) {
            reasons += "$prefix.italic is only available as StudySerif @400"
        }
        if (type.line !in 8..120) reasons += "$prefix.line must be 8..120, got ${type.line}"
        return reasons
    }

    /**
     * Structural checks for a v3 cover-art descriptor (rules A2–A7):
     * all-or-nothing population, allowlisted provider key, stable
     * https portrait without userinfo/query/fragment/non-443 port,
     * 32-byte digest, allowlisted mime, bounded pixel size.
     */
    private fun validateArtworkRef(artwork: ArtworkRef): List<String> {
        val reasons = mutableListOf<String>()
        if (artwork.provider.length !in 1..ARTWORK_PROVIDER_MAX ||
            !artwork.provider.matches(Regex("[a-z0-9-]+"))
        ) {
            reasons += "artwork.provider must be 1..32 [a-z0-9-], got ${artwork.provider}"
        } else if (artwork.provider !in ARTWORK_PROVIDERS) {
            reasons += "artwork.provider must be allowlisted, got ${artwork.provider}"
        }
        reasons += validateArtworkUrl(artwork.url)
        if (artwork.sha256.size != ARTWORK_SHA256_BYTES) {
            reasons += "artwork.sha256 must be exactly 32 bytes, got ${artwork.sha256.size}"
        }
        if (artwork.mimeType !in ARTWORK_MIME_TYPES) {
            reasons += "artwork.mimeType must be one of $ARTWORK_MIME_TYPES, got ${artwork.mimeType}"
        }
        if (artwork.width !in 1..ARTWORK_DIM_MAX || artwork.height !in 1..ARTWORK_DIM_MAX) {
            reasons += "artwork dimensions must be 1..4096, got ${artwork.width}x${artwork.height}"
        }
        return reasons
    }

    /**
     * Rule A4: absolute https URL with no userinfo, no query, no fragment,
     * and port absent or exactly 443. Signed/expiring provider URLs carry
     * query parameters, so they are rejected by construction; the catalog
     * must supply stable cover URLs.
     */
    private fun validateArtworkUrl(url: String): List<String> {
        val reasons = mutableListOf<String>()
        if (url.length !in 1..ARTWORK_URL_MAX) {
            reasons += "artwork.url must be 1..512 chars, got length ${url.length}"
        }
        if (!url.startsWith("https://")) {
            reasons += "artwork.url must be absolute https, got $url"
            return reasons
        }
        val rest = url.removePrefix("https://")
        val authority = rest.substringBefore('/')
        if (authority.isEmpty()) {
            reasons += "artwork.url must have a host, got $url"
            return reasons
        }
        if ('@' in authority) {
            reasons += "artwork.url must not carry userinfo, got $url"
        }
        if ('?' in url) {
            reasons += "artwork.url must not carry a query, got $url"
        }
        if ('#' in url) {
            reasons += "artwork.url must not carry a fragment, got $url"
        }
        if (':' in authority) {
            val port = authority.substringAfterLast(':')
            val host = authority.substringBeforeLast(':')
            if (host.isEmpty() || port != "443") {
                reasons += "artwork.url port must be absent or 443, got $url"
            }
        }
        return reasons
    }

    /** A content rect must be positive and inside its own placement cell. */
    private fun checkContentRectInside(rect: ContentRect, placement: Placement, index: Int, tag: String): List<String> =
        if (rect.width <= 0 || rect.height <= 0 ||
            rect.x < placement.x || rect.y < placement.y ||
            rect.x.toLong() + rect.width > placement.x.toLong() + placement.width ||
            rect.y.toLong() + rect.height > placement.y.toLong() + placement.height
        ) {
            listOf("placements[$index] $tag contentRect must be positive and inside its placement")
        } else {
            emptyList()
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

    /**
     * Deterministic canonical bytes of a resolved expression, dispatched on
     * [ResolvedExpression.expressionContractVersion]. Fail-closed: invalid
     * expressions (including v1/v2 values carrying v3-only fields, and any
     * unsupported version) throw instead of aliasing a legal encoding. The
     * v1 path is frozen byte-identical; v2 uses the `GENEX02` frame with the
     * trailing contentRects and noteRegion per the format KDoc above; v3
     * uses the `GENEX03` frame with per-placement crops and the global
     * style / note-type / music trailer.
     */
    fun canonicalBytes(expression: ResolvedExpression): ByteArray {
        when (val valid = validateResolved(expression)) {
            is InputValidation.Valid -> Unit
            is InputValidation.Invalid ->
                throw IllegalArgumentException(
                    "cannot encode invalid expression: ${valid.reasons.joinToString("; ")}",
                )
        }
        return when (expression.expressionContractVersion) {
            EXPRESSION_CONTRACT_V1 -> legacyExpressionBytes(expression)
            EXPRESSION_CONTRACT_V2 -> v2ExpressionBytes(expression)
            EXPRESSION_CONTRACT_V3 -> v3ExpressionBytes(expression)
            else -> throw IllegalArgumentException(
                "unsupported expressionContractVersion: ${expression.expressionContractVersion}",
            )
        }
    }

    /** Frozen v1 expression encoding (see format KDoc; do not modify). */
    private fun legacyExpressionBytes(expression: ResolvedExpression): ByteArray {
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

    /** v2 expression encoding (see format KDoc; v1 field order preserved). */
    private fun v2ExpressionBytes(expression: ResolvedExpression): ByteArray {
        val out = CanonicalWriter()
        out.putAscii(MAGIC_V2)
        out.putInt(CONTRACT_VERSION)
        out.putInt(expression.expressionContractVersion)
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
            val rect = placement.contentRect
            if (rect == null) {
                out.putByte(0)
            } else {
                out.putByte(1)
                out.putInt(rect.x)
                out.putInt(rect.y)
                out.putInt(rect.width)
                out.putInt(rect.height)
            }
        }
        out.putString(expression.noteTreatment)
        out.putInt(expression.diagnostics.size)
        for (diagnostic in expression.diagnostics) out.putString(diagnostic)
        out.putInt(expression.fontVersion)
        out.putInt(expression.paletteVersion)
        val region = expression.noteRegion
        if (region == null) {
            out.putByte(0)
        } else {
            out.putByte(1)
            out.putInt(region.x)
            out.putInt(region.y)
            out.putInt(region.width)
            out.putInt(region.height)
        }
        return out.toByteArray()
    }

    /**
     * v3 expression encoding (see format KDoc). v1 field order is preserved
     * through the placements; the v3 global trailer (style, note type,
     * optional music region) is written once after the placement loop and
     * before `noteTreatment`. Validation guarantees the v3-required fields
     * are present. The style digest rides as 32 raw bytes (no length
     * prefix); italic rides as one byte.
     */
    private fun v3ExpressionBytes(expression: ResolvedExpression): ByteArray {
        val out = CanonicalWriter()
        out.putAscii(MAGIC_V3)
        out.putInt(CONTRACT_VERSION)
        out.putInt(expression.expressionContractVersion)
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
            val rect = placement.contentRect
            if (rect == null) {
                out.putByte(0)
            } else {
                out.putByte(1)
                out.putInt(rect.x)
                out.putInt(rect.y)
                out.putInt(rect.width)
                out.putInt(rect.height)
            }
        }
        val style = requireNotNull(expression.style) { "v3 requires style" }
        out.putString(style.styleId)
        out.putString(style.styleVersion)
        require(style.digest.size == 32) { "v3 style.digest must be exactly 32 bytes" }
        out.putRaw(style.digest)
        val noteType = requireNotNull(expression.noteType) { "v3 requires noteType" }
        out.writeTextType(noteType)
        val music = expression.musicRegion
        if (music == null) {
            out.putByte(0)
        } else {
            out.putByte(1)
            out.putInt(music.x)
            out.putInt(music.y)
            out.putInt(music.width)
            out.putInt(music.height)
            out.putString(music.mode)
            out.writeArtwork(music.artwork)
            out.writeTextType(music.type)
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

    /** Freezes a validated expression; invalid expressions throw, never hash. */
    fun freeze(expression: ResolvedExpression, contentRevision: Long, frozenAtEpoch: Long): FrozenSnapshot {
        require(contentRevision >= 0) { "contentRevision must be >= 0" }
        require(frozenAtEpoch >= 0) { "frozenAtEpoch must be >= 0" }
        when (val valid = validateResolved(expression)) {
            is InputValidation.Valid -> Unit
            is InputValidation.Invalid ->
                throw IllegalArgumentException(
                    "cannot freeze invalid expression: ${valid.reasons.joinToString("; ")}",
                )
        }
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

        /** Raw bytes with no length prefix (v3 style digest: exactly 32). */
        fun putRaw(value: ByteArray) {
            parts += value
        }

        /** Frozen resolved type: family, size, weight, italic byte, line. */
        fun writeTextType(type: TextType) {
            putString(type.family)
            putInt(type.size)
            putInt(type.weight)
            putByte(if (type.italic) 1 else 0)
            putInt(type.line)
        }

        /**
         * Cover-art descriptor nested after the music mode and before the
         * type: presence byte, then provider, url, raw 32-byte digest,
         * mime, and pixel size. No-art is a single 0 byte (always paired
         * with mode "text" by rule A1).
         */
        fun writeArtwork(artwork: ArtworkRef?) {
            if (artwork == null) {
                putByte(0)
                return
            }
            putByte(1)
            putString(artwork.provider)
            putString(artwork.url)
            require(artwork.sha256.size == ARTWORK_SHA256_BYTES) {
                "artwork.sha256 must be exactly 32 bytes"
            }
            putRaw(artwork.sha256)
            putString(artwork.mimeType)
            putInt(artwork.width)
            putInt(artwork.height)
        }

        fun toByteArray(): ByteArray {
            val total = parts.sumOf { it.size }
            val buffer = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            for (part in parts) buffer.put(part)
            return buffer.array()
        }
    }
}
