package app.cuentapepas.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.cuentapepas.R

// Archivo and Public Sans are bundled as variable fonts (the google/fonts repo has no static files for them);
// each weight is an instance of the same file selected through FontVariation (API 26+).
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: FontWeight) =
    Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

val ArchivoFamily = FontFamily(
    variable(R.font.archivo, FontWeight.Medium),
    variable(R.font.archivo, FontWeight.SemiBold),
    variable(R.font.archivo, FontWeight.Bold),
)

val PublicSansFamily = FontFamily(
    variable(R.font.public_sans, FontWeight.Normal),
    variable(R.font.public_sans, FontWeight.Medium),
    variable(R.font.public_sans, FontWeight.SemiBold),
)

val PlexMonoFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/** Style for counts, intervals and table numbers. */
val numberStyle = TextStyle(fontFamily = PlexMonoFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)

private val base = Typography()

val CuentapepasTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold),
    displayMedium = base.displayMedium.copy(fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold),
    displaySmall = base.displaySmall.copy(fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = ArchivoFamily, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = ArchivoFamily, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = ArchivoFamily, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = ArchivoFamily, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = PublicSansFamily),
    bodyMedium = base.bodyMedium.copy(fontFamily = PublicSansFamily),
    bodySmall = base.bodySmall.copy(fontFamily = PublicSansFamily),
    labelLarge = base.labelLarge.copy(fontFamily = PublicSansFamily, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = PublicSansFamily, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFamily = PublicSansFamily, fontWeight = FontWeight.Medium),
)
