package dev.hryshyn.remanence.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.RecipientTarget
import dev.hryshyn.remanence.core.model.UserId
import java.util.UUID

class CapsuleDraftRepositoryTest {

    private val capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca01")
    private val senderBundleId = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000ba01")
    private val recipientId = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a502")
    private val recipientBundleId = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000ba02")
    private val idempotencyKey = UUID.fromString("0198f0a0-0000-7000-8000-00000000ad01")

    private fun request(): CapsuleDraftRequest = CapsuleDraftRequest(
        capsuleId = capsuleId,
        senderKeyBundleId = senderBundleId,
        recipientTarget = RecipientTarget.ExistingUser(recipientId, recipientBundleId),
        idempotencyKey = idempotencyKey,
        blobs = listOf(
            blob("0198f0a0-0000-7000-8000-00000000b001", CapsuleArtifactKind.RECOGNITION_MANIFEST, null, 11),
            blob("0198f0a0-0000-7000-8000-00000000b002", CapsuleArtifactKind.CONTENT_MANIFEST, null, 12),
            blob("0198f0a0-0000-7000-8000-00000000b003", CapsuleArtifactKind.PHOTO, 0, 13),
            blob("0198f0a0-0000-7000-8000-00000000b004", CapsuleArtifactKind.PHOTO, 1, 14),
            blob("0198f0a0-0000-7000-8000-00000000b005", CapsuleArtifactKind.PHOTO, 2, 15),
        ),
    )

    private fun blob(
        id: String,
        kind: CapsuleArtifactKind,
        ordinal: Int?,
        size: Long,
    ): CapsuleDraftBlobDeclaration = CapsuleDraftBlobDeclaration(
        blobId = BlobId.parseRest(id),
        kind = kind,
        ordinal = ordinal,
        ciphertextSize = size,
        ciphertextSha256 = ByteArray(32) { it.toByte() },
    )

    private fun successJson(states: List<String> = List(5) { "DECLARED" }): String =
        """
        {
          "capsule_id": "${capsuleId.toRestString()}",
          "state": "DRAFT",
          "draft_expires_at": "2026-08-30T03:00:00+00:00",
          "blobs": [
            {"blob_id": "0198f0a0-0000-7000-8000-00000000b001", "state": "${states[0]}"},
            {"blob_id": "0198f0a0-0000-7000-8000-00000000b002", "state": "${states[1]}"},
            {"blob_id": "0198f0a0-0000-7000-8000-00000000b003", "state": "${states[2]}"},
            {"blob_id": "0198f0a0-0000-7000-8000-00000000b004", "state": "${states[3]}"},
            {"blob_id": "0198f0a0-0000-7000-8000-00000000b005", "state": "${states[4]}"}
          ]
        }
        """.trimIndent()

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun repository(server: MockWebServer): CapsuleDraftRepository =
        CapsuleDraftRepository.create(ApiBaseUrl.parse(server.url("/").toString()))

    @Test
    fun successPostsExactExistingUserDraftContractAndMapsResponse() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(201)
                    .setHeader("Content-Type", "application/json")
                    .body(successJson())
                    .build(),
            )

            val result = repository(server).createDraft(request(), "pm_at_live")
            val success = assertIs<CapsuleDraftResult.Success>(result)
            assertEquals(201, success.httpStatus)
            assertEquals(capsuleId, success.draft.capsuleId)
            assertEquals(CapsuleDraftState.DRAFT, success.draft.state)
            assertEquals(5, success.draft.blobs.size)
            assertEquals(CapsuleDraftBlobState.DECLARED, success.draft.blobs.first().state)

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/v1/capsules", recorded.url.encodedPath)
            assertEquals("Bearer pm_at_live", recorded.headers["Authorization"])
            assertEquals(idempotencyKey.toString(), recorded.headers["Idempotency-Key"])
            assertTrue(recorded.headers["Content-Type"]!!.startsWith("application/json"))

            val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
            assertEquals(
                setOf(
                    "capsule_id",
                    "recipient_user_id",
                    "sender_key_bundle_id",
                    "recipient_key_bundle_id",
                    "protocol_version",
                    "blobs",
                ),
                body.keys,
            )
            assertEquals(recipientId.toRestString(), body["recipient_user_id"]!!.toString().trim('"'))
            assertEquals(senderBundleId.toRestString(), body["sender_key_bundle_id"]!!.toString().trim('"'))
            assertEquals(recipientBundleId.toRestString(), body["recipient_key_bundle_id"]!!.toString().trim('"'))
            assertTrue("sender_user_id" !in body)
            assertTrue("handle" !in body)
            assertTrue("email" !in body)
            val blobs = body["blobs"]!!.jsonArray
            assertEquals("null", blobs[0].jsonObject["ordinal"].toString())
            assertEquals("0", blobs[2].jsonObject["ordinal"].toString())
            assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", blobs[0].jsonObject["ciphertext_sha256"]!!.toString().trim('"'))
        }
    }

    @Test
    fun replayMapsStoredBlobStatesAndAccepts200() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(successJson(listOf("STORED", "STORED", "DECLARED", "STORED", "DECLARED")))
                    .build(),
            )
            val result = repository(server).createDraft(request(), "pm_at_live")
            val success = assertIs<CapsuleDraftResult.Success>(result)
            assertEquals(
                listOf(
                    CapsuleDraftBlobState.STORED,
                    CapsuleDraftBlobState.STORED,
                    CapsuleDraftBlobState.DECLARED,
                    CapsuleDraftBlobState.STORED,
                    CapsuleDraftBlobState.DECLARED,
                ),
                success.draft.blobs.map { it.state },
            )
        }
    }

    @Test
    fun stableProblemCodesMapToStructuredFailures() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(409)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(
                        """
                        {"type":"https://remanence.invalid/problems/idempotency-conflict","title":"Idempotency conflict","status":409,"code":"IDEMPOTENCY_CONFLICT","detail":"The idempotency key conflicts with an existing request.","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":false}
                        """.trimIndent(),
                    )
                    .build(),
            )
            val failure = assertIs<CapsuleDraftResult.Failure>(repository(server).createDraft(request(), "pm_at_live"))
            assertEquals(CapsuleDraftFailure.IDEMPOTENCY_CONFLICT, failure.reason)
            assertEquals(409, failure.httpStatus)
        }
    }

    @Test
    fun malformedSuccessAndUnknownProblemCodeFailClosedWithoutDetails() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder().code(201).setHeader("Content-Type", "application/json").body("not-json").build(),
            )
            assertEquals(
                CapsuleDraftFailure.INVALID_RESPONSE,
                assertIs<CapsuleDraftResult.Failure>(repository(server).createDraft(request(), "pm_at_live")).reason,
            )

            server.enqueue(
                MockResponse.Builder()
                    .code(500)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(
                        """
                        {"type":"x","title":"x","status":500,"code":"NOT_A_PUBLIC_CODE","detail":"private detail","request_id":"x","retryable":false}
                        """.trimIndent(),
                    )
                    .build(),
            )
            val failure = assertIs<CapsuleDraftResult.Failure>(repository(server).createDraft(request(), "pm_at_live"))
            assertEquals(CapsuleDraftFailure.HTTP, failure.reason)
            assertTrue(!failure.toString().contains("private detail"))
        }
    }
}
