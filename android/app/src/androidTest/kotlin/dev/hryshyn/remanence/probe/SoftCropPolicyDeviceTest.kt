package dev.hryshyn.remanence.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.*
import dev.hryshyn.remanence.ui.create.*
import dev.hryshyn.remanence.ui.hold.HoldTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real native chooser/prepare over synthetic analysis, not detector/owner-data evidence. */
@RunWith(AndroidJUnit4::class)
class SoftCropPolicyDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidence get() = File(context.getExternalFilesDir(null), "t180-fixture-evidence").apply { mkdirs() }

    @Test fun aestheticRejectionNoLongerHidesDrawableSelectableDesigns() {
        val dimensions = List(3) { 4000 to 1000 }
        val note = "a short note"
        val sources = dimensions.mapIndexed { index, (width, height) ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(160 + index * 20, 120, 95))
            canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 5f,
                Paint().apply { color = Color.rgb(80, 110 + index * 20, 130) })
            val bytes = ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                output.toByteArray()
            }
            bitmap.recycle()
            GeneratorPreviewSource("p$index", bytes, width, height)
        }
        val input = GeneratorExpression.GeneratorInput("fixture-owner", 180L,
            sources.mapIndexed { index, source ->
                GeneratorExpression.PhotoRef(source.contentId, index, dimensions[index].first,
                    dimensions[index].second, MessageDigest.getInstance("SHA-256").digest(source.jpegBytes)
                        .joinToString("") { "%02x".format(it.toInt() and 255) })
            }, note, null)
        fun field(value: Double) = DoubleArray(CollageAnalysis.GRID_SIZE * CollageAnalysis.GRID_SIZE) { value }
        val family = AllFamilyInput("fixture-owner", "qa_fixture", note, false, false,
            dimensions.mapIndexed { index, (width, height) ->
                AllFamilyPhoto("p$index", width, height, true,
                    CollageAnalysis(width, height,
                        listOf(AnalysisFace(NormBox(.45, .4, .1, .1), .9)), emptyList(), emptyList(),
                        field(1.0), field(1.0), field(1.0), field(1.0), field(1.0), field(1.0)))
            })
        val session = CollageChoiceSession().apply { beginDraft("t180-native-fixture") }
        var strictCount = -1
        var balancedCount = -1
        rule.setContent { HoldTheme { Column(Modifier.fillMaxSize().padding(16.dp)) {
            CollageChoiceHost(session, input, sources, note, null,
                cropSource = CollageCropSource { error("All-family fixture must not use legacy fallback") },
                onSelect = { assertTrue(session.select(it)) },
                candidateBuilder = { text ->
                    val measure = AllFamilyCandidateProducer.prepareTextMeasurer(family,
                        FrozenGrammarPlans.TextMeasurer { note, width, size, line, font ->
                            val result = text.layout(CollageRenderer.TextMeasureRequest(note, font,
                                size.toInt(), 400, false, line.toInt(), width.toInt(), Int.MAX_VALUE))
                            FrozenGrammarPlans.TextMetric(result.size.height.toDouble(), result.lineCount)
                        })
                    val outcomes = withContext(Dispatchers.Default) {
                        fun produce(policy: CropAdmissionPolicy) = AllFamilyCandidateProducer(
                            AndroidCollageCropMatrixSource(policy), measure, policy)
                        val strict = produce(CropAdmissionPolicy.STRICT).produce(family) as AllFamilyOutcome.Candidates
                        val balanced = produce(CropAdmissionPolicy.BALANCED).produce(family) as AllFamilyOutcome.Candidates
                        strict to balanced
                    }
                    strictCount = outcomes.first.candidates.size
                    balancedCount = outcomes.second.candidates.size
                    outcomes.second.candidates.flatMap { candidate ->
                        AllFamilyV4Adapter.stylesForGrammar(candidate.grammarId).mapNotNull { style ->
                            val sealed = AllFamilyV4Adapter.seal(candidate, input, "qa_fixture", style)
                                as? AllFamilyV4Adapter.Outcome.Sealed ?: return@mapNotNull null
                            CollageCandidate(GeneratorBer1Provider.candidateIdFor(sealed.expression),
                                style.id, candidate.branchId, sealed.expression)
                        }
                    }
                })
        } } }
        try {
            rule.waitUntil(30_000) { !session.isPreparing && balancedCount >= 0 }
            assertTrue(strictCount >= 0)
            assertTrue(balancedCount > strictCount)
            assertTrue("Native final prepare must retain actual choices", session.candidates.size > 1)
            assertNull(session.selectedIndex)
            rule.onNodeWithTag("collage_select").assertIsEnabled()
            capture("balanced-first-choice")
            rule.onNodeWithTag("collage_next").performClick()
            rule.runOnIdle { assertEquals(1, session.displayIndex); assertNull(session.selectedIndex) }
            rule.onNodeWithTag("collage_select").assertIsEnabled().performClick()
            rule.runOnIdle { assertEquals(1, session.selectedIndex) }
            capture("balanced-second-choice-selected")
            File(evidence, "counts.txt").writeText("strictGeometries=$strictCount\nbalancedGeometries=$balancedCount\n" +
                "nativePreparedUniqueVariants=${session.candidates.size}\n" +
                "variants=" + session.candidates.joinToString(",") { "${it.expression.grammarId}:${it.styleId}" } + "\n")
        } finally { sources.forEach { it.jpegBytes.fill(0) } }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
