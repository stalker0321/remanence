package dev.hryshyn.remanence.ui.locale

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.MainActivity
import dev.hryshyn.remanence.RemanenceApplication
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.data.fingerprints.EncryptedFingerprintStore
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.scan.ScanCandidateIndex
import dev.hryshyn.remanence.ui.scan.ScanMatchUiState
import dev.hryshyn.remanence.ui.scan.ScanTerminalState
import dev.hryshyn.remanence.ui.scan.ScanViewModel
import dev.hryshyn.remanence.wiring.KekBoundSecretSealer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocaleManager

/**
 * Scan-session evidence across REAL production-Activity recreation.
 *
 * What is real here: the [MainActivity] lifecycle (witness-counted
 * destroy/create), the real [AppLocaleController.select] path, a real
 * [ScanViewModel] (production class, production defaults) with a real
 * [EncryptedFingerprintStore] (production class; software KEK per the
 * established container-test pattern, since AndroidKeyStore has no
 * Robolectric shadow), a real Room DAO, and the real grant authority.
 * The only test wiring is what has no production equivalent under test:
 * no authenticated identity exists (SignedOut, like production cold start),
 * so the identity provider returns null and matching flows are never
 * entered. Before any recreation the session is explicitly begun, so these
 * tests prove an ACTIVE session survives the locale restart: epoch,
 * terminal, and match state intact, same ViewModel instance, and a
 * same-epoch re-begin is a no-op (no rebind).
 *
 * Honest boundary: camera hardware bind/unbind cannot execute on the JVM
 * (no CameraX support under Robolectric); that half of disposal safety
 * stays device-verified. Everything the locale path can disturb — session
 * epochs, generations, terminal/match state, controller resets — is proven
 * untouched here. Authenticated sessions and live grants are impossible
 * without AndroidKeyStore (no shadow in 4.16.1) and are covered on real
 * seams by [LocaleRecreationSafetyTest] instead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LocaleScanSessionRecreationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        ShadowLocaleManager.reset()
        resetAppCompatStatics()
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(
                "androidx.appcompat.app.AppCompatDelegate.application_locales_record_file",
                android.content.Context.MODE_PRIVATE,
            )
            .edit().clear().commit()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        created.clear()
        destroyed.clear()
        ApplicationProvider.getApplicationContext<Application>()
            .registerActivityLifecycleCallbacks(witness)
    }

    @After
    fun tearDown() {
        resetAppCompatStatics()
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(
                "androidx.appcompat.app.AppCompatDelegate.application_locales_record_file",
                android.content.Context.MODE_PRIVATE,
            )
            .edit().clear().commit()
        ShadowLocaleManager.reset()
        ApplicationProvider.getApplicationContext<Application>()
            .unregisterActivityLifecycleCallbacks(witness)
        Dispatchers.resetMain()
    }

    private fun resetAppCompatStatics() {
        for (name in listOf("sRequestedAppLocales", "sStoredAppLocales")) {
            AppCompatDelegate::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(null, null)
            }
        }
    }

    private fun scanViewModel(host: Activity): ScanViewModel {
        val container = (host.application as RemanenceApplication).container
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ScanViewModel(
                    persistence = EncryptedFingerprintStore(
                        roots = AccountScopedFileRoots(container.appContext.filesDir),
                        sealer = KekBoundSecretSealer(
                            SoftwareKekBoundary(),
                            KekBoundSecretSealer.FINGERPRINT_SEALING_ALIAS,
                        ),
                        dao = container.database.recognitionFingerprintDao(),
                        ownerUserIdProvider = { "7d111111-2222-4333-8444-555555555555" },
                    ),
                    database = container.database,
                    profile = RecognitionProfile.postcardSiftRootSiftV1(),
                    identityProvider = { null },
                    trustedSenderKeys = container.trustedSenderKeys,
                    presentationGrants = PresentationGrantAuthority(),
                    candidateIndexProvider = { _ ->
                        ScanCandidateIndex(candidates = emptyList())
                    },
                    incomingPresentationPreparation = null,
                ) as T
        }
        return ViewModelProvider(host as ViewModelStoreOwner, factory).get(ScanViewModel::class.java)
    }

    private fun selectRussian() {
        composeRule.onNodeWithTag("language_option_russian").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    @Config(sdk = [28])
    fun activeScanSessionSurvivesRealRecreationSdk28() {
        activeScanSessionSurvivesRealRecreation()
    }

    @Test
    @Config(sdk = [32])
    fun activeScanSessionSurvivesRealRecreationSdk32() {
        activeScanSessionSurvivesRealRecreation()
    }

    private fun activeScanSessionSurvivesRealRecreation() {
        val vm = scanViewModel(composeRule.activity)
        vm.beginSession(7L)
        assertEquals(7L, vm.initializedEpoch.value)
        assertEquals(ScanTerminalState.Idle, vm.terminal.value)
        assertEquals(ScanMatchUiState.AwaitingCapture, vm.matchState.value)

        selectRussian()

        assertEquals(AppLocale.RUSSIAN, AppLocaleRepository().load())
        assertEquals(1, destroyed.size)
        assertEquals(1, created.size)
        // Same session object, untouched state: no disposal ran and nothing
        // re-initialized during the locale restart.
        val after = scanViewModel(created.single())
        assertSame(vm, after)
        assertEquals(7L, after.initializedEpoch.value)
        assertEquals(ScanTerminalState.Idle, after.terminal.value)
        assertEquals(ScanMatchUiState.AwaitingCapture, after.matchState.value)
        // Same-epoch re-begin (what rotation/recomposition does) rebinds
        // nothing: still the same initialized epoch afterwards.
        after.beginSession(7L)
        assertEquals(7L, after.initializedEpoch.value)
        assertEquals(ScanTerminalState.Idle, after.terminal.value)
    }

    @Test
    fun noSessionDisturbanceOn33Plus() {
        val vm = scanViewModel(composeRule.activity)
        vm.beginSession(7L)

        selectRussian()

        assertEquals(AppLocale.RUSSIAN, AppLocaleRepository().load())
        assertTrue(destroyed.isEmpty())
        assertTrue(created.isEmpty())
        assertEquals(7L, vm.initializedEpoch.value)
        assertEquals(ScanTerminalState.Idle, vm.terminal.value)
        composeRule.onNodeWithTag("language_option_russian").assertIsSelected()
    }
}
