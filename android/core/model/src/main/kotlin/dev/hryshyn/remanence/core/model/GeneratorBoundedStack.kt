package dev.hryshyn.remanence.core.model

import dev.hryshyn.remanence.core.model.GeneratorDiscovery.Candidate
import dev.hryshyn.remanence.core.model.GeneratorDiscovery.GenerationRequest
import dev.hryshyn.remanence.core.model.GeneratorDiscovery.GeneratorProvider
import dev.hryshyn.remanence.core.model.GeneratorDiscovery.ProviderOutcome
import dev.hryshyn.remanence.core.model.GeneratorDiscovery.ProviderRef
import dev.hryshyn.remanence.core.model.GeneratorExpression.GeneratorInput
import dev.hryshyn.remanence.core.model.GeneratorExpression.InputValidation
import dev.hryshyn.remanence.core.model.GeneratorExpression.ResolvedExpression

/**
 * Stage1-A — one conservative, model-only bounded-stack [GeneratorProvider].
 *
 * Scope (strict): given an eligible [GeneratorInput] (3–5 ordered photos,
 * optional note within the existing v1 wire ceiling), resolve exactly one
 * deterministic candidate whose placements tile the G1 reference canvas
 * (360×640) as full-width bands in authored order. Placement/dependency
 * identity is computed exclusively with the existing G1 model
 * ([GeneratorExpression.canonicalHash]); this file invents no serialization,
 * no hash scheme, and no wire field.
 *
 * Smallest proposed contract (PROVISIONAL — not approved production policy):
 * no production grammar/branch id exists in the tree (only test values such
 * as `"g-hold"`/`"b-rows"`/`"fake-b"`), and the G2 boundary explicitly does
 * not promote the Stage-10 research grammars. Until product approves a
 * layout/branch, this provider labels its output with the provisional ids
 * below ([GRAMMAR_ID]/[BRANCH_ID], version 1, canvas/font/palette strictly
 * positive per the enumeration policy). Promoting, rendering, or delivering
 * these expressions requires gates 1–4 of `docs/hold/generator-boundary.md`
 * and is OUT OF SCOPE here.
 *
 * Layout rule (the whole policy, stated exactly): band `i` of `n` photos is
 * `x=0, w=360, h=640/n (integer division) + 1 for the first `640%n` bands,
 * `y` cumulative from 0. Bands tile the canvas exactly (`sum(h) == 640`),
 * every authored photo is represented exactly once, in authored order, with
 * `crop = null` and `maskId = null` (full originals preserved — the most
 * conservative safety posture; no crop analysis exists in this slice).
 * Aspect-fit/letterboxing, footprint classes, and text rendering are renderer
 * concerns and never affect identity.
 *
 * Outcome taxonomy:
 * - eligible input → [ProviderOutcome.Candidates] with one candidate;
 * - music reference present → [ProviderOutcome.NotApplicable] (arch §4/§6:
 *   structural exclusion routes elsewhere; v1 forbids track attachment and
 *   the music product contract does not exist yet);
 * - structurally invalid input → [ProviderOutcome.Failed] (fail-closed;
 *   [GeneratorDiscovery.run] additionally rejects invalid requests before
 *   any provider executes).
 * This provider never emits [ProviderOutcome.Incompatible]: every eligible
 * input resolves by construction (equal-band geometry always fits), so
 * "applicable but unresolvable" is unreachable here rather than hidden.
 *
 * Determinism: pure function of the input (no randomness, no clock, no
 * unordered iteration). The candidate id derives from the resolved
 * expression canonical hash — never the input alone — so a future
 * layout/grammar/version change cannot silently reuse an id. Repeat calls
 * yield byte-identical candidates.
 *
 * Explicitly NOT here: renderer, crop/CV analysis, candidate browsing,
 * selection, crop correction, freeze/publish/receive wiring, UI, delivery
 * artifact, M4, source byte handles (a G3 adapter concern — this provider
 * never touches bytes, only the declared dimensions/hashes).
 */
object GeneratorBoundedStack {

    /** Stable provider identity (reverse-dns, dispatcher-ordered). */
    const val PROVIDER_ID = "dev.hryshyn.remanence.generator.bounded-stack"
    const val PROVIDER_VERSION = 1

    /** Provisional Stage1-A grammar identity (see class KDoc). */
    const val GRAMMAR_ID = "stage1a-bounded-stack"
    const val GRAMMAR_VERSION = 1

    /** Provisional Stage1-A branch identity (see class KDoc). */
    const val BRANCH_ID = "stack-bands-3-5"

    /** Canvas contract version (G1 reference canvas 360×640). */
    const val CANVAS_VERSION = 1

    /** Dependency versions, strictly positive per the enumeration policy. */
    const val FONT_VERSION = 1
    const val PALETTE_VERSION = 1

    /** Production provider: stateless, deterministic, model-only. */
    class BoundedStackProvider(
        override val ref: ProviderRef = ProviderRef(PROVIDER_ID, PROVIDER_VERSION),
    ) : GeneratorProvider {
        override suspend fun generate(request: GenerationRequest): ProviderOutcome {
            val input = request.input
            if (input.music != null) {
                return ProviderOutcome.NotApplicable(
                    "music unsupported by bounded-stack stage1-a; route elsewhere",
                )
            }
            when (val valid = GeneratorExpression.validate(input)) {
                is InputValidation.Valid -> Unit
                is InputValidation.Invalid ->
                    return ProviderOutcome.Failed("invalid input: ${valid.reasons.joinToString("; ")}")
            }
            val expression = resolve(input)
            when (val resolved = GeneratorExpression.validateResolved(expression)) {
                is InputValidation.Valid -> Unit
                is InputValidation.Invalid ->
                    return ProviderOutcome.Failed("unresolvable input: ${resolved.reasons.joinToString("; ")}")
            }
            val candidateId = "bounded-stack-${GeneratorExpression.canonicalHash(expression).take(16)}-0"
            return ProviderOutcome.Candidates(
                listOf(Candidate(candidateId, 0, ref, expression)),
            )
        }
    }

    /**
     * Resolves the single bounded-stack expression for an eligible input.
     * Fail-fast: any [GeneratorExpression.validate] violation throws
     * [IllegalArgumentException] before geometry runs (an empty photo list
     * would otherwise divide by zero below). [BoundedStackProvider] validates
     * first and maps invalid input to [ProviderOutcome.Failed], so typed
     * provider behavior is unchanged.
     */
    fun resolve(input: GeneratorInput): ResolvedExpression {
        when (val valid = GeneratorExpression.validate(input)) {
            is InputValidation.Valid -> Unit
            is InputValidation.Invalid ->
                throw IllegalArgumentException("invalid GeneratorInput: ${valid.reasons.joinToString("; ")}")
        }
        val photos = input.photos
        val bandH = GeneratorExpression.CANVAS_HEIGHT_UNITS / photos.size
        val remainder = GeneratorExpression.CANVAS_HEIGHT_UNITS % photos.size
        var y = 0
        val placements = photos.mapIndexed { index, photo ->
            val height = bandH + if (index < remainder) 1 else 0
            GeneratorExpression.Placement(
                contentId = photo.contentId,
                x = 0,
                y = y,
                width = GeneratorExpression.CANVAS_WIDTH_UNITS,
                height = height,
                crop = null,
                maskId = null,
            ).also { y += height }
        }
        val noteBytes = input.note?.toByteArray(Charsets.UTF_8)?.size
        val note = input.note
        val noteTreatment = when {
            note == null -> "note-absent:closed"
            note.isEmpty() -> "note-empty:explicit"
            else -> "note-present:bytes=$noteBytes"
        }
        val diagnostics = listOf(
            "bounded-stack:v1 bands=${photos.size} bandH=$bandH rem=$remainder",
            "note:${noteTreatment.substringBefore(':')}",
            "crops:none masks:none",
        )
        return ResolvedExpression(
            canvasVersion = CANVAS_VERSION,
            grammarId = GRAMMAR_ID,
            grammarVersion = GRAMMAR_VERSION,
            branchId = BRANCH_ID,
            input = input,
            placements = placements,
            noteTreatment = noteTreatment,
            diagnostics = diagnostics,
            fontVersion = FONT_VERSION,
            paletteVersion = PALETTE_VERSION,
        )
    }
}
