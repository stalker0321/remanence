package dev.hryshyn.remanence.ui.settings

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.hryshyn.remanence.core.data.network.*
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.session.*
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.locale.AppLocale
import dev.hryshyn.remanence.ui.navigation.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SendAccessSettingsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: android.view.View
    private val store = ViewModelStore()
    private val jobs = mutableListOf<Job>()
    private val owner = "10000000-0000-4000-8000-000000000001"
    private val lease = SessionRequestLease(UserId.parseRest(owner), 1)
    private fun value(self: Int = 1, other: Int = 0, mode: SendAccessMode = SendAccessMode.ENFORCE,
                      life: Boolean = false) = SendAccess(mode, life, SendAccessBucket(2-self, self),
        SendAccessBucket(2-other, other), mode == SendAccessMode.RECORD || life || self > 0,
        mode == SendAccessMode.RECORD || life || other > 0)
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun close() {
        store.clear(); runBlocking { withTimeout(10_000) { jobs.joinAll() } }
        compose.waitForIdle(); Dispatchers.resetMain()
    }
    private fun vm(port: SendAccessPort): SendAccessViewModel =
        SendAccessViewModel(port, { lease }, { it == lease }, SessionBoundary()).also {
            store.put("access", it); jobs.add(it.viewModelScope.coroutineContext.job)
        }

    private fun snapshot(name: String) {
        compose.waitForIdle()
        val output = File("build/t387-review").apply { mkdirs() }
        output.resolve("$name-semantics.txt").writeText(compose.onAllNodes(isRoot()).printToString())
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(android.graphics.Canvas(bitmap))
                output.resolve("$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
    }
    private fun mount(state: () -> SendAccessUiState, scale: Float = 1f, refresh: () -> Unit = {}) {
        compose.setContent {
            root = LocalView.current.rootView
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                HoldTheme { SettingsScreen("synthetic_owner", AppLocale.SYSTEM, {}, true, {}, {},
                    sendAccessContent = { SendAccessSection(state(), refresh, {}) }) }
            }
        }
    }
    private fun textFits(tag: String) {
        val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertFalse("$tag overflow", layouts.single().hasVisualOverflow)
        for (line in 0 until layouts.single().lineCount) assertFalse(layouts.single().isLineEllipsized(line))
        val screen = compose.onNodeWithTag("settings_screen").fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= screen.left && bounds.right <= screen.right)
    }
    private fun languageLayout(name: String, scale: Float) {
        var refresh = 0
        mount({ SendAccessUiState(owner, value = value(), hidden = false) }, scale) { refresh++ }
        textFits("send_access_self"); textFits("send_access_other")
        compose.onNodeWithTag("send_access_refresh").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performTouchInput { click() }
        assertEquals(1, refresh)
        snapshot(name)
        compose.onNodeWithTag("settings_access_row").performScrollTo().performClick()
        compose.onNodeWithTag("settings_recovery_status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_logout").performScrollTo().assertIsDisplayed()
    }
    @Test fun englishNarrowLarge18() = languageLayout("access-en-320-18", 1.8f)
    @Test @Config(qualifiers = "ru-rRU-w320dp-h640dp-mdpi")
    fun russianNarrowLarge18() = languageLayout("access-ru-320-18", 1.8f)
    @Test @Config(qualifiers = "uk-rUA-w320dp-h640dp-mdpi")
    fun ukrainianNarrowLarge18() = languageLayout("access-uk-320-18", 1.8f)
    @Test @Config(qualifiers = "w390dp-h844dp-mdpi")
    fun englishNormalLarge15() = languageLayout("access-en-390-15", 1.5f)
    @Test @Config(qualifiers = "ru-rRU-w390dp-h844dp-mdpi")
    fun russianNormalLarge15() = languageLayout("access-ru-390-15", 1.5f)
    @Test @Config(qualifiers = "uk-rUA-w390dp-h844dp-mdpi")
    fun ukrainianNormalLarge15() = languageLayout("access-uk-390-15", 1.5f)

    @Test fun offRecordLifetimeAndEveryCountPairNeverShowInventedOrCombinedBalance() {
        var state by mutableStateOf(SendAccessUiState())
        mount({ state })
        compose.onNodeWithTag("send_access_section").assertDoesNotExist()
        for (self in 0..2) for (other in 0..2) {
            compose.runOnIdle { state = SendAccessUiState(owner, value = value(self, other), hidden = false) }
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            compose.onNodeWithTag("send_access_self").performScrollTo()
                .assertTextEquals(context.getString(dev.hryshyn.remanence.R.string.send_access_self, self))
            compose.onNodeWithTag("send_access_other").performScrollTo()
                .assertTextEquals(context.getString(dev.hryshyn.remanence.R.string.send_access_other, other))
        }
        compose.runOnIdle { state = state.copy(value = value(0, 0, SendAccessMode.RECORD)) }
        textFits("send_access_beta")
        compose.onNodeWithTag("send_access_self").assertDoesNotExist()
        compose.onNodeWithTag("send_access_other").assertDoesNotExist()
        snapshot("access-record")
        compose.runOnIdle { state = state.copy(value = value(0, 0, life = true)) }
        textFits("send_access_lifetime")
        compose.onNodeWithTag("send_access_self").assertDoesNotExist()
        snapshot("access-lifetime")
    }

    @Test fun realRootSettingsLifecycleAndRefreshClearOldCountsAndDoNotRearmAnything() {
        val waiting = CompletableDeferred<SendAccessResult>()
        var calls = 0
        val model = vm(SendAccessPort { calls++; if (calls == 2) waiting.await() else SendAccessResult.Loaded(value()) })
        var destination by mutableStateOf<AppDestination>(AppDestination.Settings)
        var auth by mutableStateOf<AuthUiState>(AuthUiState.Authenticated(owner, "synthetic_owner"))
        compose.setContent {
            root = LocalView.current.rootView
            HoldTheme { RootScreen(auth, destination, {}, {}, settingsContent = {
                SettingsScreen("synthetic_owner", AppLocale.SYSTEM, {}, true,
                    { destination = AppDestination.Home }, { auth = AuthUiState.SignedOut },
                    sendAccessContent = { SendAccessSettings(owner, lease, model, {}) })
            }) }
        }
        compose.onNodeWithTag("send_access_self").performScrollTo().assertExists()
        compose.onNodeWithTag("send_access_refresh").performScrollTo().performClick()
        compose.onNodeWithTag("send_access_self").assertDoesNotExist()
        compose.onNodeWithTag("send_access_loading").performScrollTo().assertIsDisplayed()
        assertEquals(2, calls)
        compose.runOnIdle { waiting.complete(SendAccessResult.Failed(SendAccessFailure.UNAVAILABLE)) }
        textFits("send_access_unavailable")
        compose.onNodeWithTag("send_access_retry").performScrollTo().performClick()
        assertEquals(3, calls)
        compose.onNodeWithTag("settings_back").performClick()
        assertEquals(SendAccessUiState(), model.state.value)
        compose.runOnIdle { destination = AppDestination.Settings }
        compose.onNodeWithTag("send_access_self").performScrollTo().assertExists()
        assertEquals(4, calls)
        compose.runOnIdle { auth = AuthUiState.SignedOut }
        compose.onNodeWithTag("send_access_section").assertDoesNotExist()
        assertEquals(SendAccessUiState(), model.state.value)
    }
}

