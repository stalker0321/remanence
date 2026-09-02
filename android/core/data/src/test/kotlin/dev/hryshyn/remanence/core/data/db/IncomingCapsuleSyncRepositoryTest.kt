package dev.hryshyn.remanence.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import dev.hryshyn.remanence.core.data.network.ApiBaseUrl
import dev.hryshyn.remanence.core.data.network.IncomingCapsuleRepository
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.UserId
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingCapsuleSyncRepositoryTest {

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        database.close()
    }

    @Test
    fun successfulPageIsAtomicOwnerScopedAndPersistsTerminalHighWatermark() = runTest {
        server.enqueue(json(pageJson(nextCursor = TERMINAL_CURSOR)))
        val repository = repository()

        val result = repository.syncNextPage()

        val committed = assertIs<IncomingSyncResult.Committed>(result)
        assertFalse(committed.hasMore)
        assertEquals(TERMINAL_CURSOR, committed.page.nextCursor)
        val request = server.takeRequest()
        assertEquals("/v1/capsules/incoming", request.url.encodedPath)
        assertEquals("50", request.url.queryParameter("limit"))
        assertEquals("Bearer access-token", request.headers["Authorization"])
        assertEquals(1, countRows("incoming_capsule"))
        assertEquals(1, countRows("incoming_envelope"))
        assertEquals(5, countRows("blob_cache"))
        assertEquals(TERMINAL_CURSOR, database.syncCursorDao().get(OWNER, INCOMING_STREAM)!!.serverCursor)
        val storedBlob = database.blobCacheDao().getByBlobIdAndOwner(BLOB_IDS.first(), OWNER)!!
        assertEquals(BlobCacheState.DOWNLOADING, storedBlob.cacheState)
        assertEquals(
            AccountScopedFileRoots(ApplicationProvider.getApplicationContext<Context>().cacheDir)
                .incomingCiphertextPath(
                    owner = UserId.parseRest(OWNER),
                    capsule = CapsuleId.parseRest(CAPSULE_ID),
                    blob = BlobId.parseRest(BLOB_IDS.first()),
                ).toString(),
            storedBlob.localPath,
        )
        assertFalse(storedBlob.localPath.contains("email"))
    }

    @Test
    fun exactReplayPreservesDownloadedStatePathAndReceivedTimestamp() = runTest {
        server.enqueue(json(pageJson(nextCursor = "opaque-cursor")))
        server.enqueue(json(pageJson(nextCursor = "opaque-cursor")))
        var now = 1_000L
        val repository = repository(clock = { now })

        assertIs<IncomingSyncResult.Committed>(repository.syncNextPage())
        server.takeRequest()
        val before = database.blobCacheDao().getByBlobIdAndOwner(BLOB_IDS.first(), OWNER)!!
        assertEquals(BlobCacheState.DOWNLOADING, before.cacheState)
        assertEquals(1_000L, database.incomingEnvelopeDao().getByCapsuleIdAndOwner(CAPSULE_ID, OWNER)!!.receivedAtEpochMs)
        assertEquals(
            LocalMaterialState.DISCOVERED,
            database.incomingCapsuleDao().getByCapsuleIdAndOwner(CAPSULE_ID, OWNER)!!.materialState,
        )
        database.blobCacheDao().markCachedForOwner(BLOB_IDS.first(), OWNER)
        database.incomingCapsuleDao().transitionMaterialStateForOwner(
            OWNER,
            CAPSULE_ID,
            LocalMaterialState.INDEX_CACHED,
        )

        now = 2_000L
        val replay = assertIs<IncomingSyncResult.Committed>(repository.syncNextPage())

        assertEquals("opaque-cursor", replay.page.nextCursor)
        assertEquals(2, server.requestCount)
        assertEquals("opaque-cursor", server.takeRequest().url.queryParameter("cursor"))
        val afterBlob = database.blobCacheDao().getByBlobIdAndOwner(BLOB_IDS.first(), OWNER)!!
        assertEquals(BlobCacheState.CACHED, afterBlob.cacheState)
        assertEquals(before.localPath, afterBlob.localPath)
        assertEquals(
            1_000L,
            database.incomingEnvelopeDao().getByCapsuleIdAndOwner(CAPSULE_ID, OWNER)!!.receivedAtEpochMs,
        )
        assertEquals(
            LocalMaterialState.INDEX_CACHED,
            database.incomingCapsuleDao().getByCapsuleIdAndOwner(CAPSULE_ID, OWNER)!!.materialState,
        )
        assertEquals("opaque-cursor", database.syncCursorDao().get(OWNER, INCOMING_STREAM)!!.serverCursor)
    }

    @Test
    fun hasMorePageThenTerminalPageCommitsBothCursorsAndExposesLoopSignal() = runTest {
        server.enqueue(json(pageJson(nextCursor = "page-one", hasMore = true)))
        server.enqueue(
            json(
                pageJson(
                    capsuleId = SECOND_CAPSULE_ID,
                    nextCursor = TERMINAL_CURSOR,
                    items = listOf(itemJson(capsuleId = SECOND_CAPSULE_ID, blobIds = SECOND_BLOB_IDS)),
                ),
            ),
        )
        val repository = repository()

        val first = assertIs<IncomingSyncResult.Committed>(repository.syncNextPage())
        val second = assertIs<IncomingSyncResult.Committed>(repository.syncNextPage())

        assertTrue(first.hasMore)
        assertTrue(first.page.hasMore)
        assertEquals("page-one", first.page.nextCursor)
        assertFalse(second.hasMore)
        assertFalse(second.page.hasMore)
        assertEquals(TERMINAL_CURSOR, second.page.nextCursor)
        assertEquals(2, countRows("incoming_capsule"))
        assertEquals(TERMINAL_CURSOR, database.syncCursorDao().get(OWNER, INCOMING_STREAM)!!.serverCursor)
        assertEquals(null, server.takeRequest().url.queryParameter("cursor"))
        assertEquals("page-one", server.takeRequest().url.queryParameter("cursor"))
    }

    @Test
    fun initialEmptyPageRequiresNullCursorAndCommitsNoRows() = runTest {
        server.enqueue(json(pageJson(items = emptyList(), hasMore = false, nextCursor = null)))

        val result = assertIs<IncomingSyncResult.Committed>(repository().syncNextPage())

        assertFalse(result.hasMore)
        assertNull(result.page.nextCursor)
        assertNull(server.takeRequest().url.queryParameter("cursor"))
        assertEquals(0, countRows("incoming_capsule"))
        assertNull(database.syncCursorDao().get(OWNER, INCOMING_STREAM)!!.serverCursor)
    }

    @Test
    fun emptyContinuationEchoesDurableTerminalCursorOnLaterInvocation() = runTest {
        server.enqueue(json(pageJson(nextCursor = TERMINAL_CURSOR)))
        server.enqueue(json(pageJson(items = emptyList(), hasMore = false, nextCursor = TERMINAL_CURSOR)))
        val repository = repository()

        assertIs<IncomingSyncResult.Committed>(repository.syncNextPage())
        val empty = assertIs<IncomingSyncResult.Committed>(repository.syncNextPage())

        assertFalse(empty.hasMore)
        assertEquals(TERMINAL_CURSOR, empty.page.nextCursor)
        server.takeRequest()
        assertEquals(TERMINAL_CURSOR, server.takeRequest().url.queryParameter("cursor"))
        assertEquals(TERMINAL_CURSOR, database.syncCursorDao().get(OWNER, INCOMING_STREAM)!!.serverCursor)
        assertEquals(1, countRows("incoming_capsule"))
    }

    @Test
    fun emptyContinuationCursorMismatchRollsBackWithoutAdvancingCursor() = runTest {
        server.enqueue(json(pageJson(nextCursor = TERMINAL_CURSOR)))
        server.enqueue(json(pageJson(items = emptyList(), hasMore = false, nextCursor = "invented-cursor")))
        val repository = repository()

        assertIs<IncomingSyncResult.Committed>(repository.syncNextPage())
        val failure = assertIs<IncomingSyncResult.Failure>(repository.syncNextPage())

        assertEquals(IncomingSyncFailure.INVALID_RESPONSE, failure.reason)
        assertFalse(failure.retryable)
        server.takeRequest()
        assertEquals(TERMINAL_CURSOR, server.takeRequest().url.queryParameter("cursor"))
        assertEquals(TERMINAL_CURSOR, database.syncCursorDao().get(OWNER, INCOMING_STREAM)!!.serverCursor)
        assertEquals(1, countRows("incoming_capsule"))
    }

    @Test
    fun malformedPaginationCombinationsFailBeforeAnyPersistence() = runTest {
        val malformedPages = listOf(
            pageJson().replace("\"has_more\":false,", ""),
            pageJson().replace("\"has_more\":false", "\"has_more\":\"false\""),
            pageJson(nextCursor = null),
            pageJson(hasMore = true, nextCursor = null),
            pageJson(items = emptyList(), hasMore = true, nextCursor = null),
            pageJson(items = emptyList(), hasMore = false, nextCursor = "invented-cursor"),
        )

        for ((index, body) in malformedPages.withIndex()) {
            server.enqueue(json(body))
            val result = repository().syncNextPage()
            assertTrue(result is IncomingSyncResult.Failure, "malformed pagination case $index was accepted")
            val failure = result

            assertEquals(IncomingSyncFailure.INVALID_RESPONSE, failure.reason)
            assertFalse(failure.retryable)
            assertEquals(0, countRows("incoming_capsule"))
            assertNull(database.syncCursorDao().get(OWNER, INCOMING_STREAM))
        }
    }

    @Test
    fun sameOwnerTokenRotationDuringFetchStillCommitsPage() = runTest {
        val liveToken = AtomicReference(ACCESS_TOKEN)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("Bearer $ACCESS_TOKEN", request.headers["Authorization"])
                liveToken.set(ROTATED_ACCESS_TOKEN)
                return json(pageJson(nextCursor = "rotated-cursor"))
            }
        }
        val repository = repository {
            IncomingSyncSession(UserId.parseRest(OWNER), liveToken.get())
        }

        val result = assertIs<IncomingSyncResult.Committed>(repository.syncNextPage())

        assertEquals("rotated-cursor", result.page.nextCursor)
        assertEquals(1, countRows("incoming_capsule"))
        assertEquals("rotated-cursor", database.syncCursorDao().get(OWNER, INCOMING_STREAM)!!.serverCursor)
    }

    @Test
    fun lateExistingBlobConflictRollsBackNewPageAndCursor() = runTest {
        server.enqueue(json(pageJson(nextCursor = "cursor-one")))
        server.enqueue(
            json(
                pageJson(
                    capsuleId = SECOND_CAPSULE_ID,
                    nextCursor = "cursor-two",
                    blobDigests = listOf(ByteArray(32) { 99 }) + BLOB_DIGESTS.drop(1),
                ),
            ),
        )
        val repository = repository()

        assertIs<IncomingSyncResult.Committed>(repository.syncNextPage())
        val failed = assertIs<IncomingSyncResult.Failure>(repository.syncNextPage())

        assertEquals(IncomingSyncFailure.DATABASE_FAILURE, failed.reason)
        assertTrue(failed.retryable)
        assertEquals(1, countRows("incoming_capsule"))
        assertEquals(1, countRows("incoming_envelope"))
        assertEquals(5, countRows("blob_cache"))
        assertEquals("cursor-one", database.syncCursorDao().get(OWNER, INCOMING_STREAM)!!.serverCursor)
        assertNull(database.incomingCapsuleDao().getByCapsuleIdAndOwner(SECOND_CAPSULE_ID, OWNER))
    }

    @Test
    fun envelopeConflictRollsBackPageWithoutAdvancingCursor() = runTest {
        server.enqueue(json(pageJson(nextCursor = "cursor-one")))
        server.enqueue(
            json(
                pageJson(
                    nextCursor = "cursor-two",
                    envelopeCiphertext = byteArrayOf(4, 5, 6),
                ),
            ),
        )
        val repository = repository()

        assertIs<IncomingSyncResult.Committed>(repository.syncNextPage())
        val failed = assertIs<IncomingSyncResult.Failure>(repository.syncNextPage())

        assertEquals(IncomingSyncFailure.DATABASE_FAILURE, failed.reason)
        assertEquals(1, countRows("incoming_capsule"))
        assertEquals("cursor-one", database.syncCursorDao().get(OWNER, INCOMING_STREAM)!!.serverCursor)
    }

    @Test
    fun ownerMismatchIsRejectedBeforeAnyPersistence() = runTest {
        server.enqueue(json(pageJson(recipientId = OTHER_OWNER)))
        val result = repository().syncNextPage()

        val failure = assertIs<IncomingSyncResult.Failure>(result)
        assertEquals(IncomingSyncFailure.INVALID_RESPONSE, failure.reason)
        assertFalse(failure.retryable)
        assertEquals(0, countRows("incoming_capsule"))
        assertNull(database.syncCursorDao().get(OWNER, INCOMING_STREAM))
    }

    @Test
    fun responseOverRequestedLimitAndLocalLimitAreRejectedWithoutRequestOrWrites() = runTest {
        server.enqueue(json(pageJson(items = listOf(itemJson(), itemJson(capsuleId = SECOND_CAPSULE_ID)))))
        val overResponse = assertIs<IncomingSyncResult.Failure>(repository().syncNextPage(limit = 1))
        assertEquals(IncomingSyncFailure.INVALID_RESPONSE, overResponse.reason)
        assertEquals(0, countRows("incoming_capsule"))

        val local = repository().syncNextPage(limit = 101)
        assertEquals(IncomingSyncFailure.VALIDATION_FAILED, assertIs<IncomingSyncResult.Failure>(local).reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun sessionChangeAfterMappingDoesNotPersistPage() = runTest {
        server.enqueue(json(pageJson()))
        val first = IncomingSyncSession(UserId.parseRest(OWNER), ACCESS_TOKEN)
        val second = IncomingSyncSession(UserId.parseRest(OTHER_OWNER), "other-access-token")
        var calls = 0
        val repository = repository {
            calls++
            if (calls < 3) first else second
        }

        val failure = assertIs<IncomingSyncResult.Failure>(repository.syncNextPage())

        assertEquals(IncomingSyncFailure.ACCOUNT_CHANGED, failure.reason)
        assertEquals(3, calls)
        assertEquals(0, countRows("incoming_capsule"))
        assertEquals(0, countRows("incoming_envelope"))
        assertEquals(0, countRows("blob_cache"))
        assertNull(database.syncCursorDao().get(OWNER, INCOMING_STREAM))
    }

    @Test
    fun logoutAfterMappingDoesNotPersistPage() = runTest {
        server.enqueue(json(pageJson()))
        val active = IncomingSyncSession(UserId.parseRest(OWNER), ACCESS_TOKEN)
        var calls = 0
        val repository = repository {
            calls++
            if (calls < 3) active else null
        }

        val failure = assertIs<IncomingSyncResult.Failure>(repository.syncNextPage())

        assertEquals(IncomingSyncFailure.ACCOUNT_CHANGED, failure.reason)
        assertEquals(3, calls)
        assertEquals(0, countRows("incoming_capsule"))
        assertEquals(0, countRows("incoming_envelope"))
        assertEquals(0, countRows("blob_cache"))
        assertNull(database.syncCursorDao().get(OWNER, INCOMING_STREAM))
    }

    @Test
    fun cancellationAtFinalSessionProofLeavesDatabaseUntouched() = runTest {
        server.enqueue(json(pageJson()))
        var calls = 0
        val repository = repository {
            calls++
            if (calls == 3) throw CancellationException("test cancellation")
            IncomingSyncSession(UserId.parseRest(OWNER), ACCESS_TOKEN)
        }

        var cancelled = false
        try {
            repository.syncNextPage()
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(0, countRows("incoming_capsule"))
        assertNull(database.syncCursorDao().get(OWNER, INCOMING_STREAM))
    }

    @Test
    fun migratedBothEmptyCryptoCompletesOnceAndPreservesMaterialState() = runTest {
        database.incomingCapsuleDao().upsertAllForOwner(OWNER, listOf(legacyCapsule()))
        assertIs<LocalMaterialTransitionResult.Accepted>(
            database.incomingCapsuleDao().transitionMaterialStateForOwner(
                ownerUserId = OWNER,
                capsuleId = CAPSULE_ID,
                requestedTarget = LocalMaterialState.INDEX_CACHED,
            ),
        )
        server.enqueue(json(pageJson(nextCursor = "migration-complete")))

        val result = assertIs<IncomingSyncResult.Committed>(repository().syncNextPage())

        assertEquals("migration-complete", result.page.nextCursor)
        val stored = database.incomingCapsuleDao().getByCapsuleIdAndOwner(CAPSULE_ID, OWNER)!!
        assertEquals(sha256(SIGNED_STATEMENT).toList(), stored.signedStatementSha256.toList())
        assertEquals(ByteArray(69) { 7 }.toList(), stored.publishSignatureBytes.toList())
        assertEquals(LocalMaterialState.INDEX_CACHED, stored.materialState)
        assertEquals("migration-complete", database.syncCursorDao().get(OWNER, INCOMING_STREAM)!!.serverCursor)
    }

    @Test
    fun partialMigrationCryptoIsRejectedWithoutChangingRowsOrCursor() = runTest {
        val existing = legacyCapsule(signedStatementSha256 = sha256(SIGNED_STATEMENT))
        database.incomingCapsuleDao().upsertAllForOwner(OWNER, listOf(existing))
        server.enqueue(json(pageJson(nextCursor = "must-not-commit")))

        val failure = assertIs<IncomingSyncResult.Failure>(repository().syncNextPage())

        assertEquals(IncomingSyncFailure.DATABASE_FAILURE, failure.reason)
        val stored = database.incomingCapsuleDao().getByCapsuleIdAndOwner(CAPSULE_ID, OWNER)!!
        assertEquals(existing.signedStatementSha256.toList(), stored.signedStatementSha256.toList())
        assertTrue(stored.publishSignatureBytes.isEmpty())
        assertNull(database.syncCursorDao().get(OWNER, INCOMING_STREAM))
        assertEquals(1, countRows("incoming_capsule"))
        assertEquals(0, countRows("incoming_envelope"))
        assertEquals(0, countRows("blob_cache"))
    }

    @Test
    fun nonEmptyMigrationCryptoMismatchIsRejectedWithoutOverwriting() = runTest {
        val existing = legacyCapsule(
            signedStatementSha256 = ByteArray(32) { 9 },
            publishSignatureBytes = ByteArray(69) { 8 },
        )
        database.incomingCapsuleDao().upsertAllForOwner(OWNER, listOf(existing))
        server.enqueue(json(pageJson(nextCursor = "must-not-commit")))

        val failure = assertIs<IncomingSyncResult.Failure>(repository().syncNextPage())

        assertEquals(IncomingSyncFailure.DATABASE_FAILURE, failure.reason)
        val stored = database.incomingCapsuleDao().getByCapsuleIdAndOwner(CAPSULE_ID, OWNER)!!
        assertEquals(existing.signedStatementSha256.toList(), stored.signedStatementSha256.toList())
        assertEquals(existing.publishSignatureBytes.toList(), stored.publishSignatureBytes.toList())
        assertNull(database.syncCursorDao().get(OWNER, INCOMING_STREAM))
    }

    @Test
    fun legacyCompletionRollsBackOnLateConflictAndDoesNotAdvanceCursor() = runTest {
        database.incomingCapsuleDao().upsertAllForOwner(OWNER, listOf(legacyCapsule()))
        database.incomingEnvelopeDao().upsertForOwner(
            OWNER,
            IncomingEnvelopeEntity(
                capsuleId = SECOND_CAPSULE_ID,
                ownerUserId = OWNER,
                recipientKeyBundleId = RECIPIENT_BUNDLE_ID,
                hpkeCiphertext = byteArrayOf(1),
                transportSha256 = ByteArray(32) { 8 },
                receivedAtEpochMs = 2_000L,
            ),
        )
        server.enqueue(
            json(
                pageJson(
                    nextCursor = "must-not-commit",
                    items = listOf(
                        itemJson(),
                        itemJson(capsuleId = SECOND_CAPSULE_ID, blobIds = SECOND_BLOB_IDS),
                    ),
                ),
            ),
        )

        val failure = assertIs<IncomingSyncResult.Failure>(repository().syncNextPage())

        assertEquals(IncomingSyncFailure.DATABASE_FAILURE, failure.reason)
        val stored = database.incomingCapsuleDao().getByCapsuleIdAndOwner(CAPSULE_ID, OWNER)!!
        assertTrue(stored.signedStatementSha256.isEmpty())
        assertTrue(stored.publishSignatureBytes.isEmpty())
        assertNull(database.syncCursorDao().get(OWNER, INCOMING_STREAM))
        assertEquals(1, countRows("incoming_capsule"))
        assertEquals(1, countRows("incoming_envelope"))
        assertEquals(0, countRows("blob_cache"))
    }

    @Test
    fun strictMalformedResponseAndBindingMismatchFailAsTransportErrors() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body(pageJson().replace("\"has_more\":false,", "\"has_more\":false,\"email\":\"secret@example.com\","))
                .build(),
        )
        val extraField = assertIs<IncomingSyncResult.Failure>(repository().syncNextPage())
        assertEquals(IncomingSyncFailure.INVALID_RESPONSE, extraField.reason)
        assertFalse(extraField.toString().contains("secret@example.com"))

        server.enqueue(json(pageJson(envelopeKeyBundleId = OTHER_BUNDLE_ID)))
        val mismatch = assertIs<IncomingSyncResult.Failure>(repository().syncNextPage())
        assertEquals(IncomingSyncFailure.INVALID_RESPONSE, mismatch.reason)
        assertEquals(0, countRows("incoming_capsule"))
        assertNull(database.syncCursorDao().get(OWNER, INCOMING_STREAM))
    }

    @Test
    fun authenticatedProblemIsMappedWithoutRetainingResponseDetails() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .setHeader("Content-Type", "application/problem+json")
                .body(
                    """{"type":"https://remanence.invalid/problems/auth-invalid","title":"Authentication invalid","status":401,"code":"AUTH_INVALID","detail":"bearer-secret-detail","request_id":"0198f0a0-0000-7000-8000-00000000e001","retryable":false}""",
                )
                .build(),
        )

        val result = repository().syncNextPage()
        val failure = assertIs<IncomingSyncResult.Failure>(result)

        assertEquals(IncomingSyncFailure.AUTH_INVALID, failure.reason)
        assertFalse(failure.retryable)
        assertFalse(failure.toString().contains("bearer-secret-detail"))
        assertEquals("Bearer access-token", server.takeRequest().headers["Authorization"])
        assertEquals(0, countRows("incoming_capsule"))
    }

    @Test
    fun expectedOwnerMismatchStopsBeforeRequestOrPersistence() = runTest {
        val result = repository {
            IncomingSyncSession(UserId.parseRest(OTHER_OWNER), "other-access-token")
        }.syncNextPage(expectedOwner = UserId.parseRest(OWNER))
        val failure = assertIs<IncomingSyncResult.Failure>(result)

        assertEquals(IncomingSyncFailure.ACCOUNT_CHANGED, failure.reason)
        assertFalse(failure.retryable)
        assertEquals(0, server.requestCount)
        assertEquals(0, countRows("incoming_capsule"))
        assertNull(database.syncCursorDao().get(OWNER, INCOMING_STREAM))
    }

    private fun repository(
        clock: () -> Long = { 1_000L },
        session: suspend () -> IncomingSyncSession? = {
            IncomingSyncSession(UserId.parseRest(OWNER), ACCESS_TOKEN)
        },
    ): IncomingCapsuleSyncRepository = IncomingCapsuleSyncRepository(
        remote = IncomingCapsuleRepository(
            client = okhttp3.OkHttpClient(),
            baseUrl = ApiBaseUrl.parse(server.url("/").toString()),
        ),
        database = database,
        roots = AccountScopedFileRoots(ApplicationProvider.getApplicationContext<Context>().cacheDir),
        currentSession = session,
        clockEpochMs = clock,
    )

    private fun json(body: String): MockResponse = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun pageJson(
        capsuleId: String = CAPSULE_ID,
        recipientId: String = OWNER,
        envelopeCiphertext: ByteArray = ENVELOPE_CIPHERTEXT,
        envelopeKeyBundleId: String = RECIPIENT_BUNDLE_ID,
        hasMore: Boolean = false,
        nextCursor: String? = TERMINAL_CURSOR,
        blobDigests: List<ByteArray> = BLOB_DIGESTS,
        items: List<String> = listOf(
            itemJson(
                capsuleId = capsuleId,
                recipientId = recipientId,
                envelopeCiphertext = envelopeCiphertext,
                envelopeKeyBundleId = envelopeKeyBundleId,
                blobDigests = blobDigests,
            ),
        ),
    ): String = """
        {"items":[${items.joinToString(",")}],"has_more":$hasMore,"next_cursor":${nextCursor?.let { "\"$it\"" } ?: "null"}}
    """.trimIndent()

    private fun itemJson(
        capsuleId: String = CAPSULE_ID,
        recipientId: String = OWNER,
        envelopeCiphertext: ByteArray = ENVELOPE_CIPHERTEXT,
        envelopeKeyBundleId: String = RECIPIENT_BUNDLE_ID,
        blobDigests: List<ByteArray> = BLOB_DIGESTS,
        blobIds: List<String> = BLOB_IDS,
    ): String {
        val statement = byteArrayOf(1, 2, 3)
        val blobJson = blobIds.mapIndexed { index, blobId ->
            val (kind, ordinal) = when (index) {
                0 -> "RECOGNITION_MANIFEST" to "null"
                1 -> "CONTENT_MANIFEST" to "null"
                else -> "PHOTO" to (index - 2).toString()
            }
            """{"blob_id":"$blobId","kind":"$kind","ordinal":$ordinal,"ciphertext_size":10,"ciphertext_sha256":"${b64(blobDigests[index])}"}"""
        }.joinToString(",")
        return """
            {
              "capsule_id":"$capsuleId",
              "sender_user_id":"$SENDER_OWNER",
              "recipient_user_id":"$recipientId",
              "sender_key_bundle_id":"$SENDER_BUNDLE_ID",
              "recipient_key_bundle_id":"$RECIPIENT_BUNDLE_ID",
              "protocol_version":1,
              "ready_at":"2026-08-28T00:00:00Z",
              "signed_publish_statement":{"statement":"${b64(statement)}","statement_sha256":"${b64(sha256(statement))}","signature":"${b64(ByteArray(69) { 7 })}"},
              "recipient_envelope":{"recipient_key_bundle_id":"$envelopeKeyBundleId","ciphertext":"${b64(envelopeCiphertext)}","ciphertext_size":${envelopeCiphertext.size},"ciphertext_sha256":"${b64(sha256(envelopeCiphertext))}"},
              "blobs":[$blobJson]
            }
        """.trimIndent()
    }

    private fun b64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private fun legacyCapsule(
        signedStatementSha256: ByteArray = ByteArray(0),
        publishSignatureBytes: ByteArray = ByteArray(0),
        materialState: LocalMaterialState = LocalMaterialState.DISCOVERED,
    ) = IncomingCapsuleEntity(
        capsuleId = CAPSULE_ID,
        ownerUserId = OWNER,
        senderUserId = SENDER_OWNER,
        recipientUserId = OWNER,
        senderSigningKeyBundleId = SENDER_BUNDLE_ID,
        recipientEncryptionKeyBundleId = RECIPIENT_BUNDLE_ID,
        protocolVersion = 1,
        serverStatus = "READY",
        readyAtEpochMs = READY_AT_EPOCH_MS,
        signedStatementBytes = SIGNED_STATEMENT.copyOf(),
        signedStatementSha256 = signedStatementSha256.copyOf(),
        publishSignatureBytes = publishSignatureBytes.copyOf(),
        materialState = materialState,
    )

    private fun countRows(table: String): Int = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM $table")
        .use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val OWNER = "0198f0a0-0000-7000-8000-00000000a001"
        const val OTHER_OWNER = "0198f0a0-0000-7000-8000-00000000a002"
        const val SENDER_OWNER = "0198f0a0-0000-7000-8000-00000000a003"
        const val CAPSULE_ID = "0198f0a0-0000-7000-8000-00000000c001"
        const val SECOND_CAPSULE_ID = "0198f0a0-0000-7000-8000-00000000c002"
        const val RECIPIENT_BUNDLE_ID = "0198f0a0-0000-7000-8000-00000000b001"
        const val OTHER_BUNDLE_ID = "0198f0a0-0000-7000-8000-00000000b002"
        const val SENDER_BUNDLE_ID = "0198f0a0-0000-7000-8000-00000000b003"
        const val ACCESS_TOKEN = "access-token"
        const val ROTATED_ACCESS_TOKEN = "rotated-access-token"
        const val TERMINAL_CURSOR = "terminal-cursor"
        const val INCOMING_STREAM = "incoming"
        val READY_AT_EPOCH_MS = Instant.parse("2026-08-28T00:00:00Z").toEpochMilli()
        val SIGNED_STATEMENT = byteArrayOf(1, 2, 3)
        val ENVELOPE_CIPHERTEXT = byteArrayOf(9, 8, 7)
        val BLOB_IDS = listOf(
            "0198f0a0-0000-7000-8000-00000000d001",
            "0198f0a0-0000-7000-8000-00000000d002",
            "0198f0a0-0000-7000-8000-00000000d003",
            "0198f0a0-0000-7000-8000-00000000d004",
            "0198f0a0-0000-7000-8000-00000000d005",
        )
        val SECOND_BLOB_IDS = listOf(
            "0198f0a0-0000-7000-8000-00000000e001",
            "0198f0a0-0000-7000-8000-00000000e002",
            "0198f0a0-0000-7000-8000-00000000e003",
            "0198f0a0-0000-7000-8000-00000000e004",
            "0198f0a0-0000-7000-8000-00000000e005",
        )
        val BLOB_DIGESTS = BLOB_IDS.mapIndexed { index, _ -> ByteArray(32) { (index + 1).toByte() } }
    }
}
