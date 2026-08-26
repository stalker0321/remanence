package dev.hryshyn.remanence.ui.capsule

import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AppNavigationController
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.navigation.CapsuleAccess
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.hryshyn.remanence.core.recognition.ScanGrantManager

/**
 * I10: end-to-end grant lifecycle binding navigation, the memory-only grant,
 * and the fullscreen presentation - open requires a live verified grant,
 * leaving consumes it, and a simulated restart forces a fresh scan.
 */
class GrantGateCapsuleFlowTest {

    private val capsuleId = UUID.randomUUID()

    private fun newState() = CapsulePresentationState(
        photoLoader = { ordinal -> DecryptedPhoto(ordinal, "jpeg-$ordinal".toByteArray()) },
    )

    private fun openedState(count: Int = 3, note: String? = null) =
        newState().also { it.open(count, note) }

    private fun authenticatedController() = AppNavigationController(
        AuthUiState.Authenticated(userId = "u", handle = "mykola"),
    )

    @Test
    fun openRequiresLiveGrantAndCloseConsumesEverything() = runBlocking {
        var now = 1_000L
        val grants = ScanGrantManager({ now })
        val controller = authenticatedController()
        val state = openedState(count = 3, note = "note")

        // Without a bound grant the capsule route is unreachable.
        controller.navigate(AppDestination.Capsule("spoofed"))
        assertEquals(AppDestination.Home, controller.current)

        // Issue + verify + bind, then enter.
        val grant = grants.issue(capsuleId)
        assertEquals(capsuleId, grants.resolveCapsuleId(grant.grantId))
        controller.grantCapsuleAccess(grant.grantId.toString(), capsuleId.toString())
        controller.navigate(AppDestination.Capsule(grant.grantId.toString()))
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), controller.current)

        // Fullscreen presentation works while open.
        val page = state.pageAt(0)
        assertEquals("jpeg-0", String(page.jpegBytes))

        // Leaving consumes the grant and ejects to Home; nothing reopens.
        assertTrue("the state owns its decrypted note while open", state.holdsDecryptedNoteForTests)
        state.close()
        assertTrue(grants.consume(grant.grantId))
        controller.consumeCapsuleAccess()
        assertTrue(state.loadedPages.isEmpty())
        assertFalse(state.isOpen)
        assertFalse("close must drop the note reference", state.holdsDecryptedNoteForTests)
        assertNull(state.note)
        assertEquals(AppDestination.Home, controller.current)
        assertNull(grants.resolveCapsuleId(grant.grantId))
        controller.navigate(AppDestination.Capsule(grant.grantId.toString()))
        assertEquals(AppDestination.Home, controller.current)
    }

    @Test
    fun simulatedRestartForcesRescanEvenWithTheOldGrantString() {
        var now = 1_000L
        val firstGrants = ScanGrantManager({ now })
        val firstController = authenticatedController()
        val grant = firstGrants.issue(capsuleId)
        firstController.grantCapsuleAccess(grant.grantId.toString(), capsuleId.toString())

        // Process death: everything in-memory is rebuilt from scratch.
        val rebornGrants = ScanGrantManager({ 999_999L })
        val rebornController = authenticatedController()

        assertNull(rebornGrants.resolveCapsuleId(grant.grantId))
        assertEquals(CapsuleAccess.None, rebornController.capsuleAccess)
        rebornController.navigate(AppDestination.Capsule(grant.grantId.toString()))
        assertEquals("restart must force rescan", AppDestination.Home, rebornController.current)
    }

    @Test
    fun expiryMidSessionEjectsOnNextResolve() = runBlocking {
        var now = 1_000L
        val grants = ScanGrantManager({ now })
        val controller = authenticatedController()
        val state = openedState(count = 4, note = "note")
        val grant = grants.issue(capsuleId)
        controller.grantCapsuleAccess(grant.grantId.toString(), capsuleId.toString())
        controller.navigate(AppDestination.Capsule(grant.grantId.toString()))

        now += ScanGrantManager.DEFAULT_GRANT_LIFETIME_MILLIS

        // The expired grant can no longer justify the screen.
        assertNull(grants.resolveCapsuleId(grant.grantId))
        state.close()
        controller.consumeCapsuleAccess()
        assertEquals(AppDestination.Home, controller.current)
        Unit
    }
}
