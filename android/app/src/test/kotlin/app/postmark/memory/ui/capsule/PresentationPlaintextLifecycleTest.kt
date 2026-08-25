package app.postmark.memory.ui.capsule

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX-REVIEW3-04 regression suite for the lifecycle/plaintext race across
 * the REAL production composition: presentation -> guarded reader -> decrypt.
 * Every scenario is driven by suspension gates on the test scheduler -
 * deterministic, no sleeps, no wall-clock dependence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresentationPlaintextLifecycleTest {

    private class GatedDecryptReader(
        private val bytes: ByteArray,
        private val noteValue: String?,
    ) : CapsuleContentReader {
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun photoCount(capsuleId: String): Int = 3

        override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto {
            calls += 1
            loadStarted.complete(Unit)
            releaseLoad.await()
            return DecryptedPhoto(ordinal, bytes)
        }

        override suspend fun noteText(capsuleId: String): String? = noteValue
    }

    /**
     * The full production chain: a page load is hanging inside decryption
     * when the GRANT dies (expiry/revocation). The guard's post-decrypt
     * validation refuses the plaintext, zeroes the bytes, and the
     * presentation caches nothing - while remaining open until its own
     * close() drops the note reference.
     */
    @Test
    fun grantDeathDuringFlightRefusesPlaintextAndCloseDropsTheNote() = runTest {
        val bytes = "plaintext-page-bytes".toByteArray()
        val reader = GatedDecryptReader(bytes, noteValue = "secret-note")
        var alive = true
        val guarded = GrantGuardedCapsuleContentSource(reader) {
            if (!alive) throw IllegalStateException("scan grant is no longer live")
        }
        val state = CapsulePresentationState(
            photoLoader = { ordinal -> guarded.loadPhoto("capsule", ordinal) },
        )
        state.open(3, "secret-note")
        assertTrue(state.holdsDecryptedNoteForTests)

        val outcome = CompletableDeferred<Result<DecryptedPhoto>>()
        launch { outcome.complete(runCatching { state.pageAt(1) }) }
        reader.loadStarted.await()

        // The grant expires WHILE the page is still decrypting...
        alive = false
        // ...and only afterwards does the decryption finish.
        reader.releaseLoad.complete(Unit)

        assertTrue("dead-grant plaintext must be refused", outcome.await().isFailure)
        assertTrue(
            "refused plaintext must be zeroed",
            bytes.contentEquals(ByteArray(bytes.size)),
        )
        assertTrue("nothing may enter the page cache", state.loadedPages.isEmpty())
        assertTrue(
            "the guard refused; closing is the route's separate responsibility",
            state.isOpen,
        )

        // The route's own close then drops every remaining reference.
        state.close()
        assertFalse(state.isOpen)
        assertFalse(state.holdsDecryptedNoteForTests)
        assertNull(state.note)
        assertTrue(state.loadedPages.isEmpty())
    }

    /**
     * The presentation-side race: close()/expiry wins while a load is still
     * hung. The completing load returns nothing, caches nothing, and zeroes
     * its bytes.
     */
    @Test
    fun closeWinningTheRaceLeavesNoPlaintextAnywhere() = runTest {
        val bytes = "stale-page".toByteArray()
        val reader = GatedDecryptReader(bytes, noteValue = null)
        val guarded = GrantGuardedCapsuleContentSource(reader) { /* alive */ }
        val state = CapsulePresentationState(
            photoLoader = { ordinal -> guarded.loadPhoto("capsule", ordinal) },
        )
        state.open(3, null)

        val outcome = CompletableDeferred<Result<DecryptedPhoto>>()
        launch { outcome.complete(runCatching { state.pageAt(2) }) }
        reader.loadStarted.await()

        state.close()
        reader.releaseLoad.complete(Unit)

        assertTrue(outcome.await().isFailure)
        assertTrue(bytes.contentEquals(ByteArray(bytes.size)))
        assertFalse(state.isOpen)
        assertTrue(state.loadedPages.isEmpty())
        assertFalse(state.holdsDecryptedNoteForTests)
    }

    /** Post-decrypt validation alone refuses without any close happening. */
    @Test
    fun postDecryptValidationIsProvableWithoutPresentationClosure() = runTest {
        val bytes = "mid-flight-death".toByteArray()
        val reader = GatedDecryptReader(bytes, noteValue = "n")
        var alive = true
        val guarded = GrantGuardedCapsuleContentSource(reader) {
            if (!alive) throw IllegalStateException("dead")
        }

        val outcome = CompletableDeferred<Result<DecryptedPhoto>>()
        launch { outcome.complete(runCatching { guarded.loadPhoto("capsule", 0) }) }
        reader.loadStarted.await()

        alive = false
        reader.releaseLoad.complete(Unit)

        assertTrue(outcome.await().isFailure)
        assertTrue(bytes.contentEquals(ByteArray(bytes.size)))
    }

    /** The decrypted note reference is dropped by close exactly once-clearly. */
    @Test
    fun noteOwnershipMovesIntoTheStateAndDiesWithIt() = runTest {
        val reader = GatedDecryptReader(ByteArray(0), noteValue = null)
        val guarded = GrantGuardedCapsuleContentSource(reader) { /* alive */ }
        val state = CapsulePresentationState(
            photoLoader = { ordinal -> guarded.loadPhoto("capsule", ordinal) },
        )

        state.open(3, "dear-mama")
        assertEqualsNoteHeld(state, expected = true)
        assertEqualsNote(state, "dear-mama")

        state.close()
        assertEqualsNoteHeld(state, expected = false)
        assertNull(state.note)
    }

    private fun assertEqualsNoteHeld(state: CapsulePresentationState, expected: Boolean) {
        assert(state.holdsDecryptedNoteForTests == expected) {
            "expected held=$expected"
        }
    }

    private fun assertEqualsNote(state: CapsulePresentationState, expected: String?) {
        assert(state.note == expected) { "unexpected note exposure" }
    }
}
