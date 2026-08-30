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
import dev.hryshyn.remanence.core.data.storage.IncomingCiphertextAdoptionResult
import dev.hryshyn.remanence.core.data.storage.IncomingCiphertextAdopter
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadRequest
import dev.hryshyn.remanence.core.data.network.RecipientBlobDownloadResult
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
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
    private val recognitionBlob = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b501")
    private val contentBlob = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000b502")
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
        recognitionState: BlobCacheState,
        contentState: BlobCacheState,
        photoStates: List<BlobCacheState>,
    ) {
        database.incomingCapsuleDao().upsertAllForOwner(
            owner.toRestString(),
            listOf(capsuleRow().copy(materialState = LocalMaterialState.DISCOVERED)),
        )
        check(
            database.incomingCapsuleDao().transitionMaterialStateForOwner(
                ownerUserId = owner.toRestString(),
                capsuleId = capsule.toRestString(),
                requestedTarget = LocalMaterialState.INDEX_CACHED,
            ) is dev.hryshyn.remanence.core.data.db.LocalMaterialTransitionResult.Accepted,
        )
        val rows = mutableListOf(
            blobRow(recognitionBlob, CapsuleArtifactKind.RECOGNITION_MANIFEST, null, recognitionState),
            blobRow(contentBlob, CapsuleArtifactKind.CONTENT_MANIFEST, null, contentState),
        )
        photoStates.forEachIndexed { index, state ->
            rows += blobRow(photoBlobs[index], CapsuleArtifactKind.PHOTO, index, state)
        }
        rows.forEach { database.blobCacheDao().upsertForOwner(owner.toRestString(), it) }
    }

    private fun capsuleRow() = IncomingCapsuleEntity(
        capsuleId = capsule.toRestString(),
        ownerUserId = owner.toRestString(),
        senderUserId = "0198f0a0-0000-7000-8000-00000000a601",
        recipientUserId = owner.toRestString(),
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
    ) = BlobCacheEntity(
        blobId = blob.toRestString(),
        ownerUserId = owner.toRestString(),
        capsuleId = capsule.toRestString(),
        kind = kind.name,
        ordinal = ordinal,
        expectedSizeBytes = expectedBytes(blob).size.toLong(),
        expectedSha256 = sha256(expectedBytes(blob)),
        localPath = destination(blob).path,
        cacheState = state,
    )

    private fun expectedBytes(blob: BlobId): ByteArray = "ciphertext-${blob.toRestString()}".toByteArray()

    private fun writeCached(blob: BlobId, kind: CapsuleArtifactKind, ordinal: Int) {
        destination(blob).apply {
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

    private fun destination(blob: BlobId): File = File(
        roots.child(owner, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT),
        "capsules/${capsule.toRestString()}/blobs/${blob.toRestString()}.ciphertext",
    ).canonicalFile

    private fun blobState(blob: BlobId): BlobCacheState = runBlocking {
        database.blobCacheDao().getByBlobIdAndOwner(blob.toRestString(), owner.toRestString())!!.cacheState
    }

    private fun capsuleState(): LocalMaterialState = runBlocking {
        database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsule.toRestString(), owner.toRestString())!!.materialState
    }

    private fun allPhotosCached(): Boolean = runBlocking {
        photoBlobs.all { blobState(it) == BlobCacheState.CACHED }
    }

    private fun sha256(bytes: ByteArray): ByteArray = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
}
