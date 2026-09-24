package dev.hryshyn.remanence.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Stable authenticated first frame: when the auth state is already
 * Authenticated, the root renders the home surface synchronously — the
 * authentication form must never flash on top of it. Tag-based,
 * locale-independent. NOT run in this tick (no Gradle execution per the
 * task brief; window 10 owns Gradle).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RootFirstFrameTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun authenticatedFirstFrameShowsHomeNotAuthForm() {
        composeRule.setContent {
            MaterialTheme {
                RootScreen(
                    authState = AuthUiState.Authenticated(userId = "u", handle = "mykola"),
                    destination = AppDestination.Home,
                    authenticationContent = {
                        Text("auth form probe", modifier = Modifier.testTag("auth_form_probe"))
                    },
                    homeContent = {
                        Text("home surface probe", modifier = Modifier.testTag("home_surface_probe"))
                    },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home_surface_probe").assertIsDisplayed()
        composeRule.onAllNodesWithTag("auth_form_probe").assertCountEquals(0)
    }

    @Test
    fun resolvingFirstFrameShowsNeutralFrameOnly() {
        composeRule.setContent {
            MaterialTheme {
                RootScreen(
                    authState = AuthUiState.Resolving,
                    destination = AppDestination.Authentication,
                    authenticationContent = {
                        Text("auth form probe", modifier = Modifier.testTag("auth_form_probe"))
                    },
                    homeContent = {
                        Text("home surface probe", modifier = Modifier.testTag("home_surface_probe"))
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // Cold-start check in flight: the neutral frame holds, and neither
        // the auth form nor any home surface exists — nothing wrong to flash.
        composeRule.onNodeWithTag("root_resolving_frame").assertIsDisplayed()
        composeRule.onAllNodesWithTag("auth_form_probe").assertCountEquals(0)
        composeRule.onAllNodesWithTag("home_surface_probe").assertCountEquals(0)
    }

    @Test
    fun loggedOutStartupShowsAuthFormNotHome() {
        composeRule.setContent {
            MaterialTheme {
                RootScreen(
                    authState = AuthUiState.SignedOut,
                    destination = AppDestination.Authentication,
                    authenticationContent = {
                        Text("auth form probe", modifier = Modifier.testTag("auth_form_probe"))
                    },
                    homeContent = {
                        Text("home surface probe", modifier = Modifier.testTag("home_surface_probe"))
                    },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("auth_form_probe").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_surface_probe").assertCountEquals(0)
        composeRule.onAllNodesWithTag("root_resolving_frame").assertCountEquals(0)
    }

    @Test
    fun capsuleRouteArrivesWithContentNotHomeOrAuthForm() {
        composeRule.setContent {
            MaterialTheme {
                RootScreen(
                    authState = AuthUiState.Authenticated(userId = "u", handle = "mykola"),
                    destination = AppDestination.Capsule("grant-1"),
                    authenticationContent = {
                        Text("auth form probe", modifier = Modifier.testTag("auth_form_probe"))
                    },
                    homeContent = {
                        Text("home surface probe", modifier = Modifier.testTag("home_surface_probe"))
                    },
                    capsuleContent = { grantId ->
                        Text(grantId, modifier = Modifier.testTag("capsule_probe"))
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // Route continuity: the granted capsule surface arrives with the
        // boundary arrival, without retaining or showing any other surface.
        composeRule.onNodeWithTag("capsule_probe").assertIsDisplayed()
        composeRule.onAllNodesWithTag("home_surface_probe").assertCountEquals(0)
        composeRule.onAllNodesWithTag("auth_form_probe").assertCountEquals(0)
    }
}
