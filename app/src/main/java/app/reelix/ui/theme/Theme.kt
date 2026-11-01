package app.reelix.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.reelix.data.settings.ThemeMode

// Restrained editorial knowledge-vault palette: deep slate-teal ink, warm paper neutrals, amber accent.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1A4F5A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E9EE),
    onPrimaryContainer = Color(0xFF07232A),
    secondary = Color(0xFF516167),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD4E5EB),
    onSecondaryContainer = Color(0xFF0E1E23),
    tertiary = Color(0xFF7B5723),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA8),
    onTertiaryContainer = Color(0xFF281900),
    error = Color(0xFFAE2F28),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF3E0503),
    background = Color(0xFFF8F7F4),
    onBackground = Color(0xFF191C1D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1D),
    surfaceVariant = Color(0xFFEBEAE5),
    onSurfaceVariant = Color(0xFF42484A),
    outline = Color(0xFF73787B),
    outlineVariant = Color(0xFFC7CBCB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CCBD8),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF0C4C57),
    onPrimaryContainer = Color(0xFFBBEBF6),
    secondary = Color(0xFFB8C9CF),
    onSecondary = Color(0xFF233237),
    secondaryContainer = Color(0xFF39494E),
    onSecondaryContainer = Color(0xFFD4E5EB),
    tertiary = Color(0xFFEDBE79),
    onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF60410D),
    onTertiaryContainer = Color(0xFFFFDEA8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111517),
    onBackground = Color(0xFFE1E3E4),
    surface = Color(0xFF171C1F),
    onSurface = Color(0xFFE1E3E4),
    surfaceVariant = Color(0xFF252D31),
    onSurfaceVariant = Color(0xFFC0C7CA),
    outline = Color(0xFF8A9295),
    outlineVariant = Color(0xFF3C4447),
)

private val ReelixTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun ReelixTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = ReelixTypography,
        content = content,
    )
}

