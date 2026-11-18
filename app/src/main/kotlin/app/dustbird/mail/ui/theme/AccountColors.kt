package app.dustbird.mail.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

data class AccountAccent(val container: Color, val content: Color)

@Composable
fun accountAccent(index: Int): AccountAccent {
    val scheme = MaterialTheme.colorScheme
    return when (index.mod(6)) {
        0 -> AccountAccent(scheme.primaryContainer, scheme.onPrimaryContainer)
        1 -> AccountAccent(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        2 -> AccountAccent(scheme.primary, scheme.onPrimary)
        3 -> AccountAccent(scheme.tertiary, scheme.onTertiary)
        4 -> AccountAccent(scheme.secondaryContainer, scheme.onSecondaryContainer)
        else -> AccountAccent(scheme.secondary, scheme.onSecondary)
    }
}
