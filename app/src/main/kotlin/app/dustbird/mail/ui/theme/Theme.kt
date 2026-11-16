package app.dustbird.mail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF2F6B4F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB4F0CF),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9D9),
    onSecondaryContainer = Color(0xFF092016),
    tertiary = Color(0xFF3B6470),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBFE9F8),
    onTertiaryContainer = Color(0xFF001F28),
    background = Color(0xFFF6FBF5),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFF6FBF5),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707972),
    outlineVariant = Color(0xFFC0C9C1),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF99D4B4),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF12513A),
    onPrimaryContainer = Color(0xFFB4F0CF),
    secondary = Color(0xFFB3CCBD),
    onSecondary = Color(0xFF1F352A),
    secondaryContainer = Color(0xFF354B40),
    onSecondaryContainer = Color(0xFFCFE9D9),
    tertiary = Color(0xFFA3CDDC),
    onTertiary = Color(0xFF053541),
    tertiaryContainer = Color(0xFF224C58),
    onTertiaryContainer = Color(0xFFBFE9F8),
    background = Color(0xFF0F1511),
    onBackground = Color(0xFFDFE4DE),
    surface = Color(0xFF0F1511),
    onSurface = Color(0xFFDFE4DE),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C1),
    outline = Color(0xFF8A938B),
    outlineVariant = Color(0xFF404943),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DustbirdTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> FallbackDark
        else -> FallbackLight
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = DustbirdTypography,
        content = content,
    )
}
