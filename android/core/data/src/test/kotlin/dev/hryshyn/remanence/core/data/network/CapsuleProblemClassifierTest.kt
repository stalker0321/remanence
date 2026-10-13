package dev.hryshyn.remanence.core.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CapsuleProblemClassifierTest {

    @Test
    fun windowExpiredIsARecognizedTerminal409Problem() {
        val result = classifyCapsuleProblem(
            problemJson(code = "WINDOW_EXPIRED", status = 409, retryable = false),
            httpStatus = 409,
            allowedCodes = setOf("WINDOW_EXPIRED"),
        )

        assertEquals("WINDOW_EXPIRED", result?.code)
        assertEquals(false, result?.retryable)
    }

    @Test
    fun windowExpiredRetryabilityContradictionIsRejected() {
        val result = classifyCapsuleProblem(
            problemJson(code = "WINDOW_EXPIRED", status = 409, retryable = true),
            httpStatus = 409,
            allowedCodes = setOf("WINDOW_EXPIRED"),
        )

        assertNull(result)
    }

    @Test
    fun legacyProtocolUnsupportedStays422() {
        val result = classifyCapsuleProblem(
            problemJson(code = "PROTOCOL_UNSUPPORTED", status = 422, retryable = false),
            httpStatus = 422,
            allowedCodes = setOf("PROTOCOL_UNSUPPORTED"),
        )

        assertEquals("PROTOCOL_UNSUPPORTED", result?.code)
        assertEquals(false, result?.retryable)
    }

    @Test
    fun scopedTuplesDoNotLeakIntoCanonical() {
        // Pending 404 without the endpoint scope: rejected (the shared
        // map must not absorb it and weaken legacy v1).
        val result = classifyCapsuleProblem(
            problemJson(code = "PROTOCOL_UNSUPPORTED", status = 404, retryable = false),
            httpStatus = 404,
            allowedCodes = setOf("PROTOCOL_UNSUPPORTED"),
        )

        assertNull(result)
    }

    @Test
    fun scopedTuplesAcceptedWithEndpointScope() {
        val scope = mapOf("ROUTE_STALE" to 409, "PROTOCOL_UNSUPPORTED" to 404)
        val stale = classifyCapsuleProblem(
            problemJson(code = "ROUTE_STALE", status = 409, retryable = false),
            httpStatus = 409,
            allowedCodes = setOf("ROUTE_STALE"),
            endpointStatuses = scope,
        )
        assertEquals("ROUTE_STALE", stale?.code)

        val protocol = classifyCapsuleProblem(
            problemJson(code = "PROTOCOL_UNSUPPORTED", status = 404, retryable = false),
            httpStatus = 404,
            allowedCodes = setOf("PROTOCOL_UNSUPPORTED"),
            endpointStatuses = scope,
        )
        assertEquals("PROTOCOL_UNSUPPORTED", protocol?.code)
    }

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """{"type":"https://remanence.invalid/problems/${code.lowercase()}","title":"safe","status":$status,"code":"$code","detail":"private detail","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}"""

    @Test
    fun exclusiveScopeSkipsSharedTuple() {
        val scope = mapOf("PROTOCOL_UNSUPPORTED" to 404)
        // Exclusive: the shared legacy 422 is skipped for this code.
        val rejected = classifyCapsuleProblem(
            problemJson(code = "PROTOCOL_UNSUPPORTED", status = 422, retryable = false),
            httpStatus = 422,
            allowedCodes = setOf("PROTOCOL_UNSUPPORTED"),
            endpointStatuses = scope,
            endpointExclusive = setOf("PROTOCOL_UNSUPPORTED"),
        )
        assertNull(rejected)
        // …while the endpoint tuple itself is accepted.
        val accepted = classifyCapsuleProblem(
            problemJson(code = "PROTOCOL_UNSUPPORTED", status = 404, retryable = false),
            httpStatus = 404,
            allowedCodes = setOf("PROTOCOL_UNSUPPORTED"),
            endpointStatuses = scope,
            endpointExclusive = setOf("PROTOCOL_UNSUPPORTED"),
        )
        assertEquals("PROTOCOL_UNSUPPORTED", accepted?.code)
    }

    @Test
    fun nonExclusiveScopeKeepsSharedTuple() {
        val scope = mapOf("ROUTE_STALE" to 409)
        // Non-exclusive codes keep the shared canonical tuple (v1 422
        // default unchanged) alongside the additive scoped one.
        val legacy = classifyCapsuleProblem(
            problemJson(code = "PROTOCOL_UNSUPPORTED", status = 422, retryable = false),
            httpStatus = 422,
            allowedCodes = setOf("PROTOCOL_UNSUPPORTED"),
            endpointStatuses = scope,
        )
        assertEquals("PROTOCOL_UNSUPPORTED", legacy?.code)
    }
}
