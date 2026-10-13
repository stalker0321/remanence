package dev.hryshyn.remanence.core.data.network

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Request

/**
 * T186 M7c: staged wire-63 profile compatibility — optional typed
 * `profile_version` decodes under the strict shared decoder (which
 * rejects undeclared fields), and the v2 capability header is emitted
 * centrally only after the pending install (never by default).
 */
class PendingProfileDtoTest {

    @BeforeTest
    fun resetHeader() {
        PendingProfileHeaders.resetForTests()
    }

    @AfterTest
    fun clearHeader() {
        PendingProfileHeaders.resetForTests()
    }

    @Test
    fun userSummaryWithProfileVersionDecodesStrict() {
        val dto = NetworkJson.decodeFromString<RegistrationUserDto>(
            """{"user_id":"0198f0a0-0000-7000-8000-00000000aa01","email":"a42@example.test","handle":null,"created_at":"2026-10-13T12:00:00Z","profile_version":2}""",
        )
        assertEquals(2, dto.profileVersion)
        assertNull(dto.handle)
    }

    @Test
    fun legacySummaryWithoutProfileVersionDecodes() {
        val dto = NetworkJson.decodeFromString<RegistrationUserDto>(
            """{"user_id":"0198f0a0-0000-7000-8000-00000000aa01","email":"a42@example.test","handle":"mykola","created_at":"2026-10-13T12:00:00Z"}""",
        )
        assertNull(dto.profileVersion)
        assertEquals("mykola", dto.handle)
    }

    @Test
    fun headerAbsentByDefault() {
        val built = PendingProfileHeaders.apply(Request.Builder().url("https://example.org/")).build()
        assertNull(built.header(PendingProfileHeaders.HEADER))
    }

    @Test
    fun headerPresentOnlyAfterPendingInstall() {
        PendingProfileHeaders.installForPendingBuilds()
        val built = PendingProfileHeaders.apply(Request.Builder().url("https://example.org/")).build()
        assertEquals("2", built.header(PendingProfileHeaders.HEADER))
    }

    @Test
    fun loginResponseWithProfileVersionDecodes() {
        val dto = NetworkJson.decodeFromString<LoginResponseDto>(
            """{"user":{"user_id":"0198f0a0-0000-7000-8000-00000000aa01","email":"a42@example.test","handle":null,"created_at":"2026-10-13T12:00:00Z","profile_version":2},"active_key_bundle":{"key_bundle_id":"b","suite":"s","protocol_version":1,"status":"ACTIVE"},"session_id":"s","access_token":"a","access_expires_at":"e","refresh_token":"r","refresh_expires_at":"e"}""",
        )
        assertEquals(2, dto.user.profileVersion)
        assertTrue(dto.user.handle == null)
    }
}
