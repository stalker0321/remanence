package dev.hryshyn.remanence.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.subtle.Base64
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.PublishStatementSigner
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.crypto.SignedPublishStatement
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleDao
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.network.CapsuleBlobUploadRequest
import dev.hryshyn.remanence.core.data.network.CapsuleBlobUploadResult
import dev.hryshyn.remanence.core.data.network.CapsuleDraft
import dev.hryshyn.remanence.core.data.network.CapsuleDraftBlob
import dev.hryshyn.remanence.core.data.network.CapsuleDraftBlobState
import dev.hryshyn.remanence.core.data.network.CapsuleDraftRequest
import dev.hryshyn.remanence.core.data.network.CapsuleDraftResult
import dev.hryshyn.remanence.core.data.network.CapsuleDraftState
import dev.hryshyn.remanence.core.data.network.CapsuleFinalize
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeFailure
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeRequest
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeResult
import dev.hryshyn.remanence.core.data.network.CapsuleFinalizeState
import dev.hryshyn.remanence.core.data.network.RecipientUserLookupResult
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialLifecycle
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.create.CapsulePublishRequest
import dev.hryshyn.remanence.create.CapsulePublisher
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A06 proof over real Room rows, retry storage, and protocol crypto. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapsuleUploadStaleRecoveryTest {

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var files: File
    private lateinit var roots: AccountScopedFileRoots
    private lateinit var retryStore: SenderRetryMaterialStore
    private lateinit var retryLifecycle: SenderRetryMaterialLifecycle
    private lateinit var senderIdentity: AccountIdentityGenerator.AccountIdentity
    private lateinit var oldRecipientIdentity: AccountIdentityGenerator.AccountIdentity
    private lateinit var newRecipientIdentity: AccountIdentityGenerator.AccountIdentity
    private lateinit var retryWrapper: SenderRetryKeysetWrapper
    private lateinit var prepared: dev.hryshyn.remanence.core.data.outbox.PreparedOutboxCapsule

    @Before
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        files = File(context.cacheDir, "a06-${UUID.randomUUID()}")
        roots = AccountScopedFileRoots(files)
        retryStore = SenderRetryMaterialStore(roots)
        retryLifecycle = SenderRetryMaterialLifecycle(retryStore, database.outboxCapsuleDao())

        senderIdentity = AccountIdentityGenerator().generate()
        oldRecipientIdentity = AccountIdentityGenerator().generate()
        newRecipientIdentity = AccountIdentityGenerator().generate()
        val boundary = SoftwareKekBoundary()
        val alias = "a06-${UUID.randomUUID()}"
        boundary.createAes256GcmKey(alias)
        retryWrapper = SenderRetryKeysetWrapper(boundary)
        prepared = CapsulePublisher(retryWrapper, alias).publish(
            CapsulePublishRequest(
                capsuleId = CAPSULE,
                senderUserId = OWNER,
                recipientUserId = RECIPIENT,
                senderKeyBundleId = SENDER_BUNDLE,
                recipientKeyBundleId = OLD_RECIPIENT_BUNDLE,
                ownerUserId = OWNER.toRestString(),
                senderHandleSnapshot = "sender",
                createdAtEpochSeconds = 1_700_000_000L,
                photoJpegs = (0 until 3).map { index ->
                    "photo-$index".toByteArray() + ByteArray(64) { it.toByte() }
                },
                photoWidthsPx = listOf(800, 800, 800),
                photoHeightsPx = listOf(600, 600, 600),
                noteUtf8 = "a06-test",
                frontFingerprintBytes = "front".toByteArray(),
                backFingerprintBytes = "back".toByteArray(),
                signingKeyset = senderIdentity.signingPrivateHandle,
                recipientEncryptionPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                    oldRecipientIdentity.encryptionPublicKeyset,
                ),
            ),
        )
        runBlocking {
            CapsuleOutboxStager(database, roots, retryStore).stage(prepared)
            assertEquals(
                1,
                database.outboxCapsuleDao().markRetryableFailureForOwner(
                    CAPSULE.toRestString(), OWNER.toRestString(), RECIPIENT_KEY_STALE,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
        files.deleteRecursively()
    }

    @Test
    fun staleFinalizeRecoveryRebindsOnlyRecipientMaterialAndSkipsStoredBlobs() = runBlocking {
        val originalRow = capsuleRow()
        val originalBlobRows = database.outboxBlobDao().getAllByCapsuleIdAndOwner(
            CAPSULE.toRestString(), OWNER.toRestString(),
        )
        val originalArtifactBytes = originalBlobRows.associate { it.blobId to File(it.localCiphertextPath).readBytes() }
        val oldEnvelope = File(originalRow.envelopePath!!).readBytes()
        val oldStatement = PublishStatement.parseFrom(File(originalRow.publishStatementPath!!).readBytes())
        val uploads = mutableListOf<CapsuleBlobUploadRequest>()
        val reads = mutableListOf<String>()
        var finalizeRequest: CapsuleFinalizeRequest? = null

        val result = orchestrator(
            draftState = CapsuleDraftBlobState.STORED,
            upload = { request, _ -> uploads += request; CapsuleBlobUploadResult.Success(204) },
            finalize = { request, _ ->
                finalizeRequest = request
                readyResult()
            },
            read = { path -> reads += path; File(path).readBytes() },
        ).run(OWNER, CAPSULE)

        assertEquals(CapsuleUploadOutcome.Succeeded, result)
        val request = finalizeRequest ?: error("finalize was not called")
        val current = capsuleRow()
        assertEquals(OutboxCapsuleState.PUBLISHED, current.state)
        assertEquals(NEW_RECIPIENT_BUNDLE.toRestString(), current.recipientKeyBundleId)
        assertEquals(RECIPIENT.toRestString(), current.recipientUserId)
        assertNotEquals(originalRow.envelopePath, current.envelopePath)
        assertNotEquals(originalRow.publishStatementPath, current.publishStatementPath)
        assertNotEquals(originalRow.publishStatementSignaturePath, current.publishStatementSignaturePath)
        assertFalse(File(originalRow.envelopePath!!).exists())
        assertFalse(File(originalRow.publishStatementPath!!).exists())
        assertFalse(File(originalRow.publishStatementSignaturePath!!).exists())
        assertTrue(current.senderRetryKeysetPath == null)
        assertFalse(retryStore.expectedPath(OWNER, CAPSULE).exists())

        assertTrue(uploads.isEmpty())
        assertTrue(originalBlobRows.all { row -> row.attemptCount == 0 })
        assertTrue(originalBlobRows.all { row ->
            originalArtifactBytes.getValue(row.blobId).contentEquals(File(row.localCiphertextPath).readBytes())
        })
        assertTrue(originalBlobRows.none { row -> reads.contains(row.localCiphertextPath) })

        val newStatement = PublishStatement.parseFrom(request.statement)
        assertEquals(NEW_RECIPIENT_BUNDLE.toProtoBytes(), newStatement.recipientKeyBundleId)
        assertEquals(oldStatement.toBuilder().setRecipientKeyBundleId(NEW_RECIPIENT_BUNDLE.toProtoBytes()).build(), newStatement)
        PublishStatementSigner().verify(
            TinkProtoKeysetFormat.parseKeysetWithoutSecret(senderIdentity.signingPublicKeyset),
            SignedPublishStatement(request.statement, request.signature),
        )

        val oldOpened = RecipientEnvelopeCryptor().open(
            oldRecipientIdentity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CAPSULE, OWNER, RECIPIENT, OLD_RECIPIENT_BUNDLE,
            ),
            oldEnvelope,
        )
        val newOpened = RecipientEnvelopeCryptor().open(
            newRecipientIdentity.encryptionPrivateHandle,
            dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput(
                CAPSULE, OWNER, RECIPIENT, NEW_RECIPIENT_BUNDLE,
            ),
            request.recipientEnvelopeCiphertext,
        )
        val oldPlaintext = RecipientEnvelopePlaintext.parseFrom(oldOpened)
        val newPlaintext = RecipientEnvelopePlaintext.parseFrom(newOpened)
        assertEquals(CAPSULE.toProtoBytes(), newPlaintext.capsuleId)
        assertEquals(RECIPIENT.toProtoBytes(), newPlaintext.recipientUserId)
        assertEquals(NEW_RECIPIENT_BUNDLE.toProtoBytes(), newPlaintext.recipientKeyBundleId)
        assertArrayEquals(oldPlaintext.capsuleAeadKeyset.toByteArray(), newPlaintext.capsuleAeadKeyset.toByteArray())
        assertEquals(
            java.security.MessageDigest.getInstance("SHA-256").digest(request.statement).toList(),
            newPlaintext.publishStatementSha256.toByteArray().toList(),
        )
    }

    @Test
    fun processRestartAfterRewrapCasReplaysNewMaterialWithoutReupload() = runBlocking {
        val lookupCalls = AtomicInteger(0)
        val first = orchestrator(
            lookup = { _, _ -> lookupCalls.incrementAndGet(); foundRecipient() },
            draftState = CapsuleDraftBlobState.STORED,
            finalize = { _, _ ->
                CapsuleFinalizeResult.Failure(CapsuleFinalizeFailure.NETWORK, retryable = true)
            },
        ).run(OWNER, CAPSULE)
        assertEquals(CapsuleUploadOutcome.Retryable(CapsuleFinalizeFailure.NETWORK.name), first)
        assertEquals(OutboxCapsuleState.RETRYABLE_FAILURE, capsuleRow().state)
        assertEquals(CapsuleFinalizeFailure.NETWORK.name, capsuleRow().lastErrorCode)
        assertEquals(NEW_RECIPIENT_BUNDLE.toRestString(), capsuleRow().recipientKeyBundleId)
        assertTrue(capsuleRow().senderRetryKeysetPath != null)

        val uploads = mutableListOf<CapsuleBlobUploadRequest>()
        val second = orchestrator(
            lookup = { _, _ -> lookupCalls.incrementAndGet(); foundRecipient() },
            draftState = CapsuleDraftBlobState.STORED,
            upload = { request, _ -> uploads += request; CapsuleBlobUploadResult.Success(204) },
        ).run(OWNER, CAPSULE)
        assertEquals(CapsuleUploadOutcome.Succeeded, second)
        assertEquals(1, lookupCalls.get())
        assertTrue(uploads.isEmpty())
        assertEquals(OutboxCapsuleState.PUBLISHED, capsuleRow().state)
    }

    @Test
    fun staleFinalizeResultParksThenTheNextAttemptRecoversIt() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE outbox_capsule SET state = 'ENCRYPTED', last_error_code = NULL " +
                "WHERE capsule_id = ? AND owner_user_id = ?",
            arrayOf(CAPSULE.toRestString(), OWNER.toRestString()),
        )
        assertEquals(
            CapsuleUploadOutcome.RecipientKeyStale,
            orchestrator(
                draftState = CapsuleDraftBlobState.STORED,
                finalize = { _, _ ->
                    CapsuleFinalizeResult.Failure(CapsuleFinalizeFailure.RECIPIENT_KEY_STALE, retryable = false)
                },
            ).run(OWNER, CAPSULE),
        )
        assertEquals(RECIPIENT_KEY_STALE, capsuleRow().lastErrorCode)

        assertEquals(
            CapsuleUploadOutcome.Succeeded,
            orchestrator(draftState = CapsuleDraftBlobState.STORED).run(OWNER, CAPSULE),
        )
        assertEquals(OutboxCapsuleState.PUBLISHED, capsuleRow().state)
    }

    @Test
    fun sameRecipientBundleRemainsParkedWithoutCreatingReplacementFiles() = runBlocking {
        val before = roots.child(OWNER, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT)
            .listFiles()!!.map { it.name }.toSet()
        var finalizeCalls = 0
        val result = orchestrator(
            lookup = { _, _ ->
                RecipientUserLookupResult.Found(
                    foundRecipient().snapshot.copy(keyBundleId = OLD_RECIPIENT_BUNDLE),
                )
            },
            finalize = { _, _ -> finalizeCalls += 1; readyResult() },
        ).run(OWNER, CAPSULE)

        assertEquals(CapsuleUploadOutcome.RecipientKeyStale, result)
        assertEquals(0, finalizeCalls)
        assertEquals(before, roots.child(OWNER, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT)
            .listFiles()!!.map { it.name }.toSet())
        assertEquals(OLD_RECIPIENT_BUNDLE.toRestString(), capsuleRow().recipientKeyBundleId)
    }

    @Test
    fun casLoserDeletesOnlyItsFilesAndResumesUsingWinnerMaterial() = runBlocking {
        val losingDao = CasLosingCapsuleDao(
            delegate = database.outboxCapsuleDao(),
            winnerRoot = roots.child(OWNER, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT).canonicalFile,
        )
        var finalizeCalls = 0
        val result = orchestrator(
            capsuleDao = losingDao,
            draftState = CapsuleDraftBlobState.STORED,
            finalize = { _, _ -> finalizeCalls += 1; readyResult() },
        ).run(OWNER, CAPSULE)

        assertEquals(CapsuleUploadOutcome.Succeeded, result)
        assertEquals(1, finalizeCalls)
        assertTrue(losingDao.winnerPaths.all(File::exists))
        assertTrue(losingDao.loserPaths.none(File::exists))
        assertEquals(losingDao.winnerPaths[0].canonicalPath, capsuleRow().envelopePath)
        assertEquals(OutboxCapsuleState.PUBLISHED, capsuleRow().state)
    }

    @Test
    fun unavailableHistoricalSenderSigningKeyFailsClosedForCurrentM2() = runBlocking {
        val result = orchestrator(
            signingLoader = { _, _ -> null },
        ).run(OWNER, CAPSULE)

        assertEquals(CapsuleUploadOutcome.RecipientKeyStale, result)
        assertEquals(RECIPIENT_KEY_STALE, capsuleRow().lastErrorCode)
        assertTrue(capsuleRow().recipientKeyBundleId == OLD_RECIPIENT_BUNDLE.toRestString())
    }

    @Test
    fun missingCorruptOrWrongAadRetryMaterialLeavesRowParked() = runBlocking {
        retryStore.delete(OWNER, CAPSULE)
        assertEquals(CapsuleUploadOutcome.RecipientKeyStale, orchestrator().run(OWNER, CAPSULE))
        assertEquals(RECIPIENT_KEY_STALE, capsuleRow().lastErrorCode)

        stageFreshStaleRow()
        val retryFile = retryStore.expectedPath(OWNER, CAPSULE)
        retryFile.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(CapsuleUploadOutcome.RecipientKeyStale, orchestrator().run(OWNER, CAPSULE))
        assertEquals(RECIPIENT_KEY_STALE, capsuleRow().lastErrorCode)

        stageFreshStaleRow()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE outbox_capsule SET sender_key_bundle_id = ? WHERE capsule_id = ? AND owner_user_id = ?",
            arrayOf(OTHER_BUNDLE.toRestString(), CAPSULE.toRestString(), OWNER.toRestString()),
        )
        assertEquals(CapsuleUploadOutcome.RecipientKeyStale, orchestrator().run(OWNER, CAPSULE))
        assertEquals(RECIPIENT_KEY_STALE, capsuleRow().lastErrorCode)
    }

    @Test
    fun notFoundInactiveBundleAccountSwitchAndCancellationNeverFinalize() = runBlocking {
        assertEquals(
            CapsuleUploadOutcome.RecipientKeyStale,
            orchestrator(lookup = { _, _ -> RecipientUserLookupResult.NotFound }).run(OWNER, CAPSULE),
        )

        stageFreshStaleRow()
        assertEquals(
            CapsuleUploadOutcome.RecipientKeyStale,
            orchestrator(lookup = { _, _ ->
                RecipientUserLookupResult.Found(foundRecipient().snapshot.copy(keyBundleStatus = "RETIRED"))
            }).run(OWNER, CAPSULE),
        )

        stageFreshStaleRow()
        var liveOwner: String? = OWNER.toRestString()
        var finalizeCalls = 0
        val switched = orchestrator(
            currentOwner = { liveOwner },
            lookup = { _, _ -> liveOwner = OTHER_OWNER.toRestString(); foundRecipient() },
            finalize = { _, _ -> finalizeCalls += 1; readyResult() },
        ).run(OWNER, CAPSULE)
        assertEquals(CapsuleUploadOutcome.AccountMismatch, switched)
        assertEquals(0, finalizeCalls)
        assertEquals(RECIPIENT_KEY_STALE, capsuleRow().lastErrorCode)

        stageFreshStaleRow()
        var cancelled = false
        try {
            orchestrator(lookup = { _, _ -> throw CancellationException("cancelled") }).run(OWNER, CAPSULE)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(RECIPIENT_KEY_STALE, capsuleRow().lastErrorCode)
    }

    private fun orchestrator(
        capsuleDao: OutboxCapsuleDao = database.outboxCapsuleDao(),
        currentOwner: suspend () -> String? = { OWNER.toRestString() },
        lookup: suspend (UserId, String) -> RecipientUserLookupResult = { _, _ -> foundRecipient() },
        draftState: CapsuleDraftBlobState = CapsuleDraftBlobState.STORED,
        upload: suspend (CapsuleBlobUploadRequest, String) -> CapsuleBlobUploadResult = { _, _ ->
            CapsuleBlobUploadResult.Success(204)
        },
        finalize: suspend (CapsuleFinalizeRequest, String) -> CapsuleFinalizeResult = { request, _ ->
            readyResult()
        },
        read: suspend (String) -> ByteArray = { File(it).readBytes() },
        signingLoader: suspend (UserId, KeyBundleId) -> com.google.crypto.tink.KeysetHandle? =
            { _, _ -> senderIdentity.signingPrivateHandle },
    ): CapsuleUploadOrchestrator = CapsuleUploadOrchestrator(
        capsuleDao = capsuleDao,
        blobDao = database.outboxBlobDao(),
        currentAccountUserId = currentOwner,
        accessToken = { "access-token" },
        createDraft = { request, _ ->
            CapsuleDraftResult.Success(
                CapsuleDraft(
                    request.capsuleId,
                    CapsuleDraftState.DRAFT,
                    "2030-01-08T00:00:00Z",
                    request.blobs.map { CapsuleDraftBlob(it.blobId, draftState) },
                ),
                200,
            )
        },
            uploadBlob = upload,
        finalizeCapsule = finalize,
        cleanupRetryMaterial = { owner, capsule -> retryLifecycle.cleanupForTerminalState(owner, capsule) },
        readCiphertext = read,
        recipientUserLookup = lookup,
        retryMaterialStore = retryStore,
        senderRetryKeysetWrapper = retryWrapper,
        loadSenderSigningKeyset = signingLoader,
        accountScopedFileRoots = roots,
    )

    private fun foundRecipient(): RecipientUserLookupResult.Found =
        RecipientUserLookupResult.Found(
            ResolvedHandleSnapshot(
                userId = RECIPIENT,
                handle = NormalizedHandle.parse("recipient"),
                keyBundleId = NEW_RECIPIENT_BUNDLE,
                suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
                protocolVersion = 1,
                encryptionPublicKeysetB64Url = Base64.urlSafeEncode(newRecipientIdentity.encryptionPublicKeyset),
                signingPublicKeysetB64Url = Base64.urlSafeEncode(newRecipientIdentity.signingPublicKeyset),
                keyBundleStatus = "ACTIVE",
                directoryVersion = "directory-v1",
            ),
        )

    private suspend fun stageFreshStaleRow() {
        database.close()
        roots.child(OWNER, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT).deleteRecursively()
        roots.child(OWNER, AccountScopedFileRoots.ChildRoot.RETRY_MATERIAL).deleteRecursively()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        retryStore = SenderRetryMaterialStore(roots)
        retryLifecycle = SenderRetryMaterialLifecycle(retryStore, database.outboxCapsuleDao())
        CapsuleOutboxStager(database, roots, retryStore).stage(prepared)
        database.outboxCapsuleDao().markRetryableFailureForOwner(
            CAPSULE.toRestString(), OWNER.toRestString(), RECIPIENT_KEY_STALE,
        )
    }

    private suspend fun capsuleRow() = database.outboxCapsuleDao().getByCapsuleIdAndOwner(
        CAPSULE.toRestString(), OWNER.toRestString(),
    )!!

    private fun readyResult(): CapsuleFinalizeResult.Success =
        CapsuleFinalizeResult.Success(
            CapsuleFinalize(
                CAPSULE,
                CapsuleFinalizeState.READY,
                "2030-01-08T00:00:00Z",
            ),
            200,
        )

    private class CasLosingCapsuleDao(
        private val delegate: OutboxCapsuleDao,
        private val winnerRoot: File,
    ) : OutboxCapsuleDao() {
        lateinit var loserPaths: List<File>
        lateinit var winnerPaths: List<File>

        override suspend fun clearForOwner(ownerUserId: String) = delegate.clearForOwner(ownerUserId)

        override suspend fun getByCapsuleIdAndOwner(capsuleId: String, ownerUserId: String) =
            delegate.getByCapsuleIdAndOwner(capsuleId, ownerUserId)

        override suspend fun getCapsuleIdsNeedingUploadForOwner(ownerUserId: String) =
            delegate.getCapsuleIdsNeedingUploadForOwner(ownerUserId)

        override suspend fun markEncryptedForOwner(capsuleId: String, ownerUserId: String) =
            delegate.markEncryptedForOwner(capsuleId, ownerUserId)

        override suspend fun beginUploadForOwner(capsuleId: String, ownerUserId: String) =
            delegate.beginUploadForOwner(capsuleId, ownerUserId)

        override suspend fun applyRecipientKeyRewrapForOwner(
            capsuleId: String,
            ownerUserId: String,
            recipientUserId: String,
            expectedRecipientKeyBundleId: String,
            newRecipientKeyBundleId: String,
            newEnvelopePath: String,
            newPublishStatementPath: String,
            newPublishStatementSignaturePath: String,
        ): Int {
            val token = UUID.randomUUID().toString()
            val losers = listOf(
                File(newEnvelopePath),
                File(newPublishStatementPath),
                File(newPublishStatementSignaturePath),
            )
            winnerPaths = listOf(
                File(winnerRoot, "winner-envelope-$token.bin"),
                File(winnerRoot, "winner-statement-$token.bin"),
                File(winnerRoot, "winner-signature-$token.bin"),
            )
            losers.zip(winnerPaths).forEach { (loser, winner) -> loser.copyTo(winner) }
            check(
                delegate.applyRecipientKeyRewrapForOwner(
                    capsuleId,
                    ownerUserId,
                    recipientUserId,
                    expectedRecipientKeyBundleId,
                    newRecipientKeyBundleId,
                    winnerPaths[0].canonicalPath,
                    winnerPaths[1].canonicalPath,
                    winnerPaths[2].canonicalPath,
                ) == 1,
            )
            loserPaths = losers
            return 0
        }

        override suspend fun beginFinalizeForOwner(capsuleId: String, ownerUserId: String) =
            delegate.beginFinalizeForOwner(capsuleId, ownerUserId)

        override suspend fun markPublishedForOwner(capsuleId: String, ownerUserId: String) =
            delegate.markPublishedForOwner(capsuleId, ownerUserId)

        override suspend fun markRetryableFailureForOwner(
            capsuleId: String,
            ownerUserId: String,
            errorCode: String?,
        ) = delegate.markRetryableFailureForOwner(capsuleId, ownerUserId, errorCode)

        override suspend fun markTerminalFailureForOwner(
            capsuleId: String,
            ownerUserId: String,
            errorCode: String?,
        ) = delegate.markTerminalFailureForOwner(capsuleId, ownerUserId, errorCode)

        override suspend fun clearSenderRetryKeysetPath(
            capsuleId: String,
            ownerUserId: String,
            expectedPath: String?,
        ) = delegate.clearSenderRetryKeysetPath(capsuleId, ownerUserId, expectedPath)

        override suspend fun insertStrict(capsule: dev.hryshyn.remanence.core.data.db.OutboxCapsuleEntity) = Unit

        override suspend fun findOwnersOfImmutableIds(capsuleId: String, idempotencyKey: String): List<String> =
            emptyList()
    }

    private companion object {
        val OWNER = UserId.parseRest("a6000000-0000-4000-8000-000000000001")
        val RECIPIENT = UserId.parseRest("a6000000-0000-4000-8000-000000000002")
        val CAPSULE = CapsuleId.parseRest("a6000000-0000-4000-8000-000000000003")
        val SENDER_BUNDLE = KeyBundleId.parseRest("a6000000-0000-4000-8000-000000000004")
        val OLD_RECIPIENT_BUNDLE = KeyBundleId.parseRest("a6000000-0000-4000-8000-000000000005")
        val NEW_RECIPIENT_BUNDLE = KeyBundleId.parseRest("a6000000-0000-4000-8000-000000000006")
        val OTHER_BUNDLE = KeyBundleId.parseRest("a6000000-0000-4000-8000-000000000007")
        val OTHER_OWNER = UserId.parseRest("a6000000-0000-4000-8000-000000000008")
        const val RECIPIENT_KEY_STALE = "RECIPIENT_KEY_STALE"
    }
}
