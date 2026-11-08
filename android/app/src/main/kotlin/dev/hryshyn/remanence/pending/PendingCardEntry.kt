package dev.hryshyn.remanence.pending

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import dev.hryshyn.remanence.ui.auth.AccessHeading
import dev.hryshyn.remanence.ui.auth.AccessPage
import dev.hryshyn.remanence.ui.hold.HoldFormScaffold
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

/** ADR-020: the way in for a card with a code; the camera stays the main action. */
@Composable
fun PendingCardEntryButton(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = PendingFeatureGate.isEnabled(),
) {
    if (!enabled) return
    OutlinedButton(onClick = onOpen, modifier = modifier.fillMaxWidth().testTag("pending_card_entry")) {
        Text(stringResource(R.string.pending_card_entry))
    }
}

/** Groups the nine card symbols as written on the card: X4T-P9R-K12. */
private object CardCodeGroups : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = buildString { raw.forEachIndexed { i, c -> if (i == 3 || i == 6) append('-'); append(c) } }
        val mapping = object : OffsetMapping {
            // A dash sits after the 3rd and 6th symbol: "ABC-DEF-GHI".
            override fun originalToTransformed(offset: Int) = offset + (if (offset > 3) 1 else 0) + (if (offset > 6) 1 else 0)
            override fun transformedToOriginal(offset: Int) =
                (offset - (if (offset > 3) 1 else 0) - (if (offset > 7) 1 else 0)).coerceIn(0, raw.length)
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}

/**
 * ADR-020 recipient: its own page in place of the camera, three steps like
 * sign-up. Code from the card, then the number, then the code from the SMS.
 */
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
    BackHandler(onBack = onClose)

    fun submitCard() {
        val parsed = PendingCardSeal.parseCode(cardCode)
        if (parsed == null) badInput = true else viewModel.startCard(parsed)
    }

    @Composable
    fun Step(number: Int) = Text(stringResource(R.string.pending_card_step, number),
        style = MaterialTheme.typography.labelSmall, color = HoldColors.Muted,
        modifier = Modifier.testTag("pending_card_step"))

    @Composable
    fun Problem(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = HoldColors.Error)

    @Composable
    fun Field(value: String, onChange: (String) -> Unit, label: Int, tag: String, keyboard: KeyboardOptions,
              transformation: VisualTransformation = VisualTransformation.None, extra: Modifier = Modifier) {
        OutlinedTextField(
            value = value, onValueChange = onChange,
            label = { Text(stringResource(label), style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.titleLarge, singleLine = true, isError = badInput,
            keyboardOptions = keyboard, visualTransformation = transformation,
            modifier = Modifier.fillMaxWidth().testTag(tag).then(extra),
        )
    }

    @Composable
    fun Page(step: Int?, title: String, detail: String?, primary: String, primaryEnabled: Boolean,
             onPrimary: () -> Unit, primaryTag: String, content: @Composable () -> Unit = {}) {
        // The scan frame above already draws back and close; system Back closes this page.
        AccessPage(onBack = null) {
            HoldFormScaffold(modifier = Modifier.padding(bottom = 16.dp), primary = {
                Button(onClick = onPrimary, enabled = primaryEnabled,
                    modifier = Modifier.fillMaxWidth().testTag(primaryTag)) {
                    Text(primary, Modifier.weight(1f), textAlign = TextAlign.Start)
                    Text("→", style = MaterialTheme.typography.titleLarge)
                }
            }) {
                if (step != null) Step(step)
                AccessHeading(title, detail)
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }

    AstraEnterTransition(motion = rememberAstraMotion()) {
      Column(modifier.fillMaxSize().testTag("pending_card_sheet")) {
        when (val current = state) {
            PendingProofViewModel.ProofState.Idle, PendingProofViewModel.ProofState.WrongCardCode -> {
                val wrong = current == PendingProofViewModel.ProofState.WrongCardCode
                Page(1, stringResource(if (wrong) R.string.pending_card_wrong_code_title else R.string.pending_card_code_title),
                    stringResource(if (wrong) R.string.pending_card_wrong_code_body else R.string.pending_card_code_body),
                    stringResource(R.string.pending_code_continue), cardCode.length == 9, ::submitCard,
                    if (wrong) "pending_card_code_retry" else "pending_card_code_continue") {
                    Field(cardCode, { typed ->
                        cardCode = typed.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() && it.code < 128 }.take(9)
                        badInput = false
                    }, R.string.pending_card_code_label, "pending_card_code",
                        KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        transformation = CardCodeGroups)
                    if (badInput) Problem(stringResource(R.string.pending_card_code_invalid))
                }
            }
            is PendingProofViewModel.ProofState.PhoneRequired -> Page(2,
                stringResource(R.string.pending_card_phone_title, current.hintDigits),
                stringResource(R.string.pending_card_phone_body),
                stringResource(R.string.pending_proof_send_code), phone.isNotBlank(), {
                    val number = PendingPhoneInput.toE164(phone, PendingPhoneInput.region(context))
                    if (number == null) badInput = true else viewModel.requestPhoneCode(number)
                }, "pending_card_phone_send") {
                Field(phone, { phone = it; badInput = false }, R.string.pending_card_phone_label, "pending_card_phone",
                    KeyboardOptions(keyboardType = KeyboardType.Phone))
                if (badInput) Problem(stringResource(R.string.pending_card_phone_invalid))
            }
            is PendingProofViewModel.ProofState.CodeRequired -> Page(3,
                stringResource(R.string.pending_card_sms_title), stringResource(R.string.pending_card_sms_body),
                stringResource(R.string.pending_proof_verify), smsCode.isNotBlank(), viewModel::checkCode,
                "pending_card_sms_verify") {
                // The SMS ends with "@remanence.to #code", so the keyboard offers it.
                Field(smsCode, viewModel::onCodeChange, R.string.pending_proof_code_label, "pending_card_sms",
                    KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    extra = Modifier.semantics { contentType = ContentType.SmsOtpCode })
            }
            is PendingProofViewModel.ProofState.Claimed -> Page(null,
                stringResource(R.string.pending_card_done_title), stringResource(R.string.pending_card_done_body),
                stringResource(R.string.pending_card_done_action), true, onClose, "pending_card_done")
            is PendingProofViewModel.ProofState.Failed -> Page(null,
                stringResource(R.string.pending_card_failed_title), stringResource(current.message.cardStringRes()),
                stringResource(R.string.pending_proof_retry), true, viewModel::retry, "pending_card_retry")
            else -> AccessPage(onBack = null) {
                // Argon2 and the claim take a few seconds on a modest phone.
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.testTag("pending_card_working"), color = HoldColors.Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.pending_card_working), style = MaterialTheme.typography.bodyMedium, color = HoldColors.Muted)
                }
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
