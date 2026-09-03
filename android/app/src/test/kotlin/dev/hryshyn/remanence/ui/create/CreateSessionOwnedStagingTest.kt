package dev.hryshyn.remanence.ui.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore

/**
 * FIX-STATE-13/LUNA-01 regression: staging is ACCOUNT + SESSION-OWNED. Every
 * publication stages plaintext only inside
 * `accounts/<owner>/temp/create/<capsule UUID>/` and cleans up exactly its own
 * directory. THE race: an old publication for A cancelled while parked in a
 * non-cooperative normalization keeps running; meanwhile account B starts a
 * new session and really publishes. When the stale job finally wakes, its
 * cleanup removes ONLY A's directory - B's staged files survive untouched and
 * B's publication creates exactly its own outbox row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateSessionOwnedStagingTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingRoot: File

    private val identity = AccountIdentityGenerator().generate()
    private val userUuid = UUID.fromString("7c111111-2222-4333-8444-555555555555")
    private val switchedUserUuid = UUID.fromString("7c222222-3333-4444-8555-666666666666")
    private val bundleUuid = UUID.fromString("7c333333-4444-4555-8666-777777777777")

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    @Before
    fun setUp() {
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
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
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            val id = "fp-${++counter}"
            stored[id] = plaintextBytes.copyOf()
            return id
        }

        override suspend fun hasBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ): Boolean = stored.isNotEmpty()

        override suspend fun decrypt(fingerprintId: String): ByteArray =
            requireNotNull(stored[fingerprintId]).copyOf()

        override suspend fun setPreferredOrigin(capsuleId: String, origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(
            capsuleId: String,
            origin: dev.hryshyn.remanence.core.data.db.FingerprintOrigin,
        ) = Unit
    }

    private class StaticDirectory : RecipientDirectoryPort {
        override suspend fun lookup(rawHandle: String): DirectoryLookupResult =
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

    private fun senderIdentity(owner: UUID = userUuid) = SenderIdentitySnapshot(
        userId = owner.toString(),
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
    ) : dev.hryshyn.remanence.create.PhotoNormalizerPort {
        val oldCalls = AtomicInteger(0)
        val newCalls = AtomicInteger(0)

        override suspend fun normalize(inputJpeg: ByteArray): dev.hryshyn.remanence.create.NormalizedPhotoDto {
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

        private fun dto(marker: String) = dev.hryshyn.remanence.create.NormalizedPhotoDto(
            "normalized-$marker".toByteArray(),
            800,
            600,
        )
    }

    private fun newViewModel(
        normalizer: dev.hryshyn.remanence.create.PhotoNormalizerPort,
        identityGate: CompletableDeferred<SenderIdentitySnapshot>,
        identityCalls: AtomicInteger,
        laterOwner: UUID = userUuid,
        enqueueUpload: suspend (UserId, CapsuleId) -> Unit = { _, _ -> },
    ): CreateViewModel {
        val persistence = RecordingPersistence()
        val retryStore = SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(File(stagingRoot.parentFile, "session-owned-outbox")))
        return CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { null },
            identityProvider = {
                // The first (stale) publication parks at the identity boundary
                // until released; every later session resolves immediately.
                if (identityCalls.incrementAndGet() == 1) identityGate.await() else senderIdentity(laterOwner)
            },
            persistence = persistence,
            outboxStager = dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager(database, dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(File(stagingRoot.parentFile, "session-owned-outbox")), retryStore),
            profile = RecognitionProfile.mvpOrbV1(),
            accountScopedFileRoots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingRoot),
            openPhotoSource = { id ->
                dev.hryshyn.remanence.create.PhotoSource {
                    java.io.ByteArrayInputStream(id.toByteArray(Charsets.US_ASCII))
                }
            },
            frontProcessor = Accepting(FingerprintSide.FRONT),
            cpuDispatcher = Dispatchers.Default,
            ioDispatcher = Dispatchers.IO,
            photoNormalizer = normalizer,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = enqueueUpload,
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
    fun accountSwitchDuringSuspendedPublishCleansOnlyTheCapturedOwner() {
        val oldPark = CountDownLatch(1)
        val newGate = CompletableDeferred<Unit>()
        val normalizer = GatedNormalizer(oldPark, newGate)
        val identityGate = CompletableDeferred<SenderIdentitySnapshot>()
        val identityCalls = AtomicInteger(0)
        val enqueued = mutableListOf<Pair<UserId, CapsuleId>>()
        val vm = newViewModel(
            normalizer,
            identityGate,
            identityCalls,
            switchedUserUuid,
            enqueueUpload = { owner, capsule -> enqueued += owner to capsule },
        )

        // --- Account A: publish and park inside non-cooperative normalization.
        vm.beginSession(1L, userUuid.toString())
        driveToReadyContent(vm, listOf("old-1", "old-2", "old-3"))
        val oldCapsuleId = vm.capsuleId
        val roots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingRoot)
        val oldDir = File(roots.createStagingRoot(UserId(userUuid)), oldCapsuleId)

        vm.startPublishing()
        assertEquals(CreateViewModel.Step.PUBLISHING, vm.step.value)
        identityGate.complete(senderIdentity(userUuid))
        // First old photo staged into ITS OWN directory; second one parked.
        awaitCondition("first staged file in $oldDir") {
            oldDir.listFiles()?.map { it.name } == listOf("photo-00.jpg")
        }

        // --- Teardown while the stale job is blocked mid-normalization.
        vm.endSession()
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertNull(vm.confirmedRecipient.value)
        assertTrue(vm.photoSelection.selectedIds.isEmpty())
        assertTrue(vm.noteEditor.isEmpty)
        assertNull(vm.frontAttempt.phase)
        // The directory belongs to the still-running publication: untouched.
        assertTrue(oldDir.isDirectory)

        // --- Account switch: a fresh B session REALLY publishes.
        val bMarker = File(
            roots.createStagingRoot(UserId(switchedUserUuid)),
            "account-b-marker.txt",
        ).apply {
            parentFile!!.mkdirs()
            writeText("B must survive")
        }
        vm.beginSession(2L, switchedUserUuid.toString())
        val newCapsuleId = vm.capsuleId
        assertTrue(newCapsuleId != oldCapsuleId)
        // The sweep is B-scoped and cannot touch A's in-flight directory.
        assertTrue(oldDir.isDirectory)
        assertEquals(listOf("photo-00.jpg"), oldDir.listFiles()?.map { it.name })

        driveToReadyContent(vm, listOf("new-1", "new-2", "new-3"))
        val newDir = File(roots.createStagingRoot(UserId(switchedUserUuid)), newCapsuleId)

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

        // The stale cleanup touched ONLY A's captured directory.
        assertTrue(newDir.isDirectory)
        assertEquals(
            listOf("photo-00.jpg", "photo-01.jpg"),
            newDir.listFiles()?.map { it.name }?.sorted(),
        )
        assertEquals(newFile0.toList(), File(newDir, "photo-00.jpg").readBytes().toList())
        assertEquals(newFile1.toList(), File(newDir, "photo-01.jpg").readBytes().toList())
        assertEquals("B must survive", bMarker.readText())

        // --- The new publication finishes with exactly its own outbox row.
        newGate.complete(Unit)
        awaitStep(vm, CreateViewModel.Step.UPLOAD_PENDING)
        assertNull(vm.publishError.value)
        assertEquals(
            listOf(UserId(switchedUserUuid) to CapsuleId(UUID.fromString(newCapsuleId))),
            enqueued,
        )

        runBlockingNullable {
            assertNull(database.outboxCapsuleDao().getByCapsuleIdAndOwner(oldCapsuleId, userUuid.toString()))
            val row = database.outboxCapsuleDao().getByCapsuleIdAndOwner(newCapsuleId, switchedUserUuid.toString())
            assertNotNull(row)
            assertEquals(OutboxCapsuleState.ENCRYPTED, row!!.state)
            assertTrue(database.outboxBlobDao().getAllByCapsuleIdAndOwner(newCapsuleId, switchedUserUuid.toString()).size >= 5)
        }

        // Own-directory cleanup after SUCCESS too: no plaintext survives.
        awaitCondition("own directory removed after success") { !newDir.exists() }
    }

    @Test
    fun sameEpochRotationPreservesInProgressSessionStaging() {
        val vm = newViewModel(
            GatedNormalizer(CountDownLatch(1), CompletableDeferred(Unit)),
            CompletableDeferred(),
            AtomicInteger(0),
        )
        val roots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingRoot)
        vm.beginSession(1L, userUuid.toString())
        driveToReadyContent(vm, listOf("old-1", "old-2", "old-3"))
        val capsuleId = vm.capsuleId
        val liveDir = File(roots.createStagingRoot(UserId(userUuid)), capsuleId)
        check(liveDir.mkdirs())
        val livePhoto = File(liveDir, "in-progress.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }

        vm.beginSession(1L, userUuid.toString())

        assertEquals(capsuleId, vm.capsuleId)
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertTrue(vm.photoSelection.canProceed)
        assertTrue("same-epoch rotation must keep the live staging directory", liveDir.isDirectory)
        assertTrue("same-epoch rotation must keep in-progress plaintext", livePhoto.isFile)
        assertEquals(listOf<Byte>(7, 8, 9), livePhoto.readBytes().toList())
    }

    @Test
    fun routeExitCleansOwnedStagingExactlyOnceAndLeavesForeignAccountUntouched() {
        val vm = newViewModel(
            GatedNormalizer(CountDownLatch(1), CompletableDeferred(Unit)),
            CompletableDeferred(),
            AtomicInteger(0),
        )
        val roots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingRoot)
        vm.beginSession(1L, userUuid.toString())
        val ownedDir = File(roots.createStagingRoot(UserId(userUuid)), vm.capsuleId).apply { mkdirs() }
        val ownedPhoto = File(ownedDir, "plain.jpg").apply { writeBytes(byteArrayOf(4)) }
        val foreignPhoto = File(
            roots.createStagingRoot(UserId(switchedUserUuid)),
            "capsule-b/photo-00.jpg",
        ).apply {
            parentFile!!.mkdirs()
            writeText("B must survive")
        }

        vm.endSession()
        assertTrue(!ownedDir.exists())
        assertTrue(!ownedPhoto.exists())
        assertEquals("B must survive", foreignPhoto.readText())

        vm.endSession()
        assertTrue(!ownedDir.exists())
        assertEquals("B must survive", foreignPhoto.readText())
    }

    @Test
    fun accountSwitchCleansPreviousOwnerStagingAndStartsAFreshSession() {
        val vm = newViewModel(
            GatedNormalizer(CountDownLatch(1), CompletableDeferred(Unit)),
            CompletableDeferred(),
            AtomicInteger(0),
            switchedUserUuid,
        )
        val roots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingRoot)
        vm.beginSession(1L, userUuid.toString())
        driveToReadyContent(vm, listOf("old-1", "old-2", "old-3"))
        val previousCapsuleId = vm.capsuleId
        val previousDir = File(roots.createStagingRoot(UserId(userUuid)), previousCapsuleId).apply { mkdirs() }
        File(previousDir, "plain.jpg").writeBytes(byteArrayOf(5))

        vm.beginSession(1L, switchedUserUuid.toString())

        assertTrue(vm.capsuleId != previousCapsuleId)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertEquals(CreateViewModel.CreateUploadStatus.NotStarted, vm.uploadStatus.value)
        assertTrue(!previousDir.exists())
        assertTrue(vm.photoSelection.selectedIds.isEmpty())
    }

    @Test
    fun beginSessionRemovesOnlyTheReplacedSessionsOwnDirectory() {
        val identityGate = CompletableDeferred<SenderIdentitySnapshot>()
        val vm = newViewModel(
            GatedNormalizer(CountDownLatch(1), CompletableDeferred(Unit)),
            identityGate,
            AtomicInteger(0),
        )

        val roots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingRoot)
        val createRoot = roots.createStagingRoot(UserId(userUuid))
        check(createRoot.mkdirs())
        // Abandoned leftovers of a dead process, planted before any sweep.
        val abandonedA = File(createRoot, "11111111-2222-4333-8444-555555555555")
        File(abandonedA, "nested").mkdirs()
        File(abandonedA, "leftover.jpg").writeBytes(byteArrayOf(1))
        val abandonedUppercase = File(createRoot, "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE")
        abandonedUppercase.mkdirs()
        // NOT session-shaped entries must never be touched by the sweep.
        val foreignDir = File(createRoot, "not-a-uuid").apply { mkdirs() }
        val looseFile = File(createRoot, "stray.txt").apply { writeBytes(byteArrayOf(2)) }

        vm.beginSession(1L, userUuid.toString())
        assertTrue("process-death leftovers belong to the startup sweep", abandonedA.isDirectory)
        assertTrue(abandonedUppercase.isDirectory)
        assertTrue(foreignDir.isDirectory)
        assertTrue(looseFile.isFile)

        val epochOneDir = File(createRoot, vm.capsuleId).apply { mkdirs() }
        val liveMarker = File(epochOneDir, "live.txt").apply { writeBytes(byteArrayOf(3)) }
        val abandonedB = File(createRoot, "99999999-8888-4777-8666-555555555555").apply { mkdirs() }

        vm.beginSession(2L, userUuid.toString())
        assertTrue(!epochOneDir.exists())
        assertTrue(!liveMarker.isFile)
        assertTrue("unrelated UUID leftovers wait for the owner-scoped startup sweep", abandonedB.isDirectory)
        assertTrue(abandonedA.isDirectory)
        assertTrue(abandonedUppercase.isDirectory)
        assertTrue(foreignDir.isDirectory)
        assertTrue(looseFile.isFile)
        assertEquals(
            setOf(
                abandonedA.name,
                abandonedUppercase.name,
                foreignDir.name,
                looseFile.name,
                abandonedB.name,
            ),
            createRoot.listFiles()?.map { it.name }?.toSet(),
        )
    }

    @Test
    fun endSessionTearsDownTransientFieldsAndSameEpochBeginAfterEndStartsFresh() {
        val vm = newViewModel(
            GatedNormalizer(CountDownLatch(1), CompletableDeferred(Unit)),
            CompletableDeferred(),
            AtomicInteger(0),
        )
        vm.beginSession(1L, userUuid.toString())
        vm.pickerVm.onHandleChange("mykola")
        driveToReadyContent(vm, listOf("old-1", "old-2", "old-3"))
        val liveCapsuleId = vm.capsuleId
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertNotNull(vm.confirmedRecipient.value)
        assertTrue(vm.photoSelection.canProceed)
        assertEquals("owned staging note", vm.noteEditor.text)
        assertNotNull(vm.frontAttempt.phase)

        vm.endSession()

        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertEquals("", vm.pickerVm.handle.value)
        assertEquals(RecipientLookupUiState.Idle, vm.pickerVm.state.value)
        assertNull(vm.pendingRecipient.value)
        assertNull(vm.confirmedRecipient.value)
        assertTrue(vm.photoSelection.selectedIds.isEmpty())
        assertFalse(vm.photoSelection.canProceed)
        assertTrue(vm.noteEditor.isEmpty)
        assertFalse(vm.noteEditor.limitReached)
        assertNull(vm.frontAttempt.phase)
        assertNull(vm.flowError.value)
        assertNull(vm.publishError.value)
        assertEquals(CreateViewModel.CreateUploadStatus.NotStarted, vm.uploadStatus.value)

        vm.endSession()
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertTrue(vm.photoSelection.selectedIds.isEmpty())
        assertNull(vm.confirmedRecipient.value)

        vm.beginSession(1L, userUuid.toString())
        assertNotEquals(liveCapsuleId, vm.capsuleId)
        assertEquals(CreateViewModel.Step.RECIPIENT_LOOKUP, vm.step.value)
        assertTrue(vm.photoSelection.selectedIds.isEmpty())
        assertTrue(vm.noteEditor.isEmpty)
    }

    @Test
    fun endSessionUnlinksLeafSymlinkAndLeavesTheTargetUntouched() {
        val vm = newViewModel(
            GatedNormalizer(CountDownLatch(1), CompletableDeferred(Unit)),
            CompletableDeferred(),
            AtomicInteger(0),
        )
        val roots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingRoot)
        vm.beginSession(1L, userUuid.toString())
        val sessionDir = File(roots.createStagingRoot(UserId(userUuid)), vm.capsuleId)
        check(sessionDir.mkdirs())
        File(sessionDir, "plain.jpg").writeBytes(byteArrayOf(4))
        val foreignTarget = File(
            roots.child(UserId(switchedUserUuid), dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots.ChildRoot.FINGERPRINTS),
            "fp-b.fpw",
        ).apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(9, 8, 7))
        }
        val leaf = File(sessionDir, "escape.jpg")
        java.nio.file.Files.createSymbolicLink(leaf.toPath(), foreignTarget.toPath())
        val sessionAsLinkTarget = File(
            roots.createStagingRoot(UserId(switchedUserUuid)),
            "capsule-b",
        ).apply {
            mkdirs()
            File(this, "kept.bin").writeBytes(byteArrayOf(1, 2))
        }

        vm.endSession()
        assertTrue(!sessionDir.exists())
        assertTrue("leaf symlink target must survive session cleanup", foreignTarget.isFile)
        assertEquals(listOf<Byte>(9, 8, 7), foreignTarget.readBytes().toList())

        vm.beginSession(2L, userUuid.toString())
        val linkedSession = File(roots.createStagingRoot(UserId(userUuid)), vm.capsuleId)
        java.nio.file.Files.createSymbolicLink(linkedSession.toPath(), sessionAsLinkTarget.toPath())
        vm.endSession()
        assertTrue(!java.nio.file.Files.exists(linkedSession.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue("session-directory symlink target must survive", sessionAsLinkTarget.isDirectory)
        assertEquals(listOf<Byte>(1, 2), File(sessionAsLinkTarget, "kept.bin").readBytes().toList())
    }

    @Test
    fun accountCreateStagingRootsAreDisjoint() {
        val roots = dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(stagingRoot)
        val ownerA = roots.createStagingRoot(UserId(userUuid))
        val ownerB = roots.createStagingRoot(UserId(switchedUserUuid))
        val aPlaintext = File(ownerA, "capsule-a/photo-00.jpg").apply {
            parentFile!!.mkdirs()
            writeText("A")
        }
        val bPlaintext = File(ownerB, "capsule-b/photo-00.jpg").apply {
            parentFile!!.mkdirs()
            writeText("B")
        }

        assertTrue(aPlaintext.canonicalPath.startsWith(ownerA.canonicalPath))
        assertTrue(bPlaintext.canonicalPath.startsWith(ownerB.canonicalPath))
        assertTrue(aPlaintext.canonicalPath.contains(userUuid.toString()))
        assertTrue(bPlaintext.canonicalPath.contains(switchedUserUuid.toString()))
        assertTrue(aPlaintext.canonicalPath != bPlaintext.canonicalPath)
        assertEquals("A", aPlaintext.readText())
        assertEquals("B", bPlaintext.readText())
    }

    @Test
    fun missingOwnerFailsClosedWithoutCreatingGlobalOrAccountStaging() {
        val vm = newViewModel(
            GatedNormalizer(CountDownLatch(1), CompletableDeferred(Unit)),
            CompletableDeferred(),
            AtomicInteger(0),
        )
        vm.beginSession(1L)
        driveToReadyContent(vm, listOf("photo-1", "photo-2", "photo-3"))

        vm.startPublishing()

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertTrue(vm.flowError.value?.contains("owner", ignoreCase = true) == true)
        assertTrue(!File(stagingRoot, "accounts").exists())
        assertTrue(!File(stagingRoot.parentFile, "create-staging").exists())
    }

    private fun runBlockingNullable(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}

private fun synthetic(side: FingerprintSide): ProcessedStill.Accepted {
    val profile = RecognitionProfile.mvpOrbV1()
    val keypoints = List(64) {
        dev.hryshyn.remanence.core.recognition.FingerprintKeypoint(
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
        serializedBytes = dev.hryshyn.remanence.core.recognition.FingerprintCodec.serialize(
            dev.hryshyn.remanence.core.recognition.PostcardFingerprint(
                profileId = profile.profileId,
                canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                canonicalHeightPx = 1000,
                coarseHash64 = 9L,
                keypoints = keypoints,
                descriptors = List(64) { i ->
                    ByteArray(32) { ((it * 23 + i * 7) and 0xFF).toByte() }
                },
                quality = dev.hryshyn.remanence.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
            ),
        ),
    )
}
