package postmark.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class KeyBundleByIdRepositoryTest {

    private val userId = "1f0a1234-5678-4abc-9def-aabbccdd1001"
    private val keyBundleId = "2f0a1234-5678-4abc-9def-aabbccdd2002"

    private fun bundleJson(status: String) = """
        {
          "key_bundle_id": "$keyBundleId",
          "user_id": "$userId",
          "suite": "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
          "protocol_version": 1,
          "encryption_public_keyset": "CIenc",
          "signing_public_keyset": "CJsig",
          "status": "$status",
          "created_at": "2026-08-23T03:00:00Z"
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

    private fun repository(server: MockWebServer): KeyBundleByIdRepository =
        KeyBundleByIdRepository.create(ApiBaseUrl.parse(server.url("/").toString()))

    @Test
    fun activeBundleIsFetchableForSignatureVerification() = runTest {
        withServer { server ->
            server.enqueue(json(bundleJson("ACTIVE")))
            val result = repository(server).fetch(keyBundleId, accessToken = "pm_at_live")

            val found = assertIs<KeyBundleByIdResult.Found>(result)
            assertEquals(keyBundleId, found.bundle.keyBundleId.toRestString())
            assertEquals(userId, found.bundle.ownerUserId.toRestString())
            assertEquals("ACTIVE", found.bundle.status)
            assertEquals("CJsig", found.bundle.signingPublicKeysetB64Url)

            val recorded = server.takeRequest()
            assertEquals("/v1/directory/key-bundles/$keyBundleId", recorded.url.encodedPath)
            assertEquals("Bearer pm_at_live", recorded.headers["Authorization"])
        }
    }

    @Test
    fun retiredAndRevokedBundlesRemainAvailableWithStatus() = runTest {
        withServer { server ->
            for (status in listOf("RETIRED", "REVOKED", "ACTIVE")) {
                server.enqueue(json(bundleJson(status)))
                val result = repository(server).fetch(keyBundleId, accessToken = "pm_at_live")
                assertEquals(status, assertIs<KeyBundleByIdResult.Found>(result).bundle.status)
            }
        }
    }

    @Test
    fun unknownBundleMapsToNotFound() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(404)
                    .setHeader("Content-Type", "application/problem+json")
                    .body("""{"code":"KEY_BUNDLE_NOT_FOUND"}""")
                    .build(),
            )
            assertEquals(
                KeyBundleByIdResult.NotFound,
                repository(server).fetch("3f0a1234-5678-4abc-9def-aabbccdd3999", accessToken = "pm_at_live"),
            )
        }
    }

    @Test
    fun unknownStatusFailsClosed() = runTest {
        withServer { server ->
            server.enqueue(json(bundleJson("SUSPENDED")))
            val failure = assertIs<KeyBundleByIdResult.Failure>(repository(server).fetch(keyBundleId, accessToken = "pm_at_live"))
            assertEquals(KeyBundleFailure.INVALID_RESPONSE, failure.reason)
        }
    }

    @Test
    fun unauthorizedIsHttpFailure() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(401).build())
            val failure = assertIs<KeyBundleByIdResult.Failure>(repository(server).fetch(keyBundleId, accessToken = "pm_at_dead"))
            assertEquals(KeyBundleFailure.HTTP, failure.reason)
            assertEquals(401, failure.httpStatus)
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()
}
