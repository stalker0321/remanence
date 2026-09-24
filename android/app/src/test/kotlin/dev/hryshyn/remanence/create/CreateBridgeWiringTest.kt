package dev.hryshyn.remanence.create

import android.content.Context
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
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorStaging
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.crypto.ContentManifestCodec
import dev.hryshyn.remanence.core.crypto.ExpressionReceiverAdmission
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.data.outbox.OutboxArtifactKind
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.security.MessageDigest
import dev.hryshyn.remanence.ui.create.CreateViewModel
import dev.hryshyn.remanence.ui.create.RecipientDirectoryPort
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import dev.hryshyn.remanence.wiring.RemanenceViewModelFactory
import java.io.ByteArrayInputStream
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

    // Shared memory-backed bridge seam (fresh per test via the class instance).
    private val bridge = MemoryGeneratorBridge()

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
    // Fixtures.
    // ------------------------------------------------------------------

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

    private fun memoryTestJpegWithOrientation(width: Int, height: Int, orientation: Int): ByteArray {
        val file = File.createTempFile("wiring-exif", ".jpg")
        try {
            file.writeBytes(memoryTestJpeg(width, height))
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    // Open counting for bind-time sabotage tests. Re-encoding is
    // deterministic, so pre-read hashes and bind re-reads always agree.
    private val sourceOpens = mutableMapOf<String, Int>()

    private fun defaultSource(id: String): PhotoSource {
        sourceOpens[id] = sourceOpens.getOrDefault(id, 0) + 1
        val (width, height) = when (id) {
            "w2" -> 80 to 40
            "w3" -> 96 to 48
            else -> 64 to 32
        }
        return PhotoSource { ByteArrayInputStream(memoryTestJpeg(width, height)) }
    }

    /** Builds a ViewModel parked at CONTENT with real-JPEG photos + note ready. */
    private fun contentStage(
        bridgeProvider: ((UserId) -> GeneratorCreateBridge.Bridge)? = bridge.provider,
        identity: suspend () -> SenderIdentitySnapshot = { senderIdentity() },
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
            cpuDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            senderRetryKeysetWrapper = testWrapper,
            senderRetryKekAlias = testAlias,
            enqueueUpload = { _, _ -> },
            outboxCapsuleDao = database.outboxCapsuleDao(),
            generatorBridgeProvider = bridgeProvider,
            generatorPreviewLoader = previewLoader,
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
        // ADR-018 note policy: a non-empty note is a typed unsupported publish
        // until on-device text measurement is wired, so the happy path here
        // publishes with an absent/empty note.
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

            val fake = bridge.provider(UserId.parseRest("8e111111-2222-4333-8444-555555555555"))
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
            GeneratorExifDecoder.decodeUpright(memoryTestJpeg(64, 32)),
        )
        assertEquals(
            GeneratorSourceBinding.UprightPhoto(32, 64),
            GeneratorExifDecoder.decodeUpright(
                memoryTestJpegWithOrientation(64, 32, ExifInterface.ORIENTATION_ROTATE_90),
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
    fun publishBindsFreezesAndLeavesOneLiveSession() = runBlocking {
        val vm = contentStage()
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(
            "publishError=" + vm.publishError.value + " flowError=" + vm.flowError.value,
            CreateViewModel.Step.UPLOAD_PENDING,
            vm.step.value,
        )
        assertEquals(OutboxCapsuleState.ENCRYPTED, outboxRow(vm.capsuleId)!!.state)
        assertEquals(listOf(UserId(userUuid)), bridge.requestedOwners)
        assertEquals(1, bridge.liveSessions())
    }

    @Test
    fun photoEditAfterBeginRevokesTheBoundSession() = runBlocking {
        val vm = contentStage()
        vm.startPublishing()
        awaitTerminalPublish(vm)
        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)

        vm.onPhotosPicked(listOf("w1", "w2", "w4"))

        assertEquals(0, bridge.liveSessions())
    }

    @Test
    fun noteEditAfterBeginRevokesTheBoundSession() = runBlocking {
        val vm = contentStage()
        vm.startPublishing()
        awaitTerminalPublish(vm)
        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)

        assertTrue(vm.noteEditor.onChange("edited after generation"))

        assertEquals(0, bridge.liveSessions())
    }

    @Test
    fun ownerEpochChangeRevokesTheBoundSession() = runBlocking {
        val vm = contentStage()
        vm.startPublishing()
        awaitTerminalPublish(vm)
        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)

        vm.beginSession(2L, userUuid.toString())

        assertEquals(0, bridge.liveSessions())
    }

    @Test
    fun surfaceExitRevokesTheBoundSession() = runBlocking {
        val vm = contentStage()
        vm.startPublishing()
        awaitTerminalPublish(vm)
        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)

        vm.endSession()

        assertEquals(0, bridge.liveSessions())
    }

    @Test
    fun nullProviderFailsClosedWithoutBridge() = runBlocking {
        val vm = contentStage(bridgeProvider = null)
        vm.startPublishing()
        awaitTerminalPublish(vm)

        // No legacy fallback: without a bridge the publication fails
        // closed and stages nothing.
        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertEquals("generator bridge is unavailable; publishing cancelled", vm.publishError.value)
        assertNull(outboxRow(vm.capsuleId))
        assertTrue(bridge.requestedOwners.isEmpty())
        assertTrue(bridge.stores.isEmpty())

        // Hooks stay no-ops without a provider.
        vm.onPhotosPicked(listOf("w1", "w2", "w3"))
        assertTrue(vm.noteEditor.onChange("no bridge either"))
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
        assertEquals(0, bridge.liveSessions())
        assertNull(outboxRow(vm.capsuleId))

        sabotageBind = false
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)
        assertEquals(OutboxCapsuleState.ENCRYPTED, outboxRow(vm.capsuleId)!!.state)
        // Exactly one live session: the failed attempt left nothing behind.
        assertEquals(1, bridge.liveSessions())
    }

    /** The exact original bytes `defaultSource` yields for a picker id. */
    private fun expectedOriginalBytes(id: String): ByteArray = when (id) {
        "w2" -> memoryTestJpeg(80, 40)
        "w3" -> memoryTestJpeg(96, 48)
        else -> memoryTestJpeg(64, 32)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Preview loader over the SAME originals the publish pre-read opens, so the
     * preview input and the publish input are descriptor-identical.
     */
    private val previewLoader = dev.hryshyn.remanence.create.GeneratorPreviewLoader { pickerId ->
        val bytes = expectedOriginalBytes(pickerId)
        val upright = dev.hryshyn.remanence.create.GeneratorExifDecoder.decodeUpright(bytes)
        dev.hryshyn.remanence.create.LoadedPreviewSource(
            originalHash = sha256Hex(bytes),
            uprightWidthPx = upright.widthPx,
            uprightHeightPx = upright.heightPx,
            previewJpegBytes = bytes,
            previewWidthPx = upright.widthPx,
            previewHeightPx = upright.heightPx,
        )
    }

    private val fitsPort = dev.hryshyn.remanence.core.model.GeneratorEditorialRows.NoteMeasurementPort {
        dev.hryshyn.remanence.core.model.GeneratorEditorialRows.NoteMeasurement.Fits(
            dev.hryshyn.remanence.core.model.GeneratorEditorialRows.NOTE_REGION,
        )
    }

    /** Opens the published outbox capsule and returns its decrypted manifest. */
    private suspend fun decryptedContent(vm: CreateViewModel): dev.hryshyn.remanence.core.crypto.ContentManifestContent {
        val row = outboxRow(vm.capsuleId)!!
        val opened = RecipientEnvelopeCryptor().open(
            identity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CapsuleId(UUID.fromString(vm.capsuleId)),
                UserId(userUuid),
                UserId(userUuid),
                KeyBundleId(UUID.fromString(row.recipientKeyBundleId)),
            ),
            File(requireNotNull(row.envelopePath)).readBytes(),
        )
        val capsuleKeyset = TinkProtoKeysetFormat.parseKeyset(
            RecipientEnvelopePlaintext.parseFrom(opened).capsuleAeadKeyset.toByteArray(),
            InsecureSecretKeyAccess.get(),
        )
        val contentRow = database.outboxBlobDao()
            .getAllByCapsuleIdAndOwner(vm.capsuleId, userUuid.toString())
            .single { it.kind == OutboxArtifactKind.CONTENT_MANIFEST.name }
        return ContentManifestCodec().decryptAndParse(
            capsuleKeyset,
            RecognitionManifestCodec.RoutingContext(
                CapsuleId(UUID.fromString(vm.capsuleId)),
                BlobId(UUID.fromString(contentRow.blobId)),
                UserId(userUuid),
                UserId(userUuid),
            ),
            File(contentRow.localCiphertextPath).readBytes(),
        )
    }

    /**
     * VM-level publish→receive proof: a real CreateViewModel publication seals
     * a v2 BER1 expression whose source descriptors are the EXACT pre-read
     * originals, and the receiver admits it with a recomputed `BEXPR01`
     * projection equal to the sealed one.
     */
    @Test
    fun publishedCapsuleSealsV2ExpressionAdmittedByTheReceiver() = runBlocking {
        val vm = contentStage()
        vm.startPublishing()
        awaitTerminalPublish(vm)
        assertEquals(CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)

        val parsed = decryptedContent(vm)
        assertEquals(2, parsed.protocolVersion)
        val admitted = ExpressionReceiverAdmission.admit(parsed)
        assertTrue("expected Supported, got $admitted", admitted is ExpressionReceiverAdmission.Result.Supported)
        val expression = (admitted as ExpressionReceiverAdmission.Result.Supported).expression

        // Exact original-descriptor identity, authored order.
        val expectedIds = listOf("w1", "w2", "w3").map { id ->
            CapsulePhotoIdentity.contentIdFor(sha256Hex(expectedOriginalBytes(id)))
        }
        assertEquals(expectedIds, expression.input.photos.map { it.contentId })
        assertEquals(
            listOf("w1", "w2", "w3").map { sha256Hex(expectedOriginalBytes(it)) },
            expression.input.photos.map { it.contentHash },
        )

        // Receiver-recomputable projection == sealed projection. The sealed
        // candidateId is the sender's frozen one (it binds owner/epoch-free
        // geometry + original descriptors + encrypted blob ids).
        assertEquals(
            CapsulePhotoIdentity.projectedHash(
                expression = expression,
                candidateId = parsed.expression!!.candidateId,
                capsuleId = CapsuleId(UUID.fromString(vm.capsuleId)),
                contentIds = expression.input.photos.map { it.contentId },
            ),
            parsed.expression!!.projectionHash,
        )
    }

    @Test
    fun measuredNoteSelectionPublishesV2ExpressionWithNoteRegion() = runBlocking {
        val vm = contentStage()
        assertTrue(vm.noteEditor.onChange("dear mama"))
        val pending = vm.generatorPreview.value as? dev.hryshyn.remanence.ui.create.GeneratorPreviewState.NotePending
            ?: error("expected NotePending, got ${vm.generatorPreview.value}")
        val measured = (
            dev.hryshyn.remanence.core.model.GeneratorEditorialRows.plan(pending.input, fitsPort)
                as dev.hryshyn.remanence.core.model.GeneratorEditorialRows.PlanResult.Planned
            ).expression
        vm.onPreviewMeasured(measured)

        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals("publishError=${vm.publishError.value}", CreateViewModel.Step.UPLOAD_PENDING, vm.step.value)
        assertEquals(OutboxCapsuleState.ENCRYPTED, outboxRow(vm.capsuleId)!!.state)
        val parsed = decryptedContent(vm)
        val admitted = ExpressionReceiverAdmission.admit(parsed)
        assertTrue("expected Supported, got $admitted", admitted is ExpressionReceiverAdmission.Result.Supported)
        val expression = (admitted as ExpressionReceiverAdmission.Result.Supported).expression
        assertEquals("dear mama", expression.input.note)
        assertEquals(
            dev.hryshyn.remanence.core.model.GeneratorEditorialRows.NOTE_REGION,
            expression.noteRegion,
        )
    }

    @Test
    fun unmeasuredNoteIsTypedRejectedAndStagesNothing() = runBlocking {
        val vm = contentStage()
        assertTrue(vm.noteEditor.onChange("dear mama"))
        // No onPreviewMeasured: the host never measured this note.
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertNotNull(vm.publishError.value)
        assertTrue(
            "expected a note-measurement rejection, was ${vm.publishError.value}",
            vm.publishError.value!!.contains("measure"),
        )
        assertNull(outboxRow(vm.capsuleId))
        assertEquals(0, bridge.liveSessions())
    }

    @Test
    fun editedNoteAfterMeasurementIsTypedRejected() = runBlocking {
        val vm = contentStage()
        assertTrue(vm.noteEditor.onChange("dear mama"))
        val pending = vm.generatorPreview.value as? dev.hryshyn.remanence.ui.create.GeneratorPreviewState.NotePending
            ?: error("expected NotePending, got ${vm.generatorPreview.value}")
        val measured = (
            dev.hryshyn.remanence.core.model.GeneratorEditorialRows.plan(pending.input, fitsPort)
                as dev.hryshyn.remanence.core.model.GeneratorEditorialRows.PlanResult.Planned
            ).expression
        vm.onPreviewMeasured(measured)
        // Editing the note invalidates the measurement for the old text.
        assertTrue(vm.noteEditor.onChange("dear mama edited"))

        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertNotNull(vm.publishError.value)
        assertNull(outboxRow(vm.capsuleId))
        assertEquals(0, bridge.liveSessions())
    }

    @Test
    fun tamperedBindDescriptorIsTypedRejectedAndStagesNothing() = runBlocking {
        // The pre-read originals are authoritative; a bind-time decoder that
        // reports different upright dims is a tampered original descriptor and
        // must fail closed (the exact-descriptor gate can never publish it).
        val tamperDecoder = object : GeneratorSourceBinding.PhotoDecoderPort {
            override suspend fun decodeUpright(jpeg: ByteArray) =
                GeneratorSourceBinding.UprightPhoto(999, 999)
        }
        val tamperBinder = GeneratorSourceBinding.SourceBinder(
            MemoryGeneratorBridge.FakeNormalizer(),
            tamperDecoder,
        )
        val tamperStore = MemoryGeneratorBridge.FakeStore()
        val tamperBridge = GeneratorCreateBridge.Bridge(
            GeneratorStaging.Manager(tamperStore, nowMillis = { 1_000L }),
            tamperBinder,
        )
        val vm = contentStage(bridgeProvider = { tamperBridge })
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertNotNull(vm.publishError.value)
        assertTrue(
            "expected a bind rejection, was ${vm.publishError.value}",
            vm.publishError.value!!.contains("bind rejected"),
        )
        assertNull(outboxRow(vm.capsuleId))
        assertTrue(tamperStore.data.isEmpty())
    }

    @Test
    fun tamperedFrozenHandoffIsTypedRejectedAndSealsNoExpression() = runBlocking {
        // A bridge whose frozen handoff descriptors differ from the session's
        // pre-read originals must abort BEFORE any expression is sealed.
        val tamperStore = MemoryGeneratorBridge.FakeStore()
        val tamperStaging = GeneratorStaging.Manager(tamperStore, nowMillis = { 1_000L })
        val tamperBinder = GeneratorSourceBinding.SourceBinder(
            MemoryGeneratorBridge.FakeNormalizer(),
            GeneratorExifDecoder,
        )
        val tampering = object : GeneratorCreateBridge.Bridge(tamperStaging, tamperBinder) {
            override fun freeze(
                context: GeneratorCreateBridge.GenerationContext,
                sessionId: String,
            ): GeneratorCreateBridge.FreezeResult {
                val real = super.freeze(context, sessionId)
                val handoff = (real as? GeneratorCreateBridge.FreezeResult.Frozen)?.handoff
                    ?: return real
                val tampered = handoff.input.copy(
                    photos = handoff.input.photos.map { it.copy(widthPx = it.widthPx + 1) },
                )
                return GeneratorCreateBridge.FreezeResult.Frozen(
                    handoff.copy(
                        input = tampered,
                        inputHash = GeneratorExpression.canonicalHash(tampered),
                    ),
                )
            }
        }
        val vm = contentStage(bridgeProvider = { tampering })
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertNotNull(vm.publishError.value)
        assertTrue(
            "expected the exact-descriptor gate to abort, was ${vm.publishError.value}",
            vm.publishError.value!!.contains("projection changed"),
        )
        assertNull(outboxRow(vm.capsuleId))
    }

    @Test
    fun wiredPublishConsumesBindResultsWithoutLegacyStaging() = runBlocking {
        val vm = contentStage()
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(
            "publishError=" + vm.publishError.value + " flowError=" + vm.flowError.value,
            CreateViewModel.Step.UPLOAD_PENDING,
            vm.step.value,
        )
        assertEquals(OutboxCapsuleState.ENCRYPTED, outboxRow(vm.capsuleId)!!.state)
        // G3 ORIGINAL-byte staging contract: exactly the three pre-read
        // originals reached the store (never the 32-byte normalizer fakes).
        val expectedSizes =
            listOf(memoryTestJpeg(64, 32).size, memoryTestJpeg(80, 40).size, memoryTestJpeg(96, 48).size).sorted()
        assertEquals(expectedSizes, bridge.stagedSizes())
        // No legacy photo staging directory is ever created, so no
        // plaintext can leak through the removed path.
        val legacyRoot = AccountScopedFileRoots(stagingDir).createStagingRoot(UserId(userUuid))
        assertTrue(legacyRoot.listFiles()?.isEmpty() ?: true)
        assertEquals(1, bridge.liveSessions())
    }

    @Test
    fun publishFailureAfterBeginRevokesTheBoundSession() = runBlocking {
        val vm = contentStage()
        outboxDir.writeText("outbox root is unavailable")
        vm.startPublishing()
        awaitTerminalPublish(vm)

        assertEquals(CreateViewModel.Step.CONTENT, vm.step.value)
        assertEquals(0, bridge.liveSessions())
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
