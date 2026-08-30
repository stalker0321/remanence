package dev.hryshyn.remanence.core.data.prefetch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.data.db.BlobCacheEntity
import dev.hryshyn.remanence.core.data.db.BlobCacheState
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleEntity
import dev.hryshyn.remanence.core.data.db.IncomingPrefetchBlobRow
import dev.hryshyn.remanence.core.data.db.IncomingSyncSession
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.IncomingCiphertextAdoptionFailure
import dev.hryshyn.remanence.core.data.storage.IncomingCiphertextAdoptionResult
import dev.hryshyn.remanence.core.data.storage.IncomingCiphertextAdopter
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadFailure
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadRequest
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadResult
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingCiphertextPrefetchCoordinatorTest {

    private val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a501")
    private val otherOwner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a502")
    private val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000c501")
    private val laterCapsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000c601")
    private val foreignCapsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000c701")
    private val recognitionBlob = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b501")
    private val contentBlob = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b502")
    private val laterRecognitionBlob = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b601")
    private val laterContentBlob = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b602")
    private val laterPhotoBlobs = listOf(
        BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b603"),
        BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b604"),
        BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b605"),
    )
    private val foreignRecognitionBlob = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b701")
    private val foreignContentBlob = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b702")
    private val foreignPhotoBlobs = listOf(
        BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b703"),
        BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b704"),
        BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b705"),
    )
    private val photoBlobs = listOf(
        BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b503"),
        BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b504"),
        BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b505"),
    )

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var root: File
    private lateinit var roots: AccountScopedFileRoots

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "incoming-prefetch-${System.nanoTime()}").apply { mkdirs() }
        roots = AccountScopedFileRoots(root)
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun boundedCanonicalPrefetchPromotesOnlyAfterEveryRequiredBlobIsCached() = runBlocking {
        seedCapsule(
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(photoBlobs.size) { BlobCacheState.DOWNLOADING },
        )
        writeCached(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1)
        val calls = mutableListOf<BlobId>()
        val coordinator = coordinator(
            maxBlobsPerRun = 2,
            download = { request, _ ->
                calls += request.blobId
                writeTemp(request)
                RecipientBlobDownloadResult.Success(request.destination, request.expectedCiphertextSize)
            },
        )

        val first = coordinator.prefetch(owner)
        assertEquals(IncomingPrefetchResult.Completed(2, 0), first)
        assertEquals(listOf(contentBlob, photoBlobs[0]), calls)
        assertEquals(LocalMaterialState.INDEX_CACHED, capsuleState())
        assertEquals(BlobCacheState.CACHED, blobState(contentBlob))
        assertEquals(BlobCacheState.CACHED, blobState(photoBlobs[0]))
        assertEquals(BlobCacheState.DOWNLOADING, blobState(photoBlobs[1]))

        val second = coordinator.prefetch(owner)
        assertEquals(IncomingPrefetchResult.Completed(2, 1), second)
        assertEquals(listOf(contentBlob, photoBlobs[0], photoBlobs[1], photoBlobs[2]), calls)
        assertEquals(LocalMaterialState.MATERIAL_CACHED, capsuleState())
        assertTrue(allPhotosCached())
    }

    @Test
    fun terminalLeadingCapsuleIsQuarantinedAndLaterCapsuleProgressesWithoutReselection() = runBlocking {
        seedInvalidCapsule()
        seedCapsule(
            capsuleId = laterCapsule,
            recognitionBlobId = laterRecognitionBlob,
            contentBlobId = laterContentBlob,
            photoBlobIds = laterPhotoBlobs,
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(laterPhotoBlobs.size) { BlobCacheState.CACHED },
        )
        writeCached(laterRecognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1, laterCapsule)
        laterPhotoBlobs.forEachIndexed { index, blob ->
            writeCached(blob, CapsuleArtifactKind.PHOTO, index, laterCapsule)
        }
        val calls = mutableListOf<BlobId>()
        val result = coordinator(
            maxBlobsPerRun = 5,
            download = { request, _ ->
                calls += request.blobId
                writeTemp(request)
                RecipientBlobDownloadResult.Success(request.destination, request.expectedCiphertextSize)
            },
        ).prefetch(owner)

        assertEquals(IncomingPrefetchResult.Completed(1, 1, 1), result)
        assertEquals(listOf(laterContentBlob), calls)
        assertEquals(LocalMaterialState.CORRUPT, capsuleState(capsule))
        assertEquals(LocalMaterialState.MATERIAL_CACHED, capsuleState(laterCapsule))
        assertTrue(photoBlobs.all { blobState(it) == BlobCacheState.DOWNLOADING })

        val replay = coordinator(
            maxBlobsPerRun = 5,
            download = { _, _ -> error("quarantined and completed capsules must not be selected") },
        ).prefetch(owner)
        assertEquals(IncomingPrefetchResult.Completed(0, 0, 0), replay)
        assertEquals(LocalMaterialState.CORRUPT, capsuleState(capsule))
    }

    @Test
    fun provenIntegrityFailureQuarantinesAndLaterCapsuleProgresses() = runBlocking {
        seedCapsule(
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(photoBlobs.size) { BlobCacheState.DOWNLOADING },
        )
        writeCached(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1)
        seedCapsule(
            capsuleId = laterCapsule,
            recognitionBlobId = laterRecognitionBlob,
            contentBlobId = laterContentBlob,
            photoBlobIds = laterPhotoBlobs,
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(laterPhotoBlobs.size) { BlobCacheState.CACHED },
        )
        writeCached(laterRecognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1, laterCapsule)
        laterPhotoBlobs.forEachIndexed { index, blob ->
            writeCached(blob, CapsuleArtifactKind.PHOTO, index, laterCapsule)
        }
        val calls = mutableListOf<BlobId>()
        val result = coordinator(
            maxBlobsPerRun = 5,
            download = { request, _ ->
                if (request.capsuleId == capsule) {
                    RecipientBlobDownloadResult.Failure(
                        reason = RecipientBlobDownloadFailure.INTEGRITY_FAILED,
                        retryable = false,
                    )
                } else {
                    calls += request.blobId
                    writeTemp(request)
                    RecipientBlobDownloadResult.Success(request.destination, request.expectedCiphertextSize)
                }
            },
        ).prefetch(owner)

        assertEquals(IncomingPrefetchResult.Completed(1, 1, 1), result)
        assertEquals(listOf(laterContentBlob), calls)
        assertEquals(LocalMaterialState.CORRUPT, capsuleState(capsule))
        assertEquals(LocalMaterialState.MATERIAL_CACHED, capsuleState(laterCapsule))
    }

    @Test
    fun accountSwitchBeforeQuarantineStopsWithoutMutatingCapsule() = runBlocking {
        seedInvalidCapsule()
        var sessionChecks = 0
        var downloadCalls = 0
        val result = coordinator(
            currentSession = {
                sessionChecks++
                if (sessionChecks == 3) IncomingSyncSession(otherOwner, "other-token")
                else IncomingSyncSession(owner, "token")
            },
            download = { _, _ ->
                downloadCalls++
                error("account switch must stop before download")
            },
        ).prefetch(owner)

        assertEquals(
            IncomingPrefetchResult.AccountStopped(IncomingPrefetchTerminalReason.ACCOUNT_CHANGED),
            result,
        )
        assertEquals(0, downloadCalls)
        assertEquals(LocalMaterialState.INDEX_CACHED, capsuleState(capsule))
    }

    @Test
    fun cancellationBeforeQuarantineIsRethrownExactlyAndDoesNotMutateCapsule() = runBlocking {
        seedInvalidCapsule()
        val expected = java.util.concurrent.CancellationException("terminal quarantine cancellation")
        var sessionChecks = 0
        try {
            coordinator(
                currentSession = {
                    sessionChecks++
                    if (sessionChecks == 3) throw expected
                    IncomingSyncSession(owner, "token")
                },
                download = { _, _ -> error("cancellation must stop before download") },
            ).prefetch(owner)
            assertTrue("cancellation must be rethrown", false)
        } catch (actual: java.util.concurrent.CancellationException) {
            assertEquals(expected.message, actual.message)
        }
        assertEquals(LocalMaterialState.INDEX_CACHED, capsuleState(capsule))
    }

    @Test
    fun foreignCapsuleIsNeverSelectedOrQuarantined() = runBlocking {
        seedCapsule(
            ownerUserId = otherOwner,
            capsuleId = foreignCapsule,
            recognitionBlobId = foreignRecognitionBlob,
            contentBlobId = foreignContentBlob,
            photoBlobIds = foreignPhotoBlobs,
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(foreignPhotoBlobs.size) { BlobCacheState.DOWNLOADING },
        )
        val result = coordinator(
            download = { _, _ -> error("foreign capsule must not reach network") },
        ).prefetch(owner)

        assertEquals(IncomingPrefetchResult.Completed(0, 0, 0), result)
        assertEquals(LocalMaterialState.INDEX_CACHED, capsuleState(foreignCapsule, otherOwner))
    }

    @Test
    fun quarantineCasLossStopsFailClosedWithoutProcessingLaterCapsule() = runBlocking {
        seedInvalidCapsule()
        seedCapsule(
            capsuleId = laterCapsule,
            recognitionBlobId = laterRecognitionBlob,
            contentBlobId = laterContentBlob,
            photoBlobIds = laterPhotoBlobs,
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(laterPhotoBlobs.size) { BlobCacheState.CACHED },
        )
        writeCached(laterRecognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1, laterCapsule)
        laterPhotoBlobs.forEachIndexed { index, blob ->
            writeCached(blob, CapsuleArtifactKind.PHOTO, index, laterCapsule)
        }
        var sessionChecks = 0
        var downloadCalls = 0
        val result = coordinator(
            maxBlobsPerRun = 5,
            currentSession = {
                sessionChecks++
                if (sessionChecks == 3) {
                    check(
                        database.incomingCapsuleDao().transitionMaterialStateForOwner(
                            ownerUserId = owner.toRestString(),
                            capsuleId = capsule.toRestString(),
                            requestedTarget = LocalMaterialState.MATERIAL_CACHED,
                        ) is dev.hryshyn.remanence.core.data.db.LocalMaterialTransitionResult.Accepted,
                    )
                }
                IncomingSyncSession(owner, "token")
            },
            download = { _, _ ->
                downloadCalls++
                error("CAS loss must stop before later network work")
            },
        ).prefetch(owner)

        assertEquals(
            IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.DATABASE_UNAVAILABLE),
            result,
        )
        assertEquals(0, downloadCalls)
        assertEquals(LocalMaterialState.MATERIAL_CACHED, capsuleState(capsule))
        assertEquals(LocalMaterialState.INDEX_CACHED, capsuleState(laterCapsule))
        assertEquals(BlobCacheState.DOWNLOADING, blobState(laterContentBlob))
    }

    @Test
    fun allCachedRowsStillRunOneReconciliationAndPromoteMaterial() = runBlocking {
        seedCapsule(
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.CACHED,
            photoStates = List(photoBlobs.size) { BlobCacheState.CACHED },
        )
        writeCached(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1)
        writeCached(contentBlob, CapsuleArtifactKind.CONTENT_MANIFEST, -1)
        photoBlobs.forEachIndexed { index, blob ->
            writeCached(blob, CapsuleArtifactKind.PHOTO, index)
        }
        var downloadCalls = 0

        val result = coordinator(
            download = { _, _ ->
                downloadCalls++
                error("complete material must not download")
            },
        ).prefetch(owner)

        assertEquals(IncomingPrefetchResult.Completed(1, 1), result)
        assertEquals(0, downloadCalls)
        assertEquals(LocalMaterialState.MATERIAL_CACHED, capsuleState())
    }

    @Test
    fun retainedDeterministicTempSurvivesRestartAndSkipsSecondDownload() = runBlocking {
        seedCapsule(
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(photoBlobs.size) { BlobCacheState.CACHED },
        )
        writeCached(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1)
        photoBlobs.forEachIndexed { index, blob ->
            writeCached(blob, CapsuleArtifactKind.PHOTO, index)
        }
        val downloads = AtomicInteger(0)
        var firstInvocation = true
        val first = coordinator(
            download = { request, _ ->
                downloads.incrementAndGet()
                writeTemp(request)
                if (firstInvocation) {
                    firstInvocation = false
                    throw java.util.concurrent.CancellationException("simulated process stop")
                }
                RecipientBlobDownloadResult.Success(request.destination, request.expectedCiphertextSize)
            },
        )
        try {
            first.prefetch(owner)
            assertTrue("first invocation must cancel", false)
        } catch (expected: java.util.concurrent.CancellationException) {
            // The complete deterministic TEMP is intentionally retained.
        }

        val second = coordinator(
            download = { request, _ ->
                downloads.incrementAndGet()
                writeTemp(request)
                RecipientBlobDownloadResult.Success(request.destination, request.expectedCiphertextSize)
            },
        ).prefetch(owner)

        assertEquals(IncomingPrefetchResult.Completed(1, 1), second)
        assertEquals(1, downloads.get())
        assertEquals(LocalMaterialState.MATERIAL_CACHED, capsuleState())
    }

    @Test
    fun accountSwitchAfterDownloadNeverAdoptsOrMutatesRows() = runBlocking {
        seedCapsule(
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(photoBlobs.size) { BlobCacheState.CACHED },
        )
        writeCached(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1)
        photoBlobs.forEachIndexed { index, blob ->
            writeCached(blob, CapsuleArtifactKind.PHOTO, index)
        }
        var sessionOwner: UserId? = owner
        var adoptionCalls = 0
        val result = coordinator(
            currentSession = { IncomingSyncSession(sessionOwner!!, "token") },
            download = { request, _ ->
                writeTemp(request)
                sessionOwner = otherOwner
                RecipientBlobDownloadResult.Success(request.destination, request.expectedCiphertextSize)
            },
            adopt = {
                adoptionCalls++
                error("adoption must not be reached")
            },
        ).prefetch(owner)

        assertEquals(
            IncomingPrefetchResult.AccountStopped(IncomingPrefetchTerminalReason.ACCOUNT_CHANGED),
            result,
        )
        assertEquals(0, adoptionCalls)
        assertEquals(BlobCacheState.DOWNLOADING, blobState(contentBlob))
        assertEquals(LocalMaterialState.INDEX_CACHED, capsuleState())
    }

    @Test
    fun retryableDownloadDoesNotAdvanceRoomState() = runBlocking {
        seedCapsule(
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(photoBlobs.size) { BlobCacheState.CACHED },
        )
        writeCached(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1)
        photoBlobs.forEachIndexed { index, blob ->
            writeCached(blob, CapsuleArtifactKind.PHOTO, index)
        }
        val result = coordinator(
            download = { request, _ ->
                assertTrue(request.destination.path.contains("incoming-prefetch"))
                RecipientBlobDownloadResult.Failure(
                    reason = dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadFailure.NETWORK,
                    retryable = true,
                )
            },
        ).prefetch(owner)

        assertEquals(
            IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.DOWNLOAD),
            result,
        )
        assertEquals(BlobCacheState.DOWNLOADING, blobState(contentBlob))
        assertEquals(LocalMaterialState.INDEX_CACHED, capsuleState())
    }

    @Test
    fun ambiguousDownloadFailuresRetryWithoutQuarantiningCapsule() = runBlocking {
        seedCapsule(
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(photoBlobs.size) { BlobCacheState.CACHED },
        )
        writeCached(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1)
        photoBlobs.forEachIndexed { index, blob ->
            writeCached(blob, CapsuleArtifactKind.PHOTO, index)
        }
        val cases = listOf(
            RecipientBlobDownloadFailure.INTERNAL_ERROR to IncomingPrefetchRetryReason.DOWNLOAD,
            RecipientBlobDownloadFailure.INVALID_RESPONSE to IncomingPrefetchRetryReason.DOWNLOAD,
            RecipientBlobDownloadFailure.HTTP to IncomingPrefetchRetryReason.DOWNLOAD,
            RecipientBlobDownloadFailure.VALIDATION_FAILED to IncomingPrefetchRetryReason.DOWNLOAD,
            RecipientBlobDownloadFailure.RATE_LIMITED to IncomingPrefetchRetryReason.DOWNLOAD,
            RecipientBlobDownloadFailure.NETWORK to IncomingPrefetchRetryReason.DOWNLOAD,
            RecipientBlobDownloadFailure.DESTINATION_NOT_FRESH to IncomingPrefetchRetryReason.LOCAL_STORAGE,
            RecipientBlobDownloadFailure.LOCAL_STORAGE to IncomingPrefetchRetryReason.LOCAL_STORAGE,
        )

        cases.forEach { (failure, retryReason) ->
            val result = coordinator(
                download = { _, _ ->
                    RecipientBlobDownloadResult.Failure(reason = failure, retryable = false)
                },
            ).prefetch(owner)

            assertEquals(IncomingPrefetchResult.Retryable(retryReason), result)
            assertEquals(LocalMaterialState.INDEX_CACHED, capsuleState())
            assertEquals(BlobCacheState.DOWNLOADING, blobState(contentBlob))
        }
    }

    @Test
    fun authInvalidStopsImmediatelyWithoutQuarantiningCapsule() = runBlocking {
        seedCapsule(
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(photoBlobs.size) { BlobCacheState.CACHED },
        )
        writeCached(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1)
        photoBlobs.forEachIndexed { index, blob ->
            writeCached(blob, CapsuleArtifactKind.PHOTO, index)
        }
        var adoptionCalls = 0
        val result = coordinator(
            download = { _, _ ->
                RecipientBlobDownloadResult.Failure(
                    reason = RecipientBlobDownloadFailure.AUTH_INVALID,
                    retryable = false,
                )
            },
            adopt = {
                adoptionCalls++
                error("AUTH_INVALID must stop before adoption")
            },
        ).prefetch(owner)

        assertEquals(
            IncomingPrefetchResult.Terminal(IncomingPrefetchTerminalReason.AUTH_INVALID),
            result,
        )
        assertEquals(0, adoptionCalls)
        assertEquals(LocalMaterialState.INDEX_CACHED, capsuleState())
        assertEquals(BlobCacheState.DOWNLOADING, blobState(contentBlob))
    }

    @Test
    fun durabilityFailurePreservesTempAndLinkedDestinationWithoutQuarantine() = runBlocking {
        seedCapsule(
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(photoBlobs.size) { BlobCacheState.CACHED },
        )
        writeCached(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1)
        photoBlobs.forEachIndexed { index, blob ->
            writeCached(blob, CapsuleArtifactKind.PHOTO, index)
        }
        lateinit var tempFile: File
        lateinit var linkedDestination: File
        val result = coordinator(
            download = { request, _ ->
                tempFile = request.destination
                writeTemp(request)
                RecipientBlobDownloadResult.Success(request.destination, request.expectedCiphertextSize)
            },
            adopt = { request ->
                linkedDestination = destination(request.blobId, request.capsuleId, request.ownerUserId)
                linkedDestination.apply {
                    parentFile!!.mkdirs()
                    writeBytes(expectedBytes(request.blobId))
                }
                IncomingCiphertextAdoptionResult.Failure(
                    reason = IncomingCiphertextAdoptionFailure.DURABILITY_UNAVAILABLE,
                    retryable = false,
                )
            },
        ).prefetch(owner)

        assertEquals(
            IncomingPrefetchResult.Retryable(IncomingPrefetchRetryReason.LOCAL_STORAGE),
            result,
        )
        assertTrue(tempFile.isFile)
        assertTrue(linkedDestination.isFile)
        assertArrayEquals(expectedBytes(contentBlob), linkedDestination.readBytes())
        assertEquals(LocalMaterialState.INDEX_CACHED, capsuleState())
        assertEquals(BlobCacheState.DOWNLOADING, blobState(contentBlob))
    }

    @Test
    fun sameCapsuleConcurrentInvocationsHaveOneDownloadAndSafeRoomWinner() = runBlocking {
        seedCapsule(
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(photoBlobs.size) { BlobCacheState.DOWNLOADING },
        )
        writeCached(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1)
        val downloads = AtomicInteger(0)
        val downloader: suspend (RecipientBlobDownloadRequest, String) -> RecipientBlobDownloadResult =
            { request, _ ->
                downloads.incrementAndGet()
                delay(20)
                writeTemp(request)
                RecipientBlobDownloadResult.Success(request.destination, request.expectedCiphertextSize)
            }
        val results = listOf(
            coordinator(maxBlobsPerRun = 1, download = downloader),
            coordinator(maxBlobsPerRun = 1, download = downloader),
        ).map { coordinator ->
            async(Dispatchers.Default) { coordinator.prefetch(owner) }
        }.awaitAll()

        assertEquals(1, downloads.get())
        assertTrue(results.all { it is IncomingPrefetchResult.Completed })
        assertEquals(BlobCacheState.CACHED, blobState(contentBlob))
    }

    @Test
    fun ownerMismatchStopsBeforeRoomSelectionOrNetwork() = runBlocking {
        seedCapsule(
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(photoBlobs.size) { BlobCacheState.DOWNLOADING },
        )
        var downloadCalls = 0
        val result = coordinator(
            download = { _, _ ->
                downloadCalls++
                error("network must not be reached")
            },
        ).prefetch(otherOwner)

        assertEquals(
            IncomingPrefetchResult.AccountStopped(IncomingPrefetchTerminalReason.ACCOUNT_CHANGED),
            result,
        )
        assertEquals(0, downloadCalls)
        assertEquals(BlobCacheState.DOWNLOADING, blobState(contentBlob))
    }

    private fun coordinator(
        maxBlobsPerRun: Int = 4,
        currentSession: suspend () -> IncomingSyncSession? = {
            IncomingSyncSession(owner, "token")
        },
        download: suspend (RecipientBlobDownloadRequest, String) -> RecipientBlobDownloadResult,
        adopt: suspend (dev.hryshyn.remanence.core.data.storage.IncomingCiphertextAdoptionRequest) -> IncomingCiphertextAdoptionResult =
            { request -> IncomingCiphertextAdopter(roots).adopt(request) },
    ) = IncomingCiphertextPrefetchCoordinator(
        prefetchDao = database.incomingPrefetchDao(),
        blobCacheDao = database.blobCacheDao(),
        roots = roots,
        currentSession = currentSession,
        download = download,
        adopt = adopt,
        maxBlobsPerRun = maxBlobsPerRun,
    )

    private suspend fun seedCapsule(
        ownerUserId: UserId = owner,
        capsuleId: CapsuleId = capsule,
        recognitionBlobId: BlobId = recognitionBlob,
        contentBlobId: BlobId = contentBlob,
        photoBlobIds: List<BlobId> = photoBlobs,
        recognitionState: BlobCacheState,
        contentState: BlobCacheState,
        photoStates: List<BlobCacheState>,
    ) {
        database.incomingCapsuleDao().upsertAllForOwner(
            ownerUserId.toRestString(),
            listOf(capsuleRow(capsuleId, ownerUserId).copy(materialState = LocalMaterialState.DISCOVERED)),
        )
        check(
            database.incomingCapsuleDao().transitionMaterialStateForOwner(
                ownerUserId = ownerUserId.toRestString(),
                capsuleId = capsuleId.toRestString(),
                requestedTarget = LocalMaterialState.INDEX_CACHED,
            ) is dev.hryshyn.remanence.core.data.db.LocalMaterialTransitionResult.Accepted,
        )
        val rows = mutableListOf(
            blobRow(
                recognitionBlobId,
                CapsuleArtifactKind.RECOGNITION_MANIFEST,
                null,
                recognitionState,
                capsuleId,
                ownerUserId,
            ),
            blobRow(
                contentBlobId,
                CapsuleArtifactKind.CONTENT_MANIFEST,
                null,
                contentState,
                capsuleId,
                ownerUserId,
            ),
        )
        photoStates.forEachIndexed { index, state ->
            rows += blobRow(photoBlobIds[index], CapsuleArtifactKind.PHOTO, index, state, capsuleId, ownerUserId)
        }
        rows.forEach { database.blobCacheDao().upsertForOwner(ownerUserId.toRestString(), it) }
    }

    private suspend fun seedInvalidCapsule() {
        seedCapsule(
            recognitionState = BlobCacheState.CACHED,
            contentState = BlobCacheState.DOWNLOADING,
            photoStates = List(photoBlobs.size) { BlobCacheState.DOWNLOADING },
        )
        database.blobCacheDao().upsertForOwner(
            owner.toRestString(),
            blobRow(
                contentBlob,
                CapsuleArtifactKind.CONTENT_MANIFEST,
                null,
                BlobCacheState.DOWNLOADING,
            ).copy(expectedSizeBytes = ProtocolV1Limits.CONTENT_MANIFEST_MAX_CIPHERTEXT_BYTES + 1),
        )
        writeCached(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1)
    }

    private fun capsuleRow(
        capsuleId: CapsuleId = capsule,
        ownerUserId: UserId = owner,
    ) = IncomingCapsuleEntity(
        capsuleId = capsuleId.toRestString(),
        ownerUserId = ownerUserId.toRestString(),
        senderUserId = "0198f0a0-0000-7000-8000-00000000a601",
        recipientUserId = ownerUserId.toRestString(),
        senderSigningKeyBundleId = "0198f0a0-0000-7000-8000-00000000a602",
        recipientEncryptionKeyBundleId = "0198f0a0-0000-7000-8000-00000000a603",
        protocolVersion = 1,
        serverStatus = "READY",
        readyAtEpochMs = 1_755_000_000_000,
        signedStatementBytes = byteArrayOf(1, 2, 3),
        signedStatementSha256 = ByteArray(32) { 4 },
        publishSignatureBytes = ByteArray(69) { 5 },
        materialState = LocalMaterialState.INDEX_CACHED,
    )

    private fun blobRow(
        blob: BlobId,
        kind: CapsuleArtifactKind,
        ordinal: Int?,
        state: BlobCacheState,
        capsuleId: CapsuleId = capsule,
        ownerUserId: UserId = owner,
    ) = BlobCacheEntity(
        blobId = blob.toRestString(),
        ownerUserId = ownerUserId.toRestString(),
        capsuleId = capsuleId.toRestString(),
        kind = kind.name,
        ordinal = ordinal,
        expectedSizeBytes = expectedBytes(blob).size.toLong(),
        expectedSha256 = sha256(expectedBytes(blob)),
        localPath = destination(blob, capsuleId, ownerUserId).path,
        cacheState = state,
    )

    private fun expectedBytes(blob: BlobId): ByteArray = "ciphertext-${blob.toRestString()}".toByteArray()

    private fun writeCached(
        blob: BlobId,
        kind: CapsuleArtifactKind,
        ordinal: Int,
        capsuleId: CapsuleId = capsule,
        ownerUserId: UserId = owner,
    ) {
        destination(blob, capsuleId, ownerUserId).apply {
            parentFile!!.mkdirs()
            writeBytes(expectedBytes(blob))
        }
    }

    private fun writeTemp(request: RecipientBlobDownloadRequest) {
        request.destination.apply {
            parentFile!!.mkdirs()
            writeBytes(expectedBytes(request.blobId))
        }
    }

    private fun destination(
        blob: BlobId,
        capsuleId: CapsuleId = capsule,
        ownerUserId: UserId = owner,
    ): File = File(
        roots.child(ownerUserId, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT),
        "capsules/${capsuleId.toRestString()}/blobs/${blob.toRestString()}.ciphertext",
    ).canonicalFile

    private fun blobState(blob: BlobId, ownerUserId: UserId = owner): BlobCacheState = runBlocking {
        database.blobCacheDao().getByBlobIdAndOwner(blob.toRestString(), ownerUserId.toRestString())!!.cacheState
    }

    private fun capsuleState(
        capsuleId: CapsuleId = capsule,
        ownerUserId: UserId = owner,
    ): LocalMaterialState = runBlocking {
        database.incomingCapsuleDao()
            .getByCapsuleIdAndOwner(capsuleId.toRestString(), ownerUserId.toRestString())!!
            .materialState
    }

    private fun allPhotosCached(): Boolean = runBlocking {
        photoBlobs.all { blobState(it) == BlobCacheState.CACHED }
    }

    private fun sha256(bytes: ByteArray): ByteArray = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
}
