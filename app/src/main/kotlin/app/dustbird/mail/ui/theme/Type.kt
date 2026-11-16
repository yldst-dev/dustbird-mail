package app.dustbird.mail.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import app.dustbird.mail.R

val Pretendard = FontFamily(
    (100..900 step 100).map { weight ->
        Font(
            R.font.pretendard_variable,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

private fun TextStyle.pretendard() = copy(
    fontFamily = Pretendard,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

val DustbirdTypography: Typography = Typography().let {
    it.copy(
        displayLarge = it.displayLarge.pretendard(),
        displayMedium = it.displayMedium.pretendard(),
        displaySmall = it.displaySmall.pretendard(),
        headlineLarge = it.headlineLarge.pretendard(),
        headlineMedium = it.headlineMedium.pretendard(),
        headlineSmall = it.headlineSmall.pretendard(),
        titleLarge = it.titleLarge.pretendard(),
        titleMedium = it.titleMedium.pretendard(),
        titleSmall = it.titleSmall.pretendard(),
        bodyLarge = it.bodyLarge.pretendard(),
        bodyMedium = it.bodyMedium.pretendard(),
        bodySmall = it.bodySmall.pretendard(),
        labelLarge = it.labelLarge.pretendard(),
        labelMedium = it.labelMedium.pretendard(),
        labelSmall = it.labelSmall.pretendard(),
        displayLargeEmphasized = it.displayLargeEmphasized.pretendard(),
        displayMediumEmphasized = it.displayMediumEmphasized.pretendard(),
        displaySmallEmphasized = it.displaySmallEmphasized.pretendard(),
        headlineLargeEmphasized = it.headlineLargeEmphasized.pretendard(),
        headlineMediumEmphasized = it.headlineMediumEmphasized.pretendard(),
        headlineSmallEmphasized = it.headlineSmallEmphasized.pretendard(),
        titleLargeEmphasized = it.titleLargeEmphasized.pretendard(),
        titleMediumEmphasized = it.titleMediumEmphasized.pretendard(),
        titleSmallEmphasized = it.titleSmallEmphasized.pretendard(),
        bodyLargeEmphasized = it.bodyLargeEmphasized.pretendard(),
        bodyMediumEmphasized = it.bodyMediumEmphasized.pretendard(),
        bodySmallEmphasized = it.bodySmallEmphasized.pretendard(),
        labelLargeEmphasized = it.labelLargeEmphasized.pretendard(),
        labelMediumEmphasized = it.labelMediumEmphasized.pretendard(),
        labelSmallEmphasized = it.labelSmallEmphasized.pretendard(),
    )
}
