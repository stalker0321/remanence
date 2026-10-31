package dev.hryshyn.remanence.pending

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.crypto.PendingCardSeal
import dev.hryshyn.remanence.ui.hold.HoldButton as Button
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldSecondaryButton as OutlinedButton
import dev.hryshyn.remanence.ui.motion.AstraEnterTransition
import dev.hryshyn.remanence.ui.motion.rememberAstraMotion
import java.util.Locale

/** Local numbers become E.164 by the phone's own rules. Null when it cannot tell. */
object PendingPhoneInput {
    fun toE164(raw: String, region: String): String? {
        val typed = raw.trim()
        if (typed.isEmpty()) return null
        return PhoneNumberUtils.formatNumberToE164(typed, region.uppercase(Locale.ROOT))
    }

    fun region(context: Context): String {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return telephony?.simCountryIso?.takeIf { it.length == 2 } ?: Locale.getDefault().country
    }
}

/** ADR-020: one enters the code from the card, then proves the number. */
@Composable
fun PendingCardEntryButton(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = PendingFeatureGate.isEnabled(),
) {
    if (!enabled) return
    OutlinedButton(onClick = onOpen, modifier = modifier.testTag("pending_card_entry")) {
        Text(stringResource(R.string.pending_card_entry))
    }
}

@Composable
fun PendingCardEntrySheet(
    newProofVm: (kotlinx.coroutines.CoroutineScope) -> PendingProofViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { newProofVm(scope) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val smsCode by viewModel.code.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var cardCode by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var badInput by remember { mutableStateOf(false) }

    @Composable
    fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

    @Composable
    fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = HoldColors.Muted)

    @Composable
    fun CardCodeField() {
        OutlinedTextField(
            value = cardCode,
            onValueChange = { cardCode = it; badInput = false },
            label = { Text(stringResource(R.string.pending_card_code_label)) },
            singleLine = true,
            isError = badInput,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth().testTag("pending_card_code"),
        )
    }

    AstraEnterTransition(motion = rememberAstraMotion()) {
        Column(modifier.fillMaxWidth().padding(16.dp).testTag("pending_card_sheet")) {
            when (val current = state) {
                PendingProofViewModel.ProofState.Idle -> {
                    Title(stringResource(R.string.pending_card_code_title))
                    Spacer(Modifier.height(12.dp))
                    CardCodeField()
                    if (badInput) Body(stringResource(R.string.pending_card_code_invalid))
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val parsed = PendingCardSeal.parseCode(cardCode)
                            if (parsed == null) badInput = true else viewModel.startCard(parsed)
                        },
                        enabled = cardCode.isNotBlank(),
                        modifier = Modifier.testTag("pending_card_code_continue"),
                    ) { Text(stringResource(R.string.pending_code_continue)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onClose) { Text(stringResource(R.string.pending_code_cancel)) }
                }
                is PendingProofViewModel.ProofState.PhoneRequired -> {
                    Title(stringResource(R.string.pending_card_phone_title, current.hintDigits))
                    Spacer(Modifier.height(8.dp))
                    Body(stringResource(R.string.pending_card_phone_body))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it; badInput = false },
                        label = { Text(stringResource(R.string.pending_card_phone_label)) },
                        singleLine = true,
                        isError = badInput,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth().testTag("pending_card_phone"),
                    )
                    if (badInput) Body(stringResource(R.string.pending_card_phone_invalid))
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val number = PendingPhoneInput.toE164(phone, PendingPhoneInput.region(context))
                            if (number == null) badInput = true else viewModel.requestPhoneCode(number)
                        },
                        enabled = phone.isNotBlank(),
                        modifier = Modifier.testTag("pending_card_phone_send"),
                    ) { Text(stringResource(R.string.pending_proof_send_code)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onClose) { Text(stringResource(R.string.pending_code_cancel)) }
                }
                is PendingProofViewModel.ProofState.CodeRequired -> {
                    Title(stringResource(R.string.pending_card_sms_title))
                    Spacer(Modifier.height(8.dp))
                    Body(stringResource(R.string.pending_card_sms_body))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = smsCode,
                        onValueChange = viewModel::onCodeChange,
                        label = { Text(stringResource(R.string.pending_proof_code_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().testTag("pending_card_sms"),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::checkCode,
                        enabled = smsCode.isNotBlank(),
                        modifier = Modifier.testTag("pending_card_sms_verify"),
                    ) { Text(stringResource(R.string.pending_proof_verify)) }
                }
                PendingProofViewModel.ProofState.WrongCardCode -> {
                    Title(stringResource(R.string.pending_card_wrong_code_title))
                    Spacer(Modifier.height(8.dp))
                    Body(stringResource(R.string.pending_card_wrong_code_body))
                    Spacer(Modifier.height(12.dp))
                    CardCodeField()
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val parsed = PendingCardSeal.parseCode(cardCode)
                            if (parsed == null) badInput = true else viewModel.startCard(parsed)
                        },
                        modifier = Modifier.testTag("pending_card_code_retry"),
                    ) { Text(stringResource(R.string.pending_code_continue)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onClose) { Text(stringResource(R.string.pending_code_cancel)) }
                }
                is PendingProofViewModel.ProofState.Claimed -> {
                    Title(stringResource(R.string.pending_card_done_title))
                    Spacer(Modifier.height(8.dp))
                    Body(stringResource(R.string.pending_card_done_body))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onClose, modifier = Modifier.testTag("pending_card_done")) {
                        Text(stringResource(R.string.pending_proof_done))
                    }
                }
                is PendingProofViewModel.ProofState.Failed -> {
                    Text(
                        stringResource(current.message.cardStringRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("pending_card_error"),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::retry) { Text(stringResource(R.string.pending_proof_retry)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onClose) { Text(stringResource(R.string.pending_code_cancel)) }
                }
                else -> {
                    // Argon2 and the claim take a few seconds on a modest phone.
                    CircularProgressIndicator(Modifier.testTag("pending_card_working"))
                    Spacer(Modifier.height(8.dp))
                    Body(stringResource(R.string.pending_card_working))
                }
            }
        }
    }
}

private fun PendingProofViewModel.PendingProofMessage.cardStringRes(): Int = when (this) {
    PendingProofViewModel.PendingProofMessage.NETWORK -> R.string.pending_proof_network
    PendingProofViewModel.PendingProofMessage.NOT_FOUND -> R.string.pending_card_no_gift
    PendingProofViewModel.PendingProofMessage.SESSION_CHANGED -> R.string.pending_proof_session
    PendingProofViewModel.PendingProofMessage.EXPIRED -> R.string.pending_card_sms_wrong
    PendingProofViewModel.PendingProofMessage.CONFLICT -> R.string.pending_proof_conflict
    PendingProofViewModel.PendingProofMessage.BLOCKED -> R.string.pending_proof_generic
    PendingProofViewModel.PendingProofMessage.GENERIC -> R.string.pending_proof_generic
}
