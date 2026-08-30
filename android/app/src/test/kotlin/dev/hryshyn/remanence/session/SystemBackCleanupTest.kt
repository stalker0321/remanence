package dev.hryshyn.remanence.session

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Android Back must use the same flow-exit cleanup callback as the chrome. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SystemBackCleanupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun createBackExitsThroughTheRootCleanupCallback() {
        var exited = false
        composeRule.setContent {
            MaterialTheme {
                RootScreen(
                    authState = AuthUiState.Authenticated(userId = "u", handle = "mykola"),
                    destination = AppDestination.Create,
                    authenticationContent = {},
                    homeContent = {},
                    createContent = {},
                    scanContent = {},
                    onExitFlow = { exited = true },
                )
            }
        }

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.runOnIdle { assertTrue(exited) }
    }

    @Test
    fun scanBackExitsThroughTheRootCleanupCallback() {
        var exited = false
        composeRule.setContent {
            MaterialTheme {
                RootScreen(
                    authState = AuthUiState.Authenticated(userId = "u", handle = "mykola"),
                    destination = AppDestination.Scan,
                    authenticationContent = {},
                    homeContent = {},
                    createContent = {},
                    scanContent = {},
                    onExitFlow = { exited = true },
                )
            }
        }

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.runOnIdle { assertTrue(exited) }
    }
}
