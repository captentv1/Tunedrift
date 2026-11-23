package com.musicflow.play.ui.theme

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

private val TunedriftDarkColors = darkColorScheme(
    primary = Color(0xFF7C9EFF),
    onPrimary = Color(0xFF0A1B47),
    primaryContainer = Color(0xFF1E3266),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF56D6C4),
    onSecondary = Color(0xFF003731),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE6E9F2),
    surface = Color(0xFF121722),
    onSurface = Color(0xFFE6E9F2),
    surfaceVariant = Color(0xFF1D2433),
    onSurfaceVariant = Color(0xFFA7B0C4),
    outline = Color(0xFF3A445C)
)

private val TunedriftLightColors = lightColorScheme(
    primary = Color(0xFF2B59C3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF0A1B47),
    secondary = Color(0xFF006A60),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF131722),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF131722),
    surfaceVariant = Color(0xFFE7ECF7),
    onSurfaceVariant = Color(0xFF4B556B),
    outline = Color(0xFFBAC4DA)
)

@Composable
fun TunedriftTheme(
    themeMode: String = "dark",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark", "oled" -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        themeMode == "oled" -> TunedriftDarkColors.copy(
            background = Color(0xFF000000),
            surface = Color(0xFF080A0F),
            surfaceVariant = Color(0xFF141821)
        )
        darkTheme -> TunedriftDarkColors
        else -> TunedriftLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
