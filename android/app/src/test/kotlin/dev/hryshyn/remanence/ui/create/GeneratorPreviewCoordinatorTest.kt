package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.create.GeneratorPreviewLoader
import dev.hryshyn.remanence.create.LoadedPreviewSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-side tests for the pure Stage1-C preview orchestration. */
class GeneratorPreviewCoordinatorTest {

    private val ids = listOf("uri-1", "uri-2", "uri-3")

    private fun source(hash: String, w: Int, h: Int) = LoadedPreviewSource(
        originalHash = hash,
        uprightWidthPx = w,
        uprightHeightPx = h,
        previewJpegBytes = byteArrayOf(1, 2, 3),
        previewWidthPx = w,
        previewHeightPx = h,
    )

    private class FakeLoader(private val produce: (String) -> LoadedPreviewSource?) : GeneratorPreviewLoader {
        var calls = 0
            private set

        override suspend fun load(pickerId: String): LoadedPreviewSource? {
            calls++
            return produce(pickerId)
        }
    }

    private fun hashFor(id: String): String = when (id) {
        "uri-1" -> "a".repeat(64)
        "uri-2" -> "b".repeat(64)
        else -> "c".repeat(64)
    }

    private fun squareLoader() = FakeLoader { id -> source(hashFor(id), 1000, 1000) }

    @Test
    fun threeSquarePhotosWithoutNoteAreReady() = runTest {
        val state = GeneratorPreviewCoordinator(squareLoader()).compute("owner-1", 7L, ids, null)
        assertTrue("expected Ready, got $state", state is GeneratorPreviewState.Ready)
        val ready = state as GeneratorPreviewState.Ready
        assertEquals(3, ready.sources.size)
        assertEquals(3, ready.sources.map { it.contentId }.distinct().size)
        assertEquals(GeneratorExpression.EXPRESSION_CONTRACT_V2, ready.expression.expressionContractVersion)
        assertTrue(
            GeneratorExpression.validateResolved(ready.expression)
                is GeneratorExpression.InputValidation.Valid,
        )
        assertEquals(64, GeneratorExpression.canonicalHash(ready.expression).length)
    }

    @Test
    fun nonEmptyNoteYieldsNotePendingNeverReady() = runTest {
        val state = GeneratorPreviewCoordinator(squareLoader()).compute("owner-1", 7L, ids, "hi")
        assertTrue("expected NotePending, got $state", state is GeneratorPreviewState.NotePending)
        val pending = state as GeneratorPreviewState.NotePending
        assertEquals(2, pending.noteUtf8Bytes)
        assertEquals(3, pending.sources.size)
    }

    @Test
    fun extremeAspectIsTypedIncompatible() = runTest {
        val loader = FakeLoader { id ->
            if (id == "uri-1") source("a".repeat(64), 2000, 100)
            else source(hashFor(id), 1000, 1000)
        }
        val state = GeneratorPreviewCoordinator(loader).compute("owner-1", 7L, ids, null)
        assertTrue("expected Incompatible, got $state", state is GeneratorPreviewState.Incompatible)
    }

    @Test
    fun tooFewPhotosRejectedAndOwnerUnavailable() = runTest {
        val coordinator = GeneratorPreviewCoordinator(squareLoader())
        assertTrue(
            coordinator.compute("owner-1", 7L, listOf("a", "b"), null)
                is GeneratorPreviewState.Rejected,
        )
        assertTrue(
            coordinator.compute(null, 7L, ids, null)
                is GeneratorPreviewState.Unavailable,
        )
    }

    @Test
    fun missingLoaderOrUnreadableSourceIsUnavailable() = runTest {
        assertTrue(
            GeneratorPreviewCoordinator(null).compute("owner-1", 7L, ids, null)
                is GeneratorPreviewState.Unavailable,
        )
        val failing = FakeLoader { null }
        assertTrue(
            GeneratorPreviewCoordinator(failing).compute("owner-1", 7L, ids, null)
                is GeneratorPreviewState.Unavailable,
        )
    }

    @Test
    fun noteOnlyChangeReusesCachedSourcesAndResetDropsThem() = runTest {
        val loader = squareLoader()
        val coordinator = GeneratorPreviewCoordinator(loader)
        coordinator.compute("owner-1", 7L, ids, null)
        assertEquals(3, loader.calls)
        coordinator.compute("owner-1", 7L, ids, "hello")
        assertEquals("note edit must not reload photos", 3, loader.calls)
        coordinator.reset()
        coordinator.compute("owner-1", 7L, ids, null)
        assertEquals(6, loader.calls)
    }
}
