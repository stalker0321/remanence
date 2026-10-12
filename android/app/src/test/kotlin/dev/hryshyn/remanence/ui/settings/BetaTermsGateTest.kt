package dev.hryshyn.remanence.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.hryshyn.remanence.core.data.network.SafetyState
import dev.hryshyn.remanence.ui.hold.HoldTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
class BetaTermsGateTest {
    @get:Rule val rule = createComposeRule()
    private val unaccepted = SafetyState("2026-10-11", false, emptyList())
    private val accepted = unaccepted.copy(terms_accepted = true)

    @Test fun successfulLegalLinkDoesNotClearFailedFetchOrRetry() {
        var fetches = 0
        var links = 0
        val retry = CompletableDeferred<SafetyState?>()
        rule.setContent { HoldTheme {
            BetaTermsGateWithActions("owner", { if (++fetches == 1) null else retry.await() },
                { true }, { links++; true }, {}) { Text("protected", Modifier.testTag("protected")) }
        } }
        rule.onNodeWithTag("beta_terms_retry").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("beta_terms_link").performScrollTo().performClick()
        rule.onNodeWithTag("beta_terms_retry").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithTag("beta_terms_retry").assertIsNotEnabled()
        rule.onNodeWithTag("beta_terms_continue").assertIsNotEnabled()
        assertEquals(2, fetches)
        assertEquals(1, links)
        rule.runOnIdle { retry.complete(unaccepted) }
        rule.onNodeWithTag("beta_terms_retry").assertDoesNotExist()
        rule.onNodeWithTag("protected").assertDoesNotExist()
    }

    @Test fun linkFailureHasSeparateCopyAndCannotCreateServerRetry() {
        rule.setContent { HoldTheme {
            BetaTermsGateWithActions("owner", { unaccepted }, { true }, { false }, {}) { Text("protected") }
        } }
        rule.onNodeWithTag("beta_privacy_link").performScrollTo().performClick()
        rule.onNodeWithTag("beta_terms_link_failed").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("beta_terms_retry").assertDoesNotExist()
        rule.onNodeWithTag("beta_terms_agreement").performScrollTo().performClick()
        rule.onNodeWithTag("beta_terms_continue").assertIsEnabled()
    }

    @Test fun acceptanceIsConfirmedByRepositoryNotByCheckboxOrPostSuccessAlone() {
        var fetches = 0
        var accepts = 0
        var confirmed = false
        rule.setContent { HoldTheme {
            BetaTermsGateWithActions("owner", { fetches++; if (confirmed) accepted else unaccepted },
                { accepts++; true }, { true }, {}) { Text("protected", Modifier.testTag("protected")) }
        } }
        rule.onNodeWithTag("beta_terms_agreement").performScrollTo().performClick()
        rule.onNodeWithTag("beta_terms_continue").performClick()
        rule.onNodeWithTag("protected").assertDoesNotExist()
        rule.onNodeWithTag("beta_terms_retry").performScrollTo().assertIsEnabled()
        assertEquals(1, accepts)
        rule.runOnIdle { confirmed = true }
        rule.onNodeWithTag("beta_terms_retry").performClick()
        rule.onNodeWithTag("protected").assertIsDisplayed()
        assertEquals(3, fetches)
    }

    @Test fun recreationRestoresCheckboxButRefetchesAuthoritativeState() {
        val restoration = StateRestorationTester(rule)
        var fetches = 0
        restoration.setContent { HoldTheme {
            BetaTermsGateWithActions("owner", { fetches++; unaccepted }, { false }, { true }, {}) { Text("protected") }
        } }
        rule.onNodeWithTag("beta_terms_agreement").performScrollTo().performClick().assertIsOn()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("beta_terms_agreement").performScrollTo().assertIsOn()
        assertEquals(2, fetches)
    }

    @Test fun ownerChangeClearsCheckboxAndRechecksConsent() {
        var owner by mutableStateOf("first")
        var fetches = 0
        rule.setContent { HoldTheme {
            BetaTermsGateWithActions(owner, { fetches++; unaccepted }, { false }, { true }, {}) { Text("protected") }
        } }
        rule.onNodeWithTag("beta_terms_agreement").performScrollTo().performClick().assertIsOn()
        rule.runOnIdle { owner = "second" }
        rule.onNodeWithTag("beta_terms_agreement").performScrollTo().assertIsOff()
        assertEquals(2, fetches)
    }

    @Test fun restorationUnderDifferentOwnerDoesNotRestoreOldAgreement() {
        val restoration = StateRestorationTester(rule)
        // Deliberately not Compose State: save the first owner's composition,
        // then restore it with a different authoritative owner.
        var owner = "first"
        restoration.setContent { HoldTheme {
            BetaTermsGateWithActions(owner, { unaccepted }, { false }, { true }, {}) { Text("protected") }
        } }
        rule.onNodeWithTag("beta_terms_agreement").performScrollTo().performClick().assertIsOn()
        owner = "second"
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("beta_terms_agreement").performScrollTo().assertIsOff()
    }

    @Test fun restorationUnderDifferentOwnerDropsBlockHandleAndExpansion() {
        val restoration = StateRestorationTester(rule)
        var owner = "first"
        restoration.setContent { HoldTheme {
            BetaSafetyPanelWithActions(owner, { accepted }, { true }, { _, _ -> true }, { true })
        } }
        rule.onNodeWithTag("settings_safety_row").performClick()
        rule.onNodeWithTag("settings_block_handle").performTextInput("friend")
        owner = "second"
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("settings_block_handle").assertDoesNotExist()
        rule.onNodeWithTag("settings_safety_row").performClick()
        rule.onNodeWithTag("settings_block_handle").assert(hasText("friend").not())
    }

    @Test fun safetyFetchFailureOffersBusyGuardedRetryAndPreservesInputOnRecreation() {
        val restoration = StateRestorationTester(rule)
        var fetches = 0
        val pending = CompletableDeferred<SafetyState?>()
        restoration.setContent { HoldTheme {
            BetaSafetyPanelWithActions("owner", {
                when (++fetches) { 1 -> null; 2 -> pending.await(); else -> accepted }
            }, { true }, { _, _ -> true }, { true })
        } }
        rule.onNodeWithTag("settings_safety_row").performClick()
        rule.onNodeWithTag("settings_block_handle").performTextInput("friend")
        rule.onNodeWithTag("settings_safety_fetch_failed").assertExists()
        rule.onNodeWithTag("settings_safety_retry").performClick().assertIsNotEnabled()
        assertEquals(2, fetches)
        rule.runOnIdle { pending.complete(accepted) }
        rule.onNodeWithTag("settings_safety_retry").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("settings_block_handle").assertTextContains("friend")
        assertEquals(3, fetches)
    }
}
