package app.dustbird.mail.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

data class AccountAccent(val container: Color, val content: Color)

@Composable
fun accountAccent(index: Int): AccountAccent {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val hueStart = hueOf(scheme.primary)
    val hue = (hueStart + (index.mod(8)) * 45f) % 360f
    val container = Color.hsl(hue, if (dark) 0.28f else 0.42f, if (dark) 0.24f else 0.86f)
    val content = Color.hsl(hue, if (dark) 0.55f else 0.60f, if (dark) 0.86f else 0.20f)
    return AccountAccent(container, content)
}

private fun hueOf(color: Color): Float {
    val max = maxOf(color.red, color.green, color.blue)
    val min = minOf(color.red, color.green, color.blue)
    val delta = max - min
    if (delta == 0f) return 150f
    val hue = when (max) {
        color.red -> ((color.green - color.blue) / delta) % 6f
        color.green -> (color.blue - color.red) / delta + 2f
        else -> (color.red - color.green) / delta + 4f
    } * 60f
    return if (hue < 0) hue + 360f else hue
}
