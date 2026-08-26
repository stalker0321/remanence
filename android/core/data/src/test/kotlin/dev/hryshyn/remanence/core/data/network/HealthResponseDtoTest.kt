package dev.hryshyn.remanence.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class HealthResponseDtoTest {
    @Test
    fun decodesExactOkStatus() {
        val dto = NetworkJson.decodeFromString<HealthResponseDto>("""{"status":"ok"}""")
        assertEquals("ok", dto.status)
    }

    @Test
    fun encodesCompactOkJson() {
        assertEquals(
            """{"status":"ok"}""",
            NetworkJson.encodeToString(HealthResponseDto("ok")),
        )
    }

    @Test
    fun rejectsUnknownField() {
        assertFailsWith<SerializationException> {
            NetworkJson.decodeFromString<HealthResponseDto>("""{"status":"ok","extra":true}""")
        }
    }

    @Test
    fun rejectsMissingStatus() {
        assertFailsWith<SerializationException> {
            NetworkJson.decodeFromString<HealthResponseDto>("{}")
        }
    }

    @Test
    fun rejectsNullStatus() {
        assertFailsWith<SerializationException> {
            NetworkJson.decodeFromString<HealthResponseDto>("""{"status":null}""")
        }
    }

    @Test
    fun rejectsNumericAndBooleanStatus() {
        assertFailsWith<SerializationException> {
            NetworkJson.decodeFromString<HealthResponseDto>("""{"status":1}""")
        }
        assertFailsWith<SerializationException> {
            NetworkJson.decodeFromString<HealthResponseDto>("""{"status":true}""")
        }
    }

    @Test
    fun rejectsMalformedAndTrailingJson() {
        assertFailsWith<SerializationException> {
            NetworkJson.decodeFromString<HealthResponseDto>("""{"status":"ok"""")
        }
        assertFailsWith<SerializationException> {
            NetworkJson.decodeFromString<HealthResponseDto>("""{"status":"ok"}{}""")
        }
    }
}
