package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import kotlin.math.ceil
import kotlin.math.floor

/**
 * T151 stage-7 — **actual** plan → sealed V4 `ResolvedExpression` adapter
 * (producer lane).
 *
 * Consumes the accepted T154 V4 schema (cherry-picked exactly at
 * `7034e2e52003636e1f9b9eef65add350fbe0b900`, not copied dirty). It maps one
 * mounted [AllFamilyCandidate] (frozen plan + complete-safe assignment +
 * envelope-50 placements) onto `GeneratorExpression.EXPRESSION_CONTRACT_V4`:
 * authored-order placements with `PhotoShape`, the nine-mode `MusicRegion`,
 * textual provenance with the sealed `line`, and decor. Everything is validated
 * by [GeneratorExpression.validateResolved]; any failure is returned typed, never
 * sealed.
 *
 * No duplicated schema/codec/renderer: this file only constructs the accepted
 * model types. Style selection and the surrounding `GeneratorInput` are supplied
 * by the integration caller (T150/MiMo5 owns UI + gates).
 */
internal object AllFamilyV4Adapter {

    sealed interface Outcome {
        data class Sealed(val expression: GeneratorExpression.ResolvedExpression) : Outcome
        data class Rejected(val reasons: List<String>) : Outcome
    }

    /**
     * Seal one candidate for [input]. [style] must be a bundled style of the
     * candidate's grammar; [artwork] is the verified cover descriptor (null when
     * none). Placements are emitted in **authored order** (`input.photos`), each
     * carrying the geometry + crop of the slot the photo was matched to, so the
     * sealed bytes preserve the complete-safe mapping.
     */
    fun seal(
        candidate: AllFamilyCandidate,
        input: GeneratorExpression.GeneratorInput,
        senderHandle: String,
        style: StyleRegistry.Style,
        artwork: GeneratorExpression.ArtworkRef? = null,
    ): Outcome {
        if (style.grammar != candidate.grammarId) {
            return Outcome.Rejected(listOf("style belongs to another grammar"))
        }
        val slotByContent = HashMap<String, Int>()
        candidate.placements.forEach { slotByContent[it.contentId] = it.slotIndex }
        val placements = ArrayList<GeneratorExpression.Placement>(input.photos.size)
        for (photo in input.photos) {
            val slot = slotByContent[photo.contentId]
                ?: return Outcome.Rejected(
                    listOf("authored photo ${photo.contentId} is not placed by branch ${candidate.branchId}"),
                )
            val mounted = candidate.placements[slot]
            val tile = candidate.plan.tiles[slot]
            placements += GeneratorExpression.Placement(
                contentId = photo.contentId,
                x = mounted.x,
                y = mounted.y,
                width = mounted.width,
                height = mounted.height,
                crop = mounted.crop,
                maskId = null,
                contentRect = null,
                shape = shape(tile.shape),
            )
        }

        val styleDigest = StyleRegistry.digest(style.id)
            ?: return Outcome.Rejected(listOf("style ${style.id} is not bundled"))

        val provenance = ArrayList<GeneratorExpression.ProvenanceMark>(2)
        for (mark in candidate.plan.provenance) {
            val kind = if (mark.role == "sender") {
                GeneratorExpression.ProvenanceKind.SENDER
            } else {
                GeneratorExpression.ProvenanceKind.BRAND
            }
            val text = if (kind == GeneratorExpression.ProvenanceKind.SENDER) {
                GeneratorExpression.senderMarkText(senderHandle) ?: continue
            } else {
                GeneratorExpression.BRAND_TEXT
            }
            val size = mark.size.toInt()
            provenance += GeneratorExpression.ProvenanceMark(
                kind = kind,
                text = text,
                x = floor(mark.rect.x).toInt(),
                y = floor(mark.rect.y).toInt(),
                width = floor(mark.rect.w).toInt(),
                height = floor(mark.rect.h).toInt(),
                family = mark.font,
                size = size,
                weight = mark.weight,
                italic = mark.italic,
                line = if (size == EDITORIAL_BRAND_YIELD_SIZE) EDITORIAL_BRAND_YIELD_LINE else GeneratorExpression.markLine(size),
                orientation = orientation(mark.orientation),
                align = align(mark.align),
            )
        }

        val expression = GeneratorExpression.ResolvedExpression(
            canvasVersion = 1,
            grammarId = "g${candidate.grammarId}",
            grammarVersion = 1,
            branchId = candidate.branchId,
            input = input,
            placements = placements,
            noteTreatment = "regular-region",
            diagnostics = emptyList(),
            fontVersion = FontRegistry.VERSION,
            paletteVersion = CollageRenderer.PALETTE_REGISTRY_VERSION,
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V4,
            noteRegion = candidate.plan.note?.let {
                GeneratorExpression.NoteRegion(
                    floor(it.rect.x).toInt(), floor(it.rect.y).toInt(),
                    floor(it.rect.w).toInt(), floor(it.rect.h).toInt(),
                )
            },
            style = GeneratorExpression.StyleBinding(style.id, style.version, styleDigest),
            noteType = textType(style.note),
            musicRegion = candidate.plan.music?.let { music ->
                GeneratorExpression.MusicRegion(
                    x = floor(music.rect.x).toInt(),
                    y = floor(music.rect.y).toInt(),
                    width = floor(music.rect.w).toInt(),
                    height = floor(music.rect.h).toInt(),
                    mode = music.mode,
                    type = textType(style.music),
                    artwork = if (music.mode in GeneratorExpression.MUSIC_COVER_MODES_V4) artwork else null,
                )
            },
            provenance = provenance.ifEmpty { null },
            decor = candidate.plan.decor
                .map {
                    GeneratorExpression.SceneDecor(
                        kind = decorKind(it.kind),
                        x = floor(it.rect.x).toInt(),
                        y = floor(it.rect.y).toInt(),
                        width = floor(it.rect.w).toInt(),
                        height = floor(it.rect.h).toInt(),
                    )
                }
                .ifEmpty { null },
        )
        return when (val validation = GeneratorExpression.validateResolved(expression)) {
            is GeneratorExpression.InputValidation.Valid -> Outcome.Sealed(expression)
            is GeneratorExpression.InputValidation.Invalid -> Outcome.Rejected(validation.reasons)
        }
    }

    /**
     * Deterministic first bundled style for [grammarId] (sorted by style id);
     * `null` when the grammar has no bundled style. Integration may prefer a
     * different bundled style of the same grammar.
     */
    fun styleForGrammar(grammarId: Int): StyleRegistry.Style? =
        stylesForGrammar(grammarId).firstOrNull()

    /** Every authored colour/type variant; all still pass seal and real prepare. */
    fun stylesForGrammar(grammarId: Int): List<StyleRegistry.Style> =
        StyleRegistry.ids.sorted().mapNotNull { id ->
            StyleRegistry.style(id)?.takeIf { it.grammar == grammarId }
        }

    private fun textType(text: StyleRegistry.TextStyle): GeneratorExpression.TextType =
        GeneratorExpression.TextType(
            family = text.font,
            size = text.size,
            weight = text.weight,
            italic = text.italic,
            line = ceil(text.size * LINE_FACTOR).toInt(),
        )

    private fun shape(value: String): GeneratorExpression.PhotoShape = when (value) {
        "round" -> GeneratorExpression.PhotoShape.ROUND
        "ellipse" -> GeneratorExpression.PhotoShape.ELLIPSE
        else -> GeneratorExpression.PhotoShape.RECT
    }

    private fun decorKind(value: String): GeneratorExpression.DecorKind = when (value) {
        "acid-ribbon" -> GeneratorExpression.DecorKind.ACID_RIBBON
        "print-plane" -> GeneratorExpression.DecorKind.PRINT_PLANE
        else -> GeneratorExpression.DecorKind.CARRIER
    }

    private fun orientation(value: String): GeneratorExpression.MarkOrientation = when (value) {
        "vertical-up" -> GeneratorExpression.MarkOrientation.VERTICAL_UP
        "vertical-down" -> GeneratorExpression.MarkOrientation.VERTICAL_DOWN
        else -> GeneratorExpression.MarkOrientation.HORIZONTAL
    }

    private fun align(value: String): GeneratorExpression.MarkAlign = when (value) {
        "right" -> GeneratorExpression.MarkAlign.RIGHT
        "center" -> GeneratorExpression.MarkAlign.CENTER
        else -> GeneratorExpression.MarkAlign.LEFT
    }

    private const val LINE_FACTOR = 1.12
    private const val EDITORIAL_BRAND_YIELD_SIZE = 64
    private const val EDITORIAL_BRAND_YIELD_LINE = 70
}
