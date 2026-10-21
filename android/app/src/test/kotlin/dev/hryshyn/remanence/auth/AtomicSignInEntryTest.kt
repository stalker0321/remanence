package dev.hryshyn.remanence.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.recovery.RecoveryPanel
import dev.hryshyn.remanence.ui.auth.AccessPage
import dev.hryshyn.remanence.ui.hold.HoldTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual production signed-out content and recovery panel; fake provider callbacks, not native acceptance. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AtomicSignInEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var googleCalls = 0
    private var restoreCalls = 0
    private var recoveryBusy by mutableStateOf(false)
    private var view by mutableStateOf(AtomicView())
    private val fixture = AtomicRegistrationControllerTest.Fixture()
    private val controller = fixture.controller()

    private fun mount(large: Boolean = false) {
        rule.setContent { HoldTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.8f else 1f)) {
                Surface(Modifier.fillMaxSize()) { AccessPage {
                    AtomicRegistrationContent(view, "", {}, { googleCalls++ }, {}, {}, {}, {}, {},
                        { controller.leave(); view = controller.state.value }, recoveryBusy) { enabled, beforeSignIn ->
                        RecoveryPanel(false, false, false, true, recoveryBusy || !enabled, false, false,
                            0, false, false, "", {}, {}, {
                                beforeSignIn()
                                assertEquals(AtomicPhase.IDLE, controller.state.value.phase)
                                restoreCalls++; recoveryBusy = true
                            }, {}, {}, {}, {}, {})
                    }
                } }
            }
        } }
    }

    @Test fun passkeyEntryNeedsNoGoogleAndFencesBothBusyDirections() {
        mount()
        rule.onNodeWithTag("recovery_restore").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithTag("atomic_google").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("atomic_leave").assertIsNotEnabled()
        assertEquals(0, googleCalls); assertTrue(fixture.requests.isEmpty()); assertEquals(1, restoreCalls)
        rule.runOnIdle { recoveryBusy = false; view = AtomicView(AtomicPhase.GOOGLE, busy = true) }
        rule.onNodeWithTag("recovery_restore").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("atomic_leave").assertIsEnabled()
    }

    @Test fun switchingToExistingPasskeyRetiresReservedOwnerWithoutNewKeys() {
        runBlocking { controller.begin(fixture.google) }
        view = controller.state.value; mount()
        rule.onNodeWithTag("recovery_restore").performScrollTo().performClick()
        runBlocking { controller.save("forbidden", fixture); controller.retry() }
        assertEquals(AtomicPhase.IDLE, controller.state.value.phase)
        assertEquals(0, fixture.generations); assertEquals(0, fixture.creates)
        assertTrue(fixture.finals.isEmpty()); assertEquals(1, restoreCalls); assertEquals(0, googleCalls)
    }

    @Test fun englishEntryShowsIndependentPasskey() = render("en", false)
    @Test @Config(qualifiers = "ru-w320dp-h640dp-xhdpi")
    fun russianNarrowLargeEntryKeepsPasskeyReachable() = render("ru-large", true)
    @Test @Config(qualifiers = "uk-w320dp-h640dp-xhdpi")
    fun ukrainianNarrowLargeEntryKeepsPasskeyReachable() = render("uk-large", true)

    private fun render(name: String, large: Boolean) {
        mount(large)
        rule.onNodeWithTag("atomic_google").performScrollTo().assertIsEnabled()
        capture("$name-google")
        rule.onNodeWithTag("recovery_restore").performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("$name-passkey")
        assertEquals(0, googleCalls); assertEquals(0, restoreCalls)
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val decor = rule.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
            decor.draw(android.graphics.Canvas(bitmap))
            val output = File("build/t423-review/atomic-$name.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
