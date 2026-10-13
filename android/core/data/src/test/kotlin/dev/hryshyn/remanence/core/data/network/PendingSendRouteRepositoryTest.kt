package dev.hryshyn.remanence.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/**
 * T186 M8: R1 route-freeze tests (frozen §8; routes scaffold, isolated
 * only). Exactly one shape per response; never both.
 */
class PendingSendRouteRepositoryTest {

    private suspend fun <T> withServer(block: suspend (MockWebServer) -> T): T {
        val server = MockWebServer()
        server.start()
        try {
            return block(server)
        } finally {
            server.close()
        }
    }

    private fun repository(server: MockWebServer): PendingSendRouteRepository =
        PendingSendRouteRepository(
            HttpClientFactory.create(),
            ApiBaseUrl.parse(server.url("/").toString()),
        )

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """{"type":"https://remanence.invalid/problems/${code.lowercase()}","title":"safe","status":$status,"code":"$code","detail":"private detail","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}"""

    @Test
    fun v1BindingFreezesExistingPipeline() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"route_id":"0198f0a0-0000-7000-8000-00000000ef01","mode":"EXISTING_USER_V1","user_id":"01010101-0202-0303-0404-050505050505","key_bundle_id":"01010101-0202-0303-0404-050505050506","binding_revision":3,"expires_at":1798765432}""",
                    )
                    .build(),
            )
            val result = assertIs<SendRouteResult.FrozenV1>(
                repository(server).freeze(
                    "11111111-2222-3333-4444-555555555555", "email",
                    "a42@example.test", "0198f0a0-0000-7000-8000-00000000c001", "token",
                ),
            )
            assertEquals("01010101-0202-0303-0404-050505050505", result.userId)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v2/send-routes", request.url.encodedPath)
            val body = assertNotNull(request.body).utf8()
            assertTrue(body.contains("a42@example.test"))
            // Raw value travels; the server normalizes authoritatively.
            assertTrue(body.contains("email"))
        }
    }

    @Test
    fun unboundAddressFreezesPending() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"route_id":"0198f0a0-0000-7000-8000-00000000ef02","mode":"PENDING_V2","delivery_target_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","normalized_destination":"a42@example.test","channel":"email","claim_deadline":1798765432,"service_key_id":"0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f","expires_at":1798765432}""",
                    )
                    .build(),
            )
            val result = assertIs<SendRouteResult.FrozenPending>(
                repository(server).freeze(
                    "11111111-2222-3333-4444-555555555555", "email",
                    "a42@example.test", "0198f0a0-0000-7000-8000-00000000c001", "token",
                ),
            )
            assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", result.deliveryTargetId)
        }
    }

    @Test
    fun handleRouteWithoutRevisionAccepted() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"route_id":"0198f0a0-0000-7000-8000-00000000ef03","mode":"EXISTING_USER_V1","user_id":"01010101-0202-0303-0404-050505050505","key_bundle_id":"01010101-0202-0303-0404-050505050506","binding_revision":null,"expires_at":1798765432}""",
                    )
                    .build(),
            )
            // Handle-routed v1 carries no revision: accepted as null, never
            // faked to 0.
            val result = assertIs<SendRouteResult.FrozenV1>(
                repository(server).freeze(
                    "11111111-2222-3333-4444-555555555555", "handle",
                    "mykola", "0198f0a0-0000-7000-8000-00000000c001", "token",
                ),
            )
            assertEquals(null, result.bindingRevision)
        }
    }

    @Test
    fun mixedShapeRejected() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"route_id":"0198f0a0-0000-7000-8000-00000000ef01","mode":"EXISTING_USER_V1","user_id":"01010101-0202-0303-0404-050505050505","key_bundle_id":"01010101-0202-0303-0404-050505050506","binding_revision":3,"delivery_target_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","expires_at":1798765432}""",
                    )
                    .build(),
            )
            // Both shapes at once: never accepted (exactly one required).
            val result = assertIs<SendRouteResult.Failure>(
                repository(server).freeze("c", "email", "a@b.c", "op", "token"),
            )
            assertEquals(SendRouteFailure.INVALID_RESPONSE, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun handleRequiredMapsForSend() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(400)
                    .setHeader("Content-Type", "application/problem+json")
                    .body(problemJson("HANDLE_REQUIRED_FOR_SEND", 400, false))
                    .build(),
            )
            val result = assertIs<SendRouteResult.Failure>(
                repository(server).freeze("c", "email", "a@b.c", "op", "token"),
            )
            assertEquals(SendRouteFailure.HANDLE_REQUIRED_FOR_SEND, result.reason)
            assertFalse(result.retryable)
        }
    }

    @Test
    fun badKindRejectedClientSide() = runTest {
        withServer { server ->
            val result = assertIs<SendRouteResult.Failure>(
                repository(server).freeze("c", "fax", "x", "op", "token"),
            )
            assertEquals(SendRouteFailure.VALIDATION_FAILED, result.reason)
            assertEquals(0, server.requestCount)
        }
    }
}
