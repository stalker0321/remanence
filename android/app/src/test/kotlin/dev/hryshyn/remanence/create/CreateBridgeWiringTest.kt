package dev.hryshyn.remanence.create

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.model.GeneratorStaging
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.ui.create.CreateViewModel
import dev.hryshyn.remanence.ui.create.RecipientDirectoryPort
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import dev.hryshyn.remanence.wiring.RemanenceViewModelFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C2 wiring tests (steps 1–3): the production container/factory/VM bridge
 * seam on memory fakes. Covers the per-owner container caches, the factory
 * provider default/override, the real EXIF decoder shape, and the frozen
 * VM session/invalidation boundaries (begin on publish; revoke on photo
 * edit, note edit, owner/epoch change, surface exit; legacy no-op without
 * a provider).
 *
 * No publisher cutover, no protocol/filesystem/renderer changes: the begun
 * sessions hold no staged bytes, so liveness is observed through the public
 * [GeneratorStaging.Manager.sweep] boundary (a sweep past TTL returns 1
 * for a live session, 0 once revoked).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class CreateBridgeWiringTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var database: RemanenceLocalDatabase
    private lateinit var stagingDir: File
    private lateinit var outboxDir: File

    private val identity = AccountIdentityGenerator().generate()
    private val userUuid = UUID.fromString("8e111111-2222-4333-8444-555555555555")
    private val bundleUuid = UUID.fromString("8e333333-4444-4555-8666-777777777777")

    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-wiring-retry-${UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    // Memory-backed bridge seam: one fake-clocked manager per owner.
    private var now = 1_000L
    private val managers = mutableMapOf<String, GeneratorStaging.Manager>()
    private val stores = mutableMapOf<String, FakeStore>()
    private val bridges = mutableMapOf<String, GeneratorCreateBridge.Bridge>()
    private val requestedOwners = mutableListOf<UserId>()

    @Before
    fun setUp() {
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stagingDir = File(context.filesDir, "bridge-wiring").apply { mkdirs() }
        outboxDir = File(context.filesDir, "bridge-wiring-outbox")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        stagingDir.deleteRecursively()
        outboxDir.deleteRecursively()
        File(context.filesDir, "accounts").deleteRecursively()
        // Factory smoke tests below build the file-backed Room database;
        // remove its files the same way the container tests do.
        val db = context.getDatabasePath(AppContainer.DATABASE_NAME)
        listOf(db, File(db.parentFile, "${AppContainer.DATABASE_NAME}-wal"), File(db.parentFile, "${AppContainer.DATABASE_NAME}-shm"))
            .forEach { it.delete() }
    }

    // ------------------------------------------------------------------
    // Memory fakes.
    // ------------------------------------------------------------------

    private class FakeStore : GeneratorStaging.BlobStore {
        val data = mutableMapOf<String, ByteArray>()
        override fun put(key: String, bytes: ByteArray) {
            data[key] = bytes.copyOf()
        }
        override fun get(key: String): ByteArray? = data[key]?.copyOf()
        override fun delete(key: String): Boolean = data.remove(key) != null
        override fun keys(): Set<String> = data.keys.toSet()
    }

    private class FakeNormalizer : PhotoNormalizerPort {
        override suspend fun normalize(inputJpeg: ByteArray) =
            NormalizedPhotoDto(ByteArray(32) { 5 }, 120, 213)
    }

    // The test binder decodes for real (production decoder): bind-time
    // upright dims must match the VM pre-read that built the begin input.
    private val memoryBinder = GeneratorSourceBinding.SourceBinder(FakeNormalizer(), GeneratorExifDecoder)

    private val memoryBridgeProvider: (UserId) -> GeneratorCreateBridge.Bridge = { owner ->
        requestedOwners += owner
        val key = owner.toRestString()
        val staging = managers.getOrPut(key) {
            val store = FakeStore()
            stores[key] = store
            GeneratorStaging.Manager(store, nowMillis = { now })
        }
        bridges.getOrPut(key) { GeneratorCreateBridge.Bridge(staging, memoryBinder) }
    }

    /** Non-destructive sweep past TTL summed over every memory manager. */
    private fun liveBridgeSessions(): Int =
        managers.values.sumOf { it.sweep(now + GeneratorStaging.DEFAULT_SESSION_TTL_MILLIS + 1) }

    private class Accepting(private val side: FingerprintSide) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill = ProcessedStill.Accepted(
            profileId = dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec.PROFILE_ID,
            serializedBytes = dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture.bytes(6),
        )
    }

    private class RecordingPersistence : dev.hryshyn.remanence.core.data.fingerprints.SealedFingerprintPersistence {
        val stored = mutableMapOf<String, ByteArray>()
        var counter = 0

        override suspend fun persist(
            capsuleId: String,
            origin: FingerprintOrigin,
            profileId: String,
            plaintextBytes: ByteArray,
        ): String {
            val id = "fp-${++counter}"
            stored[id] = plaintextBytes.copyOf()
            return id
        }

        override suspend fun hasBaseline(capsuleId: String, origin: FingerprintOrigin): Boolean =
            stored.isNotEmpty()

        override suspend fun decrypt(fingerprintId: String): ByteArray =
            requireNotNull(stored[fingerprintId]).copyOf()

        override suspend fun setPreferredOrigin(capsuleId: String, origin: FingerprintOrigin) = Unit

        override suspend fun deleteBaseline(capsuleId: String, origin: FingerprintOrigin) = Unit
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

    private fun senderIdentity() = SenderIdentitySnapshot(
        userId = userUuid.toString(),
        handle = "mykola",
        activeKeyBundleId = bundleUuid.toString(),
        encryptionPrivateHandle = identity.encryptionPrivateHandle,
        signingPrivateHandle = identity.signingPrivateHandle,
    )

    private fun realJpeg(width: Int = 64, height: Int = 32): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "fixture encode failed" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun realJpegWithOrientation(width: Int, height: Int, orientation: Int): ByteArray {
        val file = File.createTempFile("wiring-exif", ".jpg")
        try {
            file.writeBytes(realJpeg(width, height))
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    // Stable picker content: every open of one id yields identical bytes,
    // so pre-read hashes and bind-time re-reads always agree.
    private val sourceBytesById = mutableMapOf<String, ByteArray>()
    private val sourceOpens = mutableMapOf<String, Int>()

    private fun sourceBytes(id: String): ByteArray = sourceBytesById.getOrPut(id) {
        val (width, height) = when (id) {
            "w2" -> 80 to 40
            "w3" -> 96 to 48
            else -> 64 to 32
        }
        realJpeg(width, height)
    }

    private fun defaultSource(id: String): PhotoSource {
        sourceOpens[id] = sourceOpens.getOrDefault(id, 0) + 1
        return PhotoSource { ByteArrayInputStream(sourceBytes(id)) }
    }

    /** Builds a ViewModel parked at CONTENT with real-JPEG photos + note ready. */
    private fun contentStage(
        bridgeProvider: ((UserId) -> GeneratorCreateBridge.Bridge)? = memoryBridgeProvider,
        identity: suspend () -> SenderIdentitySnapshot = { senderIdentity() },
        normalizer: PhotoNormalizerPort = { input -> NormalizedPhotoDto(input.copyOf(), 800, 600) },
        sources: ((String) -> PhotoSource)? = null,
    ): CreateViewModel {
        val persistence = RecordingPersistence()
        val retryStore = SenderRetryMaterialStore(AccountScopedFileRoots(outboxDir))
        val vm = CreateViewModel(
            directory = StaticDirectory(),
            accessTokenProvider = { null },
            identityProvider = identity,
            persistence = persistence,
            outboxStager = CapsuleOutboxStager(database, AccountScopedFileRoots(outboxDir), retryStore),
            profile = RecognitionProfile.postcardSiftRootSiftV1(),
            accountScopedFileRoots = AccountScopedFileRoots(stagingDir),
            // Distinct bytes per photo: G1 requires a unique contentHash
            // per original (dedup key), so identical sources are rejected.
            openPhotoSource = { id -> sources?.invoke(id) ?: defaultSource(id) },
            frontProcessor = Accepting(FingerprintSide.FRONT),
            photoNormalizer = normalizer,
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
            outboxCapsuleDao = database.outboxCapsuleDao(),
            generatorBridgeProvider = bridgeProvider,
        )
        vm.beginSession(1L, userUuid.toString())
        vm.onResolved(selfSnapshot())
        vm.confirmRecipient()
        vm.frontAttempt.onPermissionResult(true, false)
        vm.frontAttempt.onPreviewBound()
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("f".toByteArray())
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        vm.onPhotosPicked(listOf("w1", "w2", "w3"))
        assertTrue(vm.noteEditor.onChange("wiring note"))
        return vm
    }

    private suspend fun outboxRow(capsuleId: String) =
        database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId, userUuid.toString())

    private fun awaitTerminalPublish(vm: CreateViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.step.value == CreateViewModel.Step.PUBLISHING) {
            if (System.currentTimeMillis() > deadline) {
                error("publishing never reached a terminal step; publishError=" + vm.publishError.value)
            }
            Thread.sleep(20)
        }
    }

    private fun container(): AppContainer = AppContainer(
        context,
        kekBoundaryOverride = SoftwareKekBoundary(),
    )

    // ------------------------------------------------------------------
    // Container + factory wiring (production seam, memory-free).
    // ------------------------------------------------------------------

    @Test
    fun containerBridgesAreCachedPerOwner() {
        val container = container()
        try {
            val ownerA = UserId.parseRest("9db5c67a-3a4e-45d1-8b0f-2f14a9bb1001")
            val ownerB = UserId.parseRest("9db5c67a-3a4e-45d1-8b0f-2f14a9bb1002")
            assertSame(container.generatorCreateBridge(ownerA), container.generatorCreateBridge(ownerA))
            assertTrue(container.generatorCreateBridge(ownerA) !== container.generatorCreateBridge(ownerB))
        } finally {
            container.database.close()
        }
    }

    @Test
    fun factoryDefaultsToContainerBridgesAndHonorsOverrides() {
        val container = container()
        try {
            val owner = UserId.parseRest("9db5c67a-3a4e-45d1-8b0f-2f14a9bb1003")
            val factory = RemanenceViewModelFactory(container)
            assertSame(container.generatorCreateBridge(owner), factory.generatorBridgeProvider(owner))

            val fake = memoryBridgeProvider(UserId.parseRest("8e111111-2222-4333-8444-555555555555"))
            val overridden = RemanenceViewModelFactory(container) { fake }
            assertSame(fake, overridden.generatorBridgeProvider(owner))
        } finally {
            container.database.close()
        }
    }

    @Test
    fun factoryCreatesCreateViewModelWithTheBridgeSeam() {
        val container = container()
        try {
            val vm = RemanenceViewModelFactory(container).create(CreateViewModel::class.java)
            vm.beginSession(1L, "9db5c67a-3a4e-45d1-8b0f-2f14a9bb1001")
            vm.endSession()
        } finally {
            container.database.close()
        }
    }

    // ------------------------------------------------------------------
    // Production EXIF decoder shape.
    // ------------------------------------------------------------------

    @Test
    fun productionDecoderReadsUprightDimsAndAppliesExifRotation() = runTest {
        assertEquals(
            GeneratorSourceBinding.UprightPhoto(64, 32),
            GeneratorExifDecoder.decodeUpright(realJpeg(64, 32)),
        )
        assertEquals(
            GeneratorSourceBinding.UprightPhoto(32, 64),
            GeneratorExifDecoder.decodeUpright(
                realJpegWithOrientation(64, 32, ExifInterface.ORIENTATION_ROTATE_90),
            ),
        )
    }

    @Test
    fun productionDecoderRejectsUndecodableBytes() = runTest {
        for (bytes in listOf(ByteArray(0), ByteArray(16) { it.toByte() })) {
            try {
                GeneratorExifDecoder.decodeUpright(bytes)
                fail("expected decode rejection")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    // ------------------------------------------------------------------
    // VM session-sync + invalidation boundaries on memory fakes.
    // ------------------------------------------------------------------

    @Test
    fun publishBeginsOneLiveBridgeSessionAndLeavesLegacyPathIntact() = runBlocking {
        val vm = contentStage()
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(
            "publishError=" + vm.publishError.value + " flowError=" + vm.flowError.value,
            CreateViewModel.Step.UPLOAD_PENDING,
            vm.step.value,
        )
        assertEquals(OutboxCapsuleState.ENCRYPTED, outboxRow(vm.capsuleId)!!.state)
        assertEquals(listOf(UserId(userUuid)), requestedOwners)
        assertEquals(1, liveBridgeSessions())
    }

    @Test
    fun photoEditAfterBeginRevokesTheBoundSession() = runBlocking {
        val vm = contentStage()
        vm.startPublishing()
        awaitTerminalPublish(vm)
        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)

        vm.onPhotosPicked(listOf("w1", "w2", "w4"))

        assertEquals(0, liveBridgeSessions())
    }

    @Test
    fun noteEditAfterBeginRevokesTheBoundSession() = runBlocking {
        val vm = contentStage()
        vm.startPublishing()
        awaitTerminalPublish(vm)
        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)

        assertTrue(vm.noteEditor.onChange("edited after generation"))

        assertEquals(0, liveBridgeSessions())
    }

    @Test
    fun ownerEpochChangeRevokesTheBoundSession() = runBlocking {
        val vm = contentStage()
        vm.startPublishing()
        awaitTerminalPublish(vm)
        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)

        vm.beginSession(2L, userUuid.toString())

        assertEquals(0, liveBridgeSessions())
    }

    @Test
    fun surfaceExitRevokesTheBoundSession() = runBlocking {
        val vm = contentStage()
        vm.startPublishing()
        awaitTerminalPublish(vm)
        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)

        vm.endSession()

        assertEquals(0, liveBridgeSessions())
    }

    @Test
    fun nullProviderKeepsTheLegacyPathWithoutAnyBridgeSession() = runBlocking {
        val vm = contentStage(bridgeProvider = null)
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)
        assertEquals(OutboxCapsuleState.ENCRYPTED, outboxRow(vm.capsuleId)!!.state)
        assertTrue(requestedOwners.isEmpty())
        assertTrue(managers.isEmpty())

        // Hooks stay no-ops without a provider.
        vm.onPhotosPicked(listOf("w1", "w2", "w3"))
        assertTrue(vm.noteEditor.onChange("still legacy"))
        vm.endSession()
    }

    @Test
    fun retryAfterBindFailureReBeginsCleanlyWithoutOrphan() = runBlocking {
        // Sabotage only the bind-time open of w2: the pre-read (first
        // open) succeeds so begin stores a binding, the bind re-read
        // (second open) fails.
        var sabotageBind = true
        val vm = contentStage(sources = { id ->
            val opens = sourceOpens.getOrDefault(id, 0)
            if (sabotageBind && id == "w2" && opens >= 1) {
                sourceOpens[id] = opens + 1
                PhotoSource { throw java.io.IOException("picker stream unavailable") }
            } else {
                defaultSource(id)
            }
        })
        vm.startPublishing()
        awaitTerminalPublish(vm)

        // Bind slot 1 fails: fail closed, session revoked, no partial row.
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertEquals("generator bind rejected slot 1; publishing cancelled", vm.publishError.value)
        assertEquals(0, liveBridgeSessions())
        assertNull(outboxRow(vm.capsuleId))

        sabotageBind = false
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)
        assertEquals(OutboxCapsuleState.ENCRYPTED, outboxRow(vm.capsuleId)!!.state)
        // Exactly one live session: the failed attempt left nothing behind.
        assertEquals(1, liveBridgeSessions())
    }

    @Test
    fun wiredPublishNormalizesExactlyOnceInsideTheBind() = runBlocking {
        val vm = contentStage(normalizer = {
            throw IllegalStateException("legacy normalize must not run on the wired path")
        })
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)
        assertEquals(OutboxCapsuleState.ENCRYPTED, outboxRow(vm.capsuleId)!!.state)
        // G3 ORIGINAL-byte staging contract: exactly the three pre-read
        // originals reached the store (never the 32-byte normalizer fakes).
        val stagedSizes = stores.values.flatMap { it.data.values }.map { it.size }.sorted()
        val expectedSizes =
            listOf(realJpeg(64, 32).size, realJpeg(80, 40).size, realJpeg(96, 48).size).sorted()
        assertEquals(expectedSizes, stagedSizes)
        assertEquals(1, liveBridgeSessions())
    }

    @Test
    fun publishFailureAfterBeginRevokesTheBoundSession() = runBlocking {
        val vm = contentStage()
        outboxDir.writeText("outbox root is unavailable")
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertEquals(0, liveBridgeSessions())
    }

    @Test
    fun wiredPublishReadsLocalIdentityExactlyTwice() = runBlocking {
        var identityReads = 0
        val vm = contentStage(identity = {
            identityReads++
            senderIdentity()
        })
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)
        // Exactly once for the publication (generator begin + final
        // request share the captured snapshot), plus the required
        // fail-closed owner-change re-check. Three reads would mean the
        // bridge and the publisher snapshotted independently.
        assertEquals(2, identityReads)
    }
}
