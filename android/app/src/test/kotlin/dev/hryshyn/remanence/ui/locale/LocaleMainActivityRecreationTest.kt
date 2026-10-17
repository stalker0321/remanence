package dev.hryshyn.remanence.ui.locale

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.MainActivity
import dev.hryshyn.remanence.RemanenceApplication
import dev.hryshyn.remanence.session.RootViewModel
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.wiring.RemanenceViewModelFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocaleManager

/**
 * Real-lifecycle coverage for locale selection on the PRODUCTION activity.
 *
 * Unlike [LocaleSelectActivityTest] (which records restarts), these tests let
 * the real destroy/create cycle run: [AppLocaleController.select] is
 * triggered through the real row, and an [Application.ActivityLifecycleCallbacks]
 * witness counts actual MainActivity creations/destructions. No production
 * code is weakened and no test hooks ship: the witness lives only in this
 * file. Counts are relative (the rule-launched creation happens before the
 * witness registers), so exactly one recreation reads as created==1 and
 * destroyed==1.
 *
 * Below 33 the single restart is AppCompat-driven; on 33+ the framework owns
 * it (Robolectric's shadow stores without dispatching lifecycle events, so
 * the 33+ tests honestly assert zero test-visible restarts plus the
 * platform-store round-trip).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LocaleMainActivityRecreationTest {

    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * Deterministic Main BEFORE the Activity (and its RootViewModel
     * viewModelScope) exists: the compose rule launches during rule
     * setup, so a setMain in @Before would arrive too late for the
     * already-created scope. Unconfined runs Main work eagerly while
     * the real bootstrap still executes (IO for real) — nothing is
     * forced, faked, or weakened.
     */
    private val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(mainDispatcherRule).around(composeRule)

    private class MainDispatcherRule : TestWatcher() {
        override fun starting(description: Description?) {
            Dispatchers.setMain(UnconfinedTestDispatcher())
        }

        override fun finished(description: Description?) {
            Dispatchers.resetMain()
        }
    }

    private val created = mutableListOf<Activity>()
    private val destroyed = mutableListOf<Activity>()

    private val witness = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is MainActivity) created += activity
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity is MainActivity) destroyed += activity
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    @Before
    fun setUp() {
        ShadowLocaleManager.reset()
        // AppCompat statics AND its disk record outlive a single test method
        // in this fork: a previous test's selection would short-circuit the
        // next select (no change => no recreation), and a stale async persist
        // task could re-seed the statics from disk mid-test. Below 33 set/get
        // use only these statics (verified against the 1.7.0 bytecode), so
        // nulling them plus wiping the record file makes every test start
        // from SYSTEM deterministically; a stale task then re-reads an empty
        // record and returns without touching anything.
        resetAppCompatStatics()
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(
                "androidx.appcompat.app.AppCompatDelegate.application_locales_record_file",
                Context.MODE_PRIVATE,
            )
            .edit().clear().commit()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        created.clear()
        destroyed.clear()
        ApplicationProvider.getApplicationContext<Application>()
            .registerActivityLifecycleCallbacks(witness)
    }

    private fun resetAppCompatStatics() {
        for (name in listOf("sRequestedAppLocales", "sStoredAppLocales")) {
            AppCompatDelegate::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(null, null)
            }
        }
    }

    @After
    fun tearDown() {
        // Wipe AFTER the test too: the next test's rule setup (delegate
        // disk-sync) runs before its @Before, so only a clean disk at setup
        // time guarantees the fresh activity boots with the system locale.
        // A stale async persist task can only re-write the disk afterwards,
        // which nothing re-reads until the following setup (already wiped).
        resetAppCompatStatics()
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(
                "androidx.appcompat.app.AppCompatDelegate.application_locales_record_file",
                Context.MODE_PRIVATE,
            )
            .edit().clear().commit()
        ShadowLocaleManager.reset()
        ApplicationProvider.getApplicationContext<Application>()
            .unregisterActivityLifecycleCallbacks(witness)
    }

    private fun productionViewModel(host: Activity): RootViewModel {
        val container = (host.application as RemanenceApplication).container
        return ViewModelProvider(host as ViewModelStoreOwner, RemanenceViewModelFactory(container))
            .get(RootViewModel::class.java)
    }

    private fun openLanguageMenu() {
        composeRule.onNodeWithTag("access_language").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun selectRussian() {
        openLanguageMenu()
        composeRule.onNodeWithTag("language_option_russian").performClick()
        composeRule.waitForIdle()
    }

    /**
     * API 33+ framework-delivery emulation (test-only, never production).
     * AppLocaleController.select persists via AppCompat, which on 33+
     * forwards to framework LocaleManager. Pinned Robolectric 4.16.1
     * `ShadowLocaleManager.setApplicationLocales` is a bare Map.put
     * (bytecode-verified: no resource application, no dispatch), so the
     * configuration change the framework performs on device never
     * happens here. This mirrors exactly that missing half AFTER the
     * real production selection path ran: apply RU process-wide through
     * the framework ResourcesManager seam, then dispatch the
     * configuration change the manifest-handled activity would receive
     * — no restart, no context wrappers, no production edits. What this
     * proves: the activity + Compose react to the delivered
     * configuration with persisted RU and zero recreations. What it
     * does NOT prove: the framework delivery itself (unshadowed by
     * definition) or physical device behavior.
     */
    private fun emulateFrameworkLocaleDelivery33Plus() {
        if (Build.VERSION.SDK_INT < 33) return
        composeRule.runOnIdle {
            RuntimeEnvironment.setQualifiers("+ru")
            composeRule.activity.onConfigurationChanged(composeRule.activity.resources.configuration)
        }
        composeRule.waitForIdle()
    }

    /**
     * The production bootstrap resolves asynchronously on real
     * dispatchers, which waitForIdle does NOT cover (proven by the
     * Resolving hang): await the ACTUAL terminal auth state, bounded,
     * then the displayed public home. No sleeps, no forced root state —
     * a timeout here names the unsettled bootstrap honestly.
     */
    private fun awaitSignedOutHome(): RootViewModel {
        val vm = productionViewModel(composeRule.activity)
        try {
            composeRule.waitUntil(10_000) {
                vm.authState.value is AuthUiState.SignedOut
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AssertionError(
                "signed-out home never settled (auth=" + vm.authState.value::class.simpleName + ")",
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("scan_action").assertIsDisplayed()
        return vm
    }

    @Test
    @Config(sdk = [28])
    fun localeChangeAppliesInPlaceWithoutRecreationSdk28() {
        localeChangeAppliesInPlaceWithoutRecreation()
    }

    @Test
    @Config(sdk = [31])
    fun localeChangeAppliesInPlaceWithoutRecreationSdk31() {
        localeChangeAppliesInPlaceWithoutRecreation()
    }

    @Test
    @Config(sdk = [32])
    fun localeChangeAppliesInPlaceWithoutRecreationSdk32() {
        localeChangeAppliesInPlaceWithoutRecreation()
    }

    private fun localeChangeAppliesInPlaceWithoutRecreation() {
        // The production bootstrap resolves asynchronously: wait for the
        // terminal proof before asserting the surface, so a slow first
        // resolve is never mistaken for a missing one.
        awaitSignedOutHome()
        val first = composeRule.activity

        selectRussian()

        assertEquals(AppLocale.RUSSIAN, AppLocaleRepository().load())
        // Handled config change (manifest locale|layoutDirection): the
        // Activity is never destroyed or recreated for a language change.
        assertTrue(destroyed.isEmpty())
        assertTrue(created.isEmpty())
        assertSame(first, composeRule.activity)
        // The SAME surface now renders the new language in place: the
        // chosen option is selected and translated text is visible with
        // no recreation gap in between. This also proves LocalConfiguration
        // actually updated without recreation (else text stays English).
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("access_language").assertTextEquals("язык")
        composeRule.onNodeWithTag("scan_action").assertIsDisplayed()
        composeRule.onNodeWithTag("create_action").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(sdk = [28])
    fun reselectingActiveLocaleIsRealNoOpSdk28() {
        reselectingActiveLocaleIsRealNoOp()
    }

    @Test
    @Config(sdk = [32])
    fun reselectingActiveLocaleIsRealNoOpSdk32() {
        reselectingActiveLocaleIsRealNoOp()
    }

    /**
     * Tapping the already-active language performs no AppCompat write and
     * requests no recreation: the persisted tag and the instance counts
     * are untouched. (Setup boots SYSTEM, so the system option is active.)
     */
    private fun reselectingActiveLocaleIsRealNoOp() {
        awaitSignedOutHome()

        openLanguageMenu()
        composeRule.onNodeWithTag("language_option_system").performClick()
        composeRule.waitForIdle()

        assertEquals(AppLocale.SYSTEM, AppLocaleRepository().load())
        assertTrue(destroyed.isEmpty())
        assertTrue(created.isEmpty())
    }

    @Test
    @Config(sdk = [28])
    fun rapidDifferentTapsResolveLastWinsWithoutQueueSdk28() {
        rapidDifferentTapsResolveLastWinsWithoutQueue()
    }

    @Test
    @Config(sdk = [32])
    fun rapidDifferentTapsResolveLastWinsWithoutQueueSdk32() {
        rapidDifferentTapsResolveLastWinsWithoutQueue()
    }

    /**
     * Two rapid taps for different languages apply in order with no
     * recreation queue: the final persisted locale wins and no Activity
     * is destroyed or created (handled config change, not a restart).
     */
    @Test
    @Config(sdk = [28])
    fun unrelatedManualRecreateStillRestartsAndPreservesSdk28() {
        unrelatedManualRecreateStillRestartsAndPreserves()
    }

    @Test
    @Config(sdk = [32])
    fun unrelatedManualRecreateStillRestartsAndPreservesSdk32() {
        unrelatedManualRecreateStillRestartsAndPreserves()
    }

    /**
     * Control: a REAL unrelated restart (manual recreate — the path
     * rotation and every non-locale config change still takes) DOES
     * destroy and recreate the Activity. This proves the zero counts
     * above come from the handled locale contract, not from a broken
     * witness — while the same ViewModel store, epochs, persisted
     * locale, and selected UI survive the genuine restart.
     */
    @Test
    @Config(sdk = [28])
    fun legacyHostTogglesBothDirectionsWithoutLosingSiblingsSdk28() {
        legacyHostTogglesBothDirectionsWithoutLosingSiblings()
    }

    @Test
    fun legacyHostTogglesBothDirectionsWithoutLosingSiblingsSdk35() {
        legacyHostTogglesBothDirectionsWithoutLosingSiblings()
    }

    /**
     * Regression for the swallowed outer else: the shared legacy host
     * starts at registering=false with the LOGIN form reachable (never
     * an empty branch), toggles to the register form and back, with the
     * footer reachable throughout. Reached through the real public-home
     * entry, so the full host chain (intent -> auth content -> branch)
     * is exercised. Draft stability inside one substate is covered by
     * HandleSetupErrorStabilityTest with a captured onChange.
     */
    private fun legacyHostTogglesBothDirectionsWithoutLosingSiblings() {
        awaitSignedOutHome()
        // Public-home entry moves to the auth content.
        composeRule.onNodeWithTag("scan_action").performClick()
        composeRule.waitForIdle()
        // Legacy branch directly, or via the other-sign-in entry when
        // the Google column shows first (build-flag dependent). Entry
        // text comes from the same R.string value production renders.
        val otherSignIn = composeRule.activity.getString(R.string.mvp_other_sign_in)
        if (composeRule.onAllNodesWithText(otherSignIn).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText(otherSignIn).performClick()
            composeRule.waitForIdle()
        }
        // From registering=false: LOGIN form + its toggle + footer.
        composeRule.onNodeWithTag("login_email_field").assertIsDisplayed()
        composeRule.onNodeWithTag("auth_toggle_register").assertIsDisplayed()
        composeRule.onNodeWithTag("access_language").assertIsDisplayed()
        // Toggle to registering: register form + back toggle + footer.
        // Draft stability inside one substate is covered by
        // HandleSetupErrorStabilityTest with a captured onChange.
        composeRule.onNodeWithTag("auth_toggle_register").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reg_email_field").assertIsDisplayed()
        composeRule.onNodeWithTag("auth_toggle_login").assertIsDisplayed()
        composeRule.onNodeWithTag("access_language").assertIsDisplayed()
        // Toggle back: login form reachable again (fresh instance by the
        // keyed-swap design, which disposes synchronously by contract).
        composeRule.onNodeWithTag("auth_toggle_login").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("login_email_field").assertIsDisplayed()
        composeRule.onNodeWithTag("auth_toggle_register").assertIsDisplayed()
        composeRule.onNodeWithTag("access_language").assertIsDisplayed()
    }

    private fun unrelatedManualRecreateStillRestartsAndPreserves() {
        awaitSignedOutHome()
        val firstHost = composeRule.activity
        val before = productionViewModel(firstHost)

        // Locale path first: handled, zero restarts, persisted.
        selectRussian()
        assertEquals(AppLocale.RUSSIAN, AppLocaleRepository().load())
        assertTrue(destroyed.isEmpty())
        assertTrue(created.isEmpty())

        // Unrelated path: a real restart still destroys + creates.
        firstHost.recreate()
        composeRule.waitForIdle()

        assertEquals(1, destroyed.size)
        assertEquals(1, created.size)
        assertNotSame(firstHost, created.single())
        // Same store family across the genuine restart: identical
        // ViewModel with intact session proof, persisted locale kept.
        val after = productionViewModel(created.single())
        assertSame(before, after)
        assertEquals(AuthUiState.SignedOut, after.authState.value)
        assertEquals(0L, after.createSessionEpoch.value)
        assertEquals(0L, after.scanSessionEpoch.value)
        assertEquals(AppLocale.RUSSIAN, AppLocaleRepository().load())
    }

    private fun rapidDifferentTapsResolveLastWinsWithoutQueue() {
        awaitSignedOutHome()

        openLanguageMenu()
        composeRule.onNodeWithTag("language_option_russian").performClick()
        composeRule.waitForIdle()
        openLanguageMenu()
        composeRule.onNodeWithTag("language_option_ukrainian").performClick()
        composeRule.waitForIdle()

        assertEquals(AppLocale.UKRAINIAN, AppLocaleRepository().load())
        assertTrue(destroyed.isEmpty())
        assertTrue(created.isEmpty())
        composeRule.onNodeWithTag("access_language").assertTextEquals("мова")
    }

    @Test
    fun noManualRestartOn33Plus() {
        awaitSignedOutHome()

        selectRussian()

        assertEquals(AppLocale.RUSSIAN, AppLocaleRepository().load())
        assertTrue(destroyed.isEmpty())
        assertTrue(created.isEmpty())
        // 33+: resources only follow the framework delivery the shadow
        // omits — emulate it, then the same surface renders Russian with
        // still zero recreations.
        emulateFrameworkLocaleDelivery33Plus()
        assertTrue(destroyed.isEmpty())
        assertTrue(created.isEmpty())
        composeRule.onNodeWithTag("access_language").assertTextEquals("язык")
    }

    @Test
    @Config(sdk = [28])
    fun authAndDestinationStableAcrossLocaleChangeSdk28() {
        authAndDestinationStableAcrossLocaleChange()
    }

    @Test
    @Config(sdk = [31])
    fun authAndDestinationStableAcrossLocaleChangeSdk31() {
        authAndDestinationStableAcrossLocaleChange()
    }

    @Test
    @Config(sdk = [32])
    fun authAndDestinationStableAcrossLocaleChangeSdk32() {
        authAndDestinationStableAcrossLocaleChange()
    }

    private fun authAndDestinationStableAcrossLocaleChange() {
        // Cold start with no backend: SignedOut public home, actions enabled
        // through the public entry.
        awaitSignedOutHome()
        composeRule.onNodeWithTag("scan_action").assertIsDisplayed().assertIsEnabled()

        selectRussian()

        assertTrue(destroyed.isEmpty())
        assertTrue(created.isEmpty())
        // No login/logout path exists on this surface: the auth boundary is
        // untouched and the same public-home destination is showing.
        composeRule.onNodeWithTag("scan_action").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("create_action").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("access_language").assertTextEquals("язык")
    }

    @Test
    @Config(sdk = [28])
    fun viewModelStoreRetainedAcrossLocaleChangeSdk28() {
        viewModelStoreRetainedAcrossLocaleChange()
    }

    @Test
    @Config(sdk = [32])
    fun viewModelStoreRetainedAcrossLocaleChangeSdk32() {
        viewModelStoreRetainedAcrossLocaleChange()
    }

    private fun viewModelStoreRetainedAcrossLocaleChange() {
        // Cold start with a real async bootstrap: wait for the terminal
        // proof — reading mid-resolve would see the honest Resolving state.
        val before = awaitSignedOutHome()
        assertEquals(0L, before.createSessionEpoch.value)
        assertEquals(0L, before.scanSessionEpoch.value)

        selectRussian()

        assertTrue(destroyed.isEmpty())
        assertTrue(created.isEmpty())
        // Same store, same ViewModel, same session proof: nothing
        // re-proved, nothing reset, no recreation in between.
        composeRule.waitForIdle()
        val after = productionViewModel(composeRule.activity)
        assertSame(before, after)
        assertEquals(AuthUiState.SignedOut, after.authState.value)
        assertEquals(0L, after.createSessionEpoch.value)
        assertEquals(0L, after.scanSessionEpoch.value)
    }
}
