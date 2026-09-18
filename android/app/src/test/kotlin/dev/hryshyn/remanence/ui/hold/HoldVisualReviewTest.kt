package dev.hryshyn.remanence.ui.hold

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.ui.home.*
import dev.hryshyn.remanence.ui.auth.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native-rendered review images; these are not physical-device acceptance. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoldVisualReviewTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun homeHasTwoWholeActionSurfaces() {
        composeRule.setContent {
            HoldTheme { Surface { HomeScreen(BackendHealthUiState.AVAILABLE, publicEntry = true) } }
        }
        composeRule.onNodeWithTag("scan_action").assertHasClickAction()
        composeRule.onNodeWithTag("create_action").assertHasClickAction()
        capture("home-en")
    }

    @Test @Config(qualifiers = "ru-w360dp-h780dp-xhdpi")
    fun russianHomeUsesTheAcceptedFontAndWrapping() {
        composeRule.setContent {
            HoldTheme { Surface { HomeScreen(BackendHealthUiState.AVAILABLE, publicEntry = true) } }
        }
        composeRule.onNodeWithTag("scan_action").assertIsDisplayed()
        capture("home-ru")
    }

    @Test fun loginHasMaskedPasswordAndReachableSubmit() {
        composeRule.setContent {
            HoldTheme {
                Surface {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text("let’s get you in", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(24.dp))
                        LoginScreen(LoginFormState("person@example.com", "private-password"), LoginSubmitState.Idle, {}, {}, {})
                    }
                }
            }
        }
        composeRule.onNodeWithTag("login_password_field").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        composeRule.onNodeWithTag("login_submit_button").assertIsDisplayed()
        capture("login-en")
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            // PixelCopy has no real Surface producer on Robolectric. Draw the
            // measured native View tree directly into the review bitmap.
            val view = composeRule.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val output = File("build/hold-review/$name.png")
            output.parentFile.mkdirs()
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
