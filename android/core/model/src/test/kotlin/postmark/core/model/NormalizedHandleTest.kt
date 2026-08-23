package postmark.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

class NormalizedHandleTest {
    @Test
    fun fixtureCasesNormalizeOrReject() {
        val fixture = loadHandlesFixture()
        for ((input, normalized) in fixture.valid) {
            val handle = NormalizedHandle.parse(input)
            assertEquals(normalized, handle.value)
            assertEquals("@$normalized", handle.toDisplayString())
        }
        for (input in fixture.invalid) {
            val error = assertFailsWith<IllegalArgumentException> { NormalizedHandle.parse(input) }
            assertEquals("invalid handle", error.message)
            if (input.isNotEmpty()) {
                assertFalse(input in (error.message ?: ""))
            }
        }
    }

    @Test
    fun parseIsThePublicConstructionPath() {
        val fromBare = NormalizedHandle.parse("Abc")
        val fromPrefixed = NormalizedHandle.parse("@ABC")
        assertEquals(fromBare, fromPrefixed)
        assertEquals("abc", fromBare.value)
        assertEquals("@abc", fromBare.toDisplayString())
    }

    private fun loadHandlesFixture(): HandlesFixture {
        val resource = javaClass.classLoader.getResource("handles-v1.json")
            ?: error("handles-v1.json is missing")
        val root = Json.parseToJsonElement(resource.readText(Charsets.UTF_8))
        assertTrue(root is JsonObject, "fixture root must be an object")
        assertEquals(setOf("schema_version", "valid", "invalid"), root.keys)
        val version = root.getValue("schema_version")
        assertTrue(version is JsonPrimitive && !version.isString, "schema_version must be a number")
        assertEquals(1, version.intOrNull)
        val validElement = root.getValue("valid")
        val invalidElement = root.getValue("invalid")
        assertTrue(validElement is JsonArray, "valid must be an array")
        assertTrue(invalidElement is JsonArray, "invalid must be an array")
        assertTrue(validElement.isNotEmpty(), "valid array is empty")
        assertTrue(invalidElement.isNotEmpty(), "invalid array is empty")
        val seen = HashSet<String>()
        val valid = validElement.map { entry ->
            assertTrue(entry is JsonObject, "valid entry must be an object")
            assertEquals(setOf("input", "normalized"), entry.keys)
            val input = stringField(entry, "input")
            val normalized = stringField(entry, "normalized")
            assertTrue(seen.add(input), "duplicate fixture input")
            input to normalized
        }
        val invalid = invalidElement.map { entry ->
            assertTrue(entry is JsonPrimitive && entry.isString, "invalid entry must be a string")
            val input = entry.content
            assertTrue(seen.add(input), "duplicate fixture input")
            input
        }
        return HandlesFixture(valid, invalid)
    }

    private fun stringField(obj: JsonObject, key: String): String {
        val value = obj.getValue(key)
        assertTrue(value is JsonPrimitive && value.isString, "$key must be a string")
        return requireNotNull(value.contentOrNull)
    }

    private data class HandlesFixture(
        val valid: List<Pair<String, String>>,
        val invalid: List<String>,
    )
}
