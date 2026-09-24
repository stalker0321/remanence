package dev.hryshyn.remanence.ui.hold

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun Modifier.holdPressShift(pressed: Boolean, enabled: Boolean): Modifier =
    graphicsLayer { translationY = if (pressed && enabled) 4f else 0f }

@Composable
fun HoldButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Button(
        onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.holdPressShift(pressed, enabled).heightIn(min = 52.dp),
        shape = RoundedCornerShape(15.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), content = content,
    )
}

@Composable
fun HoldDestructiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Button(
        onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.holdPressShift(pressed, enabled).heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = HoldColors.Destructive, contentColor = HoldColors.OnAccent),
        shape = RoundedCornerShape(15.dp), content = content,
    )
}

@Composable
fun HoldSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    OutlinedButton(
        onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.holdPressShift(pressed, enabled).heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp), content = content,
    )
}

@Composable
fun HoldTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), content = content)

@Composable
fun HoldInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) = OutlinedTextField(
    value = value, onValueChange = onValueChange, modifier = modifier,
    label = label, isError = isError, enabled = enabled, singleLine = singleLine,
    keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    visualTransformation = visualTransformation,
    shape = RoundedCornerShape(8.dp), textStyle = MaterialTheme.typography.bodyLarge,
    colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = HoldColors.Field, unfocusedContainerColor = HoldColors.Field,
        disabledContainerColor = HoldColors.Field, unfocusedBorderColor = Color.Transparent,
    ),
)

/** A single actionable surface. Information and subjects never use this component. */
@Composable
fun HoldActionObject(
    title: String,
    detail: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
    titleModifier: Modifier = Modifier,
    compact: Boolean = false,
    expand: Boolean = false,
    minSurfaceHeight: Dp? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    var activating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentOnClick by rememberUpdatedState(onClick)
    val pressureVisible = pressed || activating
    // Guide: 6.dp edge, 3–4.dp travel (~110ms), pressed edge 1–2.dp.
    // Snap on contact so animator-duration 0 still shows press.
    val travel by animateDpAsState(
        targetValue = if (pressureVisible && enabled) 4.dp else 0.dp,
        animationSpec = if (pressureVisible) snap() else tween(110),
        label = "Hold pressure",
    )
    val shape = RoundedCornerShape(if (secondary) 19.dp else 24.dp)
    Box(
        modifier = modifier
            .padding(bottom = 6.dp)
            .then(if (expand) Modifier.fillMaxSize() else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled && !activating, role = Role.Button) {
                // Navigation fires immediately: the press state below only
                // keeps the touch visible and debounces, never gates action.
                activating = true
                scope.launch {
                    try {
                        currentOnClick()
                    } finally {
                        // The visible press outlives the tap by one beat, and
                        // the hold always releases — even if the callback
                        // throws — so the surface never sticks disabled.
                        delay(110)
                        activating = false
                    }
                }
            },
    ) {
        Box(Modifier.matchParentSize().offset(y = 6.dp).background(
            if (!enabled) HoldColors.Field else if (secondary) HoldColors.SandEdge else HoldColors.LilacEdge, shape,
        ))
        Column(
            Modifier.fillMaxWidth()
                .then(if (expand) Modifier.fillMaxSize() else Modifier)
                // A floored surface also stretches to the height its parent
                // offers (Home weights), so the card background fills the
                // whole share. fillMaxHeight is a no-op in an unbounded
                // scrolling column, so compact mode still wraps content.
                .then(if (minSurfaceHeight != null) Modifier.fillMaxHeight().heightIn(min = minSurfaceHeight) else Modifier)
                .offset(y = travel).clip(shape)
                .background(if (!enabled) HoldColors.Field else if (secondary) HoldColors.Sand else HoldColors.Lilac)
                .then(if (focused) Modifier.border(2.dp, HoldColors.Accent, shape) else Modifier)
                .padding(if (compact) 18.dp else 24.dp),
            verticalArrangement = if (expand || minSurfaceHeight != null) Arrangement.SpaceBetween else Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp)) {
                Text(title, modifier = titleModifier, style = if (compact) MaterialTheme.typography.headlineSmall else if (secondary) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayLarge, color = HoldColors.Ink)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = HoldColors.Ink)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(action, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = HoldColors.Ink)
                Box(Modifier.size(44.dp).background(if (secondary) HoldColors.Paper else HoldColors.Accent, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Text("→", modifier = Modifier.clearAndSetSemantics {}, color = if (secondary) HoldColors.Ink else HoldColors.OnAccent, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

/** Scrollable fields above a pinned primary so IME cannot hide submit. */
@Composable
fun HoldFormScaffold(
    modifier: Modifier = Modifier,
    primary: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(HoldSpace.Related),
            content = content,
        )
        Spacer(Modifier.height(HoldSpace.Group))
        primary()
    }
}

/** Flat context: deliberately no elevation, arrow, click handler or press response. */
@Composable
fun HoldInformation(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium, color = HoldColors.Muted)
}
