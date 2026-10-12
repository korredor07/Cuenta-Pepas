package app.cuentapepas.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF2E6B3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F1BC),
    onPrimaryContainer = Color(0xFF00210A),
    secondary = Color(0xFF8A3B2E),
    tertiary = Color(0xFF6B5A2E),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9BD5A1),
    onPrimary = Color(0xFF00390F),
    primaryContainer = Color(0xFF0F5223),
    onPrimaryContainer = Color(0xFFB7F1BC),
    secondary = Color(0xFFFFB4A8),
    tertiary = Color(0xFFD8C58D),
    error = Color(0xFFF2B8B5),
)

@Composable
fun CuentapepasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = CuentapepasTypography,
        content = content,
    )
}
