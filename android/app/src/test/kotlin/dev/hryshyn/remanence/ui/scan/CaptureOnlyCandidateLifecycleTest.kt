package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.TinkProtoKeysetFormat
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.*
import dev.hryshyn.remanence.core.data.fingerprints.EncryptedFingerprintStore
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.model.*
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.ScanGrantManager
import dev.hryshyn.remanence.create.CapsulePublisher
import dev.hryshyn.remanence.create.CapsulePublishRequest
import dev.hryshyn.remanence.identity.DirectorySenderKeyStore
import dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import dev.hryshyn.remanence.wiring.KekBoundSecretSealer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fresh storage/index regression, not a received-grant/publication harness.
 * Real encrypted FRONT store, Room close/reopen, local publisher/stager; no
 * network, scan capture, matching, provider operation or grant is exercised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureOnlyCandidateLifecycleTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val owner = UUID.fromString("45800000-0000-4000-8000-000000000001")
    private val foreign = UUID.fromString("45800000-0000-4000-8000-000000000002")
    private val bundle = UUID.fromString("45800000-0000-4000-8000-000000000003")
    private val capture = UUID.fromString("45800000-0000-4000-8000-000000000004")
    private val valid = UUID.fromString("45800000-0000-4000-8000-000000000005")
    private val identity = AccountIdentityGenerator().generate()
    private val profile = RecognitionProfile.postcardSiftRootSiftV1()
    private val front = CanonicalSiftFingerprintFixture.bytes(11)
    private var currentOwner = owner
    private lateinit var context: Context
    private lateinit var database: RemanenceLocalDatabase
    private lateinit var directory: File
    private lateinit var roots: AccountScopedFileRoots
    private lateinit var databaseName: String
    private val kek = SoftwareKekBoundary()
    private val alias = "capture-lifecycle-${UUID.randomUUID()}"

    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        databaseName = "t458-capture-${UUID.randomUUID()}.db"
        directory = File(context.filesDir, "t458-capture-${UUID.randomUUID()}").apply { mkdirs() }
        roots = AccountScopedFileRoots(directory)
        database = openDatabase()
        kek.createAes256GcmKey(alias)
    }
    @After fun release() {
        database.close()
        context.deleteDatabase(databaseName)
        directory.deleteRecursively() // This test's unique synthetic files only.
        Dispatchers.resetMain()
    }
    private fun openDatabase() = Room.databaseBuilder(context, RemanenceLocalDatabase::class.java, databaseName)
        .allowMainThreadQueries().build()
    private fun reopen() { database.close(); database = openDatabase() }
    private fun store() = EncryptedFingerprintStore(roots,
        KekBoundSecretSealer(kek, "$alias-front"), database.recognitionFingerprintDao(), { currentOwner.toString() })
    private suspend fun capture(id: UUID = capture): String =
        store().persist(id.toString(), FingerprintOrigin.SENDER, profile.profileId, front.copyOf())
    private fun stager(beforeCommit: (() -> Unit)? = null) = CapsuleOutboxStager(database, roots,
        SenderRetryMaterialStore(roots), beforeCommit = beforeCommit)
    private fun prepared(id: UUID, storageOwner: UUID = owner) = CapsulePublisher(SenderRetryKeysetWrapper(kek), alias).publish(
        CapsulePublishRequest(capsuleId = CapsuleId(id), senderUserId = UserId(storageOwner),
            recipientUserId = UserId(storageOwner), senderKeyBundleId = KeyBundleId(bundle),
            recipientKeyBundleId = KeyBundleId(bundle), ownerUserId = storageOwner.toString(),
            senderHandleSnapshot = "synthetic", createdAtEpochSeconds = 1_700_000_000,
            photoJpegs = List(3) { "synthetic-photo-$it".toByteArray() },
            photoWidthsPx = List(3) { 320 }, photoHeightsPx = List(3) { 240 }, noteUtf8 = "synthetic",
            frontFingerprintBytes = front.copyOf(), signingKeyset = identity.signingPrivateHandle,
            recipientEncryptionPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.encryptionPublicKeyset)))

    private suspend fun index(sources: Map<UUID, CapsulePresentationSource> = emptyMap(),
        afterIncoming: () -> Unit = {}): ScanCandidateIndex {
        val vm = ScanViewModel(persistence = store(), database = database, profile = profile,
            identityProvider = { SenderIdentitySnapshot(currentOwner.toString(), "synthetic", bundle.toString(),
                identity.encryptionPrivateHandle, identity.signingPrivateHandle) },
            trustedSenderKeys = DirectorySenderKeyStore(directoryFetch = { error("no directory work") }, ownAccount = { null }),
            presentationGrants = PresentationGrantAuthority(ScanGrantManager({ 1_000L })),
            frontProcessor = StillProcessor { error("no capture work") },
            candidateIndexProvider = { afterIncoming(); ScanCandidateIndex(emptyList(), presentationSources = sources) },
            incomingPresentationPreparation = null, cpuDispatcher = dispatcher, ioDispatcher = dispatcher)
        return try { vm.buildCandidateIndexForTests() } finally { vm.viewModelScope.cancel() }
    }
    private suspend fun assertIds(expected: Set<UUID>, sources: Map<UUID, CapsulePresentationSource> = emptyMap()) {
        val result = index(sources)
        try { assertEquals(expected, result.candidates.map { it.capsuleId }.toSet()) }
        finally { result.wipeFingerprints() }
    }

    @Test fun captureOnlySurvivesRestartWithoutBecomingACapsule() = runBlocking {
        val fingerprint = capture()
        val before = directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).path to it.readBytes().toList() }
        assertIds(emptySet())
        reopen()
        assertIds(emptySet()) // New VM and new Room instance, same durable capture.
        assertEquals(1, database.recognitionFingerprintDao().getAllForOwner(owner.toString()).size)
        val decoded = store().decrypt(fingerprint)
        try { assertArrayEquals(front, decoded) } finally { decoded.fill(0) }
        assertEquals(before, directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).path to it.readBytes().toList() })
    }

    @Test fun validSamePostcardRemainsBesidePreservedCaptureOnlyRow() = runBlocking {
        capture(); capture(valid)
        stager().stage(prepared(valid))
        reopen()
        assertIds(setOf(valid))
        assertEquals(2, database.recognitionFingerprintDao().getAllForOwner(owner.toString()).size)
    }

    @Test fun failedBeforeCommitIsNotAdmittedAndLaterIndependentDraftIs() = runBlocking {
        capture()
        try { stager { error("synthetic before commit") }.stage(prepared(capture)); fail("must fail") }
        catch (expected: IllegalStateException) { assertEquals("synthetic before commit", expected.message) }
        reopen()
        assertNull(database.outboxCapsuleDao().getByCapsuleIdAndOwner(capture.toString(), owner.toString()))
        assertIds(emptySet())
        capture(valid); stager().stage(prepared(valid))
        assertIds(setOf(valid))
    }

    @Test fun encryptedCommitSurvivesFailureBeforeEnqueueAndIndexRetry() = runBlocking {
        capture()
        stager().stage(prepared(capture))
        // Models scheduler refusal AFTER commit, not WorkManager execution.
        val enqueue = runCatching { error("synthetic enqueue refused") }
        assertTrue(enqueue.isFailure)
        reopen()
        assertEquals(OutboxCapsuleState.ENCRYPTED,
            database.outboxCapsuleDao().getByCapsuleIdAndOwner(capture.toString(), owner.toString())?.state)
        repeat(2) { assertIds(setOf(capture)) }
    }

    @Test fun unreadIncomingMembershipAndMissingHintAreNotCaptureOnly() = runBlocking {
        capture()
        val result = index(mapOf(capture to CapsulePresentationSource.INCOMING))
        try {
            assertEquals(listOf(capture), result.candidates.map { it.capsuleId })
            assertTrue(result.chooserHints.isEmpty())
            assertEquals(CapsulePresentationSource.INCOMING, result.presentationSources[capture])
        } finally { result.wipeFingerprints() }
    }

    @Test fun pendingMembershipRemainsEligible() = runBlocking {
        capture()
        assertIds(setOf(capture), mapOf(capture to CapsulePresentationSource.PENDING))
    }

    @Test fun durableIncomingOutsideReadyProjectionIsNotMistakenForCaptureOnly() = runBlocking {
        capture()
        database.incomingCapsuleDao().upsertAllForOwner(owner.toString(), listOf(IncomingCapsuleEntity(
            capsuleId = capture.toString(), ownerUserId = owner.toString(), senderUserId = foreign.toString(),
            recipientUserId = owner.toString(), senderSigningKeyBundleId = bundle.toString(),
            recipientEncryptionKeyBundleId = bundle.toString(), protocolVersion = 1,
            serverStatus = "READY", readyAtEpochMs = 1_700_000_000_000,
            signedStatementBytes = byteArrayOf(1), materialState = LocalMaterialState.DISCOVERED)))
        assertTrue(database.incomingCapsuleDao().selectSenderIndexCandidatesForOwner(UserId(owner)).isEmpty())
        reopen()
        val result = index()
        try {
            assertEquals(listOf(capture), result.candidates.map { it.capsuleId })
            assertTrue("membership must not fabricate access", result.presentationSources.isEmpty())
        } finally { result.wipeFingerprints() }
    }

    @Test fun unresolvedDualRemainsVisibleAndFailClosed() = runBlocking {
        capture(); stager().stage(prepared(capture))
        val result = index(mapOf(capture to CapsulePresentationSource.INCOMING))
        try {
            assertEquals(listOf(capture), result.candidates.map { it.capsuleId })
            assertTrue(result.presentationSources.isEmpty())
        } finally { result.wipeFingerprints() }
    }

    @Test fun foreignOutboxCannotAdmitOwnersCapture() = runBlocking {
        capture(); stager().stage(prepared(capture, foreign))
        assertIds(emptySet())
    }

    @Test fun ownerChangeDuringLoadDiscardsIndex() = runBlocking {
        capture(); stager().stage(prepared(capture))
        val result = index(afterIncoming = { currentOwner = foreign })
        assertTrue(result.candidates.isEmpty())
    }
}
