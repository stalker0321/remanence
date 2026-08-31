package dev.hryshyn.remanence.core.data.network

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Bearer injection rules for the production client (FIX-M1-007-06). */
class BearerAuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var tokens: AuthTokenHolder
    private lateinit var client: OkHttpClient
    private var lastAuthorization: String? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                lastAuthorization = request.headers["Authorization"]
                return MockResponse.Builder().code(200).body("ok").build()
            }
        }
        server.start()
        tokens = AuthTokenHolder("pm_at_live", "pm_rt_live")
        client = OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor(tokens))
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun get(path: String, builder: Request.Builder.() -> Unit = {}): Unit = runBlocking {
        client.newCall(
            Request.Builder().url(server.url(path)).get().apply(builder).build(),
        ).execute().use { assertEquals(200, it.code) }
    }

    @Test
    fun injectsMemoryOnlyBearerOnApiRequests() {
        get("/v1/directory/handles/mykola")
        assertEquals(RefreshingAuthenticator.BEARER_PREFIX + "pm_at_live", lastAuthorization)
    }

    @Test
    fun neverTouchesRegisterLoginOrRefreshPaths() {
        listOf("/v1/auth/register", "/v1/auth/login", "/v1/auth/refresh").forEach { path ->
            get(path)
            assertNull("no bearer on $path", lastAuthorization)
        }
    }

    @Test
    fun preservesExplicitAuthorizationHeadersSuchAsLogout() {
        get("/v1/auth/logout") {
            header("Authorization", RefreshingAuthenticator.BEARER_PREFIX + "pm_at_explicit")
        }
        assertEquals(RefreshingAuthenticator.BEARER_PREFIX + "pm_at_explicit", lastAuthorization)
    }

    @Test
    fun signedOutRequestsCarryNoHeader() {
        tokens.clearSession()
        get("/v1/capsules/incoming")
        assertNull(lastAuthorization)
    }

    @Test
    fun closedDomainStripsExplicitOrdinaryAuthorization() {
        val closed = OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor { null })
            .build()
        listOf(
            "/v1/capsules/incoming",
            "/v1/capsules",
            "/v1/directory/users/0198f0a0-0000-7000-8000-00000000a001",
        ).forEach { path ->
            lastAuthorization = "sentinel"
            closed.newCall(
                Request.Builder()
                    .url(server.url(path))
                    .header("Authorization", RefreshingAuthenticator.BEARER_PREFIX + "pm_at_explicit")
                    .get()
                    .build(),
            ).execute().use { assertEquals(200, it.code) }
            assertNull("ordinary $path must not keep Authorization while closed", lastAuthorization)
        }
    }

    @Test
    fun closedDomainPreservesNoHeaderOnBareLogoutPathWithoutInjecting() {
        val closed = OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor { null })
            .build()
        lastAuthorization = "sentinel"
        closed.newCall(
            Request.Builder()
                .url(server.url("/v1/auth/logout"))
                .header("Authorization", RefreshingAuthenticator.BEARER_PREFIX + "pm_at_explicit")
                .get()
                .build(),
        ).execute().use { assertEquals(200, it.code) }
        assertNull(lastAuthorization)
    }
}
