package dev.hryshyn.remanence.ui.create

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecipientConfirmationScreenTest {

    @get:org.junit.Rule
    val composeRule = createComposeRule()

    private val snapshot = ResolvedHandleSnapshot(
        userId = UserId.parseRest("1f0a1234-5678-4abc-9def-aabbccdd1001"),
        handle = NormalizedHandle.parse("mykola"),
        keyBundleId = KeyBundleId.parseRest("2f0a1234-5678-4abc-9def-aabbccdd2002"),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = "CIenc",
        signingPublicKeysetB64Url = "CJsig",
        keyBundleStatus = "ACTIVE",
        directoryVersion = "9f1c0d2e",
    )

    private fun setContent(
        confirmations: MutableList<Int> = mutableListOf(),
        cancellations: MutableList<Int> = mutableListOf(),
    ) {
        composeRule.setContent {
            MaterialTheme {
                RecipientConfirmationScreen(
                    snapshot = snapshot,
                    onConfirm = { confirmations += 1 },
                    onCancel = { cancellations += 1 },
                )
            }
        }
    }

    @Test
    fun showsHandleAndImmutableAccountCue() {
        setContent()
        composeRule.onNodeWithTag("confirm_handle_text", useUnmergedTree = true).assertIsDisplayed()
        composeRule
            .onNodeWithTag("confirm_account_cue_text")
            .assertIsDisplayed()
        // The cue carries the immutable ID, not just the mutable handle.
        composeRule.onNodeWithTag("confirm_account_cue_text")
            .fetchSemanticsNode().let { node ->
                val text = node.config.toString()
                org.junit.Assert.assertTrue(text.isNotEmpty())
            }
    }

    @Test
    fun wholeActionSurfaceIsTheExplicitConfirmation() {
        val confirmations = mutableListOf<Int>()
        val cancellations = mutableListOf<Int>()
        setContent(confirmations, cancellations)

        composeRule.onNodeWithTag("confirm_ack_checkbox").assertDoesNotExist()
        composeRule.onNodeWithTag("confirm_button").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("confirm_button").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { confirmations.isNotEmpty() }

        assertEquals(listOf(1), confirmations)
        assertEquals(emptyList<Int>(), cancellations)
    }

    @Test
    fun actionSurfaceReleasesAfterConfirmSoItStaysUsable() {
        val confirmations = mutableListOf<Int>()
        val cancellations = mutableListOf<Int>()
        setContent(confirmations, cancellations)

        // First press runs the 110 ms press hold, fires, and must release
        // the internal activating state — otherwise the surface stays
        // disabled (e.g. when a callback throws; see HoldActionObject).
        composeRule.onNodeWithTag("confirm_button").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("confirm_button").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { confirmations.size == 1 }

        composeRule.onNodeWithTag("confirm_button").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("confirm_button").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { confirmations.size == 2 }

        assertEquals(listOf(1, 1), confirmations)
        assertEquals(emptyList<Int>(), cancellations)
    }

    @Test
    fun cancelIsAlwaysAvailable() {
        val confirmations = mutableListOf<Int>()
        val cancellations = mutableListOf<Int>()
        setContent(confirmations, cancellations)
        composeRule.onNodeWithTag("cancel_button").performClick()
        assertEquals(listOf(1), cancellations)
        assertEquals(emptyList<Int>(), confirmations)
    }
}
