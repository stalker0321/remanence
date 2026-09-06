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

    private fun problemJson(code: String, status: Int, retryable: Boolean): String =
        """{"type":"https://remanence.invalid/problems/${code.lowercase()}","title":"safe","status":$status,"code":"$code","detail":"private detail","request_id":"0198f0a0-0000-7000-8000-00000000ac01","retryable":$retryable}"""
}
