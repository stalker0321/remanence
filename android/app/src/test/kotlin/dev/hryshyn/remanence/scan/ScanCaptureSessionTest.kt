package dev.hryshyn.remanence.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import dev.hryshyn.remanence.test.CanonicalSiftFingerprintFixture

/** State-machine proof for M2-F0-07 FRONT-only scan capture session. */
class ScanCaptureSessionTest {

    private var profileCounter = 0

    @Before
    fun setUp() {
        profileCounter = 0
    }

    private fun session() = ScanCaptureSession {
        ScannedSide(
            SiftRootSiftFingerprintCodec.PROFILE_ID,
            CanonicalSiftFingerprintFixture.bytes(seed = profileCounter++),
        )
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (expected: Throwable) {
            if (expected is T) return expected
            throw expected
        }
        throw AssertionError("expected ${T::class.java.simpleName}")
    }

    @Test
    fun frontReachesReadyForMatchingImmediately() {
        val scan = session()

        assertEquals(ScanSessionState.AWAITING_FRONT, scan.state)
        val front = scan.captureFront()

        assertEquals(ScanSessionState.READY_FOR_MATCHING, scan.state)
        assertTrue(scan.readyForMatching)
        assertEquals(front.profileId, scan.front?.profileId)
    }

    @Test
    fun duplicateFrontCaptureIsRejectedUntilExplicitReset() {
        val scan = session()
        scan.captureFront()
        assertThrows<IllegalStateException> { scan.captureFront() }

        scan.reset()
        assertEquals(ScanSessionState.AWAITING_FRONT, scan.state)
        assertNull(scan.front)

        scan.captureFront()
        assertEquals(ScanSessionState.READY_FOR_MATCHING, scan.state)
    }

    @Test
    fun captureAfterCompletionAndConsumptionAreRejected() {
        val scan = session()
        scan.captureFront()
        assertThrows<IllegalStateException> { scan.captureFront() }

        scan.consume()
        assertEquals(ScanSessionState.CONSUMED, scan.state)
        assertFalse(scan.readyForMatching)
        assertNull(scan.front)
        assertThrows<IllegalStateException> { scan.captureFront() }
    }

    @Test
    fun emptySerializedFingerprintIsRejected() {
        val emptyExtractor = ScanSideExtractor {
            ScannedSide(SiftRootSiftFingerprintCodec.PROFILE_ID, ByteArray(0))
        }
        assertThrows<IllegalArgumentException> {
            ScanCaptureSession(emptyExtractor).captureFront()
        }
    }

    @Test
    fun profileMismatchIsRejectedAndWipesTheExtractedBytes() {
        val bytes = CanonicalSiftFingerprintFixture.bytes(seed = 20)
        val scan = ScanCaptureSession {
            ScannedSide("retired-profile-v0", bytes)
        }

        assertThrows<IllegalArgumentException> { scan.captureFront() }

        assertNull(scan.front)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun noncanonicalBytesAreRejectedAndWiped() {
        val bytes = CanonicalSiftFingerprintFixture.bytes(seed = 21) + byteArrayOf(0x50, 0x01)
        val scan = ScanCaptureSession {
            ScannedSide(SiftRootSiftFingerprintCodec.PROFILE_ID, bytes)
        }

        assertThrows<IllegalArgumentException> { scan.captureFront() }

        assertNull(scan.front)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun resetZeroizesCapturedFrontBytes() {
        val scan = session()
        val captured = scan.captureFront()
        val staged = captured.serializedBytes
        assertTrue(staged.isNotEmpty())

        scan.reset()

        assertNull(scan.front)
        assertTrue(staged.all { it == 0.toByte() })
    }

    @Test
    fun consumeZeroizesCapturedFrontBytes() {
        val scan = session()
        val captured = scan.captureFront()
        val staged = captured.serializedBytes
        assertTrue(staged.isNotEmpty())

        scan.consume()

        assertNull(scan.front)
        assertTrue(staged.all { it == 0.toByte() })
    }

    /**
     * A failed recapture never wipes the live FRONT: the order violation
     * throws before extraction, so the same non-empty buffer stays live
     * until an explicit reset zeroizes it.
     */
    @Test
    fun failedRecapturePreservesLiveFrontUntilResetWipesIt() {
        val live = CanonicalSiftFingerprintFixture.bytes(seed = 22)
        assertTrue(live.any { it != 0.toByte() })
        var extractions = 0
        val scan = ScanCaptureSession {
            extractions++
            ScannedSide(SiftRootSiftFingerprintCodec.PROFILE_ID, live)
        }
        scan.captureFront()
        assertThrows<IllegalStateException> { scan.captureFront() }
        assertEquals("order violation must throw before extraction", 1, extractions)
        assertSame(live, scan.front?.serializedBytes)
        assertTrue(live.any { it != 0.toByte() })

        scan.reset()

        assertNull(scan.front)
        assertTrue(live.all { it == 0.toByte() })
    }
}
