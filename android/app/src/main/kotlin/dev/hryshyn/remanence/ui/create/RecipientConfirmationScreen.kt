package dev.hryshyn.remanence.ui.create

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldTextButton

/**
 * Explicit recipient confirmation (docs/security.md section 8). Shows the
 * resolved handle plus a non-ID account cue. The immutable binding itself
 * travels in the confirmed snapshot (ViewModel/store), never in displayed
 * raw IDs — no user/key UUID is rendered here.
 */
@Composable
fun RecipientConfirmationScreen(
    snapshot: ResolvedHandleSnapshot,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activating by remember(snapshot) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val travel by animateDpAsState(
        targetValue = if (pressed) 4.dp else 0.dp,
        animationSpec = if (pressed) snap() else tween(110),
        label = "recipient pressure",
    )
    val cardShape = RoundedCornerShape(17.dp)
    Column(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().padding(top = 20.dp, end = 8.dp, bottom = 6.dp)
                .testTag("confirm_button")
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    enabled = !activating,
                    onClick = {
                        if (!activating) {
                            activating = true
                            onConfirm()
                            scope.launch { delay(110L); activating = false }
                        }
                    },
                ),
        ) {
            Box(Modifier.matchParentSize().offset(y = 6.dp).background(HoldColors.LilacEdge, cardShape))
            Column(
                Modifier.fillMaxWidth().offset(y = travel)
                    .background(HoldColors.Lilac, cardShape)
                    .padding(horizontal = 22.dp, vertical = 20.dp),
            ) {
                Text(
                    stringResource(R.string.hold_recipient_handle),
                    style = MaterialTheme.typography.bodySmall,
                    color = HoldColors.Muted,
                    modifier = Modifier.testTag("confirm_account_cue_text"),
                )
                Spacer(Modifier.height(15.dp))
                Text(
                    snapshot.handle.toDisplayString(),
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 27.sp, lineHeight = 34.sp),
                    color = HoldColors.Ink,
                    modifier = Modifier.testTag("confirm_handle_text"),
                )
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.hold_confirm),
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = HoldColors.Ink,
                    )
                    Box(
                        Modifier.size(34.dp).background(HoldColors.Accent, RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("↗", Modifier.clearAndSetSemantics {},
                            style = MaterialTheme.typography.titleLarge, color = HoldColors.OnAccent)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        HoldTextButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().testTag("cancel_button"),
        ) {
            Text(stringResource(R.string.hold_change), style = MaterialTheme.typography.bodySmall)
        }
    }
}
