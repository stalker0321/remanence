package dev.hryshyn.remanence.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {

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
        controller.grantCapsuleAccess(grantId = "grant-9", capsuleId = "capsule-9")

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
        controller.grantCapsuleAccess("grant-3", "capsule-3")
        controller.navigate(AppDestination.Capsule("grant-3"))

        controller.consumeCapsuleAccess()

        assertEquals(AppDestination.Home, controller.current)
        controller.navigate(AppDestination.Capsule("grant-3"))
        assertEquals(AppDestination.Home, controller.current)
    }

    @Test
    fun signedOutCannotResolveACapsuleEvenWithValidAccess() {
        val access = CapsuleAccess.Granted("grant-4", "capsule-4", cryptoVerified = true)

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
        controller.grantCapsuleAccess("grant-5", "capsule-5")
        controller.navigate(AppDestination.Capsule("grant-5"))
        assertEquals(AppDestination.Capsule("grant-5"), controller.current)

        controller.updateAuth(AuthUiState.SignedOut)

        assertEquals(AppDestination.Authentication, controller.current)
        assertEquals(CapsuleAccess.None, controller.capsuleAccess)

        controller.updateAuth(AuthUiState.Authenticated(userId = "u", handle = "mykola"))
        controller.navigate(AppDestination.Capsule("grant-5"))
        assertEquals(AppDestination.Home, controller.current)
    }
}
