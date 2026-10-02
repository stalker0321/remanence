package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import kotlin.math.floor

/**
 * T151 stage 5 — plan → **expected V4 expression** adapter (producer lane).
 *
 * The accepted V4 `ResolvedExpression` fields are owned by T154
 * (`tasks/T154/interface.md`: `provenance: List<ProvenanceMark>?`,
 * `decor: List<SceneDecor>?`, `Placement.shape: PhotoShape`,
 * all-nine `MusicRegion.mode`). That commit is not yet available in this
 * worktree, so this task-local payload carries the **exact values** those fields
 * will hold (grammar index, branch, per-slot placement shape, note/music mode,
 * the 24-family brand/sender marks and decor geometry) without declaring a
 * competing sealed schema or touching codec/renderer.
 *
 * When the accepted T154 commit lands, a thin mapping from [ExpectedV4Expression]
 * to `GeneratorExpression.ResolvedExpression` is the only integration step; no
 * geometry/provenance/decor value is recomputed here.
 *
 * Honesty: this is the producer's expected V4 payload. It is **not** a sealed
 * expression and does not claim the V4 features are mounted in the app.
 */
internal object AllFamilyV4Adapter {

    const val EXPRESSION_CONTRACT_V4 = 4

    data class ExpectedV4Region(val x: Int, val y: Int, val width: Int, val height: Int)

    data class ExpectedV4Placement(
        val contentId: String,
        val region: ExpectedV4Region,
        /** `rect` | `round` | `ellipse` — the T154 `PhotoShape` value. */
        val shape: String,
        val crop: GeneratorExpression.CropWindow,
    )

    data class ExpectedV4Music(
        /** One of the nine frozen modes (`text`…`pulse`). */
        val mode: String,
        val region: ExpectedV4Region,
    )

    data class ExpectedV4Mark(
        /** `BRAND` | `SENDER` — the T154 `ProvenanceKind` value. */
        val kind: String,
        val text: String,
        val region: ExpectedV4Region,
        val size: Double,
        val weight: Int,
        val italic: Boolean,
        val orientation: String,
        val align: String,
        val font: String,
    )

    data class ExpectedV4Decor(
        /** `carrier` | `acid-ribbon` | `print-plane` — the T154 `DecorKind` value. */
        val kind: String,
        val region: ExpectedV4Region,
    )

    data class ExpectedV4Expression(
        val grammarId: Int,
        val branchId: String,
        val expressionContractVersion: Int,
        val placements: List<ExpectedV4Placement>,
        val noteRegion: ExpectedV4Region?,
        val musicRegion: ExpectedV4Music?,
        val provenance: List<ExpectedV4Mark>,
        val decor: List<ExpectedV4Decor>,
    )

    /**
     * Map one mounted [AllFamilyCandidate] to the expected V4 payload.
     * [sender] is the verified handle; blank ⇒ sender marks are absent (they were
     * already filtered by the producer). Brand text is the literal `Remanence`.
     */
    fun expected(candidate: AllFamilyCandidate, sender: String): ExpectedV4Expression {
        val handle = sender.trim()
        val placements = candidate.placements.map { placement ->
            ExpectedV4Placement(
                contentId = placement.contentId,
                region = ExpectedV4Region(placement.x, placement.y, placement.width, placement.height),
                shape = placement.shape,
                crop = placement.crop,
            )
        }
        val note = candidate.plan.note
        val music = candidate.plan.music
        return ExpectedV4Expression(
            grammarId = candidate.grammarId,
            branchId = candidate.branchId,
            expressionContractVersion = EXPRESSION_CONTRACT_V4,
            placements = placements,
            noteRegion = note?.let { region(it.rect) },
            musicRegion = music?.let {
                ExpectedV4Music(mode = it.mode, region = region(it.rect))
            },
            provenance = candidate.plan.provenance.map { mark ->
                ExpectedV4Mark(
                    kind = if (mark.role == "sender") "SENDER" else "BRAND",
                    text = if (mark.role == "sender") "From $handle" else "Remanence",
                    region = region(mark.rect),
                    size = mark.size,
                    weight = mark.weight,
                    italic = mark.italic,
                    orientation = mark.orientation,
                    align = mark.align,
                    font = mark.font,
                )
            },
            decor = candidate.plan.decor.map { ExpectedV4Decor(it.kind, region(it.rect)) },
        )
    }

    private fun region(rect: FrozenGrammarPlans.Rect) =
        ExpectedV4Region(floor(rect.x).toInt(), floor(rect.y).toInt(), floor(rect.w).toInt(), floor(rect.h).toInt())
}
