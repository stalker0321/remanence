package app.postmark.memory.scan

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import postmark.core.recognition.FingerprintSide

/** State-machine proof for M1-M11 scan front/back capture session. */
class ScanCaptureSessionTest {

    private lateinit var requestedSides: MutableList<FingerprintSide>
    private var profileCounter = 0

    @Before
    fun setUp() {
        requestedSides = mutableListOf()
        profileCounter = 0
    }

    private fun session() = ScanCaptureSession { side ->
        requestedSides += side
        ScannedSide("mvp-orb-v1-${profileCounter++}", side, ByteArray(32) { it.toByte() })
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
    fun frontThenBackReachesReadyForMatchingInOrder() {
        val scan = session()

        assertEquals(ScanSessionState.AWAITING_FRONT, scan.state)
        val front = scan.captureFront()
        assertEquals(ScanSessionState.AWAITING_BACK, scan.state)
        val back = scan.captureBack()

        assertEquals(ScanSessionState.READY_FOR_MATCHING, scan.state)
        assertTrue(scan.readyForMatching)
        assertEquals(FingerprintSide.FRONT, front.side)
        assertEquals(front.profileId, scan.front?.profileId)
        assertEquals(back.profileId, scan.back?.profileId)
        assertEquals(listOf(FingerprintSide.FRONT, FingerprintSide.BACK), requestedSides)
    }

    @Test
    fun backBeforeFrontIsRejected() {
        val scan = session()
        assertThrows<IllegalStateException> { scan.captureBack() }
        assertEquals(ScanSessionState.AWAITING_FRONT, scan.state)
        assertEquals(0, requestedSides.size)
    }

    @Test
    fun duplicateFrontCaptureIsRejectedUntilExplicitReset() {
        val scan = session()
        scan.captureFront()
        assertThrows<IllegalStateException> { scan.captureFront() }

        scan.reset()
        assertEquals(ScanSessionState.AWAITING_FRONT, scan.state)
        assertNull(scan.front)
        assertNull(scan.back)

        scan.captureFront()
        assertEquals(ScanSessionState.AWAITING_BACK, scan.state)
    }

    @Test
    fun captureAfterCompletionAndConsumptionAreRejected() {
        val scan = session()
        scan.captureFront()
        scan.captureBack()
        assertThrows<IllegalStateException> { scan.captureFront() }

        scan.consume()
        assertEquals(ScanSessionState.CONSUMED, scan.state)
        assertFalse(scan.readyForMatching)
        assertNull(scan.front)
        assertNull(scan.back)
        assertThrows<IllegalStateException> { scan.captureBack() }
    }

    @Test
    fun extractorReturningTheWrongSideFailsClosed() {
        val lyingExtractor = ScanSideExtractor {
            ScannedSide("mvp-orb-v1", FingerprintSide.BACK, ByteArray(8) { 1 })
        }
        val scan = ScanCaptureSession(lyingExtractor)

        assertThrows<IllegalArgumentException> { scan.captureFront() }
        assertEquals(ScanSessionState.AWAITING_FRONT, scan.state)
    }

    @Test
    fun emptySerializedFingerprintIsRejected() {
        val emptyExtractor = ScanSideExtractor {
            ScannedSide("mvp-orb-v1", FingerprintSide.FRONT, ByteArray(0))
        }
        assertThrows<IllegalArgumentException> {
            ScanCaptureSession(emptyExtractor).captureFront()
        }
    }
}
