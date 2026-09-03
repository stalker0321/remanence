package dev.hryshyn.remanence.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.data.fingerprints.SecretSealer
import dev.hryshyn.remanence.core.data.db.BlobCacheEntity
import dev.hryshyn.remanence.core.data.db.BlobCacheState
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleEntity
import dev.hryshyn.remanence.core.data.db.IncomingEnvelopeEntity
import dev.hryshyn.remanence.core.data.db.IncomingSyncSession
import dev.hryshyn.remanence.core.data.db.IncomingIndexAcceptanceCommitRequest
import dev.hryshyn.remanence.core.data.db.IncomingIndexAcceptanceCommitResult
import dev.hryshyn.remanence.core.data.db.IncomingIndexAcceptanceCommitter
import dev.hryshyn.remanence.core.data.db.IncomingIndexAcceptanceFailure
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.network.ApiBaseUrl
import dev.hryshyn.remanence.core.data.network.AuthTokenHolder
import dev.hryshyn.remanence.core.data.network.ProductionApiStack
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadFailure
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadResult
import dev.hryshyn.remanence.core.data.network.SessionRotationSink
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.IncomingRecognitionCiphertextAdopter
import dev.hryshyn.remanence.core.data.storage.IncomingRecognitionCiphertextAdoptionFailure
import dev.hryshyn.remanence.core.data.storage.IncomingRecognitionCiphertextAdoptionRequest
import dev.hryshyn.remanence.core.data.storage.IncomingRecognitionCiphertextAdoptionResult
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.TestSenderVerification
import dev.hryshyn.remanence.core.recognition.ExtractionQuality
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintKeypoint
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.PostcardFingerprint
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.identity.TrustedSenderKeyStore
import dev.hryshyn.remanence.index.SenderIndexBundleCodec
import dev.hryshyn.remanence.index.SenderIndexBundleFileAttributes
import dev.hryshyn.remanence.index.SenderIndexBundleReader
import dev.hryshyn.remanence.index.SenderIndexBundleReaderFileSystem
import dev.hryshyn.remanence.index.SenderIndexBundleStageRequest
import dev.hryshyn.remanence.index.SenderIndexBundleStageResult
import dev.hryshyn.remanence.index.SenderIndexBundleStager
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingCapsuleAcceptanceCoordinatorTest {

    private val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000aa01")
    private val otherOwner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000aa02")
    private val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000cc01")
    private val blob = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000bb01")
    private val secondBlob = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000bb02")
    private val sender = "0198f0a0-0000-7000-8000-00000000ee01"
    private val senderBundle = "0198f0a0-0000-7000-8000-00000000ab01"
    private val recipientBundle = "0198f0a0-0000-7000-8000-00000000ad01"
    private val bytes = "verified-recognition-ciphertext".toByteArray()

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var roots: AccountScopedFileRoots
    private lateinit var adopter: IncomingRecognitionCiphertextAdopter
    private lateinit var committer: IncomingIndexAcceptanceCommitter
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testRoot = File(context.cacheDir, "a11d1-${System.nanoTime()}").apply { mkdirs() }
        roots = AccountScopedFileRoots(testRoot)
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        adopter = IncomingRecognitionCiphertextAdopter(roots)
        committer = IncomingIndexAcceptanceCommitter(database, roots)
    }

    @After
    fun tearDown() {
        database.close()
        testRoot.deleteRecursively()
    }

    @Test
    fun freshAbsentIncomingRootUsesExactOrderAndRealAdoptionAndRoomCommit() = runBlocking {
        seed()
        assertFalse(roots.child(owner, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT).exists())
        val events = mutableListOf<String>()
        var requested: IncomingControlIndexAcceptanceRequest? = null
        val verifiedPayload = verifiedPayload()
        val result = coordinator(
            download = writingDownloader { events += "download" },
            control = IncomingControlIndexAcceptancePort { request ->
                events += "crypto"
                requested = request
                IncomingControlIndexAcceptancePortResult.Verified(verifiedPayload)
            },
            persistence = IncomingVerifiedControlIndexPersistencePort { request, authenticatedOwner ->
                events += "persist"
                assertEquals(owner, authenticatedOwner)
                assertEquals(owner, request.ownerUserId)
                assertEquals(capsule, request.capsuleId)
                assertSame(verifiedPayload, request.verified)
                IncomingVerifiedControlIndexPersistenceResult.Durable
            },
            adoptionPort = IncomingRecognitionAdoptionPort { request ->
                events += "adopt"
                assertEquals(owner, request.ownerUserId)
                assertEquals(capsule, request.capsuleId)
                assertEquals(blob, request.blobId)
                assertEquals(bytes.size.toLong(), request.expectedSizeBytes)
                adopter.adopt(request)
            },
            commitPort = IncomingIndexCommitPort { request, authenticatedOwner ->
                events += "commit"
                assertEquals(owner, authenticatedOwner)
                committer.commit(request, authenticatedOwner)
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertIs<IncomingCapsuleAcceptanceResult.Committed>(result)
        assertEquals(listOf("download", "crypto", "persist", "adopt", "commit"), events)
        assertEquals(recoveryTempPath(), requested!!.recognitionCiphertextFile)
        assertFalse(recoveryTempPath().exists())
        assertArrayEquals(bytes, incomingCiphertextPath().readBytes())
        assertEquals(
            LocalMaterialState.INDEX_CACHED,
            database.incomingCapsuleDao()
                .getByCapsuleIdAndOwner(capsule.toRestString(), owner.toRestString())!!.materialState,
        )
        assertEquals(
            BlobCacheState.CACHED,
            database.blobCacheDao()
                .getByBlobIdAndOwner(blob.toRestString(), owner.toRestString())!!.cacheState,
        )
    }

    @Test
    fun reconstructedTest7UpgradeRepairsStaleSafePathBeforeDownloadAdoptionAndCommit() = runBlocking {
        seed()
        val oldPath = File(testRoot, "legacy-test7/capsules/${capsule.toRestString()}/${blob.toRestString()}.bin")
            .apply {
                parentFile!!.mkdirs()
                writeBytes("old-test7-ciphertext".toByteArray())
            }
        rewriteRecognitionPath(oldPath.path)

        val result = coordinator().accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertIs<IncomingCapsuleAcceptanceResult.Committed>(result)
        assertArrayEquals("old-test7-ciphertext".toByteArray(), oldPath.readBytes())
        assertArrayEquals(bytes, incomingCiphertextPath().readBytes())
        assertEquals(incomingCiphertextPath().path, recognitionRow().localPath)
        assertEquals(BlobCacheState.CACHED, recognitionRow().cacheState)
    }

    @Test
    fun reconstructedTest7UpgradeRepairsStaleUnsafePathWithoutTouchingIt() = runBlocking {
        seed()
        val outside = File(testRoot.parentFile, "a11d1-legacy-outside-${System.nanoTime()}").apply {
            mkdirs()
        }
        try {
            val oldPath = roots.accountDirectory(owner).toPath().resolve("legacy-test7-link.ciphertext")
            Files.createDirectories(oldPath.parent)
            Files.createSymbolicLink(oldPath, outside.toPath())
            rewriteRecognitionPath(oldPath.toString())

            val result = coordinator().accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

            assertIs<IncomingCapsuleAcceptanceResult.Committed>(result)
            assertTrue(Files.isSymbolicLink(oldPath))
            assertTrue(outside.isDirectory)
            assertArrayEquals(bytes, incomingCiphertextPath().readBytes())
            assertEquals(incomingCiphertextPath().path, recognitionRow().localPath)
            assertEquals(BlobCacheState.CACHED, recognitionRow().cacheState)
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun exactIdempotentReplaySkipsNetworkAndReturnsSuccess() = runBlocking {
        seed()
        val downloads = AtomicInteger(0)
        val first = coordinator(
            download = writingDownloader { downloads.incrementAndGet() },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertIs<IncomingCapsuleAcceptanceResult.Committed>(first)

        var snapshotClosed = 0
        val replay = coordinator(
            download = IncomingRecipientBlobDownloader { _, _ ->
                downloads.incrementAndGet()
                throw AssertionError("idempotent replay must not download")
            },
            inspection = availableInspection { snapshotClosed += 1 },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertIs<IncomingCapsuleAcceptanceResult.IdempotentReplay>(replay)
        assertEquals(1, downloads.get())
        assertEquals(1, snapshotClosed)
    }

    @Test
    fun alreadyAcceptedReplayAccountSwitchBeforeInspectionFailsClosedWithoutSideEffects() = runBlocking {
        seed()
        promoteToAlreadyAccepted()
        val beforeCiphertext = incomingCiphertextPath().readBytes()
        val recoveryTemp = recoveryTempPath()
        val indexDestination = incomingIndexBundlePath()
        val beforeRecoveryTemp = recoveryTemp.takeIf(File::exists)?.readBytes()
        val beforeIndexDestination = indexDestination.takeIf(File::exists)?.readBytes()
        assertFalse(recoveryTemp.exists())
        assertFalse(indexDestination.exists())
        val beforeCapsuleState = database.incomingCapsuleDao()
            .getByCapsuleIdAndOwner(capsule.toRestString(), owner.toRestString())!!
            .materialState
        val beforeBlobState = cachedBlobState()
        var inspectionCalls = 0

        val result = coordinator(
            session = sessionSequence(
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(otherOwner, "other-token"),
            ),
            inspection = IncomingSenderIndexBundleInspectionPort {
                inspectionCalls += 1
                throw AssertionError("account-changed replay must not inspect durable index")
            },
            download = IncomingRecipientBlobDownloader { _, _ ->
                throw AssertionError("account-changed replay must not download")
            },
            control = IncomingControlIndexAcceptancePort {
                throw AssertionError("account-changed replay must not run crypto")
            },
            persistence = IncomingVerifiedControlIndexPersistencePort { _, _ ->
                throw AssertionError("account-changed replay must not persist")
            },
            adoptionPort = IncomingRecognitionAdoptionPort {
                throw AssertionError("account-changed replay must not adopt")
            },
            commitPort = IncomingIndexCommitPort { _, _ ->
                throw AssertionError("account-changed replay must not commit")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.ACCOUNT_CHANGED,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(result).reason,
        )
        assertEquals(0, inspectionCalls)
        assertArrayEquals(beforeCiphertext, incomingCiphertextPath().readBytes())
        assertEquals(beforeRecoveryTemp?.toList(), recoveryTemp.takeIf(File::exists)?.readBytes()?.toList())
        assertEquals(beforeIndexDestination?.toList(), indexDestination.takeIf(File::exists)?.readBytes()?.toList())
        assertEquals(
            beforeCapsuleState,
            database.incomingCapsuleDao()
                .getByCapsuleIdAndOwner(capsule.toRestString(), owner.toRestString())!!
                .materialState,
        )
        assertEquals(beforeBlobState, cachedBlobState())
    }

    @Test
    fun productionConstructorBindsRealReaderForAcceptedReplayAndRejectsMissingBundle() = runBlocking {
        seed()
        promoteToAlreadyAccepted()
        val sealer = CoordinatorAuthenticatedSealer()
        val staged = SenderIndexBundleStager(roots, sealer).stage(
            SenderIndexBundleStageRequest(
                authenticatedOwnerUserId = owner,
                ownerUserId = owner,
                capsuleId = capsule,
                verifiedRecognition = indexRecognition(),
                senderVerification = TestSenderVerification.forCapsule(capsule),
            ),
        )
        assertTrue(staged is SenderIndexBundleStageResult.Staged)

        val snapshotCloseCalls = AtomicInteger(0)
        val reader = SenderIndexBundleReader(
            roots = roots,
            sealer = sealer,
            codec = SenderIndexBundleCodec(),
            fileSystem = CoordinatorReaderFileSystem,
            wipe = { it.fill(0) },
            wipeChars = {
                snapshotCloseCalls.incrementAndGet()
                it.fill('\u0000')
            },
        )
        val replay = productionCoordinator(reader).accept(
            IncomingCapsuleAcceptanceRequest(owner, capsule),
        )
        assertIs<IncomingCapsuleAcceptanceResult.IdempotentReplay>(replay)
        assertEquals(1, snapshotCloseCalls.get())

        incomingIndexBundlePath().delete()
        val missing = productionCoordinator(reader).accept(
            IncomingCapsuleAcceptanceRequest(owner, capsule),
        )
        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.DURABLE_STATE_INVALID,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(missing).reason,
        )
    }

    @Test
    fun durableReplayRequiresExistingIndexBundleAndNeverRunsAcceptancePipeline() = runBlocking {
        seed()
        assertIs<IncomingCapsuleAcceptanceResult.Committed>(
            coordinator().accept(IncomingCapsuleAcceptanceRequest(owner, capsule)),
        )

        val calls = AtomicInteger(0)
        val missing = coordinator(
            inspection = IncomingSenderIndexBundleInspectionPort {
                calls.incrementAndGet()
                IncomingSenderIndexBundleInspectionResult.Missing
            },
            download = IncomingRecipientBlobDownloader { _, _ ->
                throw AssertionError("missing durable index must not download")
            },
            control = IncomingControlIndexAcceptancePort {
                throw AssertionError("missing durable index must not re-run crypto")
            },
            adoptionPort = IncomingRecognitionAdoptionPort {
                throw AssertionError("missing durable index must not adopt")
            },
            commitPort = IncomingIndexCommitPort { _, _ ->
                throw AssertionError("missing durable index must not commit")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.DURABLE_STATE_INVALID,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(missing).reason,
        )
        assertEquals(1, calls.get())

        val corrupt = coordinator(
            inspection = IncomingSenderIndexBundleInspectionPort {
                IncomingSenderIndexBundleInspectionResult.Invalid
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.DURABLE_STATE_INVALID,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(corrupt).reason,
        )
    }

    @Test
    fun durableReplayUnavailableIsRetryableAndSnapshotClosesAfterAccountSwitch() = runBlocking {
        seed()
        assertIs<IncomingCapsuleAcceptanceResult.Committed>(
            coordinator().accept(IncomingCapsuleAcceptanceRequest(owner, capsule)),
        )

        val unavailable = coordinator(
            inspection = IncomingSenderIndexBundleInspectionPort {
                IncomingSenderIndexBundleInspectionResult.Unavailable(
                    IncomingSenderIndexBundleInspectionUnavailableReason.SEALER_UNAVAILABLE,
                )
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRetryReason.VERIFIED_PAYLOAD_PERSISTENCE,
            assertIs<IncomingCapsuleAcceptanceResult.Retryable>(unavailable).reason,
        )

        var snapshotClosed = 0
        val switched = coordinator(
            session = sessionSequence(
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(otherOwner, "other-token"),
            ),
            inspection = availableInspection { snapshotClosed += 1 },
            download = IncomingRecipientBlobDownloader { _, _ ->
                throw AssertionError("account switch replay must not download")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.ACCOUNT_CHANGED,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(switched).reason,
        )
        assertEquals(1, snapshotClosed)
    }

    @Test
    fun durableReplayCloseFailureCannotMaskAccountChange() = runBlocking {
        seed()
        assertIs<IncomingCapsuleAcceptanceResult.Committed>(
            coordinator().accept(IncomingCapsuleAcceptanceRequest(owner, capsule)),
        )

        var closeCalls = 0
        val switched = coordinator(
            session = sessionSequence(
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(otherOwner, "other-token"),
            ),
            inspection = availableInspection {
                closeCalls += 1
                throw IllegalStateException("close failure")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.ACCOUNT_CHANGED,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(switched).reason,
        )
        assertEquals(1, closeCalls)
    }

    @Test
    fun inspectorIsNotCalledForDiscoveredDeclaration() = runBlocking {
        seed()
        var inspectionCalls = 0
        val result = coordinator(
            inspection = IncomingSenderIndexBundleInspectionPort {
                inspectionCalls += 1
                throw AssertionError("DISCOVERED declaration must not inspect durable index")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertIs<IncomingCapsuleAcceptanceResult.Committed>(result)
        assertEquals(0, inspectionCalls)
    }

    @Test
    fun postLockAlreadyAcceptedUsesTheSameDurableReplayReconciliation() = runBlocking {
        seed()
        var sessionCalls = 0
        var inspectionCalls = 0
        var snapshotClosed = 0
        var downloads = 0
        val result = coordinator(
            session = {
                sessionCalls += 1
                if (sessionCalls == 2) promoteToAlreadyAccepted()
                IncomingSyncSession(owner, "token")
            },
            inspection = IncomingSenderIndexBundleInspectionPort { request ->
                inspectionCalls += 1
                assertEquals(owner, request.authenticatedOwnerUserId)
                assertEquals(owner, request.ownerUserId)
                assertEquals(capsule, request.capsuleId)
                availableInspection { snapshotClosed += 1 }
                    .inspect(request)
            },
            download = IncomingRecipientBlobDownloader { _, _ ->
                downloads += 1
                throw AssertionError("post-lock replay must not download")
            },
            control = IncomingControlIndexAcceptancePort {
                throw AssertionError("post-lock replay must not re-run crypto")
            },
            adoptionPort = IncomingRecognitionAdoptionPort {
                throw AssertionError("post-lock replay must not adopt")
            },
            commitPort = IncomingIndexCommitPort { _, _ ->
                throw AssertionError("post-lock replay must not commit")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertIs<IncomingCapsuleAcceptanceResult.IdempotentReplay>(result)
        assertEquals(1, inspectionCalls)
        assertEquals(1, snapshotClosed)
        assertEquals(0, downloads)
    }

    @Test
    fun retryableAdoptionPreservesDeterministicSourceForReconstructedCoordinator() = runBlocking {
        seed()
        assertFalse(roots.child(owner, AccountScopedFileRoots.ChildRoot.TEMP).exists())
        var firstAdoption = true
        val first = coordinator(
            adoptionPort = IncomingRecognitionAdoptionPort {
                if (firstAdoption) {
                    firstAdoption = false
                    IncomingRecognitionCiphertextAdoptionResult.Failure(
                        IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
                        retryable = true,
                    )
                } else {
                    adopter.adopt(it)
                }
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRetryReason.ADOPTION,
            assertIs<IncomingCapsuleAcceptanceResult.Retryable>(first).reason,
        )
        assertTrue(recoveryTempPath().exists())
        assertArrayEquals(bytes, recoveryTempPath().readBytes())

        val downloads = AtomicInteger(0)
        val second = coordinator(
            download = IncomingRecipientBlobDownloader { _, _ ->
                downloads.incrementAndGet()
                throw AssertionError("reconstructed retry must use retained source")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertIs<IncomingCapsuleAcceptanceResult.Committed>(second)
        assertEquals(0, downloads.get())
        assertFalse(recoveryTempPath().exists())
        assertEquals(BlobCacheState.CACHED, cachedBlobState())
    }

    @Test
    fun existingTempRootSymlinkIsRejectedWithoutFollowingIt() = runBlocking {
        seed()
        val outside = File(testRoot.parentFile, "a11d1-outside-${System.nanoTime()}").apply { mkdirs() }
        try {
            val ownerDirectory = roots.accountDirectory(owner).toPath()
            Files.createDirectories(ownerDirectory)
            val tempRoot = ownerDirectory.resolve(AccountScopedFileRoots.ChildRoot.TEMP.directoryName)
            Files.createSymbolicLink(
                tempRoot,
                outside.toPath(),
            )

            var downloads = 0
            val result = coordinator(
                download = IncomingRecipientBlobDownloader { _, _ ->
                    downloads += 1
                    throw AssertionError("unsafe TEMP must not download")
                },
            ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

            assertEquals(
                IncomingCapsuleAcceptanceRejectionReason.TEMP_PATH_UNSAFE,
                assertIs<IncomingCapsuleAcceptanceResult.Rejected>(result).reason,
            )
            assertEquals(0, downloads)
            assertTrue(outside.isDirectory)
            assertFalse(
                tempRoot.resolve(
                    "incoming-recognition/${capsule.toRestString()}/blobs/" +
                        "${blob.toRestString()}.ciphertext.tmp",
                ).toFile().isFile,
            )
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun existingTempNonDirectoryComponentIsRejectedWithoutReplacingIt() = runBlocking {
        seed()
        val tempRoot = roots.child(owner, AccountScopedFileRoots.ChildRoot.TEMP).toPath()
        Files.createDirectories(tempRoot)
        val component = tempRoot.resolve("incoming-recognition").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val result = coordinator().accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.TEMP_PATH_UNSAFE,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(result).reason,
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), component.readBytes())
    }

    @Test
    fun existingTempDescendantSymlinkIsRejectedWithoutFollowingIt() = runBlocking {
        seed()
        val tempRoot = roots.child(owner, AccountScopedFileRoots.ChildRoot.TEMP).toPath()
        val outside = File(testRoot.parentFile, "a11d1-outside-descendant-${System.nanoTime()}").apply { mkdirs() }
        try {
            Files.createDirectories(tempRoot)
            Files.createSymbolicLink(tempRoot.resolve("incoming-recognition"), outside.toPath())

            val result = coordinator().accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

            assertEquals(
                IncomingCapsuleAcceptanceRejectionReason.TEMP_PATH_UNSAFE,
                assertIs<IncomingCapsuleAcceptanceResult.Rejected>(result).reason,
            )
            assertFalse(outside.resolve(capsule.toRestString()).exists())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun existingIncomingRootSymlinkIsRejectedWithoutFollowingIt() = runBlocking {
        seed()
        val ownerDirectory = roots.accountDirectory(owner).toPath()
        Files.createDirectories(ownerDirectory)
        val redirected = ownerDirectory.resolve("incoming-target").toFile().apply { mkdirs() }
        val incomingRoot = ownerDirectory.resolve(
            AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT.directoryName,
        )
        Files.createSymbolicLink(incomingRoot, redirected.toPath())

        var downloads = 0
        val result = coordinator(
            download = IncomingRecipientBlobDownloader { _, _ ->
                downloads += 1
                throw AssertionError("unsafe incoming root must not download")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.TEMP_PATH_UNSAFE,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(result).reason,
        )
        assertEquals(0, downloads)
        assertFalse(redirected.resolve("capsules").exists())
    }

    @Test
    fun existingIncomingRootNonDirectoryIsRejectedWithoutReplacingIt() = runBlocking {
        seed()
        val ownerDirectory = roots.accountDirectory(owner).toPath()
        Files.createDirectories(ownerDirectory)
        val incomingRoot = ownerDirectory.resolve(
            AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT.directoryName,
        ).toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val result = coordinator().accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.TEMP_PATH_UNSAFE,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(result).reason,
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), incomingRoot.readBytes())
    }

    @Test
    fun escapingIncomingRootIsRejectedWithoutFollowingIt() = runBlocking {
        seed()
        val outside = File(testRoot.parentFile, "a11d1-incoming-outside-${System.nanoTime()}").apply { mkdirs() }
        try {
            val ownerDirectory = roots.accountDirectory(owner).toPath()
            Files.createDirectories(ownerDirectory)
            Files.createSymbolicLink(
                ownerDirectory.resolve(AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT.directoryName),
                outside.toPath(),
            )

            var downloads = 0
            val result = coordinator(
                download = IncomingRecipientBlobDownloader { _, _ ->
                    downloads += 1
                    throw AssertionError("escaping incoming root must not download")
                },
            ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

            assertEquals(
                IncomingCapsuleAcceptanceRejectionReason.TEMP_PATH_UNSAFE,
                assertIs<IncomingCapsuleAcceptanceResult.Rejected>(result).reason,
            )
            assertEquals(0, downloads)
            assertFalse(outside.resolve("capsules").exists())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun corruptRetainedSourceFailsClosedAndCleansOnlyItsDerivedPath() = runBlocking {
        seed()
        recoveryTempPath().parentFile!!.mkdirs()
        recoveryTempPath().writeBytes("corrupt".toByteArray())
        var downloads = 0

        val result = coordinator(
            download = IncomingRecipientBlobDownloader { _, _ ->
                downloads += 1
                throw AssertionError("corrupt retained source must not download")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.RECOVERY_TEMP_INVALID,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(result).reason,
        )
        assertEquals(0, downloads)
        assertFalse(recoveryTempPath().exists())
        assertInitialState()
    }

    @Test
    fun concurrentFirstCallersReconcileExactDestinationWinner() = runBlocking {
        seed()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val callNumber = AtomicInteger(0)
        val downloader = IncomingRecipientBlobDownloader { request, _ ->
            if (callNumber.incrementAndGet() == 1) {
                assertTrue(request.destination.createNewFile())
                request.destination.writeBytes(bytes.copyOf(bytes.size / 2))
                firstEntered.countDown()
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                request.destination.writeBytes(bytes)
                RecipientBlobDownloadResult.Success(request.destination, bytes.size.toLong())
            } else {
                throw AssertionError("second caller must wait for the first pipeline")
            }
        }

        val results = coroutineScope {
            val first = async(Dispatchers.IO) {
                coordinator(download = downloader).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO) {
                coordinator(download = downloader).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
            }
            delay(100)
            assertFalse(second.isCompleted)
            assertTrue(recoveryTempPath().exists())
            releaseFirst.countDown()
            listOf(first.await(), second.await())
        }

        assertEquals(1, results.count { it is IncomingCapsuleAcceptanceResult.Committed })
        assertEquals(1, results.count { it is IncomingCapsuleAcceptanceResult.IdempotentReplay })
        assertEquals(1, callNumber.get())
        assertArrayEquals(bytes, incomingCiphertextPath().readBytes())
        assertEquals(BlobCacheState.CACHED, cachedBlobState())
    }

    @Test
    fun downloadRetryAndTerminalFailureMapAndCleanSource() = runBlocking {
        seed()
        var retryCalls = 0
        val retry = coordinator(
            download = IncomingRecipientBlobDownloader { request, _ ->
                retryCalls += 1
                request.destination.createNewFile()
                request.destination.writeBytes(bytes)
                RecipientBlobDownloadResult.Failure(
                    reason = RecipientBlobDownloadFailure.NETWORK,
                    retryable = true,
                )
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRetryReason.DOWNLOAD,
            assertIs<IncomingCapsuleAcceptanceResult.Retryable>(retry).reason,
        )
        assertEquals(1, retryCalls)
        assertFalse(recoveryTempPath().exists())

        var terminalCalls = 0
        val terminal = coordinator(
            download = IncomingRecipientBlobDownloader { request, _ ->
                terminalCalls += 1
                request.destination.createNewFile()
                request.destination.writeBytes(bytes)
                RecipientBlobDownloadResult.Failure(
                    reason = RecipientBlobDownloadFailure.INTEGRITY_FAILED,
                    retryable = false,
                )
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.DOWNLOAD_REJECTED,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(terminal).reason,
        )
        assertEquals(1, terminalCalls)
        assertFalse(recoveryTempPath().exists())
        assertInitialState()
    }

    @Test
    fun cryptoRetryAndRejectionCleanSourceAndNeverAdopt() = runBlocking {
        seed()
        val retry = coordinator(
            control = IncomingControlIndexAcceptancePort {
                IncomingControlIndexAcceptancePortResult.Retryable(
                    IncomingAcceptanceRetryReason.RECIPIENT_KEY_UNAVAILABLE,
                )
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRetryReason.CRYPTO_ACCEPTANCE,
            assertIs<IncomingCapsuleAcceptanceResult.Retryable>(retry).reason,
        )
        assertFalse(recoveryTempPath().exists())
        assertInitialState()

        seed()
        val rejected = coordinator(
            control = IncomingControlIndexAcceptancePort {
                IncomingControlIndexAcceptancePortResult.Rejected(
                    IncomingAcceptanceRejectionReason.SIGNATURE_INVALID,
                )
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.CRYPTO_REJECTED,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(rejected).reason,
        )
        assertFalse(recoveryTempPath().exists())
        assertInitialState()
    }

    @Test
    fun verifiedPersistenceMustSucceedBeforeAdoptionAndCanBeReplayed() = runBlocking {
        seed()
        val payload = verifiedPayload()
        var cryptoCalls = 0
        var adoptionCalls = 0
        var commitCalls = 0
        val first = coordinator(
            control = IncomingControlIndexAcceptancePort {
                cryptoCalls += 1
                IncomingControlIndexAcceptancePortResult.Verified(payload)
            },
            persistence = IncomingVerifiedControlIndexPersistencePort { _, _ ->
                IncomingVerifiedControlIndexPersistenceResult.Retryable(
                    IncomingVerifiedControlIndexPersistenceRetryReason.LOCAL_STORAGE,
                )
            },
            adoptionPort = IncomingRecognitionAdoptionPort {
                adoptionCalls += 1
                throw AssertionError("persistence must precede adoption")
            },
            commitPort = IncomingIndexCommitPort { _, _ ->
                commitCalls += 1
                throw AssertionError("persistence must precede commit")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertEquals(
            IncomingCapsuleAcceptanceRetryReason.VERIFIED_PAYLOAD_PERSISTENCE,
            assertIs<IncomingCapsuleAcceptanceResult.Retryable>(first).reason,
        )
        assertEquals(1, cryptoCalls)
        assertEquals(0, adoptionCalls)
        assertEquals(0, commitCalls)
        assertFalse(recoveryTempPath().exists())
        assertInitialState()

        val second = coordinator(
            control = IncomingControlIndexAcceptancePort {
                cryptoCalls += 1
                IncomingControlIndexAcceptancePortResult.Verified(payload)
            },
            adoptionPort = IncomingRecognitionAdoptionPort {
                adoptionCalls += 1
                adopter.adopt(it)
            },
            commitPort = IncomingIndexCommitPort { request, authenticatedOwner ->
                commitCalls += 1
                committer.commit(request, authenticatedOwner)
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertIs<IncomingCapsuleAcceptanceResult.Committed>(second)
        assertEquals(2, cryptoCalls)
        assertEquals(1, adoptionCalls)
        assertEquals(1, commitCalls)
        assertEquals(BlobCacheState.CACHED, cachedBlobState())
    }

    @Test
    fun unavailableLocalCapabilityIsGenericPersistenceRejectionNotOwnerMismatch() = runBlocking {
        seed()
        var adoptionCalls = 0
        var commitCalls = 0
        val result = coordinator(
            persistence = IncomingVerifiedControlIndexPersistencePort { _, _ ->
                IncomingVerifiedControlIndexPersistenceResult.Rejected(
                    IncomingVerifiedControlIndexPersistenceRejectionReason.LOCAL_CAPABILITY_UNAVAILABLE,
                )
            },
            adoptionPort = IncomingRecognitionAdoptionPort {
                adoptionCalls += 1
                throw AssertionError("rejected persistence must stop adoption")
            },
            commitPort = IncomingIndexCommitPort { _, _ ->
                commitCalls += 1
                throw AssertionError("rejected persistence must stop commit")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.PERSISTENCE_REJECTED,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(result).reason,
        )
        assertEquals(0, adoptionCalls)
        assertEquals(0, commitCalls)
        assertInitialState()
    }

    @Test
    fun verifiedPersistenceCancellationStopsBeforeAdoptionAndCommit() = runBlocking {
        seed()
        val cancellation = CancellationException("persistence cancelled")
        var adoptionCalls = 0
        var commitCalls = 0
        var propagated = false
        try {
            coordinator(
                persistence = IncomingVerifiedControlIndexPersistencePort { _, _ ->
                    throw cancellation
                },
                adoptionPort = IncomingRecognitionAdoptionPort {
                    adoptionCalls += 1
                    throw AssertionError("cancelled persistence must stop adoption")
                },
                commitPort = IncomingIndexCommitPort { _, _ ->
                    commitCalls += 1
                    throw AssertionError("cancelled persistence must stop commit")
                },
            ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        assertEquals(0, adoptionCalls)
        assertEquals(0, commitCalls)
        assertFalse(recoveryTempPath().exists())
        assertInitialState()
    }

    @Test
    fun accountChangesAtEachBoundaryFailClosedBeforeAdvancement() = runBlocking {
        seed()
        val beforeMetadata = coordinator(
            session = sessionSequence(null),
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.NO_AUTHENTICATED_OWNER,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(beforeMetadata).reason,
        )
        assertInitialState()

        seed()
        val beforeNetwork = coordinator(
            session = sessionSequence(
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(otherOwner, "other-token"),
            ),
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.ACCOUNT_CHANGED,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(beforeNetwork).reason,
        )
        assertInitialState()

        seed()
        var downloads = 0
        val beforeCrypto = coordinator(
            session = sessionSequence(
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(otherOwner, "other-token"),
            ),
            download = writingDownloader { downloads += 1 },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.ACCOUNT_CHANGED,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(beforeCrypto).reason,
        )
        assertEquals(1, downloads)
        assertFalse(recoveryTempPath().exists())
        assertInitialState()

        seed()
        var adopted = 0
        val beforeAdoption = coordinator(
            session = sessionSequence(
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(otherOwner, "other-token"),
            ),
            adoptionPort = IncomingRecognitionAdoptionPort {
                adopted += 1
                adopter.adopt(it)
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.ACCOUNT_CHANGED,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(beforeAdoption).reason,
        )
        assertEquals(0, adopted)
        assertInitialState()

        seed()
        var commits = 0
        val beforeCommit = coordinator(
            session = sessionSequence(
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(owner, "token"),
                IncomingSyncSession(otherOwner, "other-token"),
            ),
            commitPort = IncomingIndexCommitPort { _, _ ->
                commits += 1
                throw AssertionError("account switch must stop before commit")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.ACCOUNT_CHANGED,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(beforeCommit).reason,
        )
        assertEquals(0, commits)
        assertInitialState()
        assertArrayEquals(bytes, incomingCiphertextPath().readBytes())
    }

    @Test
    fun malformedMultipleAndWrongOwnerDeclarationsNeverReachNetwork() = runBlocking {
        seed(recognitionCount = 2)
        var downloads = 0
        val multiple = coordinator(
            download = IncomingRecipientBlobDownloader { _, _ ->
                downloads += 1
                throw AssertionError("multiple recognition rows must stop before network")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(multiple).reason,
        )
        assertEquals(0, downloads)

        seed()
        val wrongOwner = coordinator(
            session = { IncomingSyncSession(otherOwner, "other-token") },
            download = IncomingRecipientBlobDownloader { _, _ ->
                throw AssertionError("wrong owner must not reach network")
            },
        ).accept(IncomingCapsuleAcceptanceRequest(otherOwner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRejectionReason.CAPSULE_METADATA_MISSING,
            assertIs<IncomingCapsuleAcceptanceResult.Rejected>(wrongOwner).reason,
        )
        assertInitialState()
    }

    @Test
    fun adopterRetryableAndCommitterFailureMapWithoutDeletingDurableWinner() = runBlocking {
        seed()
        val adoptionFailure = coordinator(
            adoptionPort = IncomingRecognitionAdoptionPort {
                IncomingRecognitionCiphertextAdoptionResult.Failure(
                    IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
                    retryable = true,
                )
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRetryReason.ADOPTION,
            assertIs<IncomingCapsuleAcceptanceResult.Retryable>(adoptionFailure).reason,
        )
        assertTrue(recoveryTempPath().exists())

        seed()
        val commitFailure = coordinator(
            commitPort = IncomingIndexCommitPort { _, _ ->
                IncomingIndexAcceptanceCommitResult.Failure(
                    IncomingIndexAcceptanceFailure.CONCURRENT_OR_STALE,
                    retryable = true,
                )
            },
        ).accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        assertEquals(
            IncomingCapsuleAcceptanceRetryReason.ROOM_COMMIT,
            assertIs<IncomingCapsuleAcceptanceResult.Retryable>(commitFailure).reason,
        )
        assertTrue(incomingCiphertextPath().exists())
        assertArrayEquals(bytes, incomingCiphertextPath().readBytes())
        assertInitialState()
    }

    @Test
    fun cancellationPropagatesAndDoesNotAdvanceRows() = runBlocking {
        seed()
        val cancellation = CancellationException("cancelled")
        var propagated = false
        try {
            coordinator(session = { throw cancellation })
                .accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
        } catch (thrown: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        assertInitialState()
        assertFalse(recoveryTempPath().exists())
    }

    @Test
    fun resultsAndInputsAreRedacted() {
        val request = IncomingCapsuleAcceptanceRequest(owner, capsule)
        val payload = verifiedPayload()
        assertFalse(request.toString().contains(owner.toRestString()))
        assertFalse(request.toString().contains(capsule.toRestString()))
        assertFalse(payload.toString().contains("sender"))
        assertFalse(
            IncomingControlIndexAcceptancePortResult.Verified(payload)
                .toString().contains("sender"),
        )
        assertFalse(
            IncomingVerifiedControlIndexPersistenceRequest(owner, capsule, payload)
                .toString().contains("sender"),
        )
        assertFalse(
            IncomingCapsuleAcceptanceResult.Retryable(
                IncomingCapsuleAcceptanceRetryReason.DOWNLOAD,
            ).toString().contains("token"),
        )
        assertFalse(
            IncomingCapsuleAcceptanceResult.Rejected(
                IncomingCapsuleAcceptanceRejectionReason.DOWNLOAD_REJECTED,
            ).toString().contains(testRoot.path),
        )
    }

    private fun coordinator(
        session: suspend () -> IncomingSyncSession? = { IncomingSyncSession(owner, "token") },
        download: IncomingRecipientBlobDownloader = writingDownloader(),
        control: IncomingControlIndexAcceptancePort = IncomingControlIndexAcceptancePort {
            IncomingControlIndexAcceptancePortResult.Verified(verifiedPayload())
        },
        persistence: IncomingVerifiedControlIndexPersistencePort =
            IncomingVerifiedControlIndexPersistencePort { _, _ ->
                IncomingVerifiedControlIndexPersistenceResult.Durable
            },
        inspection: IncomingSenderIndexBundleInspectionPort =
            availableInspection(),
        adoptionPort: IncomingRecognitionAdoptionPort = IncomingRecognitionAdoptionPort {
            adopter.adopt(it)
        },
        commitPort: IncomingIndexCommitPort = IncomingIndexCommitPort { request, authenticatedOwner ->
            committer.commit(request, authenticatedOwner)
        },
    ) = IncomingCapsuleAcceptanceCoordinator(
        incomingCapsuleDao = database.incomingCapsuleDao(),
        incomingEnvelopeDao = database.incomingEnvelopeDao(),
        blobCacheDao = database.blobCacheDao(),
        roots = roots,
        currentSession = session,
        download = download,
        controlAcceptance = control,
        verifiedControlIndexPersistence = persistence,
        adoption = adoptionPort,
        commit = commitPort,
        senderIndexBundleInspection = inspection,
    )

    private fun productionCoordinator(
        reader: SenderIndexBundleReader,
        session: suspend () -> IncomingSyncSession? = { IncomingSyncSession(owner, "token") },
    ): IncomingCapsuleAcceptanceCoordinator {
        val apiStack = ProductionApiStack.create(
            baseUrl = ApiBaseUrl.parse("http://localhost/"),
            tokens = AuthTokenHolder(),
            refreshTokenReader = dev.hryshyn.remanence.core.data.network.RefreshTokenReader { null },
            rotationSink = object : SessionRotationSink {
                override fun rotate(
                    accessToken: String,
                    refreshToken: String,
                    ownerUserId: dev.hryshyn.remanence.core.model.UserId,
                ) = Unit
                override fun clear() = Unit
            },
        )
        val unusedControlCoordinator = IncomingControlIndexAcceptanceCoordinator(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            incomingEnvelopeDao = database.incomingEnvelopeDao(),
            blobCacheDao = database.blobCacheDao(),
            currentRecipientIdentity = { null },
            trustedSenderKeys = object : TrustedSenderKeyStore {
                override suspend fun senderVerifyingKeyset(
                    senderUserId: UserId,
                    senderKeyBundleId: KeyBundleId,
                ) = error("unused in accepted replay")
            },
        )
        return IncomingCapsuleAcceptanceCoordinator(
            incomingCapsuleDao = database.incomingCapsuleDao(),
            incomingEnvelopeDao = database.incomingEnvelopeDao(),
            blobCacheDao = database.blobCacheDao(),
            roots = roots,
            currentSession = session,
            recipientBlobDownloadRepository = apiStack.recipientBlobDownloadRepository,
            controlIndexAcceptanceCoordinator = unusedControlCoordinator,
            senderIndexBundleReader = reader,
            verifiedControlIndexPersistence = IncomingVerifiedControlIndexPersistencePort { _, _ ->
                IncomingVerifiedControlIndexPersistenceResult.Durable
            },
            incomingRecognitionCiphertextAdopter = adopter,
            incomingIndexAcceptanceCommitter = committer,
        )
    }

    private fun availableInspection(
        onClose: () -> Unit = {},
    ): IncomingSenderIndexBundleInspectionPort =
        IncomingSenderIndexBundleInspectionPort { request ->
            assertEquals(owner, request.authenticatedOwnerUserId)
            assertEquals(owner, request.ownerUserId)
            assertEquals(capsule, request.capsuleId)
            IncomingSenderIndexBundleInspectionResult.Available(
                IncomingSenderIndexBundleInspectionSnapshot(onClose),
            )
        }

    private fun writingDownloader(onCall: () -> Unit = {}): IncomingRecipientBlobDownloader =
        IncomingRecipientBlobDownloader { request, accessToken ->
            assertEquals("token", accessToken)
            onCall()
            if (!request.destination.createNewFile()) {
                RecipientBlobDownloadResult.Failure(
                    reason = RecipientBlobDownloadFailure.DESTINATION_NOT_FRESH,
                    retryable = false,
                )
            } else {
                request.destination.writeBytes(bytes)
                RecipientBlobDownloadResult.Success(request.destination, bytes.size.toLong())
            }
        }

    private fun sessionSequence(vararg values: IncomingSyncSession?): suspend () -> IncomingSyncSession? {
        var index = 0
        return {
            values.getOrElse(index++) { values.lastOrNull() }
        }
    }

    private fun seed(recognitionCount: Int = 1) = runBlocking {
        database.clearAllTables()
        val ownerText = owner.toRestString()
        database.incomingCapsuleDao().upsertAllForOwner(
            ownerText,
            listOf(
                IncomingCapsuleEntity(
                    capsuleId = capsule.toRestString(),
                    ownerUserId = ownerText,
                    senderUserId = sender,
                    recipientUserId = ownerText,
                    senderSigningKeyBundleId = senderBundle,
                    recipientEncryptionKeyBundleId = recipientBundle,
                    protocolVersion = ProtocolV1Limits.PROTOCOL_VERSION,
                    serverStatus = "READY",
                    readyAtEpochMs = 1_755_000_000_000,
                    signedStatementBytes = byteArrayOf(1, 2, 3),
                    signedStatementSha256 = ByteArray(32) { 4 },
                    publishSignatureBytes = byteArrayOf(5, 6),
                    materialState = LocalMaterialState.DISCOVERED,
                ),
            ),
        )
        database.incomingEnvelopeDao().upsertForOwner(
            ownerText,
            IncomingEnvelopeEntity(
                capsuleId = capsule.toRestString(),
                ownerUserId = ownerText,
                recipientKeyBundleId = recipientBundle,
                hpkeCiphertext = byteArrayOf(7, 8, 9),
                transportSha256 = ByteArray(32) { 10 },
                receivedAtEpochMs = 1_755_000_000_001,
            ),
        )
        val blobs = (0 until recognitionCount).map { index ->
            val id = if (index == 0) blob else secondBlob
            BlobCacheEntity(
                blobId = id.toRestString(),
                ownerUserId = ownerText,
                capsuleId = capsule.toRestString(),
                kind = CapsuleArtifactKind.RECOGNITION_MANIFEST.name,
                ordinal = null,
                expectedSizeBytes = bytes.size.toLong(),
                expectedSha256 = sha256(bytes),
                localPath = incomingCiphertextPath(id).path,
                cacheState = BlobCacheState.DOWNLOADING,
            )
        }
        blobs.forEach { database.blobCacheDao().upsertForOwner(ownerText, it) }
    }

    private fun promoteToAlreadyAccepted() {
        incomingCiphertextPath().parentFile!!.mkdirs()
        incomingCiphertextPath().writeBytes(bytes)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE blob_cache SET cache_state = 'CACHED' " +
                "WHERE blob_id = '${blob.toRestString()}' AND owner_user_id = '${owner.toRestString()}'",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE incoming_capsule SET material_state = 'INDEX_CACHED' " +
                "WHERE capsule_id = '${capsule.toRestString()}' AND owner_user_id = '${owner.toRestString()}'",
        )
    }

    private fun assertInitialState() = runBlocking {
        assertEquals(
            LocalMaterialState.DISCOVERED,
            database.incomingCapsuleDao()
                .getByCapsuleIdAndOwner(capsule.toRestString(), owner.toRestString())!!.materialState,
        )
        assertEquals(BlobCacheState.DOWNLOADING, cachedBlobState())
    }

    private suspend fun cachedBlobState(): BlobCacheState = database.blobCacheDao()
        .getByBlobIdAndOwner(blob.toRestString(), owner.toRestString())!!.cacheState

    private fun rewriteRecognitionPath(path: String) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE blob_cache SET local_path = ? WHERE blob_id = ? AND owner_user_id = ?",
            arrayOf(path, blob.toRestString(), owner.toRestString()),
        )
    }

    private suspend fun recognitionRow(): BlobCacheEntity =
        database.blobCacheDao().getByBlobIdAndOwner(blob.toRestString(), owner.toRestString())!!

    private fun recoveryTempPath(): File = roots.child(
        owner,
        AccountScopedFileRoots.ChildRoot.TEMP,
    ).toPath()
        .resolve("incoming-recognition/${capsule.toRestString()}/blobs/${blob.toRestString()}.ciphertext.tmp")
        .toFile()

    private fun incomingCiphertextPath(id: BlobId = blob): File = roots
        .incomingCiphertextPath(owner, capsule, id)
        .toFile()

    private fun incomingIndexBundlePath(): File = File(
        roots.child(owner, AccountScopedFileRoots.ChildRoot.FINGERPRINTS),
        "capsules/${capsule.toRestString()}.index.bundle",
    )

    private fun indexRecognition(): RecognitionManifestContent = verifiedPayload().recognition.copy(
        capsuleIdRaw = capsule.toProtoBytes().toByteArray(),
        frontFingerprint = indexFingerprint(FingerprintSide.FRONT),
    )

    private fun indexFingerprint(side: FingerprintSide): ByteArray = FingerprintCodec.serialize(
        PostcardFingerprint(
            profileId = RecognitionProfile.MVP_ORB_V1_ID,
            canonicalWidthPx = 1200,
            canonicalHeightPx = 800,
            coarseHash64 = 17L,
            keypoints = listOf(
                FingerprintKeypoint(
                    xNormalized = 0.5,
                    yNormalized = 0.5,
                    scaleNormalized = 1.0,
                    angleCentiDegrees = 9000,
                    responseQuantized = 2,
                    octave = 0,
                ),
            ),
            descriptors = listOf(ByteArray(FingerprintCodec.DESCRIPTOR_BYTES) { 3 }),
            quality = ExtractionQuality(
                blurScore = 1.0,
                exposureScore = 1.0,
                glareFraction = 0.1,
                detectedAreaRatio = 0.5,
            ),
        ),
    )

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private fun verifiedPayload() = IncomingVerifiedControlIndexPayload(
        statement = PublishStatement.getDefaultInstance(),
        recognition = RecognitionManifestContent(
            manifestVersion = RecognitionManifestCodec.FORMAT_VERSION,
            capsuleIdRaw = ByteArray(0),
            senderHandleSnapshot = "sender",
            createdAtEpochSeconds = 1,
            placeLabel = null,
            frontFingerprint = byteArrayOf(1),
        ),
        senderVerification = TestSenderVerification.forCapsule(capsule),
    )

    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue(value is T)
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}

private object CoordinatorReaderFileSystem : SenderIndexBundleReaderFileSystem {
    override fun attributes(path: Path): SenderIndexBundleFileAttributes? = try {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        SenderIndexBundleFileAttributes(
            isSymbolicLink = attributes.isSymbolicLink,
            isRegularFile = attributes.isRegularFile,
            isDirectory = attributes.isDirectory,
            size = attributes.size(),
        )
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

    override fun openRead(path: Path): InputStream = Files.newInputStream(
        path,
        StandardOpenOption.READ,
        LinkOption.NOFOLLOW_LINKS,
    )
}

private class CoordinatorAuthenticatedSealer : SecretSealer {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val random = SecureRandom()

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(ciphertext.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOf(12)))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
    }
}
