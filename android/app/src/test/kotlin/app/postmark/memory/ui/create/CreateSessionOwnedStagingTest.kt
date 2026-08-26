package app.postmark.memory.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.postmark.memory.capture.PreparedBackItem
import app.postmark.memory.capture.ProcessedStill
import app.postmark.memory.capture.StillProcessor
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
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
import postmark.core.crypto.AccountIdentityGenerator
import postmark.core.data.db.OutboxCapsuleState
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
 * FIX-STATE-13 regression: staging is SESSION-OWNED. Every publication stages
 * plaintext only inside `create-staging/<capsule UUID>/` and cleans up exactly
 * its own directory. THE race: an old publication cancelled while parked in a
 * non-cooperative normalization keeps running; meanwhile a NEW epoch starts
 * and really publishes. When the stale job finally wakes, its cleanup removes
 * ONLY its own directory - the new session's staged files survive untouched
 * and the new publication creates exactly its own outbox row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateSessionOwnedStagingTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: PostmarkLocalDatabase
    private lateinit var stagingRoot: File

    private val identity = AccountIdentityGenerator().generate()
    private val userUuid = UUID.fromString("7c111111-2222-4333-8444-555555555555")
    private val bundleUuid = UUID.fromString("7c333333-4444-4555-8666-777777777777")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PostmarkLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stagingRoot = File(context.filesDir, "session-owned-staging").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingRoot.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Fixtures.
    // ------------------------------------------------------------------

    private class Accepting(private val side: FingerprintSide) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill = synthetic(side)
    }

    private class RecordingPersistence : SealedFingerprintPersistence {
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

    private fun b64Url(bytes: ByteArray): String =
        com.google.crypto.tink.subtle.Base64.urlSafeEncode(bytes)

    private fun selfSnapshot() = ResolvedHandleSnapshot(
        userId = UserId(userUuid),
        handle = NormalizedHandle.parse("mykola"),
        keyBundleId = KeyBundleId(bundleUuid),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = b64Url(identity.encryptionPublicKeyset),
        signingPublicKeysetB64Url = b64Url(identity.signingPublicKeyset),
        keyBundleStatus = "ACTIVE",
        directoryVersion = "v1",
    )

    private fun senderIdentity() = SenderIdentitySnapshot(
        userId = userUuid.toString(),
        handle = "mykola",
        activeKeyBundleId = bundleUuid.toString(),
        encryptionPrivateHandle = identity.encryptionPrivateHandle,
        signingPrivateHandle = identity.signingPrivateHandle,
    )

    /**
     * Non-cooperative normalization stand-in for the real CPU pipeline: the
     * SECOND old-session photo blocks the calling worker on a plain
     * [CountDownLatch] (invisible to coroutine cancellation), and the THIRD
     * new-session photo blocks on [newGate]. Everything else passes through.
     */
    private class GatedNormalizer(
        private val oldPark: CountDownLatch,
        private val newGate: CompletableDeferred<Unit>,
    ) : app.postmark.memory.create.PhotoNormalizerPort {
        val oldCalls = AtomicInteger(0)
        val newCalls = AtomicInteger(0)

        override suspend fun normalize(inputJpeg: ByteArray): app.postmark.memory.create.NormalizedPhotoDto {
            val text = inputJpeg.toString(Charsets.US_ASCII)
            return when {
                text.startsWith("old-") -> {
                    if (oldCalls.incrementAndGet() == 2) {
                        // Non-cooperative park: coroutine cancellation cannot
                        // interrupt this; the job only unwinds afterwards.
                        assertTrue(oldPark.await(30, TimeUnit.SECONDS))
                    }
                    dto(text)
                }
                text.startsWith("new-") -> {
                    if (newCalls.incrementAndGet() == 3) newGate.await()
                    dto(text)
                }
                else -> error("unexpected photo payload $text")
            }
        }

        private fun dto(marker: String) = app.postmark.memory.create.NormalizedPhotoDto(
            "normalized-$marker".toByteArray(),
            800,
            600,
        )
    }

    private fun newViewModel(
        normalizer: app.postmark.memory.create.PhotoNormalizerPort,
        identityGate: CompletableDeferred<SenderIdentitySnapshot>,
        identityCalls: AtomicInteger,
    ): CreateViewModel {
        val persistence = RecordingPersistence()
        return CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { null },
            identityProvider = {
                // The first (stale) publication parks at the identity boundary
                // until released; every later session resolves immediately.
                if (identityCalls.incrementAndGet() == 1) identityGate.await() else senderIdentity()
            },
            persistence = persistence,
            outboxStager = postmark.core.data.outbox.CapsuleOutboxStager(database, File(stagingRoot.parentFile, "session-owned-outbox")),
            profile = RecognitionProfile.mvpOrbV1(),
            stagingDirectory = stagingRoot,
            openPhotoSource = { id ->
                app.postmark.memory.create.PhotoSource {
                    java.io.ByteArrayInputStream(id.toByteArray(Charsets.US_ASCII))
                }
            },
            frontProcessor = Accepting(FingerprintSide.FRONT),
            backProcessor = Accepting(FingerprintSide.BACK),
            cpuDispatcher = Dispatchers.Default,
            ioDispatcher = Dispatchers.IO,
            photoNormalizer = normalizer,
        )
    }

    /** Drives ONE full legal session from RECIPIENT_LOOKUP to ready CONTENT. */
    private fun driveToReadyContent(vm: CreateViewModel, photoIds: List<String>) {
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        vm.onResolved(selfSnapshot())
        vm.confirmRecipient()
        vm.frontAttempt.onPermissionResult(true, false)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("front".toByteArray())
        awaitStep(vm, CreateViewModel.Step.BACK_CHECKLIST)
        PreparedBackItem.entries.forEach { item -> vm.backGate.setChecked(item, true) }
        vm.proceedToBackChecklist()
        vm.backAttempt.onPermissionResult(true, false)
        vm.backAttempt.onPreviewBound()
        assertTrue(vm.beginBackCapture())
        vm.deliverBackJpeg("back".toByteArray())
        awaitStep(vm, CreateViewModel.Step.CONTENT)
        vm.onPhotosPicked(photoIds)
        assertTrue(vm.noteEditor.onChange("owned staging note"))
    }

    private fun awaitStep(vm: CreateViewModel, expected: CreateViewModel.Step) {
        awaitCondition("step $expected") { vm.step.value == expected }
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    // ------------------------------------------------------------------
    // Scenarios.
    // ------------------------------------------------------------------

    @Test
    fun stalePublicationCleanupSparesTheNewSessionArtifactsAndRow() {
        val oldPark = CountDownLatch(1)
        val newGate = CompletableDeferred<Unit>()
        val normalizer = GatedNormalizer(oldPark, newGate)
        val identityGate = CompletableDeferred<SenderIdentitySnapshot>()
        val identityCalls = AtomicInteger(0)
        val vm = newViewModel(normalizer, identityGate, identityCalls)

        // --- Epoch 1: publish and park inside non-cooperative normalization.
        vm.beginSession(1L)
        driveToReadyContent(vm, listOf("old-1", "old-2", "old-3"))
        val oldCapsuleId = vm.capsuleId
        val oldDir = File(stagingRoot, oldCapsuleId)

        vm.startPublishing()
        assertEquals(CreateViewModel.Step.PUBLISHING, vm.step.value)
        identityGate.complete(senderIdentity())
        // First old photo staged into ITS OWN directory; second one parked.
        awaitCondition("first staged file in $oldDir") {
            oldDir.listFiles()?.map { it.name } == listOf("photo-00.jpg")
        }

        // --- Teardown while the stale job is blocked mid-normalization.
        vm.endSession()
        assertEquals(CreateViewModel.Step.PUBLISHING, vm.step.value) // dead surface
        // The directory belongs to the still-running publication: untouched.
        assertTrue(oldDir.isDirectory)

        // --- Epoch 2: a fresh session REALLY publishes.
        vm.beginSession(2L)
        val newCapsuleId = vm.capsuleId
        assertTrue(newCapsuleId != oldCapsuleId)
        // The sweep must respect the in-flight owner of the old directory.
        assertTrue(oldDir.isDirectory)
        assertEquals(listOf("photo-00.jpg"), oldDir.listFiles()?.map { it.name })

        driveToReadyContent(vm, listOf("new-1", "new-2", "new-3"))
        val newDir = File(stagingRoot, newCapsuleId)

        vm.startPublishing()
        assertEquals(CreateViewModel.Step.PUBLISHING, vm.step.value)
        // New session stages two photos, then parks on its third: both files
        // exist as REAL artifacts of a live publication.
        awaitCondition("two staged files in $newDir") {
            newDir.listFiles()?.map { it.name }?.sorted() == listOf("photo-00.jpg", "photo-01.jpg")
        }
        val newFile0 = File(newDir, "photo-00.jpg").readBytes()
        val newFile1 = File(newDir, "photo-01.jpg").readBytes()
        assertEquals("normalized-new-1", newFile0.toString(Charsets.US_ASCII))
        assertEquals("normalized-new-2", newFile1.toString(Charsets.US_ASCII))

        // --- THE race: the stale job wakes and runs its cancellation cleanup.
        oldPark.countDown()
        awaitCondition("stale session directory removed") { !oldDir.exists() }

        // The stale cleanup touched ONLY its own directory.
        assertTrue(newDir.isDirectory)
        assertEquals(
            listOf("photo-00.jpg", "photo-01.jpg"),
            newDir.listFiles()?.map { it.name }?.sorted(),
        )
        assertEquals(newFile0.toList(), File(newDir, "photo-00.jpg").readBytes().toList())
        assertEquals(newFile1.toList(), File(newDir, "photo-01.jpg").readBytes().toList())

        // --- The new publication finishes with exactly its own outbox row.
        newGate.complete(Unit)
        awaitStep(vm, CreateViewModel.Step.PUBLISHED)
        assertNull(vm.publishError.value)

        runBlockingNullable {
            assertNull(database.outboxCapsuleDao().getByCapsuleId(oldCapsuleId))
            val row = database.outboxCapsuleDao().getByCapsuleId(newCapsuleId)
            assertNotNull(row)
            assertEquals(OutboxCapsuleState.ENCRYPTED, row!!.state)
            assertTrue(database.outboxBlobDao().getAllByCapsuleId(newCapsuleId).size >= 5)
        }

        // Own-directory cleanup after SUCCESS too: no plaintext survives.
        awaitCondition("own directory removed after success") { !newDir.exists() }
    }

    @Test
    fun beginSessionSweepsOnlyAbandonedUuidDirectories() {
        val identityGate = CompletableDeferred<SenderIdentitySnapshot>()
        val vm = newViewModel(
            GatedNormalizer(CountDownLatch(1), CompletableDeferred(Unit)),
            identityGate,
            AtomicInteger(0),
        )

        // Abandoned leftovers of a dead process, planted before any sweep.
        val abandonedA = File(stagingRoot, "11111111-2222-4333-8444-555555555555")
        File(abandonedA, "nested").mkdirs()
        File(abandonedA, "leftover.jpg").writeBytes(byteArrayOf(1))
        val abandonedUppercase = File(stagingRoot, "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE")
        abandonedUppercase.mkdirs()
        // NOT session-shaped entries must never be touched by the sweep.
        val foreignDir = File(stagingRoot, "not-a-uuid").apply { mkdirs() }
        val looseFile = File(stagingRoot, "stray.txt").apply { writeBytes(byteArrayOf(2)) }

        vm.beginSession(1L)
        assertTrue(!abandonedA.exists())
        assertTrue(!abandonedUppercase.exists())
        assertTrue(foreignDir.isDirectory)
        assertTrue(looseFile.isFile)

        // The live epoch-1 session stages artifacts into its OWN directory;
        // another process-death leftover appears alongside it.
        val epochOneDir = File(stagingRoot, vm.capsuleId).apply { mkdirs() }
        val liveMarker = File(epochOneDir, "live.txt").apply { writeBytes(byteArrayOf(3)) }
        val abandonedB = File(stagingRoot, "99999999-8888-4777-8666-555555555555").apply { mkdirs() }

        // Rotating to epoch 2 removes the replaced IDLE session's own
        // directory and the newly abandoned one - and nothing else.
        vm.beginSession(2L)
        assertTrue(!epochOneDir.exists())
        assertTrue(!liveMarker.isFile)
        assertTrue(!abandonedB.exists())
        assertTrue(foreignDir.isDirectory)
        assertTrue(looseFile.isFile)
        assertEquals(
            setOf(foreignDir.name, looseFile.name),
            stagingRoot.listFiles()?.map { it.name }?.toSet(),
        )
    }

    private fun runBlockingNullable(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}

private fun synthetic(side: FingerprintSide): ProcessedStill.Accepted {
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
                coarseHash64 = 9L,
                keypoints = keypoints,
                descriptors = List(64) { i ->
                    ByteArray(32) { ((it * 23 + i * 7) and 0xFF).toByte() }
                },
                quality = postmark.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
            ),
        ),
    )
}
