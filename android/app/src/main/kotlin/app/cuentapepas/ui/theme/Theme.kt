package app.cuentapepas.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Cloverance brand tokens (docs/marca/estilo-cloverance.md). */
object Brand {
    val Ink = Color(0xFF010E0B)
    val InkSoft = Color(0xFF3A4A44)
    val Paper = Color(0xFFF8F7F4)
    val Surface = Color(0xFFFFFFFF)
    val Mist = Color(0xFFDFE2DC)
    val MistDeep = Color(0xFFAFB6AE)
    val Green = Color(0xFF47B914)
    val GreenDeep = Color(0xFF2A700C)
    val GreenTint = Color(0xFFEAF6E4)
    val Amber = Color(0xFFB87614)
    val AmberBright = Color(0xFFE09A1E)
    val AmberTint = Color(0xFFFAF0DC)
    val Brick = Color(0xFF9E4B33)
    val BrickTint = Color(0xFFF5E3DE)
    val InkSurface = Color(0xFF0B1A16)
}

/** Status colours that Material3 has no slot for: warning (amber) and success (green-deep). */
@Immutable
class StatusColors(
    val warning: Color,
    val warningContainer: Color,
    val success: Color,
    val successContainer: Color,
)

private val LightStatus = StatusColors(Brand.Amber, Brand.AmberTint, Brand.GreenDeep, Brand.GreenTint)
private val DarkStatus = StatusColors(Brand.AmberBright, Color(0xFF2A2210), Brand.Green, Color(0xFF12301A))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

val MaterialTheme.status: StatusColors
    @Composable @ReadOnlyComposable get() = LocalStatusColors.current

private val Light = lightColorScheme(
    primary = Brand.GreenDeep,
    onPrimary = Color.White,
    primaryContainer = Brand.GreenTint,
    onPrimaryContainer = Color(0xFF173F06),
    secondary = Brand.Green,
    onSecondary = Brand.Ink,
    secondaryContainer = Brand.GreenTint,
    onSecondaryContainer = Color(0xFF173F06),
    tertiary = Brand.Amber,
    onTertiary = Color.White,
    tertiaryContainer = Brand.AmberTint,
    onTertiaryContainer = Color(0xFF4A2F06),
    error = Brand.Brick,
    onError = Color.White,
    errorContainer = Brand.BrickTint,
    onErrorContainer = Color(0xFF4A1F10),
    background = Brand.Paper,
    onBackground = Brand.Ink,
    surface = Brand.Surface,
    onSurface = Brand.Ink,
    surfaceVariant = Brand.Mist,
    onSurfaceVariant = Brand.InkSoft,
    surfaceTint = Brand.GreenDeep,
    outline = Brand.MistDeep,
    outlineVariant = Brand.Mist,
    surfaceBright = Brand.Surface,
    surfaceDim = Brand.Paper,
    surfaceContainerLowest = Brand.Surface,
    surfaceContainerLow = Brand.Surface,
    surfaceContainer = Brand.Surface,
    surfaceContainerHigh = Brand.Surface,
    surfaceContainerHighest = Brand.Surface,
)

private val Dark = darkColorScheme(
    primary = Brand.Green,
    onPrimary = Brand.Ink,
    primaryContainer = Color(0xFF12301A),
    onPrimaryContainer = Color(0xFFBFEFA8),
    secondary = Brand.Green,
    onSecondary = Brand.Ink,
    secondaryContainer = Color(0xFF12301A),
    onSecondaryContainer = Color(0xFFBFEFA8),
    tertiary = Brand.AmberBright,
    onTertiary = Brand.Ink,
    tertiaryContainer = Color(0xFF2A2210),
    onTertiaryContainer = Color(0xFFF3D9A4),
    error = Color(0xFFE08A70),
    onError = Brand.Ink,
    errorContainer = Color(0xFF3A1D14),
    onErrorContainer = Brand.BrickTint,
    background = Brand.Ink,
    onBackground = Brand.Paper,
    surface = Brand.InkSurface,
    onSurface = Brand.Paper,
    surfaceVariant = Color(0xFF1B2B26),
    onSurfaceVariant = Color(0xFFC3CBC6),
    surfaceTint = Brand.Green,
    outline = Color(0xFF4A5A54),
    outlineVariant = Color(0xFF26352F),
    surfaceBright = Color(0xFF14241F),
    surfaceDim = Brand.Ink,
    surfaceContainerLowest = Brand.Ink,
    surfaceContainerLow = Brand.InkSurface,
    surfaceContainer = Brand.InkSurface,
    surfaceContainerHigh = Color(0xFF11211C),
    surfaceContainerHighest = Color(0xFF11211C),
)

private val BrandShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

/** Card shadow approximating the brand token (soft, 1-2dp). */
@Composable
fun brandCardElevation(): CardElevation = CardDefaults.cardElevation(defaultElevation = 2.dp)

@Composable
fun CuentapepasTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = CuentapepasTypography,
            shapes = BrandShapes,
            content = content,
        )
    }
}
