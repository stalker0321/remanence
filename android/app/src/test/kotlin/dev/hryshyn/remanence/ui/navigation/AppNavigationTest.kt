package dev.hryshyn.remanence.ui.navigation

import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {

    private val owner = "1f0a1234-5678-4abc-9def-aabbccdd1001"

    @Test
    fun signedOutStartsOnAuthenticationSurface() {
        val controller = AppNavigationController(AuthUiState.SignedOut)
        assertEquals(AppDestination.Authentication, controller.current)
    }

    @Test
    fun signedOutCannotReachHome() {
        val controller = AppNavigationController(AuthUiState.SignedOut)
        controller.navigate(AppDestination.Home)
        assertEquals(AppDestination.Authentication, controller.current)
    }

    @Test
    fun authenticatedReachesHome() {
        val controller = AppNavigationController(
            AuthUiState.Authenticated(userId = "1f0a1234-5678-4abc-9def-aabbccdd1001", handle = "mykola"),
        )
        assertEquals(AppDestination.Home, RouteGuard.initialDestination(controller.authState))
        controller.navigate(AppDestination.Home)
        assertEquals(AppDestination.Home, controller.current)
    }

    @Test
    fun logoutMidAppRedirectsToAuthentication() {
        val controller = AppNavigationController(
            AuthUiState.Authenticated(userId = "1f0a1234-5678-4abc-9def-aabbccdd1001", handle = "mykola"),
        )
        controller.navigate(AppDestination.Home)
        controller.updateAuth(AuthUiState.SignedOut)
        assertEquals(AppDestination.Authentication, controller.current)
    }

    @Test
    fun recoveryRequiredLandsOnAuthenticationNotHome() {
        val controller = AppNavigationController()
        controller.updateAuth(AuthUiState.RecoveryRequired)
        assertEquals(AppDestination.Authentication, controller.current)
        controller.navigate(AppDestination.Home)
        assertEquals(AppDestination.Authentication, controller.current)
    }

    @Test
    fun destinationInventoryAllowsOnlyAuthHomeCreateScanAndGrantGatedCapsule() {
        val inventory = RouteGuard.allDestinations().map { it.javaClass.simpleName }.toSet()
        assertEquals(setOf("Authentication", "Home", "Create", "Scan", "Capsule"), inventory)
        for (forbidden in listOf("Gallery", "Inbox", "History", "Feed", "DeepLink")) {
            org.junit.Assert.assertFalse("forbidden route type: $forbidden", forbidden in inventory.joinToString())
        }
    }

    @Test
    fun createAndScanAreAuthenticatedOnlyDestinations() {
        val controller = AppNavigationController(AuthUiState.SignedOut)
        controller.navigate(AppDestination.Create)
        assertEquals(AppDestination.Authentication, controller.current)
        controller.navigate(AppDestination.Scan)
        assertEquals(AppDestination.Authentication, controller.current)

        val authenticated = authenticatedController()
        authenticated.navigate(AppDestination.Create)
        assertEquals(AppDestination.Create, authenticated.current)
        authenticated.navigate(AppDestination.Scan)
        assertEquals(AppDestination.Scan, authenticated.current)
    }

    @Test
    fun ownerOrKeyBoundaryExitsCreateAndScanToHome() {
        val boundaries = listOf(
            AuthUiState.Authenticated(
                userId = "2f0a1234-5678-4abc-9def-aabbccdd1002",
                handle = "other",
            ),
            AuthUiState.Authenticated(
                userId = owner,
                handle = "mykola",
                activeKeyBundleId = "key-b",
            ),
        )
        for (next in boundaries) {
            for (flow in listOf(AppDestination.Create, AppDestination.Scan)) {
                val controller = authenticatedController()
                controller.navigate(flow)
                controller.updateAuth(next)
                assertEquals(AppDestination.Home, controller.current)
            }
        }
    }

    private fun authenticatedController() = AppNavigationController(
        AuthUiState.Authenticated(userId = "1f0a1234-5678-4abc-9def-aabbccdd1001", handle = "mykola"),
    )

    @Test
    fun capsuleRequestWithoutAnyGrantFallsBackToHome() {
        val controller = authenticatedController()

        controller.navigate(AppDestination.Capsule(grantId = "grant-1"))

        assertEquals(AppDestination.Home, controller.current)
    }

    @Test
    fun capsuleRequestBeforeCryptoVerificationFallsBackToHome() {
        val controller = authenticatedController()
        // A grant string alone must never open anything: this simulates a
        // leaked or guessed ID without the verified crypto result.
        controller.navigate(AppDestination.Capsule(grantId = "unverified-grant"))
        assertEquals(AppDestination.Home, controller.current)
    }

    @Test
    fun verifiedCapsuleIsReachableByItsExactGrantId() {
        val controller = authenticatedController()
        controller.grantCapsuleAccess(
            grantId = "grant-9",
            capsuleId = "capsule-9",
            ownerUserId = owner,
            source = CapsulePresentationSource.OUTBOX,
            scanGeneration = 0,
        )

        controller.navigate(AppDestination.Capsule(grantId = "grant-8")) // wrong id
        assertEquals(AppDestination.Home, controller.current)

        controller.navigate(AppDestination.Capsule(grantId = "grant-9"))
        assertEquals(
            AppDestination.Capsule("grant-9"),
            controller.current,
        )
    }

    @Test
    fun consumingTheGrantEjectsToHomeAndBlocksReentry() {
        val controller = authenticatedController()
        controller.grantCapsuleAccess(
            "grant-3",
            "capsule-3",
            owner,
            CapsulePresentationSource.OUTBOX,
            0,
        )
        controller.navigate(AppDestination.Capsule("grant-3"))

        controller.consumeCapsuleAccess()

        assertEquals(AppDestination.Home, controller.current)
        controller.navigate(AppDestination.Capsule("grant-3"))
        assertEquals(AppDestination.Home, controller.current)
    }

    @Test
    fun signedOutCannotResolveACapsuleEvenWithValidAccess() {
        val access = CapsuleAccess.Granted(
            "grant-4",
            "capsule-4",
            cryptoVerified = true,
            ownerUserId = owner,
            source = CapsulePresentationSource.OUTBOX,
            scanGeneration = 0,
        )

        assertEquals(
            AppDestination.Authentication,
            RouteGuard.resolve(AuthUiState.SignedOut, AppDestination.Capsule("grant-4"), access),
        )
        assertEquals(
            AppDestination.Authentication,
            RouteGuard.resolve(AuthUiState.RecoveryRequired, AppDestination.Capsule("grant-4"), access),
        )
    }

    @Test
    fun logoutWhileViewingCapsuleDropsAccessCompletely() {
        val controller = authenticatedController()
        controller.grantCapsuleAccess(
            "grant-5",
            "capsule-5",
            owner,
            CapsulePresentationSource.OUTBOX,
            0,
        )
        controller.navigate(AppDestination.Capsule("grant-5"))
        assertEquals(AppDestination.Capsule("grant-5"), controller.current)

        controller.updateAuth(AuthUiState.SignedOut)

        assertEquals(AppDestination.Authentication, controller.current)
        assertEquals(CapsuleAccess.None, controller.capsuleAccess)

        controller.updateAuth(AuthUiState.Authenticated(userId = owner, handle = "mykola"))
        controller.navigate(AppDestination.Capsule("grant-5"))
        assertEquals(AppDestination.Home, controller.current)
    }
}
