package dev.hryshyn.remanence.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class DirectoryRepositoryTest {

    private val userId = "1f0a1234-5678-4abc-9def-aabbccdd1001"
    private val keyBundleId = "2f0a1234-5678-4abc-9def-aabbccdd2002"

    private val responseJson = """
        {
          "user": {"user_id": "$userId", "handle": "mykola"},
          "key_bundle": {
            "key_bundle_id": "$keyBundleId",
            "user_id": "$userId",
            "suite": "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
            "protocol_version": 1,
            "encryption_public_keyset": "CIabc123",
            "signing_public_keyset": "CJxyz789",
            "status": "ACTIVE",
            "created_at": "2026-08-23T03:00:00Z"
          },
          "directory_version": "9f1c0d2e7a6b4c5d"
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

    private fun repository(server: MockWebServer): DirectoryRepository =
        DirectoryRepository.create(ApiBaseUrl.parse(server.url("/").toString()))

    @Test
    fun foundMapsToDomainIdsAndKeyBundle() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(responseJson).build(),
            )
            val result = repository(server).lookup("mykola", accessToken = "pm_at_live")

            val found = assertIs<DirectoryLookupResult.Found>(result)
            assertEquals(userId, found.snapshot.userId.toRestString())
            assertEquals("@mykola", found.snapshot.handle.toDisplayString())
            assertEquals(keyBundleId, found.snapshot.keyBundleId.toRestString())
            assertEquals(1, found.snapshot.protocolVersion)
            assertEquals("CIabc123", found.snapshot.encryptionPublicKeysetB64Url)
            assertEquals("9f1c0d2e7a6b4c5d", found.snapshot.directoryVersion)

            val recorded = server.takeRequest()
            assertEquals("/v1/directory/handles/mykola", recorded.url.encodedPath)
            assertEquals("Bearer pm_at_live", recorded.headers["Authorization"])
        }
    }

    @Test
    fun snapshotNeverCarriesEmail() = runTest {
        withServer { server ->
            // An unexpected extra field (like an email leak) must fail closed.
            val leaked = responseJson.replace(
                "\"directory_version\": \"9f1c0d2e7a6b4c5d\"",
                "\"directory_version\": \"9f1c0d2e7a6b4c5d\", \"email\": \"secret@example.com\"",
            )
            assertTrue(leaked.contains("secret@example.com"))
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(leaked).build(),
            )
            val result = repository(server).lookup("mykola", accessToken = "pm_at_live")
            assertIs<DirectoryLookupResult.Failure>(result)
            assertFalse(result.toString().contains("secret@example.com"))
        }
    }

    @Test
    fun unknownHandleMapsToNotFound() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(404)
                    .setHeader("Content-Type", "application/problem+json")
                    .body("""{"code":"HANDLE_NOT_FOUND"}""")
                    .build(),
            )
            assertEquals(DirectoryLookupResult.NotFound, repository(server).lookup("nobody", accessToken = "pm_at_live"))
        }
    }

    @Test
    fun unauthorizedIsHttpFailureWithStatus() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(401)
                    .setHeader("Content-Type", "application/problem+json")
                    .body("""{"code":"AUTHENTICATION_REQUIRED"}""")
                    .build(),
            )
            val failure = assertIs<DirectoryLookupResult.Failure>(repository(server).lookup("mykola", accessToken = "pm_at_dead"))
            assertEquals(DirectoryFailure.HTTP, failure.reason)
            assertEquals(401, failure.httpStatus)
        }
    }

    @Test
    fun inconsistentIdsBetweenSummaryAndBundleAreRejected() = runTest {
        withServer { server ->
            val root = Json.parseToJsonElement(responseJson).jsonObject.toMutableMap()
            val bundle = root["key_bundle"]!!.jsonObject.toMutableMap()
            bundle["user_id"] = JsonPrimitive("1f0a1234-5678-4abc-9def-aabbccdd1999")
            root["key_bundle"] = JsonObject(bundle)
            val tampered = JsonObject(root).toString()

            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(tampered).build(),
            )
            val failure = assertIs<DirectoryLookupResult.Failure>(repository(server).lookup("mykola", accessToken = "pm_at_live"))
            assertEquals(DirectoryFailure.INVALID_RESPONSE, failure.reason)
        }
    }

    @Test
    fun handlePathSegmentIsPercentEncoded() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder().code(404).setHeader("Content-Type", "application/json").body("{}").build(),
            )
            repository(server).lookup("weird name", accessToken = "pm_at_live")
            assertEquals("/v1/directory/handles/weird%20name", server.takeRequest().url.encodedPath)
        }
    }
}
