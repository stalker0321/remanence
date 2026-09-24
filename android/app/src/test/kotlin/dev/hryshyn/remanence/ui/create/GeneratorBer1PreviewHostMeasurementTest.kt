package dev.hryshyn.remanence.ui.create

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ADR-018 host measurement proof: a non-empty note in [GeneratorPreviewState.NotePending]
 * is measured by the real Compose path and reported through `onMeasured` as a
 * fitted BER1 plan (never a fabricated fit). The VM then freshness-checks that
 * exact input before freezing it for publish.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GeneratorBer1PreviewHostMeasurementTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun realJpeg(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun input(note: String): GeneratorExpression.GeneratorInput =
        GeneratorExpression.GeneratorInput(
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
            note = note,
            music = null,
        )

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun notePendingReportsTheRealMeasuredPlan() {
        val note = "dear mama"
        val sources = listOf("p1", "p2", "p3").map { id ->
            GeneratorPreviewSource(id, realJpeg(), 8, 8)
        }
        var reported: GeneratorExpression.ResolvedExpression? = null

        composeRule.setContent {
            MaterialTheme {
                GeneratorBer1PreviewHost(
                    state = GeneratorPreviewState.NotePending(
                        input = input(note),
                        sources = sources,
                        noteUtf8Bytes = note.toByteArray(Charsets.UTF_8).size,
                    ),
                    onMeasured = { reported = it },
                )
            }
        }
        composeRule.waitForIdle()

        assertNotNull("the host must report a measured plan", reported)
        assertEquals(note, reported!!.input.note)
        assertEquals(GeneratorEditorialRows.NOTE_REGION, reported!!.noteRegion)
        assertEquals(
            "measured plan must come from the fixed ADR-017 region",
            GeneratorEditorialRows.NOTE_REGION,
            reported!!.noteRegion,
        )
    }
}
