package dev.hryshyn.remanence.auth

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Actual atomic Compose content/resources on JVM, not a device/provider claim. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AtomicProviderCopyTest {
    @get:Rule val rule = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var reason by mutableStateOf("PROVIDER_CANCELLED")

    @Test fun englishFailureCopyDoesNotInventUserCancelBackendFailureOrSavedAccount() = checkCopy(
        "the passkey step didn’t finish", "the password manager couldn’t finish this step")
    @Test @Config(qualifiers = "ru")
    fun russianFailureCopyIsLocalized() = checkCopy(
        "шаг с ключом доступа не закончился", "менеджер паролей не смог закончить этот шаг")
    @Test @Config(qualifiers = "uk")
    fun ukrainianFailureCopyIsLocalized() = checkCopy(
        "крок із ключем доступу не завершився", "менеджер паролів не зміг завершити цей крок")

    private fun checkCopy(cancelPrefix: String, failurePrefix: String) {
        var actions = 0
        rule.setContent { MaterialTheme {
            AtomicRegistrationContent(AtomicView(if (reason == "PRF_UNAVAILABLE") AtomicPhase.VERIFY_REQUIRED else AtomicPhase.HANDLE,
                error = reason, handle = "synthetic_handle"), "synthetic_handle",
                { actions++ }, { actions++ }, { actions++ }, { actions++ }, { actions++ },
                { actions++ }, { actions++ }, { actions++ }, false) { _, _ -> }
        } }
        assertTrue(context.getString(R.string.auth_passkey_cancelled).startsWith(cancelPrefix))
        assertTrue(context.getString(R.string.auth_credential_failed).startsWith(failurePrefix))
        val messages = mapOf(
            "PROVIDER_CANCELLED" to R.string.auth_passkey_cancelled,
            "PROVIDER_NO_CREDENTIAL" to R.string.auth_credential_missing,
            "PROVIDER_NOT_CONFIGURED" to R.string.auth_credential_not_configured,
            "PROVIDER_UNAVAILABLE" to R.string.auth_credential_unavailable,
            "PROVIDER_FAILED" to R.string.auth_credential_failed,
            "PRF_UNAVAILABLE" to R.string.atomic_registration_prf_unavailable,
            "CREDENTIAL_PENDING" to R.string.recovery_verification_pending,
            "unknown private text" to R.string.atomic_registration_failed,
        )
        for ((value, id) in messages) {
            rule.runOnIdle { reason = value }
            rule.onNodeWithTag("atomic_error").performScrollTo().assertIsDisplayed().assertTextEquals(context.getString(id))
            if (value == "PRF_UNAVAILABLE") {
                rule.onNodeWithTag("atomic_handle").assertDoesNotExist()
                rule.onNodeWithTag("atomic_leave_setup").assertExists()
                rule.onNodeWithTag("atomic_verify").assertExists()
            } else rule.onNodeWithText("synthetic_handle").assertExists()
            val text = context.getString(id)
            assertFalse(Regex("PROVIDER_|PRF|50152|UNREGISTERED|dev\\.hryshyn|pm_at_|rgs_|unknown private").containsMatchIn(text))
            assertNotEquals(context.getString(R.string.auth_invalid_response), text)
        }
        assertNotEquals(context.getString(R.string.recovery_prf_missing), context.getString(R.string.atomic_registration_prf_unavailable))
        assertEquals(0, actions) // Displaying a failure cannot invoke retry, alternate provider or account changes.
    }
}
