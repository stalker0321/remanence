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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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

private fun Modifier.holdPressShift(pressed: Boolean, enabled: Boolean): Modifier =
    graphicsLayer { translationY = if (pressed && enabled) 5f else 0f }

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
    visualTransformation: VisualTransformation = VisualTransformation.None,
) = OutlinedTextField(
    value = value, onValueChange = onValueChange, modifier = modifier,
    label = label, isError = isError, enabled = enabled, singleLine = singleLine,
    keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
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
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    // Press snaps immediately so it remains visible when animator duration is 0.
    val travel by animateDpAsState(
        targetValue = if (pressed && enabled) 6.dp else 0.dp,
        animationSpec = if (pressed) snap() else tween(140),
        label = "Hold pressure",
    )
    val shape = RoundedCornerShape(if (secondary) 19.dp else 24.dp)
    Box(
        modifier = modifier
            .padding(bottom = 6.dp)
            .then(if (expand) Modifier.fillMaxSize() else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.matchParentSize().offset(y = 6.dp).background(
            if (!enabled) HoldColors.Field else if (secondary) HoldColors.SandEdge else HoldColors.LilacEdge, shape,
        ))
        Column(
            Modifier.fillMaxWidth()
                .then(if (expand) Modifier.fillMaxSize() else Modifier)
                .offset(y = travel).clip(shape)
                .background(if (!enabled) HoldColors.Field else if (secondary) HoldColors.Sand else HoldColors.Lilac)
                .then(if (focused) Modifier.border(2.dp, HoldColors.Accent, shape) else Modifier)
                .padding(if (compact) 18.dp else 24.dp),
            verticalArrangement = if (expand) Arrangement.SpaceBetween else Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
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

/** Flat context: deliberately no elevation, arrow, click handler or press response. */
@Composable
fun HoldInformation(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium, color = HoldColors.Muted)
}
