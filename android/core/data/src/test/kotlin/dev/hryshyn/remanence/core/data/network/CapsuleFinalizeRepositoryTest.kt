package dev.hryshyn.remanence.core.data.network

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId

class CapsuleFinalizeRepositoryTest {

    private val capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca01")
    private val senderKeyBundleId = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000ba01")
    private val recipientKeyBundleId = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000ba02")

    private fun request(
        statement: ByteArray = STATEMENT,
        signature: ByteArray = SIGNATURE,
        ciphertext: ByteArray = ENVELOPE_CIPHERTEXT,
        ciphertextSha256: ByteArray = sha256(ciphertext),
    ): CapsuleFinalizeRequest = CapsuleFinalizeRequest(
        capsuleId = capsuleId,
        statement = statement,
        signature = signature,
        senderKeyBundleId = senderKeyBundleId,
        recipientKeyBundleId = recipientKeyBundleId,
        recipientEnvelopeCiphertext = ciphertext,
        recipientEnvelopeCiphertextSha256 = ciphertextSha256,
    )

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    @Test
    fun freshFinalizeEmitsExactRequestAndMaps201() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(201)
                    .setHeader("Content-Type", "application/json")
                    .body(successJson())
                    .build(),
            )

            val result = repository(server).finalize(request(), accessToken = "pm_at_live")

            val success = assertIs<CapsuleFinalizeResult.Success>(result)
            assertEquals(201, success.httpStatus)
            assertEquals(capsuleId, success.finalize.capsuleId)
            assertEquals(CapsuleFinalizeState.READY, success.finalize.state)
            assertEquals("2030-01-01T12:00:00Z", success.finalize.readyAt)

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/v1/capsules/${capsuleId.toRestString()}/finalize", recorded.url.encodedPath)
            assertEquals("Bearer pm_at_live", recorded.headers["Authorization"])
            assertEquals("application/json; charset=utf-8", recorded.headers["Content-Type"])
            assertNull(recorded.headers["Idempotency-Key"])

            val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
            assertEquals(setOf("signed_publish_statement", "recipient_envelope"), body.keys)
            val statement = body["signed_publish_statement"]!!.jsonObject
            assertEquals(
                setOf("statement", "signature", "sender_key_bundle_id"),
                statement.keys,
            )
            assertEquals(base64url(STATEMENT), statement["statement"]!!.jsonPrimitiveContent())
            assertEquals(base64url(SIGNATURE), statement["signature"]!!.jsonPrimitiveContent())
            assertEquals(senderKeyBundleId.toRestString(), statement["sender_key_bundle_id"]!!.jsonPrimitiveContent())
            val envelope = body["recipient_envelope"]!!.jsonObject
            assertEquals(
                setOf("recipient_key_bundle_id", "ciphertext", "ciphertext_size", "ciphertext_sha256"),
                envelope.keys,
            )
            assertEquals(recipientKeyBundleId.toRestString(), envelope["recipient_key_bundle_id"]!!.jsonPrimitiveContent())
            assertEquals(base64url(ENVELOPE_CIPHERTEXT), envelope["ciphertext"]!!.jsonPrimitiveContent())
            assertEquals(ENVELOPE_CIPHERTEXT.size.toString(), envelope["ciphertext_size"]!!.toString())
            assertEquals(base64url(sha256(ENVELOPE_CIPHERTEXT)), envelope["ciphertext_sha256"]!!.jsonPrimitiveContent())
            assertFalse(body.keys.contains("capsule_id"))
        }
    }

    @Test
    fun idempotentReplayMaps200WithTheSameReadyResult() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(successJson()).build())

            val result = repository(server).finalize(request(), accessToken = "pm_at_live")

            val success = assertIs<CapsuleFinalizeResult.Success>(result)
            assertEquals(200, success.httpStatus)
            assertEquals(CapsuleFinalizeState.READY, success.finalize.state)
        }
    }

    @Test
    fun recipientKeyStaleIsASeparateStructuredFailureAndDetailsAreRedacted() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(409)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("RECIPIENT_KEY_STALE", 409, false))
                    .build(),
            )

            val failure = assertIs<CapsuleFinalizeResult.Failure>(
                repository(server).finalize(request(), accessToken = "pm_at_live"),
            )

            assertEquals(CapsuleFinalizeFailure.RECIPIENT_KEY_STALE, failure.reason)
            assertEquals(409, failure.httpStatus)
            assertFalse(failure.retryable)
            assertFalse(failure.toString().contains(SECRET_DETAIL))
        }
    }

    @Test
    fun retryabilityPreservesCanonicalProblemsAndRejectsContradictions() = runTest {
        withServer { server ->
            fun enqueue(status: Int, code: String, retryable: Boolean) {
                server.enqueue(
                    MockResponse.Builder()
                        .code(status)
                        .setHeader("Content-Type", "application/problem+json")
                        .body(problemJson(code, status, retryable))
                        .build(),
                )
            }

            enqueue(429, "RATE_LIMITED", true)
            val rateLimited = assertIs<CapsuleFinalizeResult.Failure>(repository(server).finalize(request(), "pm_at_live"))
            assertEquals(CapsuleFinalizeFailure.RATE_LIMITED, rateLimited.reason)
            assertTrue(rateLimited.retryable)

            enqueue(503, "INTERNAL_ERROR", true)
            val unavailable = assertIs<CapsuleFinalizeResult.Failure>(repository(server).finalize(request(), "pm_at_live"))
            assertEquals(CapsuleFinalizeFailure.INTERNAL_ERROR, unavailable.reason)
            assertTrue(unavailable.retryable)

            enqueue(500, "INTERNAL_ERROR", false)
            val internal = assertIs<CapsuleFinalizeResult.Failure>(repository(server).finalize(request(), "pm_at_live"))
            assertEquals(CapsuleFinalizeFailure.INTERNAL_ERROR, internal.reason)
            assertFalse(internal.retryable)

            enqueue(422, "SIGNATURE_INVALID", false)
            val integrity = assertIs<CapsuleFinalizeResult.Failure>(repository(server).finalize(request(), "pm_at_live"))
            assertEquals(CapsuleFinalizeFailure.SIGNATURE_INVALID, integrity.reason)
            assertFalse(integrity.retryable)

            enqueue(503, "INTERNAL_ERROR", false)
            val contradictory503 = assertIs<CapsuleFinalizeResult.Failure>(repository(server).finalize(request(), "pm_at_live"))
            assertEquals(CapsuleFinalizeFailure.HTTP, contradictory503.reason)
            assertTrue(contradictory503.retryable)

            enqueue(409, "RECIPIENT_KEY_STALE", true)
            val contradictory409 = assertIs<CapsuleFinalizeResult.Failure>(repository(server).finalize(request(), "pm_at_live"))
            assertEquals(CapsuleFinalizeFailure.HTTP, contradictory409.reason)
            assertFalse(contradictory409.retryable)
        }
    }

    @Test
    fun malformedAndOversizedResponsesFailClosedWithinBoundedReader() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(201).setHeader("Content-Type", "application/json").body("not-json").build())
            val malformedSuccess = assertIs<CapsuleFinalizeResult.Failure>(
                repository(server).finalize(request(), accessToken = "pm_at_live"),
            )
            assertEquals(CapsuleFinalizeFailure.INVALID_RESPONSE, malformedSuccess.reason)

            server.enqueue(
                MockResponse.Builder()
                    .code(500)
                    .setHeader("Content-Type", "application/problem+json")
                    .body("x".repeat(64 * 1024 + 1))
                    .build(),
            )
            val oversized = assertIs<CapsuleFinalizeResult.Failure>(
                repository(server).finalize(request(), accessToken = "pm_at_live"),
            )
            assertEquals(CapsuleFinalizeFailure.INVALID_RESPONSE, oversized.reason)
            assertTrue(oversized.retryable)
        }
    }

    @Test
    fun malformedAndProxyResponsesUseHttpRetryabilityFallback() = runTest {
        withServer { server ->
            fun enqueue(status: Int, contentType: String?, body: String) {
                server.enqueue(
                    MockResponse.Builder()
                        .code(status)
                        .apply { if (contentType != null) setHeader("Content-Type", contentType) }
                        .body(body)
                        .build(),
                )
            }

            enqueue(503, "application/problem+json", "not-json")
            assertTrue(assertIs<CapsuleFinalizeResult.Failure>(repository(server).finalize(request(), "pm_at_live")).retryable)

            enqueue(503, "text/plain", "proxy failure")
            assertTrue(assertIs<CapsuleFinalizeResult.Failure>(repository(server).finalize(request(), "pm_at_live")).retryable)

            enqueue(429, "application/problem+json", "not-json")
            assertTrue(assertIs<CapsuleFinalizeResult.Failure>(repository(server).finalize(request(), "pm_at_live")).retryable)

            enqueue(429, "text/plain", "rate limited")
            assertTrue(assertIs<CapsuleFinalizeResult.Failure>(repository(server).finalize(request(), "pm_at_live")).retryable)

            enqueue(429, "application/problem+json", "x".repeat(64 * 1024 + 1))
            assertTrue(assertIs<CapsuleFinalizeResult.Failure>(repository(server).finalize(request(), "pm_at_live")).retryable)

            enqueue(418, "application/problem+json", "not-json")
            assertFalse(assertIs<CapsuleFinalizeResult.Failure>(repository(server).finalize(request(), "pm_at_live")).retryable)

            enqueue(201, "application/json", "not-json")
            assertFalse(assertIs<CapsuleFinalizeResult.Failure>(repository(server).finalize(request(), "pm_at_live")).retryable)
        }
    }

    @Test
    fun networkFailureIsRetryable() = runTest {
        val server = MockWebServer()
        server.start()
        val baseUrl = ApiBaseUrl.parse(server.url("/").toString())
        server.close()

        val failure = assertIs<CapsuleFinalizeResult.Failure>(
            CapsuleFinalizeRepository.create(baseUrl).finalize(request(), "pm_at_live"),
        )
        assertEquals(CapsuleFinalizeFailure.NETWORK, failure.reason)
        assertTrue(failure.retryable)
    }

    @Test
    fun requestFreezesAllMutableBinaryInputsAndRedactsToString() = runTest {
        withServer { server ->
            val statement = "private-statement-marker".toByteArray()
            val signature = SIGNATURE.copyOf()
            val ciphertext = "private-envelope-marker".toByteArray()
            val hash = sha256(ciphertext)
            val expectedStatement = statement.copyOf()
            val expectedSignature = signature.copyOf()
            val expectedCiphertext = ciphertext.copyOf()
            val expectedHash = hash.copyOf()
            val upload = request(statement, signature, ciphertext, hash)

            statement[0] = 0
            signature[0] = 0
            ciphertext[0] = 0
            hash[0] = 0
            upload.statement[0] = 0
            upload.signature[0] = 0
            upload.recipientEnvelopeCiphertext[0] = 0
            upload.recipientEnvelopeCiphertextSha256[0] = 0
            assertFalse(upload.toString().contains("private-statement-marker"))
            assertFalse(upload.toString().contains("private-envelope-marker"))
            server.enqueue(MockResponse.Builder().code(201).setHeader("Content-Type", "application/json").body(successJson()).build())

            assertIs<CapsuleFinalizeResult.Success>(repository(server).finalize(upload, accessToken = "pm_at_live"))
            val recorded = server.takeRequest()
            val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
            val statementJson = body["signed_publish_statement"]!!.jsonObject
            val envelopeJson = body["recipient_envelope"]!!.jsonObject
            assertEquals(base64url(expectedStatement), statementJson["statement"]!!.jsonPrimitiveContent())
            assertEquals(base64url(expectedSignature), statementJson["signature"]!!.jsonPrimitiveContent())
            assertEquals(base64url(expectedCiphertext), envelopeJson["ciphertext"]!!.jsonPrimitiveContent())
            assertEquals(base64url(expectedHash), envelopeJson["ciphertext_sha256"]!!.jsonPrimitiveContent())
        }
    }

    private fun repository(server: MockWebServer): CapsuleFinalizeRepository =
        CapsuleFinalizeRepository.create(ApiBaseUrl.parse(server.url("/").toString()))

    private fun successJson(): String =
        """{"capsule_id":"${capsuleId.toRestString()}","state":"READY","ready_at":"2030-01-01T12:00:00Z"}"""

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """{"type":"https://remanence.invalid/problems/${code.lowercase()}","title":"safe","status":$status,"code":"$code","detail":"$SECRET_DETAIL","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}"""

    private companion object {
        val STATEMENT = "canonical-publish-statement".toByteArray()
        val SIGNATURE = ByteArray(69) { (it + 1).toByte() }
        val ENVELOPE_CIPHERTEXT = "recipient-envelope-ciphertext".toByteArray()
        const val SECRET_DETAIL = "private finalize detail must not cross the repository boundary"

        fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

        fun base64url(value: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
    (this as kotlinx.serialization.json.JsonPrimitive).content
