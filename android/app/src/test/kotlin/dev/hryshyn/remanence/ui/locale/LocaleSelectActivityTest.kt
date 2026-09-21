package dev.hryshyn.remanence.ui.locale

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.ScanGrantManager
import dev.hryshyn.remanence.session.RootViewModel
import dev.hryshyn.remanence.session.SessionState
import dev.hryshyn.remanence.session.SessionStateResolver
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.capsule.PresentationGrantAuthority
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.home.AccountCapabilityState
import dev.hryshyn.remanence.ui.home.BackendHealthUiState
import dev.hryshyn.remanence.ui.home.HomeScreen
import dev.hryshyn.remanence.ui.navigation.AppDestination
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocaleManager

/**
 * Activity-level traversal of the real locale-selection seam
 * ([AppLocaleController.select]) on a real [AppCompatActivity] host.
 *
 * The harness records `recreate()` instead of executing the framework
 * destroy/create cycle, so every restart count below is deterministic: what
 * is proven is that one `select()` requests exactly one restart on every
 * tier — AppCompat-driven below 33, framework-owned on 33+ (where our code
 * requests none). There is no manual restart anywhere, so camera/session
 * disposal paths run exactly once per selection. The tests also prove
 * `select()` itself never touches auth state, flow epochs, or live capsule
 * grants. Framework destroy/create mechanics and ViewModelStore retention
 * stay the platform's tested responsibility.
 *
 * Below 33 the AppCompat store is a process static (no delegate or platform
 * involvement); on 33+ the attached delegate bridges to the shadow
 * LocaleManager, so the 33+ tests prove the platform round-trip too.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LocaleSelectActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<LocaleSelectHarnessActivity>()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class NoopResolver : SessionStateResolver {
        override suspend fun bootstrap(): SessionState =
            SessionState.Active("7d111111-2222-4333-8444-555555555555", "mykola", true, true)

        override suspend fun logout(): SessionState = SessionState.SignedOut
    }

    private class LocaleVmFactory(
        private val authority: PresentationGrantAuthority,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RootViewModel(
                NoopResolver(),
                presentationGrants = authority,
                clockMillis = { 1_000L },
            ) as T
    }

    private val capsuleA = UUID.fromString("7a111111-2222-4333-8444-555555555555")
    private val owner = UserId(UUID.fromString("7d111111-2222-4333-8444-555555555555"))

    private fun freshAuthority() = PresentationGrantAuthority(ScanGrantManager(clockMillis = { 1_000L }))

    private fun viewModel(authority: PresentationGrantAuthority): RootViewModel =
        ViewModelProvider(composeRule.activity, LocaleVmFactory(authority))
            .get(RootViewModel::class.java)

    private fun PresentationGrantAuthority.issueOutbox(capsuleId: UUID) = issue(
        ownerUserId = owner,
        capsuleId = capsuleId,
        source = CapsulePresentationSource.OUTBOX,
        scanGeneration = 0,
        expectedEpoch = currentEpoch(),
    )

    @Test
    @Config(sdk = [28])
    fun selectOnAuthenticatedHomePersistsAndRestartsOnceSdk28() {
        selectOnAuthenticatedHomePersistsAndRestartsOnce()
    }

    @Test
    @Config(sdk = [32])
    fun selectOnAuthenticatedHomePersistsAndRestartsOnceSdk32() {
        selectOnAuthenticatedHomePersistsAndRestartsOnce()
    }

    private fun selectOnAuthenticatedHomePersistsAndRestartsOnce() {
        val host = composeRule.activity
        host.recreations = 0
        val tapped = mutableListOf<AppLocale>()
        composeRule.setContent {
            var current by remember { mutableStateOf(AppLocaleRepository().load()) }
            HoldTheme {
                HomeScreen(
                    state = BackendHealthUiState.AVAILABLE,
                    accountCapability = AccountCapabilityState.CryptoReady("uid", "mykola"),
                    appLocale = current,
                    onLocaleSelected = { locale ->
                        tapped += locale
                        current = AppLocaleController.select(locale)
                    },
                )
            }
        }
        composeRule.onNodeWithTag("language_option_russian").performScrollTo().performClick()

        assertEquals(listOf(AppLocale.RUSSIAN), tapped)
        assertEquals(AppLocale.RUSSIAN, AppLocaleRepository().load())
        assertEquals("ru", AppLocaleRepository().currentTag())
        assertEquals(1, host.recreations)
        composeRule.onNodeWithTag("language_option_russian").assertIsSelected()
        composeRule.onNodeWithTag("create_action").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_action").performScrollTo().assertIsDisplayed()

        // Re-selecting the active locale requests no restart at all.
        composeRule.onNodeWithTag("language_option_russian").performScrollTo().performClick()
        assertEquals(1, host.recreations)
        assertEquals(AppLocale.RUSSIAN, AppLocaleRepository().load())
    }

    @Test
    @Config(sdk = [28])
    fun scanSessionEpochStableAcrossRealSelectSdk28() = runTest {
        scanSessionEpochStableAcrossRealSelect()
    }

    @Test
    @Config(sdk = [32])
    fun scanSessionEpochStableAcrossRealSelectSdk32() = runTest {
        scanSessionEpochStableAcrossRealSelect()
    }

    private suspend fun scanSessionEpochStableAcrossRealSelect() {
        val host = composeRule.activity
        host.recreations = 0
        val authority = freshAuthority()
        val vm = viewModel(authority)
        vm.resolveNow()
        vm.openScan()
        val epoch = vm.scanSessionEpoch.value

        composeRule.setContent {
            HoldTheme {
                LanguageSwitchRow(
                    current = AppLocaleRepository().load(),
                    onSelect = { locale -> AppLocaleController.select(locale) },
                )
            }
        }
        composeRule.onNodeWithTag("language_option_ukrainian").performClick()

        assertEquals(AppLocale.UKRAINIAN, AppLocaleRepository().load())
        assertEquals(1, host.recreations)
        assertEquals(epoch, vm.scanSessionEpoch.value)
        assertEquals(AppDestination.Scan, vm.destination.value)
    }

    @Test
    @Config(sdk = [28])
    fun liveGrantSurvivesRealSelectSdk28() = runTest {
        liveGrantSurvivesRealSelect()
    }

    @Test
    @Config(sdk = [32])
    fun liveGrantSurvivesRealSelectSdk32() = runTest {
        liveGrantSurvivesRealSelect()
    }

    private suspend fun liveGrantSurvivesRealSelect() {
        val host = composeRule.activity
        host.recreations = 0
        val authority = freshAuthority()
        val vm = viewModel(authority)
        vm.resolveNow()
        val grant = authority.issueOutbox(capsuleA)
        vm.openCapsuleWithGrant(grant.grantId.toString())

        composeRule.setContent {
            HoldTheme {
                LanguageSwitchRow(
                    current = AppLocaleRepository().load(),
                    onSelect = { locale -> AppLocaleController.select(locale) },
                )
            }
        }
        composeRule.onNodeWithTag("language_option_english").performClick()

        assertEquals(AppLocale.ENGLISH, AppLocaleRepository().load())
        assertEquals(1, host.recreations)
        assertNotNull(vm.presentationGrantFor(grant.grantId.toString()))
        assertEquals(capsuleA.toString(), vm.capsuleIdFor(grant.grantId.toString()))
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), vm.destination.value)
    }

    @Test
    fun selectPushesToPlatformStoreWithoutManualRecreate() {
        val host = composeRule.activity
        host.recreations = 0
        ShadowLocaleManager.reset()

        composeRule.setContent {
            var current by remember { mutableStateOf(AppLocaleRepository().load()) }
            HoldTheme {
                LanguageSwitchRow(
                    current = current,
                    onSelect = { locale ->
                        current = AppLocaleController.select(locale)
                    },
                )
            }
        }
        composeRule.onNodeWithTag("language_option_russian").performClick()

        assertEquals(AppLocale.RUSSIAN, AppLocaleRepository().load())
        assertEquals("ru", AppLocaleRepository().currentTag())
        assertEquals(0, host.recreations)
        composeRule.onNodeWithTag("language_option_russian").assertIsSelected()
    }

    @Test
    fun systemResetClearsPlatformStoreWithoutManualRecreate() {
        val host = composeRule.activity
        host.recreations = 0
        ShadowLocaleManager.reset()

        composeRule.setContent {
            var current by remember { mutableStateOf(AppLocaleRepository().load()) }
            HoldTheme {
                LanguageSwitchRow(
                    current = current,
                    onSelect = { locale ->
                        current = AppLocaleController.select(locale)
                    },
                )
            }
        }
        composeRule.onNodeWithTag("language_option_ukrainian").performClick()
        assertEquals(AppLocale.UKRAINIAN, AppLocaleRepository().load())
        composeRule.onNodeWithTag("language_option_system").performClick()

        assertEquals(AppLocale.SYSTEM, AppLocaleRepository().load())
        assertTrue(AppLocaleRepository().currentTag().isEmpty())
        assertEquals(0, host.recreations)
    }

    @Test
    fun storedLocaleSurvivesToNextReadAfterSelect() {
        val host = composeRule.activity
        host.recreations = 0
        ShadowLocaleManager.reset()
        AppLocaleController.select(AppLocale.RUSSIAN)

        composeRule.setContent {
            HoldTheme {
                LanguageSwitchRow(
                    current = AppLocaleRepository().load(),
                    onSelect = { locale -> AppLocaleController.select(locale) },
                )
            }
        }

        composeRule.onNodeWithTag("language_option_russian").assertIsSelected()
    }
}

/**
 * AppCompat host for locale-selection traversal. Records `recreate()` instead
 * of executing the framework destroy/create cycle so restart counts are
 * asserted deterministically; retention across real recreation stays the
 * platform's responsibility.
 */
class LocaleSelectHarnessActivity : AppCompatActivity() {
    var recreations: Int = 0

    override fun recreate() {
        recreations += 1
    }
}
