package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageCandidate
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.FontRegistry
import dev.hryshyn.remanence.ui.collage.StyleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S5 gate (tasks/T154/gate-patches.md, owned by T150): the create publish gate
 * accepts contract V3 **and** V4, and every later check (stale input,
 * identity, projection, input hash, music pairing) still binds the V4 bytes.
 *
 * The expression is hand-built with structurally valid inputs (64-hex unique
 * content hashes — F2) so the assertions exercise the GATE, not a provider
 * fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CollageV4PublishGateTest {

    private val capsuleId = "0198f0a0-0000-7000-8000-00000000ca01"

    private fun input() = GeneratorExpression.GeneratorInput(
        ownerId = "0198f0a0-0000-7000-8000-000000000001",
        epoch = 1L,
        photos = List(3) { index ->
            GeneratorExpression.PhotoRef(
                contentId = "content-$index",
                ordinal = index,
                widthPx = 1000,
                heightPx = 1000,
                // F2: unique 64 lowercase-hex (GeneratorExpression.validate).
                contentHash = "%064x".format(index + 1),
            )
        },
        note = null,
        music = null,
    )

    private fun expression(input: GeneratorExpression.GeneratorInput) =
        GeneratorExpression.ResolvedExpression(
            canvasVersion = 1,
            grammarId = "g5",
            grammarVersion = 5,
            branchId = "editorial-1+2-equal-1",
            input = input,
            placements = input.photos.mapIndexed { index, photo ->
                GeneratorExpression.Placement(
                    contentId = photo.contentId,
                    x = 0,
                    y = index * 120,
                    width = 340,
                    height = 120,
                    crop = GeneratorExpression.CropWindow(0, 0, 10_000, 10_000),
                    maskId = null,
                    contentRect = null,
                )
            },
            noteTreatment = "regular-region",
            diagnostics = emptyList(),
            fontVersion = FontRegistry.VERSION,
            paletteVersion = CollageRenderer.PALETTE_REGISTRY_VERSION,
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
            noteRegion = null,
            style = GeneratorExpression.StyleBinding(
                styleId = "press",
                styleVersion = "style-study-1",
                digest = requireNotNull(StyleRegistry.digest("press")) { "press style must be bundled" },
            ),
            noteType = GeneratorExpression.TextType("StudySans", 16, 400, false, 18),
            musicRegion = null,
        )

    private fun candidateV3(): CollageCandidate {
        val expression = expression(input())
        val validation = GeneratorExpression.validateResolved(expression)
        assertTrue(
            "the fixture expression must be valid V3, got " +
                (validation as? GeneratorExpression.InputValidation.Invalid)?.reasons,
            validation is GeneratorExpression.InputValidation.Valid,
        )
        return CollageCandidate(
            candidateId = GeneratorBer1Provider.candidateIdFor(expression),
            styleId = "press",
            branchId = expression.branchId,
            expression = expression,
        )
    }

    /** V4 sibling of the same candidate: contract bumped, identity recomputed. */
    private fun asV4(candidate: CollageCandidate): CollageCandidate {
        val expression = candidate.expression.copy(
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V4,
        )
        assertTrue(
            "a V3-shaped expression must validate as V4 (R5: provenance may be null)",
            GeneratorExpression.validateResolved(expression) is GeneratorExpression.InputValidation.Valid,
        )
        return candidate.copy(
            candidateId = GeneratorBer1Provider.candidateIdFor(expression),
            expression = expression,
        )
    }

    @Test
    fun v4FrozenSelectionIsAcceptedByThePublishGate() {
        val input = input()
        val v4 = asV4(candidateV3())
        val frozen = requireNotNull(buildFrozenSelection(v4, input, capsuleId)) {
            "V4 expression must freeze"
        }
        assertEquals(v4.candidateId, frozen.candidateId)

        val acceptance = acceptFrozenForPublish(frozen, input, trackSnapshot = null, capsuleId = capsuleId)
        assertTrue("S5 must accept V4, got $acceptance", acceptance is FrozenPublishAcceptance.Accepted)
    }

    @Test
    fun v3FrozenSelectionIsStillAcceptedAfterS5() {
        val input = input()
        val candidate = candidateV3()
        val frozen = requireNotNull(buildFrozenSelection(candidate, input, capsuleId)) {
            "V3 expression must freeze"
        }
        val acceptance = acceptFrozenForPublish(frozen, input, trackSnapshot = null, capsuleId = capsuleId)
        assertTrue("S5 must keep V3 accepted, got $acceptance", acceptance is FrozenPublishAcceptance.Accepted)
    }

    @Test
    fun v4GateStillRefusesStaleInputAndTamperedIdentity() {
        val input = input()
        val v4 = asV4(candidateV3())
        val frozen = requireNotNull(buildFrozenSelection(v4, input, capsuleId))

        // Stale input: the publication's preread input differs.
        val stale = input.copy(epoch = 2L)
        val staleAcceptance = acceptFrozenForPublish(frozen, stale, trackSnapshot = null, capsuleId = capsuleId)
        assertTrue(
            "stale input must still be refused, got $staleAcceptance",
            staleAcceptance is FrozenPublishAcceptance.Refused,
        )
        assertEquals(
            "the frozen collage is stale for this content",
            (staleAcceptance as FrozenPublishAcceptance.Refused).reason,
        )

        // Tampered identity: the carried candidate id no longer names the bytes.
        val tampered = frozen.copy(candidateId = "ber1-tampered")
        val tamperedAcceptance = acceptFrozenForPublish(tampered, input, trackSnapshot = null, capsuleId = capsuleId)
        assertTrue(
            "tampered identity must still be refused, got $tamperedAcceptance",
            tamperedAcceptance is FrozenPublishAcceptance.Refused,
        )
        assertEquals(
            "the frozen collage identity changed",
            (tamperedAcceptance as FrozenPublishAcceptance.Refused).reason,
        )
    }

    @Test
    fun structurallyInvalidInputHashIsRejectedByValidation() {
        // Guards F2 itself: a short/uppercase hash must never validate, so the
        // gate can never be green on a malformed fixture.
        val bad = input().let { base ->
            base.copy(
                photos = base.photos.mapIndexed { index, photo ->
                    if (index == 0) photo.copy(contentHash = "hash-$index") else photo
                },
            )
        }
        val invalid = GeneratorExpression.validate(bad)
        assertTrue("short hash must be invalid, got $invalid", invalid is GeneratorExpression.InputValidation.Invalid)
        val reasons = (invalid as GeneratorExpression.InputValidation.Invalid).reasons
        assertTrue(
            "reason must name the hash, got $reasons",
            reasons.any { it.contains("contentHash") },
        )
    }
}
