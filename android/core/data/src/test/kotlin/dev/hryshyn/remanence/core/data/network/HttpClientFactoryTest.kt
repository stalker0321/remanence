package dev.hryshyn.remanence.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns

class HttpClientFactoryTest {
    @Test
    fun createUsesFixedTimeoutsRedirectsAndRetryWithoutInterceptors() {
        val client = HttpClientFactory.create()
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(45_000, client.callTimeoutMillis)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertTrue(client.retryOnConnectionFailure)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertEquals(Authenticator.NONE, client.authenticator)
        assertEquals(CookieJar.NO_COOKIES, client.cookieJar)
        assertNull(client.cache)
        assertEquals(Dns.SYSTEM, client.dns)
    }
}
