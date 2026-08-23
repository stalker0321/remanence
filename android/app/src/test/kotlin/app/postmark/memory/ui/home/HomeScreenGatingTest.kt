package app.postmark.memory.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class AccountCapabilityStateTest {

    @Test
    fun onlyCryptoReadyEnablesActions() {
        assertFalse(AccountCapabilityState.NotAuthenticated.actionsEnabled)
        assertFalse(AccountCapabilityState.RecoveryRequired.actionsEnabled)
        assertTrue(
            AccountCapabilityState.CryptoReady(
                userId = "1f0a1234-5678-4abc-9def-aabbccdd1001",
                handle = "mykola",
            ).actionsEnabled,
        )
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeScreenGatingTest {

    private fun setContent(capability: AccountCapabilityState) {
        composeRule.setContent {
            MaterialTheme {
                HomeScreen(BackendHealthUiState.AVAILABLE, capability)
            }
        }
    }

    @get:org.junit.Rule
    val composeRule = createComposeRule()

    @Test
    fun notAuthenticatedKeepsCreateAndScanDisabled() {
        setContent(AccountCapabilityState.NotAuthenticated)
        composeRule.onNodeWithTag("create_action").assertIsNotEnabled()
        composeRule.onNodeWithTag("scan_action").assertIsNotEnabled()
    }

    @Test
    fun recoveryRequiredShowsExplanationAndDisablesActions() {
        setContent(AccountCapabilityState.RecoveryRequired)
        composeRule.onNodeWithTag("create_action").assertIsNotEnabled()
        composeRule.onNodeWithTag("scan_action").assertIsNotEnabled()
        composeRule.onNodeWithTag("home_recovery_note").assertIsDisplayed()
    }

    @Test
    fun cryptoReadyAccountEnablesCreateAndScan() {
        setContent(
            AccountCapabilityState.CryptoReady(
                userId = "1f0a1234-5678-4abc-9def-aabbccdd1001",
                handle = "mykola",
            ),
        )
        composeRule.onNodeWithTag("create_action").assertIsEnabled()
        composeRule.onNodeWithTag("scan_action").assertIsEnabled()
    }
}
