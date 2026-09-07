package dev.hryshyn.rv01probe.probe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceJsonTest {
    @Test
    fun `evidence contains enums only and no secret or exception material`() {
        val result = LocalDryRunFactory.runBlockStoreDryRun()
        val json = result.evidenceJson

        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
        assertTrue(json.contains("\"tuple\":\"P1_CAPABILITY\""))
        assertTrue(json.contains("\"evidenceClass\":\"LOCAL_DRY_RUN_NOT_EVIDENCE\""))
        assertFalse(json.contains("canary", ignoreCase = true))
        assertFalse(json.contains("unwrapMaterial", ignoreCase = true))
        assertFalse(json.contains("email", ignoreCase = true))
        assertFalse(json.contains("token", ignoreCase = true))
        assertFalse(json.contains("exception", ignoreCase = true))
        assertFalse(json.contains("providerSubject", ignoreCase = true))
    }
}
