package com.maciekhetman.cubetimer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Blue10,
    primaryContainer = Blue10,
    onPrimaryContainer = Blue95,
    secondary = Gray80,
    onSecondary = Gray20,
    tertiary = Gray70,
    onTertiary = Gray10,
    background = Gray10,
    onBackground = Gray90,
    surface = Gray10,
    onSurface = Gray90,
    surfaceVariant = Gray30,
    onSurfaceVariant = Gray80,
    outline = Gray60
)

private val LightColorScheme = lightColorScheme(
    primary = Blue40,
    onPrimary = Gray98,
    primaryContainer = Blue90,
    onPrimaryContainer = Blue10,
    secondary = Gray40,
    onSecondary = Gray98,
    tertiary = Gray50,
    onTertiary = Gray98,
    background = Gray98,
    onBackground = Gray10,
    surface = Gray98,
    onSurface = Gray10,
    surfaceVariant = Gray90,
    onSurfaceVariant = Gray30,
    outline = Gray50
)

@Composable
fun CubeTimerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
