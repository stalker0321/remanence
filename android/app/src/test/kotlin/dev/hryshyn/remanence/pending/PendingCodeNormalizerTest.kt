package dev.hryshyn.remanence.pending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCodeNormalizerTest {

    @Test
    fun canonicalFiveCharCodePasses() {
        assertEquals("A3K9Q", PendingCodeNormalizer.normalize("A3K9Q"))
    }

    @Test
    fun lowercaseSpacesAndHyphensNormalized() {
        assertEquals("A3K9Q", PendingCodeNormalizer.normalize(" a3-k9q "))
    }

    @Test
    fun wrongLengthRejected() {
        assertNull(PendingCodeNormalizer.normalize("A3K9"))
        assertNull(PendingCodeNormalizer.normalize("A3K9Q1"))
        assertNull(PendingCodeNormalizer.normalize(""))
    }

    @Test
    fun frozenContractAliasesMapped() {
        // T185 api-contract §6: O→0, I/L→1.
        assertEquals("A306Q", PendingCodeNormalizer.normalize("A3O6Q"))
        assertEquals("A319Q", PendingCodeNormalizer.normalize("A3I9Q"))
        assertEquals("A319Q", PendingCodeNormalizer.normalize("A3L9Q"))
    }

    @Test
    fun unmappedLettersStillRejected() {
        assertNull(PendingCodeNormalizer.normalize("A3U9Q"))
        assertNull(PendingCodeNormalizer.normalize("?????"))
    }

    @Test
    fun genericWellFormedCheck() {
        assertTrue(PendingCodeNormalizer.isWellFormed("a3k9q"))
        assertFalse(PendingCodeNormalizer.isWellFormed("?????"))
    }
}
