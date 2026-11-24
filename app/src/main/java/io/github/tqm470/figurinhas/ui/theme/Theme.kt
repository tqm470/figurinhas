package io.github.tqm470.figurinhas.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF14833F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F1C2),
    onPrimaryContainer = Color(0xFF00210C),
    secondary = Color(0xFF506352),
    secondaryContainer = Color(0xFFD3E8D3),
    tertiary = Color(0xFF3A6470),
    background = Color(0xFFF6FBF4),
    surface = Color(0xFFF6FBF4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FDA9B),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF00522A),
    onPrimaryContainer = Color(0xFF9BF7B5),
    secondary = Color(0xFFB7CCB8),
    secondaryContainer = Color(0xFF394B3B),
    tertiary = Color(0xFFA2CDDB),
    background = Color(0xFF0F1510),
    surface = Color(0xFF0F1510),
)

@Composable
fun FigurinhasTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** WhatsApp-ish green for the "Add to WhatsApp" button regardless of dynamic color. */
val WhatsAppGreen = Color(0xFF1FAA59)
