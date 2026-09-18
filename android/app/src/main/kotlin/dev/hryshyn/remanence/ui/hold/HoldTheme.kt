package dev.hryshyn.remanence.ui.hold

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hryshyn.remanence.R

object HoldColors {
    val Paper = Color(0xFFF4F2EC)
    val Ink = Color(0xFF333951)
    val Accent = Color(0xFF59649B)
    val Muted = Color(0xFF62697E)
    val Field = Color(0xFFE8E9EE)
    val Lilac = Color(0xFFDCD9EE)
    val Sand = Color(0xFFE9DFCA)
    val LilacEdge = Color(0xFFB0ACC8)
    val SandEdge = Color(0xFFC7BDA8)
    val Error = Color(0xFF843C4A)
    val Destructive = Color(0xFF86515B)
    val OnAccent = Color(0xFFFFFDF3)
}

object HoldSpace {
    val Small = 8.dp
    val Related = 12.dp
    val Group = 16.dp
    val Gutter = 24.dp
    val Section = 32.dp
}

private val HoldFont = FontFamily(Font(R.font.hold_sans, FontWeight.Normal))
private fun type(size: Int, line: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = HoldFont, fontWeight = FontWeight.Normal,
    fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp,
)
private val HoldTypography = Typography(
    displayLarge = type(32, 38, -.6f), displayMedium = type(30, 36, -.2f),
    displaySmall = type(28, 34, -.4f), headlineLarge = type(32, 38, -.6f),
    headlineMedium = type(30, 36, -.2f), headlineSmall = type(24, 30),
    titleLarge = type(24, 30), titleMedium = type(18, 25), titleSmall = type(14, 21),
    bodyLarge = type(16, 24), bodyMedium = type(14, 22), bodySmall = type(12, 18),
    labelLarge = type(14, 20), labelMedium = type(12, 18), labelSmall = type(12, 18),
)

/** Accepted light art direction; do not inherit dynamic wallpaper colours. */
@Composable
fun HoldTheme(content: @Composable () -> Unit) {
    val language = LocalConfiguration.current.locales[0].language
    val typography = if (language == "ru" || language == "uk") {
        HoldTypography.copy(displayLarge = type(30, 36, -.2f), headlineLarge = type(30, 36, -.2f))
    } else HoldTypography
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = HoldColors.Accent, onPrimary = HoldColors.OnAccent,
            primaryContainer = HoldColors.Lilac, onPrimaryContainer = HoldColors.Ink,
            secondary = HoldColors.Accent, onSecondary = HoldColors.OnAccent,
            secondaryContainer = HoldColors.Sand, onSecondaryContainer = HoldColors.Ink,
            background = HoldColors.Paper, onBackground = HoldColors.Ink,
            surface = HoldColors.Paper, onSurface = HoldColors.Ink,
            surfaceVariant = HoldColors.Field, onSurfaceVariant = HoldColors.Muted,
            outline = Color(0xFFC5C8D5), error = HoldColors.Error,
        ),
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(15.dp), large = RoundedCornerShape(19.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
