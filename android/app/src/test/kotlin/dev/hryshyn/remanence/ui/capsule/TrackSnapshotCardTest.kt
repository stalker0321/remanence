package dev.hryshyn.remanence.ui.capsule

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A genuinely decodable JPEG for the ready path. */
private fun trackCardJpeg(color: Long): ByteArray {
    val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color.toInt())
    val output = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
    bitmap.recycle()
    return output.toByteArray()
}

/**
 * S2b-receiver focused tests: the offline track card, the Ber1 route with
 * and without a snapshot, and the grant guard around the new accessor.
 * Outbox/incoming source overrides are one-line delegates over the same
 * decrypt path the codec tests already seal; the route test below exercises
 * them through the shared [CapsuleContentReader] interface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TrackSnapshotCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeSource : CapsuleContentReader {
        var count = 3
        var admission: CapsulePresentationAdmission = CapsulePresentationAdmission.LegacyV1
        var snapshot: CapsuleTrackSnapshotV1? = null
        var trackSnapshotCalls = 0

        override suspend fun photoCount(capsuleId: String): Int = count

        override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto =
            DecryptedPhoto(ordinal, trackCardJpeg(0xFF336699L + ordinal))

        override suspend fun noteText(capsuleId: String): String? = null

        override suspend fun trackSnapshot(capsuleId: String): CapsuleTrackSnapshotV1? {
            trackSnapshotCalls += 1
            return snapshot
        }

        override suspend fun presentationAdmission(capsuleId: String): CapsulePresentationAdmission =
            admission
    }

    private fun snapshot() = CapsuleTrackSnapshotV1.parse(
        trackId = "be30e36b-1111-4111-8111-000000000001",
        title = "505",
        artistDisplay = "Arctic Monkeys",
        version = "Live at the Apollo",
        durationMs = 261000L,
    )

    private fun ber1Expression(): GeneratorExpression.ResolvedExpression {
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

    private fun admissionFor(expression: GeneratorExpression.ResolvedExpression): CapsulePresentationAdmission {
        val content = dev.hryshyn.remanence.core.crypto.ContentManifestContent(
            protocolVersion = 2,
            photos = expression.input.photos.mapIndexed { index, photo ->
                dev.hryshyn.remanence.core.crypto.ManifestPhoto(
                    blobId = UUID.nameUUIDFromBytes("blob-$index".toByteArray()),
                    ordinal = index,
                    width = photo.widthPx,
                    height = photo.heightPx,
                )
            },
            note = null,
            expression = dev.hryshyn.remanence.core.crypto.ContentExpression(
                candidateId = "cand-1",
                projectionHash = "a".repeat(64),
                expression = expression,
            ),
        )
        return when (val admitted = dev.hryshyn.remanence.core.crypto.ExpressionReceiverAdmission.admit(content)) {
            is dev.hryshyn.remanence.core.crypto.ExpressionReceiverAdmission.Result.Supported ->
                CapsulePresentationAdmission.Ber1(admitted.expression)
            is dev.hryshyn.remanence.core.crypto.ExpressionReceiverAdmission.Result.Unsupported ->
                CapsulePresentationAdmission.Unsupported(admitted.reason)
        }
    }

    @Test
    fun cardRendersTitleArtistAndVersion() {
        composeRule.setContent {
            MaterialTheme {
                TrackSnapshotCard(track = snapshot())
            }
        }

        composeRule.onNodeWithTag("capsule_track_card").assertIsDisplayed()
        composeRule.onNodeWithText("505").assertIsDisplayed()
        composeRule.onNodeWithText("Arctic Monkeys").assertIsDisplayed()
        composeRule.onNodeWithText("Live at the Apollo").assertIsDisplayed()
    }

    @Test
    fun cardWithoutVersionOmitsVersionNode() {
        composeRule.setContent {
            MaterialTheme {
                TrackSnapshotCard(
                    track = CapsuleTrackSnapshotV1.parse(
                        trackId = "be30e36b-1111-4111-8111-000000000001",
                        title = "505",
                        artistDisplay = "Arctic Monkeys",
                        version = null,
                        durationMs = null,
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("capsule_track_card").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_track_title").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_track_artist").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("capsule_track_version").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun ber1RouteWithSnapshotShowsCard() {
        val source = FakeSource().apply {
            admission = admissionFor(ber1Expression())
            snapshot = snapshot()
        }
        composeRule.setContent {
            MaterialTheme {
                CapsuleRoute(
                    grantId = "grant-1",
                    contentFactory = {
                        CapsuleContentBinding(capsuleId = "capsule-1", reader = source)
                    },
                    validateLiveGrant = {},
                    revocations = MutableSharedFlow(extraBufferCapacity = 8),
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("capsule_track_card").assertIsDisplayed()
        composeRule.onNodeWithText("505").assertIsDisplayed()
        assertEquals(1, source.trackSnapshotCalls)
    }

    @Test
    fun ber1RouteWithoutSnapshotShowsNoCard() {
        val source = FakeSource().apply {
            admission = admissionFor(ber1Expression())
            snapshot = null
        }
        composeRule.setContent {
            MaterialTheme {
                CapsuleRoute(
                    grantId = "grant-1",
                    contentFactory = {
                        CapsuleContentBinding(capsuleId = "capsule-1", reader = source)
                    },
                    validateLiveGrant = {},
                    revocations = MutableSharedFlow(extraBufferCapacity = 8),
                    onClose = {},
                )
            }
        }

        assertTrue(composeRule.onAllNodesWithTag("capsule_track_card").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("capsule_ber1_canvas").assertIsDisplayed()
    }

    @Test
    fun liveGrantServesSnapshotThroughGuard() = runTest {
        val guarded = GrantGuardedCapsuleContentSource(FakeSource().apply { snapshot = snapshot() }) { /* alive */ }

        assertEquals(snapshot(), guarded.trackSnapshot("capsule"))
    }

    @Test
    fun deadGrantRefusesSnapshotWithoutDelivery() = runTest {
        var alive = true
        val inner = FakeSource().apply { snapshot = snapshot() }
        val dying = object : CapsuleContentReader by inner {
            override suspend fun trackSnapshot(capsuleId: String): CapsuleTrackSnapshotV1? {
                alive = false
                return inner.snapshot
            }
        }
        val guarded = GrantGuardedCapsuleContentSource(dying) {
            if (!alive) throw IllegalStateException("scan grant is no longer live")
        }

        val outcome = CompletableDeferred<Result<CapsuleTrackSnapshotV1?>>()
        launch { outcome.complete(runCatching { guarded.trackSnapshot("capsule") }) }

        assertTrue(outcome.await().isFailure)
    }

    @Test
    fun defaultReaderReportsNullSnapshot() = runTest {
        val plain = object : CapsuleContentReader {
            override suspend fun photoCount(capsuleId: String): Int = 3
            override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto =
                DecryptedPhoto(ordinal, ByteArray(0))
            override suspend fun noteText(capsuleId: String): String? = null
        }
        assertNull(plain.trackSnapshot("capsule"))
    }

    @Test
    fun preS3CardWithoutLauncherHasNoListenAction() {
        composeRule.setContent {
            MaterialTheme {
                TrackSnapshotCard(track = snapshot())
            }
        }

        composeRule.onNodeWithTag("capsule_track_card").assertIsDisplayed()
        composeRule.onAllNodesWithTag("capsule_track_open").fetchSemanticsNodes().isEmpty().let {
            assertTrue("Listen action must be absent without a launcher", it)
        }
    }

    @Test
    fun tappingServiceFiresExactSearchUrl() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                TrackSnapshotCard(
                    track = snapshot(),
                    onOpenService = { service ->
                        opened.add(
                            service.searchUrl("505", "Arctic Monkeys"),
                        )
                        true
                    },
                )
            }
        }

        composeRule.onNodeWithTag("capsule_track_open").performClick()
        composeRule.onNodeWithTag("capsule_track_open_spotify").performClick()

        assertEquals(
            listOf("https://open.spotify.com/search/505%20Arctic%20Monkeys"),
            opened,
        )
    }

    @Test
    fun failedLaunchShowsInlineErrorAndKeepsCard() {
        composeRule.setContent {
            MaterialTheme {
                TrackSnapshotCard(
                    track = snapshot(),
                    onOpenService = { false },
                )
            }
        }

        composeRule.onNodeWithTag("capsule_track_open").performClick()
        composeRule.onNodeWithTag("capsule_track_open_apple").performClick()

        composeRule.onNodeWithTag("capsule_track_no_browser").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_track_card").assertIsDisplayed()
    }

    @Test
    fun pickerDismissesWithoutFiring() {
        var calls = 0
        composeRule.setContent {
            MaterialTheme {
                TrackSnapshotCard(
                    track = snapshot(),
                    onOpenService = { calls += 1; true },
                )
            }
        }

        composeRule.onNodeWithTag("capsule_track_open").performClick()
        composeRule.onNodeWithTag("capsule_track_open_dismiss").performClick()

        assertEquals(0, calls)
        composeRule.onNodeWithTag("capsule_track_card").assertIsDisplayed()
    }

    @Test
    fun throwingLauncherBecomesInlineErrorInsteadOfCrash() {
        composeRule.setContent {
            MaterialTheme {
                TrackSnapshotCard(
                    track = snapshot(),
                    onOpenService = { throw IllegalArgumentException("oversized") },
                )
            }
        }

        composeRule.onNodeWithTag("capsule_track_open").performClick()
        composeRule.onNodeWithTag("capsule_track_open_spotify").performClick()

        composeRule.onNodeWithTag("capsule_track_no_browser").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_track_card").assertIsDisplayed()
    }
}
