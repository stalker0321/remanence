package dev.hryshyn.remanence.ui.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.auth.CurrentAccountPort
import dev.hryshyn.remanence.auth.RegistrationAuthApiPort
import dev.hryshyn.remanence.auth.RegistrationUseCase
import dev.hryshyn.remanence.wiring.PreparedIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.data.network.AuthFailure
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.data.network.RegisterRequestDto
import dev.hryshyn.remanence.core.data.network.RegisterResponseDto
import dev.hryshyn.remanence.core.data.network.RegistrationUserDto

/** Scripted network port; one outcome per registration call. */
private class ScriptedRegistrationApi(
    private val outcomes: ArrayDeque<AuthResult<RegisterResponseDto>>,
) : RegistrationAuthApiPort {
    var calls = 0
        private set

    override suspend fun register(
        request: RegisterRequestDto,
    ): AuthResult<RegisterResponseDto> {
        calls += 1
        return outcomes.removeFirst()
    }
}

private object NoAccounts : CurrentAccountPort {
    override suspend fun recordCurrentAccount(user: RegistrationUserDto, activeKeyBundleId: String) = Unit
}

private object FixedIdentity : dev.hryshyn.remanence.auth.RegistrationIdentityPort {
    override fun prepareIdentity(): PreparedIdentity = PreparedIdentity(
        keyBundleId = "0198f0a0-0000-7000-8000-00000000b001",
        encryptionPublicKeysetB64Url = "enc",
        signingPublicKeysetB64Url = "sig",
    )
}

private fun ok201() = AuthResult.Success(
    value = RegisterResponseDto(
        user = RegistrationUserDto(
            userId = "0198f0a0-0000-7000-8000-00000000a001",
            email = "mykola@example.com",
            handle = "mykola",
            createdAt = "2026-01-01T00:00:00Z",
        ),
        activeKeyBundleId = "0198f0a0-0000-7000-8000-00000000b001",
        accessToken = "access",
        accessExpiresAt = "2026-01-01T01:00:00Z",
        refreshToken = "refresh",
        refreshExpiresAt = "2026-01-02T00:00:00Z",
    ),
    httpStatus = 201,
)

/**
 * FIX-STATE-07 regression: the registration surface renders THE authoritative
 * submit state - Submitting disables the button with visible progress, Failed
 * renders a visible actionable message with a retry path, and Completed never
 * allows a second submit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RegistrationSubmitLifecycleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun validViewModel(api: RegistrationAuthApiPort): RegistrationViewModel {
        val viewModel = RegistrationViewModel(RegistrationUseCase(FixedIdentity, api, NoAccounts))
        viewModel.onFieldChange(RegistrationField.EMAIL, "mykola@example.com")
        viewModel.onFieldChange(RegistrationField.PASSWORD, "correct horse battery")
        viewModel.onFieldChange(RegistrationField.HANDLE, "mykola")
        return viewModel
    }

    private fun setContent(viewModel: RegistrationViewModel) {
        composeRule.setContent {
            // FIX-STATE-07: collect THE authoritative state, not one-shot values.
            val form by viewModel.form.collectAsState()
            val submitState by viewModel.submitState.collectAsState()
            MaterialTheme {
                RegistrationFormScreen(
                    form = form,
                    submitState = submitState,
                    onFieldChange = viewModel::onFieldChange,
                    onSubmit = viewModel::submit,
                )
            }
        }
    }

    @Test
    fun submittingDisablesTheButtonAndShowsVisibleProgressThenFailedMessage() {
        val gate = CompletableDeferred<AuthResult<RegisterResponseDto>>()
        val api = ScriptedRegistrationApi(ArrayDeque())
        val deferredOutcome = object : RegistrationAuthApiPort {
            override suspend fun register(request: RegisterRequestDto): AuthResult<RegisterResponseDto> =
                gate.await()
        }
        val viewModel = validViewModel(deferredOutcome)
        setContent(viewModel)

        composeRule.onNodeWithTag("reg_submit_button")
            .assertIsDisplayed()
            .performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reg_submit_progress").assertIsDisplayed()
        composeRule.onNodeWithTag("reg_submit_button").assertIsNotEnabled()

        // The gated outcome releases the flow; the failure becomes visible.
        composeRule.runOnIdle {
            gate.complete(
                AuthResult.Failure(reason = AuthFailure.HTTP, httpStatus = 409),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reg_error_message").assertIsDisplayed()
        composeRule.onNodeWithTag("reg_submit_progress")
        composeRule.onNodeWithTag("reg_submit_button").assertIsDisplayed()
        assertTrue(api.calls == 0)
    }

    @Test
    fun failedOutcomeRendersVisibleMessageAndKeepsRetryPossible() {
        val api = ScriptedRegistrationApi(
            ArrayDeque(listOf(AuthResult.Failure(AuthFailure.HTTP, httpStatus = 409))),
        )
        val viewModel = validViewModel(api)
        setContent(viewModel)

        composeRule.onNodeWithTag("reg_submit_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reg_error_message").assertIsDisplayed()
        composeRule.onNodeWithTag("reg_submit_button").assertIsDisplayed()
        assertEquals(1, api.calls)
    }

    @Test
    fun completedTerminalNeverAllowsASubmitAgain() {
        val api = ScriptedRegistrationApi(ArrayDeque(listOf(ok201())))
        val viewModel = validViewModel(api)
        setContent(viewModel)

        composeRule.onNodeWithTag("reg_submit_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reg_completed_message").assertIsDisplayed()
        composeRule.onNodeWithTag("reg_submit_button").assertIsNotEnabled()

        // A click attempt changes nothing - no second network call.
        composeRule.onNodeWithTag("reg_submit_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, api.calls)
    }
}
