package dev.hryshyn.remanence.ui.capsule

import androidx.compose.ui.graphics.ImageBitmap
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-018 receiver state: a mid-load failure (e.g. the second photo fails to
 * decrypt/decode) must leave the presentation closed with ZERO retained
 * bitmaps — never a partial composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Ber1PresentationStateTest {

    private fun expression(): GeneratorExpression.ResolvedExpression {
        val input = GeneratorExpression.GeneratorInput(
            ownerId = "owner-1",
            epoch = 1L,
            photos = listOf("p1", "p2", "p3").mapIndexed { index, id ->
                GeneratorExpression.PhotoRef(
                    contentId = id,
                    ordinal = index,
                    widthPx = 1000,
                    heightPx = 1000,
                    contentHash = ('a' + index).toString().repeat(64),
                )
            },
            note = null,
            music = null,
        )
        val plan = GeneratorEditorialRows.plan(input)
        check(plan is GeneratorEditorialRows.PlanResult.Planned) { "expected Planned, got $plan" }
        return (plan as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    @Test
    fun secondPhotoFailureLeavesZeroBitmapsAndClosed() = runBlocking {
        val state = Ber1PresentationState(
            expression = expression(),
            loadPhoto = { ordinal ->
                if (ordinal == 1) throw IllegalStateException("decrypt failed")
                ByteArray(8)
            },
            decoder = Ber1PhotoDecoder { ImageBitmap(1, 1) },
        )

        val result = runCatching { state.load() }

        assertTrue("load must fail", result.isFailure)
        assertTrue("no partial bitmaps may be retained", state.bitmaps.isEmpty())
        assertFalse("state must remain closed", state.isOpen)
    }
}
