package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.DurableIncomingCiphertextFile
import dev.hryshyn.remanence.core.data.storage.IncomingRecognitionCiphertextAdopter
import dev.hryshyn.remanence.core.data.storage.IncomingRecognitionCiphertextAdoptionRequest
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingIndexAcceptanceCommitterTest {

    private val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000aa01")
    private val otherOwner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000aa02")
    private val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000cc01")
    private val blob = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000bb01")
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
        testRoot = File(context.cacheDir, "a11c2-${UUID.randomUUID()}").apply { mkdirs() }
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
    fun happyPathChangesBothRowsInOneOwnerScopedCommit() = runTest {
        val request = seedAndAdopt()

        assertIs<IncomingIndexAcceptanceCommitResult.Committed>(committer.commit(request, owner))
        assertEquals(
            LocalMaterialState.INDEX_CACHED,
            database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsule.toRestString(), owner.toRestString())!!.materialState,
        )
        assertEquals(
            BlobCacheState.CACHED,
            database.blobCacheDao().getByBlobIdAndOwner(blob.toRestString(), owner.toRestString())!!.cacheState,
        )
    }

    @Test
    fun missingAuthenticatedOwnerLeavesFileAndRowsUntouched() = runTest {
        val request = seedAndAdopt()
        val before = request.durableCiphertext.asFile().readBytes()

        val result = assertIs<IncomingIndexAcceptanceCommitResult.Failure>(
            committer.commit(request, authenticatedOwnerUserId = null),
        )

        assertEquals(IncomingIndexAcceptanceFailure.NO_AUTHENTICATED_OWNER, result.reason)
        assertFalse(result.retryable)
        assertArrayEquals(before, request.durableCiphertext.asFile().readBytes())
        assertUnchanged()
    }

    @Test
    fun mismatchedAuthenticatedOwnerLeavesFileAndRowsUntouched() = runTest {
        val request = seedAndAdopt()
        val before = request.durableCiphertext.asFile().readBytes()

        val result = assertIs<IncomingIndexAcceptanceCommitResult.Failure>(
            committer.commit(request, authenticatedOwnerUserId = otherOwner),
        )

        assertEquals(IncomingIndexAcceptanceFailure.OWNER_MISMATCH, result.reason)
        assertFalse(result.retryable)
        assertArrayEquals(before, request.durableCiphertext.asFile().readBytes())
        assertUnchanged()
    }

    @Test
    fun secondCasFailureRollsBackFirstCas() = runTest {
        val request = seedAndAdopt()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_index_cache BEFORE UPDATE OF material_state ON incoming_capsule " +
                "WHEN NEW.material_state = 'INDEX_CACHED' BEGIN SELECT RAISE(IGNORE); END",
        )

        val result = committer.commit(request, owner)

        val failure = assertIs<IncomingIndexAcceptanceCommitResult.Failure>(result)
        assertEquals(IncomingIndexAcceptanceFailure.CONCURRENT_OR_STALE, failure.reason)
        assertTrue(failure.retryable)
        assertEquals(
            LocalMaterialState.DISCOVERED,
            database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsule.toRestString(), owner.toRestString())!!.materialState,
        )
        assertEquals(
            BlobCacheState.DOWNLOADING,
            database.blobCacheDao().getByBlobIdAndOwner(blob.toRestString(), owner.toRestString())!!.cacheState,
        )
    }

    @Test
    fun exactReplayIsIdempotentOnlyWhenBothRowsAndBindingsMatch() = runTest {
        val request = seedAndAdopt()
        assertIs<IncomingIndexAcceptanceCommitResult.Committed>(committer.commit(request, owner))

        assertEquals(
            IncomingIndexAcceptanceFailure.NO_AUTHENTICATED_OWNER,
            assertIs<IncomingIndexAcceptanceCommitResult.Failure>(
                committer.commit(request, authenticatedOwnerUserId = null),
            ).reason,
        )
        assertEquals(
            IncomingIndexAcceptanceFailure.OWNER_MISMATCH,
            assertIs<IncomingIndexAcceptanceCommitResult.Failure>(
                committer.commit(request, authenticatedOwnerUserId = otherOwner),
            ).reason,
        )
        assertIs<IncomingIndexAcceptanceCommitResult.IdempotentReplay>(committer.commit(request, owner))
    }

    @Test
    fun wrongOwnerCapsuleBlobOrCapabilityCannotAdvanceRows() = runTest {
        val request = seedAndAdopt()
        val wrongOwnerRequest = IncomingIndexAcceptanceCommitRequest(
            ownerUserId = otherOwner,
            capsuleId = capsule,
            recognitionBlobId = blob,
            expectedSizeBytes = bytes.size.toLong(),
            expectedSha256 = sha256(bytes),
            durableCiphertext = request.durableCiphertext,
        )
        val wrongCapsule = request.copy(capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000cc02"))
        val wrongBlob = request.copy(recognitionBlobId = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000bb02"))
        val wrongPath = IncomingIndexAcceptanceCommitRequest(
            ownerUserId = owner,
            capsuleId = capsule,
            recognitionBlobId = blob,
            expectedSizeBytes = bytes.size.toLong(),
            expectedSha256 = sha256(bytes),
            durableCiphertext = DurableIncomingCiphertextFile(
                owner,
                capsule,
                blob,
                File(testRoot, "not-the-derived-destination"),
            ),
        )

        listOf(wrongOwnerRequest, wrongCapsule, wrongBlob, wrongPath).forEach { candidate ->
            val result = assertIs<IncomingIndexAcceptanceCommitResult.Failure>(committer.commit(candidate, owner))
            assertFalse(result.retryable)
        }
        assertUnchanged()
    }

    @Test
    fun wrongHashOrSizeIsRejectedWithoutStateAdvance() = runTest {
        val request = seedAndAdopt()
        val wrongHash = request.copy(expectedSha256 = ByteArray(32) { 9 })
        val wrongSize = request.copy(expectedSizeBytes = request.expectedSizeBytes - 1)

        assertEquals(
            IncomingIndexAcceptanceFailure.SOURCE_INTEGRITY_MISMATCH,
            assertIs<IncomingIndexAcceptanceCommitResult.Failure>(committer.commit(wrongHash, owner)).reason,
        )
        assertEquals(
            IncomingIndexAcceptanceFailure.SOURCE_INTEGRITY_MISMATCH,
            assertIs<IncomingIndexAcceptanceCommitResult.Failure>(committer.commit(wrongSize, owner)).reason,
        )
        assertUnchanged()
    }

    @Test
    fun persistedPathHashAndSizeBindingsAreNotReplacedByCallerValues() = runTest {
        val request = seedAndAdopt()
        val original = database.blobCacheDao().getByBlobIdAndOwner(blob.toRestString(), owner.toRestString())!!

        database.blobCacheDao().upsertForOwner(
            owner.toRestString(),
            original.copy(localPath = File(testRoot, "wrong-path").absolutePath),
        )
        assertEquals(
            IncomingIndexAcceptanceFailure.IMMUTABLE_BINDING_MISMATCH,
            assertIs<IncomingIndexAcceptanceCommitResult.Failure>(committer.commit(request, owner)).reason,
        )
        assertUnchanged()

        database.blobCacheDao().upsertForOwner(
            owner.toRestString(),
            original.copy(expectedSha256 = ByteArray(32) { 8 }),
        )
        assertEquals(
            IncomingIndexAcceptanceFailure.IMMUTABLE_BINDING_MISMATCH,
            assertIs<IncomingIndexAcceptanceCommitResult.Failure>(committer.commit(request, owner)).reason,
        )
        assertUnchanged()

        database.blobCacheDao().upsertForOwner(
            owner.toRestString(),
            original.copy(expectedSizeBytes = original.expectedSizeBytes + 1),
        )
        assertEquals(
            IncomingIndexAcceptanceFailure.IMMUTABLE_BINDING_MISMATCH,
            assertIs<IncomingIndexAcceptanceCommitResult.Failure>(committer.commit(request, owner)).reason,
        )
        assertUnchanged()
    }

    @Test
    fun nonReadyServerStateIsRejectedWithoutAdvancingMaterial() = runTest {
        val request = seedAndAdopt()
        val capsuleRow = database.incomingCapsuleDao()
            .getByCapsuleIdAndOwner(capsule.toRestString(), owner.toRestString())!!
        database.incomingCapsuleDao().upsertAllForOwner(
            owner.toRestString(),
            listOf(capsuleRow.copy(serverStatus = "AVAILABLE")),
        )

        val result = assertIs<IncomingIndexAcceptanceCommitResult.Failure>(committer.commit(request, owner))

        assertFalse(result.retryable)
        assertEquals(IncomingIndexAcceptanceFailure.IMMUTABLE_BINDING_MISMATCH, result.reason)
        assertUnchanged()
    }

    @Test
    fun missingRowsAndWrongOwnerRowsAreTerminalAndOwnerIsolated() = runTest {
        val request = seedAndAdopt()
        val missingDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RemanenceLocalDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val missing = IncomingIndexAcceptanceCommitter(missingDatabase, roots).commit(request, owner)
            assertEquals(
                IncomingIndexAcceptanceFailure.MISSING_ROW,
                assertIs<IncomingIndexAcceptanceCommitResult.Failure>(missing).reason,
            )
        } finally {
            missingDatabase.close()
        }

        val ownerBRequest = seedAndAdopt(ownerUserId = otherOwner, persistRows = false)
        val isolated = committer.commit(ownerBRequest, otherOwner)
        assertEquals(
            IncomingIndexAcceptanceFailure.MISSING_ROW,
            assertIs<IncomingIndexAcceptanceCommitResult.Failure>(isolated).reason,
        )
        assertUnchanged()
    }

    @Test
    fun illegalAndMixedStatesFailClosed() = runTest {
        val illegal = seedAndAdopt(
            capsuleState = LocalMaterialState.CORRUPT,
            blobState = BlobCacheState.DOWNLOADING,
        )
        assertEquals(
            IncomingIndexAcceptanceFailure.ILLEGAL_STATE,
            assertIs<IncomingIndexAcceptanceCommitResult.Failure>(committer.commit(illegal, owner)).reason,
        )
        assertState(LocalMaterialState.CORRUPT, BlobCacheState.DOWNLOADING)

        tearDownAndSetUp()
        val mixed = seedAndAdopt(
            capsuleState = LocalMaterialState.DISCOVERED,
            blobState = BlobCacheState.CACHED,
        )
        assertEquals(
            IncomingIndexAcceptanceFailure.ILLEGAL_STATE,
            assertIs<IncomingIndexAcceptanceCommitResult.Failure>(committer.commit(mixed, owner)).reason,
        )
        assertState(LocalMaterialState.DISCOVERED, BlobCacheState.CACHED)
    }

    @Test
    fun concurrentCallersHaveOneCommitAndOneExactReplay() = runTest {
        val request = seedAndAdopt()

        val results = coroutineScope {
            listOf(
                async(Dispatchers.IO) { committer.commit(request, owner) },
                async(Dispatchers.IO) { committer.commit(request, owner) },
            ).awaitAll()
        }

        assertEquals(1, results.count { it is IncomingIndexAcceptanceCommitResult.Committed })
        assertEquals(1, results.count { it is IncomingIndexAcceptanceCommitResult.IdempotentReplay })
        assertEquals(
            LocalMaterialState.INDEX_CACHED,
            database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsule.toRestString(), owner.toRestString())!!.materialState,
        )
        assertEquals(
            BlobCacheState.CACHED,
            database.blobCacheDao().getByBlobIdAndOwner(blob.toRestString(), owner.toRestString())!!.cacheState,
        )
    }

    @Test
    fun corruptDurableFileIsRejectedAndNeitherRowAdvances() = runTest {
        val request = seedAndAdopt()
        request.durableCiphertext.asFile().writeBytes("corrupt".toByteArray())

        val result = assertIs<IncomingIndexAcceptanceCommitResult.Failure>(committer.commit(request, owner))

        assertEquals(IncomingIndexAcceptanceFailure.SOURCE_INTEGRITY_MISMATCH, result.reason)
        assertUnchanged()
    }

    @Test
    fun leafSymlinkIsRejectedBeforeItsTargetCanSatisfyHash() = runTest {
        val request = seedAndAdopt()
        val destination = request.durableCiphertext.asFile().toPath()
        val target = File(testRoot, "leaf-symlink-target").apply { writeBytes(bytes) }
        Files.delete(destination)
        Files.createSymbolicLink(destination, target.toPath())

        val result = assertIs<IncomingIndexAcceptanceCommitResult.Failure>(committer.commit(request, owner))

        assertFalse(result.retryable)
        assertEquals(IncomingIndexAcceptanceFailure.CAPABILITY_MISMATCH, result.reason)
        assertUnchanged()
    }

    @Test
    fun ancestorSymlinkIsRejectedEvenWhenResolvedTargetIsInRoot() = runTest {
        val request = seedAndAdopt()
        val blobs = request.durableCiphertext.asFile().toPath().parent
        val relocated = File(testRoot, "relocated-blobs-${UUID.randomUUID()}").toPath()
        Files.move(blobs, relocated)
        Files.createSymbolicLink(blobs, relocated)

        val result = assertIs<IncomingIndexAcceptanceCommitResult.Failure>(committer.commit(request, owner))

        assertFalse(result.retryable)
        assertEquals(IncomingIndexAcceptanceFailure.CAPABILITY_MISMATCH, result.reason)
        assertUnchanged()
    }

    @Test
    fun requestAndFailureAreRedacted() = runTest {
        val request = seedAndAdopt()
        val failure = assertIs<IncomingIndexAcceptanceCommitResult.Failure>(
            committer.commit(request.copy(expectedSha256 = ByteArray(32) { 3 }), owner),
        )

        assertFalse(request.toString().contains(request.durableCiphertext.asFile().path))
        assertFalse(failure.toString().contains(request.durableCiphertext.asFile().path))
    }

    private suspend fun seedAndAdopt(
        ownerUserId: UserId = owner,
        capsuleState: LocalMaterialState = LocalMaterialState.DISCOVERED,
        blobState: BlobCacheState = BlobCacheState.DOWNLOADING,
        persistRows: Boolean = true,
    ): IncomingIndexAcceptanceCommitRequest {
        val ownerText = ownerUserId.toRestString()
        val sourceRoot = roots.child(ownerUserId, AccountScopedFileRoots.ChildRoot.TEMP)
        sourceRoot.mkdirs()
        val source = File(sourceRoot, "recognition-${UUID.randomUUID()}.tmp")
        source.writeBytes(bytes)
        val adopted = assertIs<dev.hryshyn.remanence.core.data.storage.IncomingRecognitionCiphertextAdoptionResult.Adopted>(
            adopter.adopt(
                IncomingRecognitionCiphertextAdoptionRequest(
                    ownerUserId = ownerUserId,
                    capsuleId = capsule,
                    blobId = blob,
                    expectedSizeBytes = bytes.size.toLong(),
                    expectedSha256 = sha256(bytes),
                    sourceTempFile = source,
                ),
            ),
        ).destination

        if (persistRows) {
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
                        protocolVersion = 1,
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
            database.blobCacheDao().upsertForOwner(
                ownerText,
                BlobCacheEntity(
                    blobId = blob.toRestString(),
                    ownerUserId = ownerText,
                    capsuleId = capsule.toRestString(),
                    kind = "RECOGNITION_MANIFEST",
                    ordinal = null,
                    expectedSizeBytes = bytes.size.toLong(),
                    expectedSha256 = sha256(bytes),
                    localPath = adopted.asFile().canonicalPath,
                    cacheState = blobState,
                ),
            )
            if (capsuleState != LocalMaterialState.DISCOVERED) {
                database.incomingCapsuleDao().transitionMaterialStateForOwner(
                    ownerUserId = ownerText,
                    capsuleId = capsule.toRestString(),
                    requestedTarget = capsuleState,
                )
            }
        }
        return IncomingIndexAcceptanceCommitRequest(
            ownerUserId = ownerUserId,
            capsuleId = capsule,
            recognitionBlobId = blob,
            expectedSizeBytes = bytes.size.toLong(),
            expectedSha256 = sha256(bytes),
            durableCiphertext = adopted,
        )
    }

    private fun IncomingIndexAcceptanceCommitRequest.copy(
        ownerUserId: UserId = this.ownerUserId,
        capsuleId: CapsuleId = this.capsuleId,
        recognitionBlobId: BlobId = this.recognitionBlobId,
        expectedSizeBytes: Long = this.expectedSizeBytes,
        expectedSha256: ByteArray = this.expectedSha256,
        durableCiphertext: DurableIncomingCiphertextFile = this.durableCiphertext,
    ) = IncomingIndexAcceptanceCommitRequest(
        ownerUserId,
        capsuleId,
        recognitionBlobId,
        expectedSizeBytes,
        expectedSha256,
        durableCiphertext,
    )

    private suspend fun assertUnchanged() = assertState(
        LocalMaterialState.DISCOVERED,
        BlobCacheState.DOWNLOADING,
    )

    private suspend fun assertState(
        capsuleState: LocalMaterialState,
        blobState: BlobCacheState,
    ) {
        assertEquals(
            capsuleState,
            database.incomingCapsuleDao().getByCapsuleIdAndOwner(capsule.toRestString(), owner.toRestString())!!.materialState,
        )
        assertEquals(
            blobState,
            database.blobCacheDao().getByBlobIdAndOwner(blob.toRestString(), owner.toRestString())!!.cacheState,
        )
    }

    private fun tearDownAndSetUp() {
        database.close()
        testRoot.deleteRecursively()
        setUp()
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)
}
