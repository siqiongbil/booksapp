package com.moread.app.ui.theme

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

private val InkPrimary = Color(0xFF3F51B5)
private val LightScheme = lightColorScheme(
    primary = InkPrimary,
    secondary = Color(0xFF7986CB),
    surface = Color(0xFFFAFAF7),
    background = Color(0xFFFAFAF7),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9FA8DA),
    secondary = Color(0xFF5C6BC0),
)

@Composable
fun MoReadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
