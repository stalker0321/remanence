package app.postmark.memory.ui.navigation

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
    fun destinationInventoryContainsNoCapsuleOrGalleryRoutes() {
        val inventory = RouteGuard.allDestinations().map { it.javaClass.simpleName }.toSet()
        assertEquals(setOf("Authentication", "Home"), inventory)
        for (forbidden in listOf("Capsule", "Gallery", "Inbox", "History", "Feed", "DeepLink")) {
            org.junit.Assert.assertFalse("forbidden route type: $forbidden", forbidden in inventory.joinToString())
        }
    }
}
