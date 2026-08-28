package dev.hryshyn.remanence.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.data.db.OutboxBlobEntity
import dev.hryshyn.remanence.core.data.db.OutboxBlobUploadState
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleEntity
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.network.CapsuleBlobUploadFailure
import dev.hryshyn.remanence.core.data.network.CapsuleBlobUploadRequest
import dev.hryshyn.remanence.core.data.network.CapsuleBlobUploadResult
import dev.hryshyn.remanence.core.data.network.CapsuleDraft
import dev.hryshyn.remanence.core.data.network.CapsuleDraftBlob
import dev.hryshyn.remanence.core.data.network.CapsuleDraftBlobDeclaration
import dev.hryshyn.remanence.core.data.network.CapsuleDraftBlobState
import dev.hryshyn.remanence.core.data.network.CapsuleDraftFailure
import dev.hryshyn.remanence.core.data.network.CapsuleDraftRequest
import dev.hryshyn.remanence.core.data.network.CapsuleDraftResult
import dev.hryshyn.remanence.core.data.network.CapsuleDraftState
import dev.hryshyn.remanence.core.data.network.CapsuleFinalize
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeFailure
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeRequest
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeResult
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeState
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialLifecycle
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapsuleUploadOrchestratorTest {

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var files: File
    private lateinit var retryStore: SenderRetryMaterialStore
    private lateinit var retryLifecycle: SenderRetryMaterialLifecycle

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        files = File(context.cacheDir, "a04-${UUID.randomUUID()}").apply { mkdirs() }
        val roots = AccountScopedFileRoots(files)
        retryStore = SenderRetryMaterialStore(roots)
        retryLifecycle = SenderRetryMaterialLifecycle(retryStore, database.outboxCapsuleDao())
    }

    @After
    fun tearDown() {
        database.close()
        files.deleteRecursively()
    }

    @Test
    fun accountMismatchDoesNotReadOrMutateTheCapsuleOrCallNetwork() = runBlocking {
        seed(OutboxCapsuleState.ENCRYPTED)
        val events = mutableListOf<String>()
        val orchestrator = orchestrator(currentOwner = OTHER_OWNER)

        assertEquals(
            CapsuleUploadOutcome.AccountMismatch,
            orchestrator.run(OWNER_TYPED, CAPSULE_TYPED),
        )
        assertTrue(events.isEmpty())
        assertEquals(OutboxCapsuleState.ENCRYPTED, capsuleRow().state)
        assertTrue(blobRows().all { it.uploadState == OutboxBlobUploadState.PENDING })
    }

    @Test
    fun successfulRunUsesDraftThenEveryBlobThenFinalizeAndPublishesLast() = runBlocking {
        seed(OutboxCapsuleState.ENCRYPTED)
        val events = mutableListOf<String>()
        var draftRequest: CapsuleDraftRequest? = null
        val uploaded = mutableListOf<CapsuleBlobUploadRequest>()
        var finalizeRequest: CapsuleFinalizeRequest? = null
        val orchestrator = orchestrator {
            createDraft = { request, _ ->
                events += "draft"
                draftRequest = request
                CapsuleDraftResult.Success(
                    CapsuleDraft(
                        request.capsuleId,
                        CapsuleDraftState.DRAFT,
                        "2030-01-08T00:00:00Z",
                        request.blobs.map { CapsuleDraftBlob(it.blobId, CapsuleDraftBlobState.DECLARED) },
                    ),
                    201,
                )
            }
            uploadBlob = { request, _ ->
                events += "upload:${request.blobId.toRestString()}"
                uploaded += request
                CapsuleBlobUploadResult.Success(204)
            }
            finalizeCapsule = { request, _ ->
                events += "finalize"
                finalizeRequest = request
                CapsuleFinalizeResult.Success(
                    CapsuleFinalize(CAPSULE_TYPED, CapsuleFinalizeState.READY, "2030-01-08T00:00:00Z"),
                    201,
                )
            }
            cleanupRetryMaterial = { _, _ ->
                events += "cleanup"
                SenderRetryMaterialLifecycle.Result.OK
            }
        }

        val outcome = orchestrator.run(OWNER_TYPED, CAPSULE_TYPED)
        assertEquals(
            "outcome=$outcome events=$events state=${capsuleRow().state}",
            CapsuleUploadOutcome.Succeeded,
            outcome,
        )
        assertEquals("draft", events.first())
        assertEquals("finalize", events[events.size - 2])
        assertEquals("cleanup", events.last())
        assertEquals(8, events.size)
        assertEquals(5, uploaded.size)
        assertEquals(
            listOf("RECOGNITION_MANIFEST", "CONTENT_MANIFEST", "PHOTO", "PHOTO", "PHOTO"),
            draftRequest!!.blobs.map { it.kind.name },
        )
        assertEquals(
            draftRequest!!.blobs.map { it.blobId },
            uploaded.map { it.blobId },
        )
        assertEquals(CAPSULE_TYPED, finalizeRequest!!.capsuleId)
        assertEquals(OutboxCapsuleState.PUBLISHED, capsuleRow().state)
        assertEquals(null, capsuleRow().lastErrorCode)
        assertTrue(blobRows().all { it.uploadState == OutboxBlobUploadState.STORED })
        assertTrue(blobRows().all { it.attemptCount == 1 })
    }

    @Test
    fun networkFailureMarksRetryableAndDoesNotFinalizeOrDeleteRetryMaterial() = runBlocking {
        seed(OutboxCapsuleState.ENCRYPTED)
        val events = mutableListOf<String>()
        var failUploadOnce = true
        val orchestrator = orchestrator {
            createDraft = { request, _ ->
                events += "draft"
                draftSuccess(request)
            }
            uploadBlob = { _, _ ->
                events += "upload"
                if (failUploadOnce) {
                    failUploadOnce = false
                    CapsuleBlobUploadResult.Failure(CapsuleBlobUploadFailure.NETWORK)
                } else {
                    CapsuleBlobUploadResult.Success(204)
                }
            }
            finalizeCapsule = { _, _ ->
                events += "finalize"
                CapsuleFinalizeResult.Success(
                    CapsuleFinalize(CAPSULE_TYPED, CapsuleFinalizeState.READY, "2030-01-08T00:00:00Z"),
                    201,
                )
            }
            cleanupRetryMaterial = { _, _ ->
                events += "cleanup"
                if (failUploadOnce) {
                    error("retry material cleanup must wait for accepted finalization")
                }
                SenderRetryMaterialLifecycle.Result.OK
            }
        }

        assertEquals(
            CapsuleUploadOutcome.Retryable(CapsuleBlobUploadFailure.NETWORK.name),
            orchestrator.run(OWNER_TYPED, CAPSULE_TYPED),
        )
        assertEquals(OutboxCapsuleState.RETRYABLE_FAILURE, capsuleRow().state)
        assertEquals(CapsuleBlobUploadFailure.NETWORK.name, capsuleRow().lastErrorCode)
        assertFalse("retry material is retained before accepted READY", "cleanup" in events)
        assertEquals(listOf("draft", "upload"), events)

        assertEquals(CapsuleUploadOutcome.Succeeded, orchestrator.run(OWNER_TYPED, CAPSULE_TYPED))
        assertEquals(OutboxCapsuleState.PUBLISHED, capsuleRow().state)
        assertEquals(2, events.count { it == "draft" })
        assertEquals(6, events.count { it == "upload" })
        assertEquals(listOf("finalize", "cleanup"), events.takeLast(2))
    }

    @Test
    fun protocolFailureMarksTerminalAndDoesNotPretendToPublish() = runBlocking {
        seed(OutboxCapsuleState.ENCRYPTED, withRetryMaterial = true)
        val events = mutableListOf<String>()
        val orchestrator = orchestrator {
            createDraft = { _, _ ->
                events += "draft"
                CapsuleDraftResult.Failure(CapsuleDraftFailure.VALIDATION_FAILED, 422)
            }
            uploadBlob = { _, _ ->
                events += "upload"
                error("upload must not run after terminal draft failure")
            }
            finalizeCapsule = { _, _ ->
                events += "finalize"
                error("finalize must not run after terminal draft failure")
            }
            cleanupRetryMaterial = { owner, capsule ->
                events += "cleanup"
                retryLifecycle.cleanupForTerminalState(owner, capsule)
            }
        }

        assertEquals(
            CapsuleUploadOutcome.TerminalFailure(CapsuleDraftFailure.VALIDATION_FAILED.name),
            orchestrator.run(OWNER_TYPED, CAPSULE_TYPED),
        )
        assertEquals(OutboxCapsuleState.TERMINAL_FAILURE, capsuleRow().state)
        assertEquals(CapsuleDraftFailure.VALIDATION_FAILED.name, capsuleRow().lastErrorCode)
        assertEquals(listOf("draft", "cleanup"), events)
        assertNull(capsuleRow().senderRetryKeysetPath)
        assertNull(retryStore.read(OWNER_TYPED, CAPSULE_TYPED))
    }

    @Test
    fun terminalBlobFailureCleansRetryMaterialAfterDurableCas() = runBlocking {
        seed(OutboxCapsuleState.ENCRYPTED, withRetryMaterial = true)
        val events = mutableListOf<String>()
        val orchestrator = orchestrator {
            createDraft = { request, _ ->
                events += "draft"
                draftSuccess(request)
            }
            uploadBlob = { _, _ ->
                events += "upload"
                CapsuleBlobUploadResult.Failure(CapsuleBlobUploadFailure.BLOB_SIZE_INVALID)
            }
            finalizeCapsule = { _, _ ->
                error("finalize must not run after terminal blob failure")
            }
            cleanupRetryMaterial = { owner, capsule ->
                events += "cleanup"
                retryLifecycle.cleanupForTerminalState(owner, capsule)
            }
        }

        assertEquals(
            CapsuleUploadOutcome.TerminalFailure(CapsuleBlobUploadFailure.BLOB_SIZE_INVALID.name),
            orchestrator.run(OWNER_TYPED, CAPSULE_TYPED),
        )
        assertEquals(OutboxCapsuleState.TERMINAL_FAILURE, capsuleRow().state)
        assertEquals(CapsuleBlobUploadFailure.BLOB_SIZE_INVALID.name, capsuleRow().lastErrorCode)
        assertEquals(listOf("draft", "upload", "cleanup"), events)
        assertNull(capsuleRow().senderRetryKeysetPath)
        assertNull(retryStore.read(OWNER_TYPED, CAPSULE_TYPED))
    }

    @Test
    fun terminalFinalizeFailureCleansRetryMaterialAfterDurableCas() = runBlocking {
        seed(OutboxCapsuleState.ENCRYPTED, withRetryMaterial = true)
        val events = mutableListOf<String>()
        val orchestrator = orchestrator {
            createDraft = { request, _ ->
                events += "draft"
                draftSuccess(request)
            }
            uploadBlob = { request, _ ->
                events += "upload:${request.blobId.toRestString()}"
                CapsuleBlobUploadResult.Success(204)
            }
            finalizeCapsule = { _, _ ->
                events += "finalize"
                CapsuleFinalizeResult.Failure(CapsuleFinalizeFailure.SIGNATURE_INVALID, 422)
            }
            cleanupRetryMaterial = { owner, capsule ->
                events += "cleanup"
                retryLifecycle.cleanupForTerminalState(owner, capsule)
            }
        }

        assertEquals(
            CapsuleUploadOutcome.TerminalFailure(CapsuleFinalizeFailure.SIGNATURE_INVALID.name),
            orchestrator.run(OWNER_TYPED, CAPSULE_TYPED),
        )
        assertEquals(OutboxCapsuleState.TERMINAL_FAILURE, capsuleRow().state)
        assertEquals(CapsuleFinalizeFailure.SIGNATURE_INVALID.name, capsuleRow().lastErrorCode)
        assertEquals("cleanup", events.last())
        assertNull(capsuleRow().senderRetryKeysetPath)
        assertNull(retryStore.read(OWNER_TYPED, CAPSULE_TYPED))
    }

    @Test
    fun terminalReplayCleansWithoutCallingNetworkAndPreservesOriginalError() = runBlocking {
        seed(
            OutboxCapsuleState.TERMINAL_FAILURE,
            withRetryMaterial = true,
            errorCode = "ORIGINAL_TERMINAL_ERROR",
        )
        val events = mutableListOf<String>()
        val orchestrator = orchestrator {
            createDraft = { _, _ -> error("terminal replay must not create a draft") }
            uploadBlob = { _, _ -> error("terminal replay must not upload") }
            finalizeCapsule = { _, _ -> error("terminal replay must not finalize") }
            cleanupRetryMaterial = { owner, capsule ->
                events += "cleanup"
                retryLifecycle.cleanupForTerminalState(owner, capsule)
            }
        }

        assertEquals(
            CapsuleUploadOutcome.TerminalFailure("ORIGINAL_TERMINAL_ERROR"),
            orchestrator.run(OWNER_TYPED, CAPSULE_TYPED),
        )
        assertEquals(listOf("cleanup"), events)
        assertEquals(OutboxCapsuleState.TERMINAL_FAILURE, capsuleRow().state)
        assertEquals("ORIGINAL_TERMINAL_ERROR", capsuleRow().lastErrorCode)
        assertNull(capsuleRow().senderRetryKeysetPath)
        assertNull(retryStore.read(OWNER_TYPED, CAPSULE_TYPED))
    }

    @Test
    fun terminalReplayWithMissingFileClearsLivePointer() = runBlocking {
        seed(
            OutboxCapsuleState.TERMINAL_FAILURE,
            withRetryMaterial = true,
            errorCode = "ORIGINAL_TERMINAL_ERROR",
        )
        assertTrue(retryStore.expectedPath(OWNER_TYPED, CAPSULE_TYPED).delete())
        assertTrue(capsuleRow().senderRetryKeysetPath != null)

        val orchestrator = orchestrator {
            cleanupRetryMaterial = { owner, capsule ->
                retryLifecycle.cleanupForTerminalState(owner, capsule)
            }
        }

        assertEquals(
            CapsuleUploadOutcome.TerminalFailure("ORIGINAL_TERMINAL_ERROR"),
            orchestrator.run(OWNER_TYPED, CAPSULE_TYPED),
        )
        assertNull(capsuleRow().senderRetryKeysetPath)
        assertEquals(OutboxCapsuleState.TERMINAL_FAILURE, capsuleRow().state)
    }

    @Test
    fun terminalCleanupFailureIsRetryableAndReplayCanFinishCleanup() = runBlocking {
        seed(
            OutboxCapsuleState.TERMINAL_FAILURE,
            withRetryMaterial = true,
            errorCode = "ORIGINAL_TERMINAL_ERROR",
        )
        var failCleanup = true
        val orchestrator = orchestrator {
            cleanupRetryMaterial = { owner, capsule ->
                if (failCleanup) {
                    failCleanup = false
                    SenderRetryMaterialLifecycle.Result.DELETE_FAILED
                } else {
                    retryLifecycle.cleanupForTerminalState(owner, capsule)
                }
            }
        }

        assertEquals(
            CapsuleUploadOutcome.Retryable("RETRY_MATERIAL_CLEANUP"),
            orchestrator.run(OWNER_TYPED, CAPSULE_TYPED),
        )
        assertEquals(OutboxCapsuleState.TERMINAL_FAILURE, capsuleRow().state)
        assertEquals("ORIGINAL_TERMINAL_ERROR", capsuleRow().lastErrorCode)
        assertTrue(capsuleRow().senderRetryKeysetPath != null)
        assertTrue(retryStore.expectedPath(OWNER_TYPED, CAPSULE_TYPED).exists())

        assertEquals(
            CapsuleUploadOutcome.TerminalFailure("ORIGINAL_TERMINAL_ERROR"),
            orchestrator.run(OWNER_TYPED, CAPSULE_TYPED),
        )
        assertEquals(OutboxCapsuleState.TERMINAL_FAILURE, capsuleRow().state)
        assertEquals("ORIGINAL_TERMINAL_ERROR", capsuleRow().lastErrorCode)
        assertNull(capsuleRow().senderRetryKeysetPath)
        assertNull(retryStore.read(OWNER_TYPED, CAPSULE_TYPED))
    }

    @Test
    fun staleRecipientKeyRemainsRetryableAndRetainsRetryMaterial() = runBlocking {
        seed(OutboxCapsuleState.ENCRYPTED, withRetryMaterial = true)
        var cleanupCalls = 0
        val orchestrator = orchestrator {
            createDraft = { _, _ ->
                CapsuleDraftResult.Failure(CapsuleDraftFailure.RECIPIENT_KEY_STALE, 409)
            }
            cleanupRetryMaterial = { _, _ ->
                cleanupCalls += 1
                error("stale recipient key must not clean retry material")
            }
        }

        assertEquals(
            CapsuleUploadOutcome.Retryable(CapsuleDraftFailure.RECIPIENT_KEY_STALE.name),
            orchestrator.run(OWNER_TYPED, CAPSULE_TYPED),
        )
        assertEquals(OutboxCapsuleState.RETRYABLE_FAILURE, capsuleRow().state)
        assertEquals(0, cleanupCalls)
        assertTrue(capsuleRow().senderRetryKeysetPath != null)
        assertTrue(retryStore.expectedPath(OWNER_TYPED, CAPSULE_TYPED).exists())
    }

    @Test
    fun cancellationBeforeTerminalDecisionRethrowsAndRetainsRetryMaterial() = runBlocking {
        seed(OutboxCapsuleState.ENCRYPTED, withRetryMaterial = true)
        var cleanupCalls = 0
        val orchestrator = orchestrator {
            createDraft = { _, _ -> throw CancellationException("cancelled") }
            cleanupRetryMaterial = { _, _ ->
                cleanupCalls += 1
                SenderRetryMaterialLifecycle.Result.OK
            }
        }

        var rethrown = false
        try {
            orchestrator.run(OWNER_TYPED, CAPSULE_TYPED)
        } catch (_: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(0, cleanupCalls)
        assertEquals(OutboxCapsuleState.ENCRYPTED, capsuleRow().state)
        assertTrue(retryStore.expectedPath(OWNER_TYPED, CAPSULE_TYPED).exists())
    }

    @Test
    fun cancellationDuringTerminalCleanupRethrowsAfterDurableTerminalCas() = runBlocking {
        seed(OutboxCapsuleState.ENCRYPTED, withRetryMaterial = true)
        val orchestrator = orchestrator {
            createDraft = { _, _ ->
                CapsuleDraftResult.Failure(CapsuleDraftFailure.VALIDATION_FAILED, 422)
            }
            cleanupRetryMaterial = { _, _ -> throw CancellationException("cancelled") }
        }

        var rethrown = false
        try {
            orchestrator.run(OWNER_TYPED, CAPSULE_TYPED)
        } catch (_: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(OutboxCapsuleState.TERMINAL_FAILURE, capsuleRow().state)
        assertEquals(CapsuleDraftFailure.VALIDATION_FAILED.name, capsuleRow().lastErrorCode)
        assertTrue(capsuleRow().senderRetryKeysetPath != null)
        assertTrue(retryStore.expectedPath(OWNER_TYPED, CAPSULE_TYPED).exists())
    }

    @Test
    fun terminalAccountMismatchDoesNotCleanRetryMaterial() = runBlocking {
        seed(
            OutboxCapsuleState.TERMINAL_FAILURE,
            withRetryMaterial = true,
            errorCode = "ORIGINAL_TERMINAL_ERROR",
        )
        var cleanupCalls = 0
        val orchestrator = orchestrator(currentOwner = OTHER_OWNER) {
            cleanupRetryMaterial = { _, _ ->
                cleanupCalls += 1
                error("foreign account must not clean retry material")
            }
        }

        assertEquals(
            CapsuleUploadOutcome.AccountMismatch,
            orchestrator.run(OWNER_TYPED, CAPSULE_TYPED),
        )
        assertEquals(0, cleanupCalls)
        assertEquals(OutboxCapsuleState.TERMINAL_FAILURE, capsuleRow().state)
        assertTrue(capsuleRow().senderRetryKeysetPath != null)
        assertTrue(retryStore.expectedPath(OWNER_TYPED, CAPSULE_TYPED).exists())
    }

    @Test
    fun workRequestContainsOnlyCanonicalAccountAndCapsuleScope() {
        val request = CapsuleUploadWorker.request(OWNER_TYPED, CAPSULE_TYPED)

        assertEquals(
            setOf(CapsuleUploadWorker.INPUT_OWNER_USER_ID, CapsuleUploadWorker.INPUT_CAPSULE_ID),
            request.workSpec.input.keyValueMap.keys,
        )
        assertEquals(OWNER_TYPED.toRestString(), request.workSpec.input.getString(CapsuleUploadWorker.INPUT_OWNER_USER_ID))
        assertEquals(CAPSULE_TYPED.toRestString(), request.workSpec.input.getString(CapsuleUploadWorker.INPUT_CAPSULE_ID))
        assertEquals(
            setOf(
                CapsuleUploadWorker::class.java.name,
                "remanence",
                AccountWorkIdentity.accountTag(OWNER_TYPED),
                AccountWorkIdentity.capsuleTag(CAPSULE_TYPED),
            ),
            request.tags,
        )
    }

    private fun orchestrator(
        currentOwner: String = OWNER,
        configure: OrchestratorConfig.() -> Unit = {},
    ): CapsuleUploadOrchestrator {
        val config = OrchestratorConfig().apply(configure)
        return CapsuleUploadOrchestrator(
            capsuleDao = database.outboxCapsuleDao(),
            blobDao = database.outboxBlobDao(),
            currentAccountUserId = { currentOwner },
            accessToken = { "access-token" },
            createDraft = config.createDraft,
            uploadBlob = config.uploadBlob,
            finalizeCapsule = config.finalizeCapsule,
            cleanupRetryMaterial = config.cleanupRetryMaterial,
            readCiphertext = { path -> File(path).readBytes() },
        )
    }

    private inner class OrchestratorConfig {
        var createDraft: suspend (CapsuleDraftRequest, String) -> CapsuleDraftResult = { request, _ ->
            draftSuccess(request)
        }
        var uploadBlob: suspend (CapsuleBlobUploadRequest, String) -> CapsuleBlobUploadResult = { _, _ ->
            CapsuleBlobUploadResult.Success(204)
        }
        var finalizeCapsule: suspend (CapsuleFinalizeRequest, String) -> CapsuleFinalizeResult = { _, _ ->
            CapsuleFinalizeResult.Success(
                CapsuleFinalize(CAPSULE_TYPED, CapsuleFinalizeState.READY, "2030-01-08T00:00:00Z"),
                201,
            )
        }
        var cleanupRetryMaterial: suspend (UserId, CapsuleId) -> SenderRetryMaterialLifecycle.Result = { _, _ ->
            SenderRetryMaterialLifecycle.Result.OK
        }
    }

    private fun draftSuccess(request: CapsuleDraftRequest): CapsuleDraftResult.Success =
        CapsuleDraftResult.Success(
            CapsuleDraft(
                request.capsuleId,
                CapsuleDraftState.DRAFT,
                "2030-01-08T00:00:00Z",
                request.blobs.map { CapsuleDraftBlob(it.blobId, CapsuleDraftBlobState.DECLARED) },
            ),
            201,
        )

    private suspend fun seed(
        state: OutboxCapsuleState,
        withRetryMaterial: Boolean = false,
        errorCode: String? = null,
    ) {
        val retryPath = if (withRetryMaterial) {
            retryStore.write(OWNER_TYPED, CAPSULE_TYPED, RETRY_BYTES)
        } else {
            null
        }
        val capsule = OutboxCapsuleEntity(
            capsuleId = CAPSULE,
            idempotencyKey = IDEMPOTENCY,
            ownerUserId = OWNER,
            senderUserId = OWNER,
            recipientUserId = RECIPIENT,
            senderKeyBundleId = SENDER_BUNDLE,
            recipientKeyBundleId = RECIPIENT_BUNDLE,
            senderSigningPublicKeysetB64 = "public-only-test-export",
            state = state,
            recognitionManifestPath = null,
            contentManifestPath = null,
            envelopePath = File(files, "envelope.bin").absolutePath,
            publishStatementPath = File(files, "statement.bin").absolutePath,
            publishStatementSignaturePath = File(files, "signature.bin").absolutePath,
            senderRetryKeysetPath = retryPath,
            lastErrorCode = errorCode,
        )
        File(files, "envelope.bin").writeBytes("opaque-envelope".toByteArray())
        File(files, "statement.bin").writeBytes("canonical-statement".toByteArray())
        File(files, "signature.bin").writeBytes(ByteArray(69) { 1 })
        database.outboxCapsuleDao().insertOrAbort(OWNER, capsule)

        val blobs = listOf(
            blob("0198f0a0-0000-7000-8000-00000000a401", "RECOGNITION_MANIFEST", -1, 0),
            blob("0198f0a0-0000-7000-8000-00000000a402", "CONTENT_MANIFEST", -1, 1),
            blob("0198f0a0-0000-7000-8000-00000000a403", "PHOTO", 0, 2),
            blob("0198f0a0-0000-7000-8000-00000000a404", "PHOTO", 1, 3),
            blob("0198f0a0-0000-7000-8000-00000000a405", "PHOTO", 2, 4),
        )
        database.outboxBlobDao().upsertAll(OWNER, blobs)
    }

    private fun blob(id: String, kind: String, ordinal: Int, index: Int): OutboxBlobEntity {
        val bytes = "opaque-blob-$index".toByteArray()
        val path = File(files, "blob-$index.bin").apply { writeBytes(bytes) }.absolutePath
        return OutboxBlobEntity(
            blobId = id,
            ownerUserId = OWNER,
            capsuleId = CAPSULE,
            kind = kind,
            ordinal = ordinal,
            localCiphertextPath = path,
            sizeBytes = bytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes),
            uploadState = OutboxBlobUploadState.PENDING,
            attemptCount = 0,
        )
    }

    private suspend fun capsuleRow(): OutboxCapsuleEntity =
        database.outboxCapsuleDao().getByCapsuleIdAndOwner(CAPSULE, OWNER)!!

    private suspend fun blobRows(): List<OutboxBlobEntity> =
        database.outboxBlobDao().getAllByCapsuleIdAndOwner(CAPSULE, OWNER)

    private companion object {
        const val OWNER = "0198f0a0-0000-7000-8000-00000000a401"
        const val OTHER_OWNER = "0198f0a0-0000-7000-8000-00000000a402"
        const val CAPSULE = "0198f0a0-0000-7000-8000-00000000a411"
        const val IDEMPOTENCY = "0198f0a0-0000-7000-8000-00000000a412"
        const val RECIPIENT = "0198f0a0-0000-7000-8000-00000000a421"
        const val SENDER_BUNDLE = "0198f0a0-0000-7000-8000-00000000a431"
        const val RECIPIENT_BUNDLE = "0198f0a0-0000-7000-8000-00000000a432"

        val RETRY_BYTES = "opaque-retry-material".toByteArray()

        val OWNER_TYPED = UserId.parseRest(OWNER)
        val CAPSULE_TYPED = CapsuleId.parseRest(CAPSULE)
    }
}
