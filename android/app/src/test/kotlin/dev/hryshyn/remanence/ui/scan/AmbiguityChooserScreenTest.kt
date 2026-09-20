package dev.hryshyn.remanence.ui.scan

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Minimal-hints/no-gallery proof for M1-M13 ambiguity chooser. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AmbiguityChooserScreenTest {

    @get:org.junit.Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val rows = listOf(
        ChooserHintRow(
            candidateId = "cap-a",
            primarySenderLabel = "from \u2068@mykola\u2069",
            senderIdentityVerified = true,
            claimedSenderHandle = "mykola",
            yearAndDateLabel = "2026 · May 14",
            placeLabel = "Lviv",
        ),
        ChooserHintRow(
            candidateId = "cap-b",
            primarySenderLabel = "unverified sender",
            senderIdentityVerified = false,
            claimedSenderHandle = "olena",
            yearAndDateLabel = "2026 · March 02",
            placeLabel = null,
        ),
    )

    @Test
    fun rendersEveryRowWithHintsOnlyAndOrderedContent() {
        composeRule.setContent {
            AmbiguityChooserScreen(rows = rows, onSelected = {}, onRecapture = {})
        }

        composeRule.onNodeWithTag("chooser_title").assertIsDisplayed()
        composeRule.onNodeWithTag("chooser_row_cap-a").assertIsDisplayed()
        composeRule.onNodeWithTag("chooser_row_cap-b").assertIsDisplayed()
        // Exactly two candidate affordances: no gallery, no browsing surface.
        composeRule.onAllNodesWithTag("chooser_row_cap-a", useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onNodeWithTag("chooser_recapture_button").assertIsDisplayed()
    }

    @Test
    fun selectionReportsOnlyTheChosenCandidate() {
        var chosen: ChooserHintRow? = null
        composeRule.setContent {
            AmbiguityChooserScreen(rows = rows, onSelected = { chosen = it }, onRecapture = {})
        }

        composeRule.onNodeWithTag("chooser_row_cap-b").performClick()

        assertEquals("cap-b", chosen?.candidateId)
        assertNull(chosen?.placeLabel)
        assertEquals(
            "second row must not inherit the first row's place hint",
            null,
            chosen?.placeLabel,
        )
    }

    @Test
    fun recaptureButtonNeverSelectsACandidate() {
        var selectedCount = 0
        var recaptured = false
        composeRule.setContent {
            AmbiguityChooserScreen(
                rows = rows,
                onSelected = { selectedCount++ },
                onRecapture = { recaptured = true },
            )
        }

        composeRule.onNodeWithTag("chooser_recapture_button").performClick()

        assertEquals(0, selectedCount)
        assertTrue(recaptured)
    }

    @Test
    fun trustedMappingRendersPrimaryAndSenderClaimRendersOnlyAsSecondary() {
        composeRule.setContent {
            AmbiguityChooserScreen(rows = rows, onSelected = {}, onRecapture = {})
        }

        composeRule.onNodeWithTag("chooser_primary_cap-a", useUnmergedTree = true)
            .assertTextEquals("from \u2068@mykola\u2069")
        composeRule.onNodeWithTag("chooser_claim_cap-a", useUnmergedTree = true)
            .assertTextEquals(context.getString(R.string.hold_chooser_claim, bidiIsolated("mykola")))
        composeRule.onNodeWithTag("chooser_place_cap-a", useUnmergedTree = true)
            .assertTextEquals(bidiIsolated("Lviv"))
    }

    @Test
    fun missingTrustedMappingShowsExplicitUnverifiedPrimaryNeverTheClaim() {
        composeRule.setContent {
            AmbiguityChooserScreen(rows = rows, onSelected = {}, onRecapture = {})
        }

        composeRule.onNodeWithTag("chooser_primary_cap-b", useUnmergedTree = true)
            .assertTextEquals("unverified sender")
        composeRule.onNodeWithTag("chooser_claim_cap-b", useUnmergedTree = true)
            .assertTextEquals(context.getString(R.string.hold_chooser_claim, bidiIsolated("olena")))
    }

    @Test
    fun trustedHandlePolicyNeverPromotesASenderSuppliedNameWithoutTrustedMapping() {
        assertNull(chooserTrustedHandle(null))
        assertNull(chooserTrustedHandle("   "))
        assertEquals("alice", chooserTrustedHandle("alice"))
    }

    @Test
    fun bidiIsolationWrapsUserControlledRuns() {
        assertEquals("\u2068alice\u2069", bidiIsolated("alice"))
        assertEquals("\u2068@alice\u2069", bidiIsolated("@alice"))
    }

    @Test
    fun claimLabelIsNullWithoutSenderClaimAndLocalizedWithOne() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    if (chooserClaimLabel(null) == null && chooserClaimLabel("") == null) {
                        Text("no claim")
                    }
                    Text(chooserClaimLabel("impostor") ?: "")
                }
            }
        }

        composeRule.onNodeWithText("no claim").assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.hold_chooser_claim, bidiIsolated("impostor")),
        ).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h780dp-xhdpi")
    fun russianChooserLabelsAreAuthoredAndBidiIsolated() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    Text(chooserPrimaryLabel("alice"))
                    Text(chooserPrimaryLabel(null))
                    Text(chooserClaimLabel("bob") ?: "")
                }
            }
        }

        composeRule.onNodeWithText("от \u2068@alice\u2069").assertIsDisplayed()
        composeRule.onNodeWithText("отправитель не подтверждён").assertIsDisplayed()
        composeRule.onNodeWithText("имя от отправителя: \u2068bob\u2069 (не подтверждено)")
            .assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "uk-w360dp-h780dp-xhdpi")
    fun ukrainianChooserLabelsAreAuthoredAndBidiIsolated() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    Text(chooserPrimaryLabel("alice"))
                    Text(chooserPrimaryLabel(null))
                    Text(chooserClaimLabel("bob") ?: "")
                }
            }
        }

        composeRule.onNodeWithText("від \u2068@alice\u2069").assertIsDisplayed()
        composeRule.onNodeWithText("відправника не підтверджено").assertIsDisplayed()
        composeRule.onNodeWithText("ім’я від відправника: \u2068bob\u2069 (не підтверджено)")
            .assertIsDisplayed()
    }

    /**
     * Adversarial RTL/control-direction fixture: every user-controlled chooser
     * run (trusted handle with its `@` prefix, claimed name, place label) is
     * exactly one FSI/PDI unit, and the surrounding verified/claimed label
     * text stays outside it and keeps its order.
     */
    @Test
    fun rtlAndControlDirectionChooserRunsAreIsolatedAsUnits() {
        val rtlName = "\u202Eevil\u202C"
        val rtlPlace = "\u05D0\u05D1\u05D2"
        val row = ChooserHintRow(
            candidateId = "cap-rtl",
            primarySenderLabel = "from \u2068@x\u2069",
            senderIdentityVerified = true,
            claimedSenderHandle = rtlName,
            yearAndDateLabel = "2026",
            placeLabel = rtlPlace,
        )
        composeRule.setContent {
            MaterialTheme {
                Column {
                    Text(chooserPrimaryLabel(rtlName))
                    AmbiguityChooserScreen(rows = listOf(row), onSelected = {}, onRecapture = {})
                }
            }
        }

        val primary = context.getString(R.string.hold_chooser_from, bidiIsolated("@$rtlName"))
        val claim = context.getString(R.string.hold_chooser_claim, bidiIsolated(rtlName))
        composeRule.onNodeWithText(primary).assertIsDisplayed()
        composeRule.onNodeWithTag("chooser_claim_cap-rtl", useUnmergedTree = true)
            .assertTextEquals(claim)
        composeRule.onNodeWithTag("chooser_place_cap-rtl", useUnmergedTree = true)
            .assertTextEquals(bidiIsolated(rtlPlace))

        // Surrounding label stays outside the isolate and keeps its order.
        assertTrue(primary.startsWith("from "))
        assertTrue(primary.endsWith(bidiIsolated("@$rtlName")))
        assertFalse(primary.startsWith(rtlName))
        assertEquals(1, primary.count { it == '\u2068' })
        assertEquals(1, primary.count { it == '\u2069' })
        assertEquals("@$rtlName", primary.substringAfter('\u2068').substringBefore('\u2069'))

        // The claim suffix stays outside the isolated sender name.
        assertTrue(claim.endsWith("(not verified)"))
        assertEquals(1, claim.count { it == '\u2068' })
        assertEquals(1, claim.count { it == '\u2069' })
        assertEquals(rtlName, claim.substringAfter('\u2068').substringBefore('\u2069'))
    }
}
