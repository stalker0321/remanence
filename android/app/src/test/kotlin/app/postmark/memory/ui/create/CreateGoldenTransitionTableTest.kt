package app.postmark.memory.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.postmark.memory.capture.CaptureAttemptController
import app.postmark.memory.capture.CaptureAttemptPhase
import app.postmark.memory.capture.PreparedBackItem
import app.postmark.memory.capture.ProcessedStill
import app.postmark.memory.capture.StillProcessor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import postmark.core.data.db.PostmarkLocalDatabase
import postmark.core.data.fingerprints.SealedFingerprintPersistence
import postmark.core.data.network.DirectoryLookupResult
import postmark.core.data.network.ResolvedHandleSnapshot
import postmark.core.model.KeyBundleId
import postmark.core.model.NormalizedHandle
import postmark.core.model.UserId
import postmark.core.recognition.FingerprintSide
import postmark.core.recognition.RecognitionProfile

/**
 * FIX-STATE-08 (I): the GOLDEN transition table. From every synchronous flow
 * state, each ViewModel event either performs its documented transition or is
 * refused with a visible recovery error - nothing else, no exceptions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateGoldenTransitionTableTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: PostmarkLocalDatabase
    private lateinit var stagingDir: File

    private val RECIPIENT_LOOKUP = CreateViewModel.Step.RECIPIENT_LOOKUP
    private val RECIPIENT_CONFIRM = CreateViewModel.Step.RECIPIENT_CONFIRM
    private val FRONT = CreateViewModel.Step.FRONT
    private val BACK_CHECKLIST = CreateViewModel.Step.BACK_CHECKLIST
    private val BACK = CreateViewModel.Step.BACK
    private val CONTENT = CreateViewModel.Step.CONTENT
    private val PUBLISHING = CreateViewModel.Step.PUBLISHING

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PostmarkLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stagingDir = File(context.filesDir, "golden-staging").apply { mkdirs() }
    }


    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Fixtures.
    // ------------------------------------------------------------------

    private class Accepting(private val result: ProcessedStill.Accepted) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill = result
    }

    private fun goldenSynthetic(side: FingerprintSide): ProcessedStill.Accepted {
        val profile = RecognitionProfile.mvpOrbV1()
        val keypoints = List(64) {
            postmark.core.recognition.FingerprintKeypoint(
                xNormalized = (it % 8) / 8.0,
                yNormalized = (it / 8) / 8.0,
                scaleNormalized = 1.0,
                angleCentiDegrees = 0,
                responseQuantized = it,
                octave = 0,
            )
        }
        return ProcessedStill.Accepted(
            profileId = profile.profileId,
            serializedBytes = postmark.core.recognition.FingerprintCodec.serialize(
                postmark.core.recognition.PostcardFingerprint(
                    profileId = profile.profileId,
                    side = side,
                    canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                    canonicalHeightPx = 1000,
                    coarseHash64 = 3L,
                    keypoints = keypoints,
                    descriptors = List(64) { i ->
                        ByteArray(32) { ((it * 5 + i * 11) and 0xFF).toByte() }
                    },
                    quality = postmark.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
                ),
            ),
        )
    }

    private class NoPersistence : SealedFingerprintPersistence {
        val stored = mutableMapOf<String, ByteArray>()
        var counter = 0

        override suspend fun persist(
            capsuleId: String,
            side: postmark.core.data.db.FingerprintSide,
            origin: postmark.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            val id = "fp-${++counter}"
            stored[id] = plaintextBytes
            return id
        }

        override suspend fun hasBaseline(
            capsuleId: String,
            side: postmark.core.data.db.FingerprintSide,
            origin: postmark.core.data.db.FingerprintOrigin,
        ): Boolean = stored.isNotEmpty()

        override suspend fun decrypt(fingerprintId: String): ByteArray =
            requireNotNull(stored[fingerprintId])

        override suspend fun setPreferredPair(capsuleId: String, origin: postmark.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            side: postmark.core.data.db.FingerprintSide,
            origin: postmark.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private class StaticDirectory : RecipientDirectoryPort {
        override suspend fun lookup(rawHandle: String, accessToken: String): DirectoryLookupResult =
            DirectoryLookupResult.NotFound
    }

    private fun newViewModel(): CreateViewModel = CreateViewModel(
        directory = StaticDirectory(),
        accessTokenProvider = { null },
        // Parked identity keeps the PUBLISHING transition observable.
        identityProvider = { CompletableDeferred<SenderIdentitySnapshot>().await() },
        persistence = NoPersistence(),
        outboxStager = postmark.core.data.outbox.CapsuleOutboxStager(database, stagingDir),
        profile = RecognitionProfile.mvpOrbV1(),
        stagingDirectory = stagingDir,
        openPhotoSource = { error("unused") },
        frontProcessor = Accepting(goldenSynthetic(FingerprintSide.FRONT)),
        backProcessor = Accepting(goldenSynthetic(FingerprintSide.BACK)),
        cpuDispatcher = testDispatcher,
        ioDispatcher = testDispatcher,
    ).also { it.beginSession(1L) }

    private fun selfSnapshot() = ResolvedHandleSnapshot(
        userId = UserId(UUID.fromString("9c111111-2222-4333-8444-555555555555")),
        handle = NormalizedHandle.parse("mykola"),
        keyBundleId = KeyBundleId(UUID.fromString("9c333333-4444-4555-8666-777777777777")),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = "enc",
        signingPublicKeysetB64Url = "sig",
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )

    /** Brings a fresh ViewModel to the requested step through legal events only. */
    private fun reach(step: CreateViewModel.Step): CreateViewModel {
        val vm = newViewModel()
        when (step) {
            CreateViewModel.Step.RECIPIENT_LOOKUP -> Unit
            CreateViewModel.Step.RECIPIENT_CONFIRM -> vm.onResolved(selfSnapshot())
            CreateViewModel.Step.FRONT -> {
                vm.onResolved(selfSnapshot())
                vm.confirmRecipient()
                bind(vm.frontAttempt)
            }
            CreateViewModel.Step.BACK_CHECKLIST -> {
                vm.onResolved(selfSnapshot())
                vm.confirmRecipient()
                bind(vm.frontAttempt)
                assertTrue(vm.beginFrontCapture())
                vm.deliverFrontJpeg("f".toByteArray())
                // The readiness gate is part of reaching this state legally.
                PreparedBackItem.entries.forEach { item -> vm.backGate.setChecked(item, true) }
            }
            CreateViewModel.Step.BACK -> {
                // Full legal walk: lookup -> confirm -> front -> checklist -> BACK.
                vm.onResolved(selfSnapshot())
                vm.confirmRecipient()
                bind(vm.frontAttempt)
                assertTrue(vm.beginFrontCapture())
                vm.deliverFrontJpeg("f".toByteArray())
                PreparedBackItem.entries.forEach { item -> vm.backGate.setChecked(item, true) }
                vm.proceedToBackChecklist()
                bind(vm.backAttempt)
            }
            CreateViewModel.Step.CONTENT -> {
                vm.onResolved(selfSnapshot())
                vm.confirmRecipient()
                bind(vm.frontAttempt)
                assertTrue(vm.beginFrontCapture())
                vm.deliverFrontJpeg("f".toByteArray())
                PreparedBackItem.entries.forEach { item -> vm.backGate.setChecked(item, true) }
                vm.proceedToBackChecklist()
                bind(vm.backAttempt)
                assertTrue(vm.beginBackCapture())
                vm.deliverBackJpeg("b".toByteArray())
                // Content readiness belongs to reaching CONTENT legally.
                repeat(3) { vm.photoSelection.toggle("content://$it") }
            }
            else -> error("state $step has no synchronous path")
        }
        return vm
    }

    private fun bind(attempt: CaptureAttemptController) {
        attempt.onPermissionResult(true, false)
        attempt.onPreviewBound()
    }

    // ------------------------------------------------------------------
    // The golden table.
    // ------------------------------------------------------------------

    private enum class Outcome { TRANSITION_TO_TARGET, UNCHANGED_VISIBLE_ERROR, UNCHANGED_OK }

    private data class Row(
        val state: CreateViewModel.Step,
        val event: String,
        val outcome: Outcome,
        val target: CreateViewModel.Step? = null,
    )

    private val table: List<Row> = listOf(
        // RECIPIENT_LOOKUP
        Row(RECIPIENT_LOOKUP, "resolve", Outcome.TRANSITION_TO_TARGET, RECIPIENT_CONFIRM),
        Row(RECIPIENT_LOOKUP, "confirm", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(RECIPIENT_LOOKUP, "restartLookup", Outcome.UNCHANGED_OK),
        Row(RECIPIENT_LOOKUP, "beginFront", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(RECIPIENT_LOOKUP, "checklistContinue", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(RECIPIENT_LOOKUP, "publish", Outcome.UNCHANGED_VISIBLE_ERROR),
        // RECIPIENT_CONFIRM
        Row(RECIPIENT_CONFIRM, "resolve", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(RECIPIENT_CONFIRM, "confirm", Outcome.TRANSITION_TO_TARGET, FRONT),
        Row(RECIPIENT_CONFIRM, "restartLookup", Outcome.TRANSITION_TO_TARGET, RECIPIENT_LOOKUP),
        Row(RECIPIENT_CONFIRM, "publish", Outcome.UNCHANGED_VISIBLE_ERROR),
        // FRONT
        Row(FRONT, "resolve", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(FRONT, "confirm", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(FRONT, "beginFront", Outcome.UNCHANGED_OK), // attempt begins (phase-level)
        Row(FRONT, "beginBack", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(FRONT, "checklistContinue", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(FRONT, "publish", Outcome.UNCHANGED_VISIBLE_ERROR),
        // BACK_CHECKLIST
        Row(BACK_CHECKLIST, "checklistContinue", Outcome.TRANSITION_TO_TARGET, BACK),
        Row(BACK_CHECKLIST, "beginFront", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(BACK_CHECKLIST, "beginBack", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(BACK_CHECKLIST, "publish", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(BACK_CHECKLIST, "confirm", Outcome.UNCHANGED_VISIBLE_ERROR),
        // BACK
        Row(BACK, "beginBack", Outcome.UNCHANGED_OK),
        Row(BACK, "beginFront", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(BACK, "checklistContinue", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(BACK, "publish", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(BACK, "resolve", Outcome.UNCHANGED_VISIBLE_ERROR),
        // CONTENT
        Row(CONTENT, "publish", Outcome.TRANSITION_TO_TARGET, PUBLISHING),
        Row(CONTENT, "beginFront", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(CONTENT, "beginBack", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(CONTENT, "checklistContinue", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(CONTENT, "confirm", Outcome.UNCHANGED_VISIBLE_ERROR),
        Row(CONTENT, "resolve", Outcome.UNCHANGED_VISIBLE_ERROR),
    )


    @Test
    fun goldenTableEveryStateAllowsOnlyItsDocumentedEvents() {
        for (row in table) {
            val vm = reach(row.state)
            val before = vm.step.value
            assertEquals("fixture must reach ${row.state}", row.state, before)

            when (row.event) {
                "resolve" -> vm.onResolved(selfSnapshot())
                "confirm" -> vm.confirmRecipient()
                "restartLookup" -> vm.restartLookup()
                "beginFront" -> vm.beginFrontCapture()
                "beginBack" -> vm.beginBackCapture()
                "checklistContinue" -> vm.proceedToBackChecklist()
                "publish" -> vm.startPublishing()
                else -> error("unknown event ${row.event}")
            }

            when (row.outcome) {
                Outcome.TRANSITION_TO_TARGET ->
                    assertEquals("row $row", row.target, vm.step.value)
                Outcome.UNCHANGED_VISIBLE_ERROR -> {
                    assertEquals("row $row", before, vm.step.value)
                    assertNotNull("row $row must surface recovery", vm.flowError.value)
                }
                Outcome.UNCHANGED_OK -> {
                    assertEquals("row $row", before, vm.step.value)
                    assertNull("row $row must not raise the banner", vm.flowError.value)
                }
            }
        }
    }
}
