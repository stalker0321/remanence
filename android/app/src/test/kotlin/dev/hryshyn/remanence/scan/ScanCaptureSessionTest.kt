package dev.hryshyn.remanence.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** State-machine proof for M2-F0-07 FRONT-only scan capture session. */
class ScanCaptureSessionTest {

    private var profileCounter = 0

    @Before
    fun setUp() {
        profileCounter = 0
    }

    private fun session() = ScanCaptureSession {
        ScannedSide("mvp-orb-v1-${profileCounter++}", ByteArray(32) { it.toByte() })
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
            ScannedSide("mvp-orb-v1", ByteArray(0))
        }
        assertThrows<IllegalArgumentException> {
            ScanCaptureSession(emptyExtractor).captureFront()
        }
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

    @Test
    fun failedValidationZeroizesStagedBytes() {
        val staged = ByteArray(0)
        val emptyExtractor = ScanSideExtractor {
            ScannedSide("mvp-orb-v1", staged)
        }
        val scan = ScanCaptureSession(emptyExtractor)
        assertThrows<IllegalArgumentException> { scan.captureFront() }
        assertTrue(staged.all { it == 0.toByte() })
        assertNull(scan.front)
    }
}
