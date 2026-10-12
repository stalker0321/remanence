package dev.hryshyn.remanence.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.ui.hold.HoldTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h560dp-mdpi")
class BetaTermsPageTest {
    @get:Rule val rule = createComposeRule()
    @Test fun largeTextConsentLinksAndBackRemainReachable() {
        var agree by mutableStateOf(false)
        var continued = 0; var back = 0; var terms = 0; var privacy = 0
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.8f)) {
            HoldTheme { BetaTermsPage(agree, false, true, false, { agree = it }, { back++ },
                { terms++ }, { privacy++ }, {}, { continued++ }) }
        } }
        rule.onNodeWithTag("beta_terms_continue").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithTag("beta_terms_link").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("beta_privacy_link").performScrollTo().performClick()
        rule.onNodeWithTag("beta_terms_agreement").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("beta_terms_continue").assertIsDisplayed().assertIsEnabled().performClick()
        rule.onNodeWithTag("access_back").assertIsDisplayed().performClick()
        assertEquals(1, continued); assertEquals(1, back); assertEquals(1, terms); assertEquals(1, privacy)
    }
    @Test fun consentCannotBypassMissingBackendOrInFlightRequest() {
        var available by mutableStateOf(false); var busy by mutableStateOf(false)
        rule.setContent { HoldTheme { BetaTermsPage(true, busy, available, true, {}, {}, {}, {}, {}, {}) } }
        rule.onNodeWithTag("beta_terms_continue").assertIsNotEnabled()
        rule.runOnIdle { available = true; busy = true }
        rule.onNodeWithTag("beta_terms_continue").assertIsNotEnabled()
        rule.onNodeWithTag("beta_terms_retry").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("beta_terms_agreement").performScrollTo().assertIsNotEnabled()
        rule.runOnIdle { busy = false }
        rule.onNodeWithTag("beta_terms_continue").assertIsEnabled()
    }
}
