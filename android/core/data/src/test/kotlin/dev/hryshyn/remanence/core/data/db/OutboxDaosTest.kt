package dev.hryshyn.remanence.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxDaosTest {

    private companion object {
        const val OWNER = "0198f0a0-0000-7000-8000-00000000ow01"
        const val OTHER_OWNER = "0198f0a0-0000-7000-8000-00000000ow02"
    }

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var capsuleDao: OutboxCapsuleDao
    private lateinit var blobDao: OutboxBlobDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        capsuleDao = database.outboxCapsuleDao()
        blobDao = database.outboxBlobDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun capsule(
        capsuleId: String = "0198f0a0-0000-7000-8000-00000000ca01",
        idempotencyKey: String = "0198f0a0-0000-7000-8000-00000000id01",
        state: OutboxCapsuleState = OutboxCapsuleState.PREPARING,
    ) = OutboxCapsuleEntity(
        capsuleId = capsuleId,
        idempotencyKey = idempotencyKey,
        ownerUserId = "0198f0a0-0000-7000-8000-00000000ow01",
        senderUserId = "0198f0a0-0000-7000-8000-00000000se01",
        recipientUserId = "0198f0a0-0000-7000-8000-00000000re01",
        senderKeyBundleId = "0198f0a0-0000-7000-8000-00000000sk01",
        recipientKeyBundleId = "0198f0a0-0000-7000-8000-00000000rk01",
        senderSigningPublicKeysetB64 = null,
        state = state,
        recognitionManifestPath = null,
        contentManifestPath = null,
        envelopePath = null,
        publishStatementPath = null,
        publishStatementSignaturePath = null,
        senderRetryKeysetPath = null,
        lastErrorCode = null,
    )

    private fun blob(blobId: String, kind: String, ordinal: Int?) = OutboxBlobEntity(
        blobId = blobId,
        ownerUserId = "0198f0a0-0000-7000-8000-00000000ow01",
        capsuleId = "0198f0a0-0000-7000-8000-00000000ca01",
        kind = kind,
        ordinal = ordinal,
        localCiphertextPath = "files/outbox/ca01/$blobId.bin",
        sizeBytes = 1_024,
        sha256 = ByteArray(32) { (it + 3).toByte() },
        uploadState = OutboxBlobUploadState.PENDING,
        attemptCount = 0,
    )

    private suspend fun insertCapsule(capsule: OutboxCapsuleEntity) {
        capsuleDao.insertOrAbort(capsule.ownerUserId, capsule)
    }

    private suspend fun insertBlobs(blobs: List<OutboxBlobEntity>) {
        blobDao.upsertAll(blobs.first().ownerUserId, blobs)
    }

    @Test
    fun upsertThenReadReturnsOutboxRecord() = runBlocking {
        val record = capsule(state = OutboxCapsuleState.ENCRYPTED)
        insertCapsule(record)
        assertEquals(OutboxCapsuleState.ENCRYPTED, capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.state)
    }

    @Test
    fun authoritativeOwnerMismatchIsRejectedBeforeAnyOutboxWrite() = runBlocking {
        val candidate = capsule().copy(ownerUserId = OTHER_OWNER)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { capsuleDao.insertOrAbort(OWNER, candidate) }
        }
        assertEquals(0, countOutboxRows())
    }

    /**
     * M2 review fix: strict insert means ANY collision - duplicate
     * idempotency key or capsule id - is a hard refusal, never converted
     * into an update of the existing row.
     */
    @Test
    fun collidingCapsuleRowInsertIsRefusedNotConvertedIntoAnUpdate(): Unit = runBlocking {
        val original = capsule(capsuleId = "capsule-a")
        insertCapsule(original)

        // Same idempotency key under another capsule ID: refused outright.
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                insertCapsule(
                    capsule(capsuleId = "capsule-b", idempotencyKey = "0198f0a0-0000-7000-8000-00000000id01"),
                )
            }
        }
        assertEquals(1, countOutboxRows())
        assertEquals(original, capsuleDao.getByCapsuleIdAndOwner("capsule-a", OWNER))

        // Same owner re-inserting its own capsule ID is equally refused.
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { insertCapsule(capsule()) }
        }
    }

    /**
     * M2 review regression: A cannot overwrite B by reusing B's capsule_id.
     * The strict insert aborts on the foreign-owned row and leaves every
     * byte of B's durable material unchanged.
     */
    @Test
    fun foreignOwnerCollidingCapsuleIdCannotOverwriteTheOriginalRow(): Unit = runBlocking {
        val sharedCapsuleId = "0198f0a0-0000-7000-8000-00000000ca77"
        val originalB = capsule(capsuleId = sharedCapsuleId).copy(ownerUserId = OTHER_OWNER)
        insertCapsule(originalB)

        val aAttemptsToReuseBCapsuleId =
            capsule(capsuleId = sharedCapsuleId).copy(ownerUserId = OWNER)
        val constraintViolation = assertThrows(IllegalStateException::class.java) {
            runBlocking { insertCapsule(aAttemptsToReuseBCapsuleId) }
        }
        assertTrue(constraintViolation.message!!.contains("another local account"))

        // B's logical row is exactly as committed before A's attempt.
        val loaded = capsuleDao.getByCapsuleIdAndOwner(sharedCapsuleId, OTHER_OWNER)!!
        assertEquals(OTHER_OWNER, loaded.ownerUserId)
        assertEquals(originalB.state, loaded.state)
        assertEquals(1, countOutboxRows())
        // And A can observe nothing under that id either way.
        assertNull(capsuleDao.getByCapsuleIdAndOwner(sharedCapsuleId, OWNER))
    }

    private fun countOutboxRows(): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox_capsule").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    @Test
    fun capsuleNamedTransitionsFollowTheCanonicalLifecycle() = runBlocking {
        val record = capsule()
        insertCapsule(record)

        assertEquals(1, capsuleDao.markEncryptedForOwner(record.capsuleId, OWNER))
        assertEquals(0, capsuleDao.markEncryptedForOwner(record.capsuleId, OWNER))
        assertEquals(1, capsuleDao.beginUploadForOwner(record.capsuleId, OWNER))
        assertEquals(1, capsuleDao.markRetryableFailureForOwner(record.capsuleId, OWNER, "BLOB_HASH_MISMATCH"))
        assertEquals(1, capsuleDao.beginUploadForOwner(record.capsuleId, OWNER))
        assertEquals(1, capsuleDao.beginFinalizeForOwner(record.capsuleId, OWNER))
        assertEquals(1, capsuleDao.markPublishedForOwner(record.capsuleId, OWNER))
        assertEquals(0, capsuleDao.markPublishedForOwner(record.capsuleId, OWNER))
        val loaded = capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!
        assertEquals(OutboxCapsuleState.PUBLISHED, loaded.state)
        assertEquals("BLOB_HASH_MISMATCH", loaded.lastErrorCode)
    }

    @Test
    fun everyNamedTransitionAcceptsOnlyItsCanonicalSourceStates() = runBlocking {
        OutboxCapsuleState.values().forEach { source ->
            val encrypted = capsule(
                capsuleId = "matrix-encrypted-${source.name}",
                idempotencyKey = "matrix-encrypted-idem-${source.name}",
                state = source,
            )
            val upload = capsule(
                capsuleId = "matrix-upload-${source.name}",
                idempotencyKey = "matrix-upload-idem-${source.name}",
                state = source,
            )
            val finalize = capsule(
                capsuleId = "matrix-finalize-${source.name}",
                idempotencyKey = "matrix-finalize-idem-${source.name}",
                state = source,
            )
            val published = capsule(
                capsuleId = "matrix-published-${source.name}",
                idempotencyKey = "matrix-published-idem-${source.name}",
                state = source,
            )
            val retryable = capsule(
                capsuleId = "matrix-retryable-${source.name}",
                idempotencyKey = "matrix-retryable-idem-${source.name}",
                state = source,
            )
            val terminal = capsule(
                capsuleId = "matrix-terminal-${source.name}",
                idempotencyKey = "matrix-terminal-idem-${source.name}",
                state = source,
            )
            for (row in listOf(encrypted, upload, finalize, published, retryable, terminal)) {
                insertCapsule(row)
            }

        assertEquals(
                if (source == OutboxCapsuleState.PREPARING) 1 else 0,
                capsuleDao.markEncryptedForOwner(encrypted.capsuleId, OWNER),
            )
            assertEquals(
                if (source == OutboxCapsuleState.ENCRYPTED || source == OutboxCapsuleState.RETRYABLE_FAILURE) 1 else 0,
                capsuleDao.beginUploadForOwner(upload.capsuleId, OWNER),
            )
            assertEquals(
                if (source == OutboxCapsuleState.UPLOADING) 1 else 0,
                capsuleDao.beginFinalizeForOwner(finalize.capsuleId, OWNER),
            )
            assertEquals(
                if (source == OutboxCapsuleState.FINALIZING) 1 else 0,
                capsuleDao.markPublishedForOwner(published.capsuleId, OWNER),
            )
            assertEquals(
                if (source == OutboxCapsuleState.ENCRYPTED ||
                    source == OutboxCapsuleState.UPLOADING ||
                    source == OutboxCapsuleState.FINALIZING
                ) 1 else 0,
                capsuleDao.markRetryableFailureForOwner(retryable.capsuleId, OWNER, "retry-${source.name}"),
            )
            assertEquals(
                if (source != OutboxCapsuleState.PUBLISHED && source != OutboxCapsuleState.TERMINAL_FAILURE) 1 else 0,
                capsuleDao.markTerminalFailureForOwner(terminal.capsuleId, OWNER, "terminal-${source.name}"),
            )

            val retryableAfter = capsuleDao.getByCapsuleIdAndOwner(retryable.capsuleId, OWNER)!!
            assertEquals(source == OutboxCapsuleState.ENCRYPTED ||
                source == OutboxCapsuleState.UPLOADING ||
                source == OutboxCapsuleState.FINALIZING, retryableAfter.lastErrorCode != null)
            if (retryableAfter.lastErrorCode != null) {
                assertEquals("retry-${source.name}", retryableAfter.lastErrorCode)
            }
            val terminalAfter = capsuleDao.getByCapsuleIdAndOwner(terminal.capsuleId, OWNER)!!
            assertEquals(
                if (source != OutboxCapsuleState.PUBLISHED && source != OutboxCapsuleState.TERMINAL_FAILURE) {
                    "terminal-${source.name}"
                } else {
                    null
                },
                terminalAfter.lastErrorCode,
            )
        }
    }

    @Test
    fun publishedTerminalStateCannotReturnToUploading() = runBlocking {
        val record = capsule()
        insertCapsule(record)
        capsuleDao.markEncryptedForOwner(record.capsuleId, OWNER)
        capsuleDao.beginUploadForOwner(record.capsuleId, OWNER)
        capsuleDao.beginFinalizeForOwner(record.capsuleId, OWNER)
        assertEquals(1, capsuleDao.markPublishedForOwner(record.capsuleId, OWNER))
        assertEquals(0, capsuleDao.beginUploadForOwner(record.capsuleId, OWNER))
        assertEquals(0, capsuleDao.markTerminalFailureForOwner(record.capsuleId, OWNER, "late"))
    }

    @Test
    fun blobCardinalityQueriesExposeArtifactCounts() = runBlocking {
        insertBlobs(
            listOf(
                blob("blob-rec", "RECOGNITION_MANIFEST", null),
                blob("blob-con", "CONTENT_MANIFEST", null),
                blob("blob-p0", "PHOTO", 0),
                blob("blob-p1", "PHOTO", 1),
                blob("blob-p2", "PHOTO", 2),
            ),
        )
        assertEquals(1, blobDao.countByKindAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER, "RECOGNITION_MANIFEST"))
        assertEquals(1, blobDao.countByKindAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER, "CONTENT_MANIFEST"))
        assertEquals(3, blobDao.countByKindAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER, "PHOTO"))
        assertEquals(5, blobDao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER).size)

        insertBlobs(listOf(blob("blob-p3", "PHOTO", 3), blob("blob-p4", "PHOTO", 4)))
        assertEquals(5, blobDao.countByKindAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER, "PHOTO"))

        // M2-P02: an occupied (kind, ordinal) slot is a HARD conflict - blob
        // inserts abort instead of upserting, so a colliding write can never
        // be converted into an update that silently reassigns another
        // account's row onto this relationship.
        val ordinalZeroBefore = blobDao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER)
            .single { it.ordinal == 0 }
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking {
                insertBlobs(listOf(blob("blob-dup-ordinal", "PHOTO", 0)))
            }
        }
        assertEquals(5, blobDao.countByKindAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER, "PHOTO"))
        assertEquals(
            ordinalZeroBefore.blobId,
            blobDao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER)
                .single { it.ordinal == 0 }
                .blobId,
        )
        val ordinals = blobDao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER)
            .filter { it.kind == "PHOTO" }
            .map { it.ordinal }
            .filterNotNull()
            .sorted()
        assertEquals(listOf(0, 1, 2, 3, 4), ordinals)
    }

    @Test
    fun blobBatchPreflightLeavesEarlierRowsUninsertedOnForeignIdCollision() = runBlocking {
        val foreign = blob("blob-foreign", "PHOTO", 99).copy(
            ownerUserId = OTHER_OWNER,
            capsuleId = "0198f0a0-0000-7000-8000-00000000ca99",
        )
        blobDao.upsertAll(OTHER_OWNER, listOf(foreign))

        val newForOwner = blob("blob-new", "PHOTO", 10)
        val attemptedForeignReuse = foreign.copy(ownerUserId = OWNER)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { blobDao.upsertAll(OWNER, listOf(newForOwner, attemptedForeignReuse)) }
        }

        assertTrue(blobDao.getAllByCapsuleIdAndOwner(newForOwner.capsuleId, OWNER).isEmpty())
        assertEquals(
            foreign,
            blobDao.getAllByCapsuleIdAndOwner(foreign.capsuleId, OTHER_OWNER).single(),
        )
    }

    @Test
    fun blobBatchOwnerMismatchLeavesEarlierRowsUninserted() = runBlocking {
        val first = blob("blob-first", "PHOTO", 10)
        val mismatched = blob("blob-mismatch", "PHOTO", 11).copy(ownerUserId = OTHER_OWNER)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { blobDao.upsertAll(OWNER, listOf(first, mismatched)) }
        }
        assertTrue(blobDao.getAllByCapsuleIdAndOwner(first.capsuleId, OWNER).isEmpty())
    }

    @Test
    fun outboxCleanupIsOwnerScoped() = runBlocking {
        val ownerCapsule = capsule(capsuleId = "capsule-owner")
        val otherCapsule = capsule(
            capsuleId = "capsule-other",
            idempotencyKey = "0198f0a0-0000-7000-8000-00000000id02",
        ).copy(ownerUserId = OTHER_OWNER)
        insertCapsule(ownerCapsule)
        insertCapsule(otherCapsule)
        insertBlobs(listOf(blob("blob-owner", "PHOTO", 0)))
        blobDao.upsertAll(OTHER_OWNER, listOf(blob("blob-other", "PHOTO", 0).copy(
            capsuleId = otherCapsule.capsuleId,
            ownerUserId = OTHER_OWNER,
        )))

        capsuleDao.clearForOwner(OWNER)
        blobDao.clearForOwner(OWNER)

        assertNull(capsuleDao.getByCapsuleIdAndOwner(ownerCapsule.capsuleId, OWNER))
        assertNotNull(capsuleDao.getByCapsuleIdAndOwner(otherCapsule.capsuleId, OTHER_OWNER))
        assertEquals(1, countOutboxRows())
        assertEquals(1, blobDao.getAllByCapsuleIdAndOwner(otherCapsule.capsuleId, OTHER_OWNER).size)
    }

    @Test
    fun blobUploadTransitionAndAttemptCounter() = runBlocking {
        val record = blob("blob-up", "PHOTO", 4)
        insertBlobs(listOf(record))

        assertEquals(0, blobDao.incrementAttemptCountForOwner("missing-blob", OWNER))
        assertEquals(1, blobDao.incrementAttemptCountForOwner("blob-up", OWNER))
        assertEquals(1, blobDao.incrementAttemptCountForOwner("blob-up", OWNER))

        assertEquals(0, blobDao.markStoredForOwner("missing-blob", OWNER))
        assertEquals(1, blobDao.markStoredForOwner("blob-up", OWNER))
        assertEquals(0, blobDao.markStoredForOwner("blob-up", OWNER))

        val stored = blobDao.getAllByCapsuleIdAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER).single { it.blobId == "blob-up" }
        assertEquals(OutboxBlobUploadState.STORED, stored.uploadState)
        assertEquals(2, stored.attemptCount)
    }

    @Test
    fun deleteByCapsuleRemovesOnlyThatCapsuleBlobs() = runBlocking {
        insertCapsule(capsule())
        val otherCapsule = capsule(
            capsuleId = "0198f0a0-0000-7000-8000-00000000ca02",
            idempotencyKey = "0198f0a0-0000-7000-8000-00000000id02",
        )
        insertCapsule(otherCapsule)
        insertBlobs(
            listOf(
                blob("blob-a", "PHOTO", 0).copy(capsuleId = otherCapsule.capsuleId),
                blob("blob-b", "PHOTO", 1),
            ),
        )
        blobDao.deleteByCapsuleIdAndOwner(otherCapsule.capsuleId, OWNER)
        assertNull(blobDao.getAllByCapsuleIdAndOwner(otherCapsule.capsuleId, OWNER).firstOrNull())
        assertEquals(1, blobDao.countByKindAndOwner("0198f0a0-0000-7000-8000-00000000ca01", OWNER, "PHOTO"))
    }

    /**
     * M2-P03: the ONLY account-owned surface left is owner-required, so
     * another local account can neither observe nor mutate this account's
     * durable outbox material.
     */
    @Test
    fun wrongOwnerSeesNothingAndMutatesNothing() = runBlocking {
        val record = capsule()
        insertCapsule(record)
        insertBlobs(listOf(blob("blob-up", "PHOTO", 4)))

        assertNull(capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OTHER_OWNER))
        assertTrue(blobDao.getAllByCapsuleIdAndOwner(record.capsuleId, OTHER_OWNER).isEmpty())
        assertEquals(0, blobDao.countByKindAndOwner(record.capsuleId, OTHER_OWNER, "PHOTO"))

        assertEquals(0, capsuleDao.markPublishedForOwner(record.capsuleId, OTHER_OWNER))
        assertEquals(0, capsuleDao.markTerminalFailureForOwner(record.capsuleId, OTHER_OWNER, "X"))
        assertEquals(0, blobDao.markStoredForOwner("blob-up", OTHER_OWNER))
        assertEquals(0, blobDao.incrementAttemptCountForOwner("blob-up", OTHER_OWNER))
        assertEquals(0, blobDao.deleteByCapsuleIdAndOwner(record.capsuleId, OTHER_OWNER))

        // The owner's material is untouched and still fully resolvable.
        assertNotNull(capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!)
        assertEquals(OutboxCapsuleState.PREPARING, capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!.state)
    }

    @Test
    fun uploadDiscoveryReturnsOnlyReplayableOwnerRowsInStableOrder() = runBlocking {
        val retryPointer = "files/outbox/retry-material.bin"
        val rows = listOf(
            capsule("resume-07", "resume-idem-07", OutboxCapsuleState.PUBLISHED)
                .copy(senderRetryKeysetPath = retryPointer),
            capsule("resume-03", "resume-idem-03", OutboxCapsuleState.ENCRYPTED),
            capsule("resume-05", "resume-idem-05", OutboxCapsuleState.RETRYABLE_FAILURE),
            capsule("resume-01", "resume-idem-01", OutboxCapsuleState.UPLOADING),
            capsule("resume-06", "resume-idem-06", OutboxCapsuleState.TERMINAL_FAILURE)
                .copy(senderRetryKeysetPath = retryPointer),
            capsule("resume-02", "resume-idem-02", OutboxCapsuleState.FINALIZING),
            capsule("resume-04", "resume-idem-04", OutboxCapsuleState.RETRYABLE_FAILURE)
                .copy(lastErrorCode = "NETWORK"),
            capsule("resume-08", "resume-idem-08", OutboxCapsuleState.PREPARING),
            capsule("resume-09", "resume-idem-09", OutboxCapsuleState.RETRYABLE_FAILURE)
                .copy(lastErrorCode = "RECIPIENT_KEY_STALE"),
            capsule("resume-12", "resume-idem-12", OutboxCapsuleState.RETRYABLE_FAILURE)
                .copy(lastErrorCode = "RECIPIENT_KEY_STALE_DRAFT"),
            capsule("resume-13", "resume-idem-13", OutboxCapsuleState.FINALIZING)
                .copy(lastErrorCode = "RECIPIENT_KEY_STALE_FINALIZE"),
            capsule("resume-10", "resume-idem-10", OutboxCapsuleState.PUBLISHED),
            capsule("resume-11", "resume-idem-11", OutboxCapsuleState.TERMINAL_FAILURE),
            capsule("resume-00", "resume-idem-00", OutboxCapsuleState.ENCRYPTED)
                .copy(ownerUserId = OTHER_OWNER),
        )
        rows.forEach { insertCapsule(it) }

            assertEquals(
            listOf(
                "resume-01", "resume-02", "resume-03", "resume-04", "resume-05", "resume-06", "resume-07",
                "resume-12", "resume-13",
            ),
            capsuleDao.getCapsuleIdsNeedingUploadForOwner(OWNER),
        )
        assertEquals(
            listOf("resume-00"),
            capsuleDao.getCapsuleIdsNeedingUploadForOwner(OTHER_OWNER),
        )
    }

    /**
     * M2-P08 schema-only continuation: the new
     * `sender_retry_keyset_path` column is NULL by default. A row
     * inserted without an explicit pointer reads back with NULL, and
     * the value is observable only through the owner-scoped DAO
     * lookup, never through an unscoped query.
     */
    @Test
    fun senderRetryKeysetPathDefaultsToNullAndRoundTripsThroughOwnerScopedLookup(): Unit = runBlocking {
        // Default-null: a row inserted without an explicit pointer
        // reads back with NULL.
        val defaulted = capsule()
        insertCapsule(defaulted)
        val loadedDefault = capsuleDao.getByCapsuleIdAndOwner(defaulted.capsuleId, OWNER)!!
        assertNull(
            "the new column MUST default to NULL on insert",
            loadedDefault.senderRetryKeysetPath,
        )

        // Non-null: a row inserted with a pointer round-trips
        // through the owner-scoped DAO lookup.
        val pointerPath =
            "files/accounts/$OWNER/retry-material/${defaulted.capsuleId}.bin"
        val withPointer = capsule(
            capsuleId = "0198f0a0-0000-7000-8000-00000000ca03",
            idempotencyKey = "0198f0a0-0000-7000-8000-00000000id03",
        ).copy(senderRetryKeysetPath = pointerPath)
        insertCapsule(withPointer)
        val loadedPointer = capsuleDao.getByCapsuleIdAndOwner(withPointer.capsuleId, OWNER)!!
        assertEquals(pointerPath, loadedPointer.senderRetryKeysetPath)

        // Wrong owner: the pointer is hidden behind the owner-scoped
        // DAO contract. A second account can neither observe this
        // row nor see the pointer value.
        assertNull(capsuleDao.getByCapsuleIdAndOwner(withPointer.capsuleId, OTHER_OWNER))
        assertNull(capsuleDao.getByCapsuleIdAndOwner(defaulted.capsuleId, OTHER_OWNER))
    }

    /**
     * M2-P08 schema-only continuation: an inserted pointer is opaque
     * at the entity layer. The value is a string typed by the
     * column, and the only invariant checked here is that exactly
     * what was written is what the owner-scoped lookup reads back -
     * never keyset bytes, never a handle, never an email, because
     * the lifecycle (out of scope) is the only writer of the
     * column and the column carries a path only.
     */
    @Test
    fun senderRetryKeysetPathIsRoundTrippedAsAString(): Unit = runBlocking {
        val raw =
            "files/accounts/$OWNER/retry-material/cap-pointer.bin"
        val record = capsule(
            capsuleId = "0198f0a0-0000-7000-8000-00000000ca04",
            idempotencyKey = "0198f0a0-0000-7000-8000-00000000id04",
        ).copy(senderRetryKeysetPath = raw)
        insertCapsule(record)
        val loaded = capsuleDao.getByCapsuleIdAndOwner(record.capsuleId, OWNER)!!
        assertEquals(raw, loaded.senderRetryKeysetPath)
        // The string is opaque to the entity; the only structural
        // contract is owner-scoping.
        assertEquals(OWNER, loaded.ownerUserId)
    }
}
