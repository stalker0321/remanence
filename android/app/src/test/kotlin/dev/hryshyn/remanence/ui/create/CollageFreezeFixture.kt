package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.create.DefaultGeneratorPreviewLoader
import dev.hryshyn.remanence.create.GeneratorPreviewLoader
import dev.hryshyn.remanence.create.GeneratorSourceBinding
import dev.hryshyn.remanence.create.NormalizedPhotoDto
import dev.hryshyn.remanence.create.PhotoNormalizerPort
import dev.hryshyn.remanence.create.PhotoSource
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.io.ByteArrayInputStream
import dev.hryshyn.remanence.ui.collage.CollageCandidateProvider
import dev.hryshyn.remanence.ui.collage.CollageCropSource
import dev.hryshyn.remanence.ui.collage.CollageProviderResult
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

/**
 * T150 integration fixture: a **real frozen candidate** for publish tests.
 *
 * The integration gate is `startPublishing() -> COMPOSE ->
 * acceptFrozenForPublish(...)`, so any test that asserts a publish outcome
 * must first materialise candidates through the production
 * [CollageCandidateProvider] and freeze one explicitly — the same call sequence
 * the UI performs (`selectCollageCandidate` -> `freezeCollageSelection`). This
 * never relaxes a gate: the frozen expression still has to survive
 * `acceptFrozenForPublish` (stale-input, identity, projection checks).
 *
 * The crop source is the accepted full-window stub (a permissive but legal
 * analysis: every slot keeps the whole source), and the note measurer is the
 * stub line measurer the production host uses in JVM tests.
 */
internal object CollageFreezeFixture {

    /**
     * The production preview loader for fixtures that must reach a choosable
     * preview (Ready/NotePending) before a candidate can be frozen. Mirrors the
     * accepted `CreateComposeFlowTest` setup: real bytes through the loader,
     * stub upright/normalizer ports.
     */
    fun previewLoader(original: (String) -> ByteArray): GeneratorPreviewLoader =
        DefaultGeneratorPreviewLoader(
            openSource = { pickerId -> PhotoSource { ByteArrayInputStream(original(pickerId)) } },
            // Real upright dimensions: the publish preread decodes the same
            // originals with GeneratorExifDecoder, and a stubbed 1000x1000
            // would make the frozen input stale ("the frozen collage is stale
            // for this content") at acceptFrozenForPublish.
            decoder = GeneratorSourceBinding.PhotoDecoderPort { bytes ->
                val upright = dev.hryshyn.remanence.create.GeneratorExifDecoder.decodeUpright(bytes)
                GeneratorSourceBinding.UprightPhoto(upright.widthPx, upright.heightPx)
            },
            normalizer = PhotoNormalizerPort { NormalizedPhotoDto(byteArrayOf(9, 8, 7), 100, 100) },
        )

    /**
     * Reports the VM's own current drawable inputs as a positive Ready
     * proof, mirroring what the host effect reports after a successful real
     * prepare. This is not a bypass: [CreateViewModel.onCandidatePrepared]
     * stores it keyed by candidate, and every gate revalidates equality
     * against live state, so stale or mismatched reports are refused
     * exactly as in production.
     */
    fun reportDrawable(vm: CreateViewModel, candidateId: String) {
        vm.onCandidatePrepared(candidateId, true, vm.prepareInputs())
    }

    /** Waits until the preview exposes a chooser input (Ready or NotePending). */
    fun awaitChooserInput(vm: CreateViewModel, timeoutMs: Long = 10_000): GeneratorExpression.GeneratorInput {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            vm.collageChooserInput()?.let { return it }
            if (System.currentTimeMillis() > deadline) {
                error("preview never exposed a chooser input; state=${vm.generatorPreview.value}")
            }
            Thread.sleep(20)
        }
    }

    /**
     * Builds candidates for the current chooser input with the production
     * provider, selects the first and freezes it. Returns the frozen
     * candidate id. Fails the test when the provider cannot enumerate.
     */
    fun freezeFirstCandidate(vm: CreateViewModel, aspectMatchedSlots: Boolean = false): String {
        val input = awaitChooserInput(vm)
        val note = vm.noteEditor.text.takeIf { it.isNotEmpty() }
        val provider = CollageCandidateProvider(
            CollageCropSource { requests ->
                requests.associate { request ->
                    val crop = if (aspectMatchedSlots && request.slotWidth > 0 && request.slotHeight > 0) {
                        val aspect = request.slotWidth.toDouble() / request.slotHeight.toDouble()
                        if (aspect >= 1.0) {
                            val height = (10_000.0 / aspect).toInt().coerceIn(1, 10_000)
                            GeneratorExpression.CropWindow(0, 0, 10_000, height)
                        } else {
                            val width = (10_000.0 * aspect).toInt().coerceIn(1, 10_000)
                            GeneratorExpression.CropWindow(0, 0, width, 10_000)
                        }
                    } else {
                        GeneratorExpression.CropWindow(0, 0, 10_000, 10_000)
                    }
                    request.contentId to crop
                }
            },
            CollageRenderer.TextMeasurer { request ->
                CollageRenderer.TextMeasurement(1, request.line.coerceAtLeast(1), false)
            },
        )
        val result = runBlocking { provider.candidates(input, note, vm.collageChooserTrack) }
        val candidates = (result as? CollageProviderResult.Candidates)?.candidates
            ?: error("expected frozen-eligible candidates, got $result")
        assertTrue("candidate list must not be empty", candidates.isNotEmpty())
        vm.collageChoices.replaceCandidates(candidates)
        reportDrawable(vm, candidates[0].candidateId)
        CollageFreezeFixture.reportDrawable(vm, vm.collageChoices.candidates[0].candidateId)
        assertTrue("candidate must be selectable", vm.selectCollageCandidate(0))
        assertTrue("candidate must freeze", vm.freezeCollageSelection())
        return requireNotNull(vm.frozenCollageSelection).candidateId
    }

    /**
     * Same as [freezeFirstCandidate], but the selected candidate's placement
     * is upgraded to a contract-V4 expression whose aperture shape is
     * `ROUND` (grammar 11 interlock) and re-identified. Used by the F1 shape
     * wiring regression: the corrected window must be re-classified under the
     * round mask, never as a rect.
     */
    fun freezeRoundCandidate(vm: CreateViewModel, aspectMatchedSlots: Boolean = false): String {
        val input = awaitChooserInput(vm)
        val note = vm.noteEditor.text.takeIf { it.isNotEmpty() }
        val provider = CollageCandidateProvider(
            CollageCropSource { requests ->
                requests.associate { request ->
                    val crop = if (aspectMatchedSlots && request.slotWidth > 0 && request.slotHeight > 0) {
                        val aspect = request.slotWidth.toDouble() / request.slotHeight.toDouble()
                        if (aspect >= 1.0) {
                            val height = (10_000.0 / aspect).toInt().coerceIn(1, 10_000)
                            GeneratorExpression.CropWindow(0, 0, 10_000, height)
                        } else {
                            val width = (10_000.0 * aspect).toInt().coerceIn(1, 10_000)
                            GeneratorExpression.CropWindow(0, 0, width, 10_000)
                        }
                    } else {
                        GeneratorExpression.CropWindow(0, 0, 10_000, 10_000)
                    }
                    request.contentId to crop
                }
            },
            CollageRenderer.TextMeasurer { request ->
                CollageRenderer.TextMeasurement(1, request.line.coerceAtLeast(1), false)
            },
        )
        val result = runBlocking { provider.candidates(input, note, vm.collageChooserTrack) }
        val candidates = (result as? CollageProviderResult.Candidates)?.candidates
            ?: error("expected candidates, got $result")
        val first = candidates.first()
        val expression = first.expression.copy(
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V4,
            placements = first.expression.placements.map {
                it.copy(shape = GeneratorExpression.PhotoShape.ROUND)
            },
        )
        val round = first.copy(
            candidateId = GeneratorBer1Provider.candidateIdFor(expression),
            expression = expression,
        )
        vm.collageChoices.replaceCandidates(listOf(round))
        reportDrawable(vm, round.candidateId)
        CollageFreezeFixture.reportDrawable(vm, vm.collageChoices.candidates[0].candidateId)
        assertTrue("round candidate must be selectable", vm.selectCollageCandidate(0))
        assertTrue("round candidate must freeze (V4 gate)", vm.freezeCollageSelection())
        return round.candidateId
    }

    /**
     * Same as [freezeFirstCandidate] but candidates are enumerated through the
     * VM's own production [AndroidCollageCropSource][dev.hryshyn.remanence.ui.collage.AndroidCollageCropSource],
     * so every placement carries a solver-approved window with the slot's own
     * aspect. Required before [CreateViewModel.applyCropAndClose], whose
     * T157 gate rejects an aspect-mismatched base window.
     */
    fun freezeFirstCandidateWithProductionSource(vm: CreateViewModel): String {
        val input = awaitChooserInput(vm)
        val note = vm.noteEditor.text.takeIf { it.isNotEmpty() }
        val cropSource = vm.collageCropSource
            ?: error("VM has no production crop source; pass collageSourceFactory to the fixture")
        val provider = CollageCandidateProvider(
            cropSource,
            CollageRenderer.TextMeasurer { request ->
                CollageRenderer.TextMeasurement(1, request.line.coerceAtLeast(1), false)
            },
        )
        val result = runBlocking { provider.candidates(input, note, vm.collageChooserTrack) }
        val candidates = (result as? CollageProviderResult.Candidates)?.candidates
            ?: error("expected production candidates, got $result")
        assertTrue("candidate list must not be empty", candidates.isNotEmpty())
        vm.collageChoices.replaceCandidates(candidates)
        reportDrawable(vm, candidates[0].candidateId)
        CollageFreezeFixture.reportDrawable(vm, vm.collageChoices.candidates[0].candidateId)
        assertTrue("candidate must be selectable", vm.selectCollageCandidate(0))
        assertTrue("candidate must freeze", vm.freezeCollageSelection())
        return requireNotNull(vm.frozenCollageSelection).candidateId
    }
}
