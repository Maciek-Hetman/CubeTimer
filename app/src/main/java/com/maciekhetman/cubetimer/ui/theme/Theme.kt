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
    secondaryContainer = Gray30,
    onSecondaryContainer = Gray90,
    tertiary = Gray70,
    onTertiary = Gray10,
    tertiaryContainer = Gray40,
    onTertiaryContainer = Gray95,
    background = Gray10,
    onBackground = Gray90,
    surface = Gray10,
    onSurface = Gray90,
    surfaceDim = Gray10,
    surfaceBright = Gray20,
    surfaceContainerLowest = Gray10,
    surfaceContainerLow = Gray20,
    surfaceContainer = Gray30,
    surfaceContainerHigh = Gray40,
    surfaceContainerHighest = Gray40,
    surfaceVariant = Gray30,
    onSurfaceVariant = Gray80,
    outline = Gray60,
    outlineVariant = Gray50,
    error = Red40,
    onError = Gray98,
    errorContainer = Red10,
    onErrorContainer = Red90,
    inverseSurface = Gray90,
    inverseOnSurface = Gray10,
    inversePrimary = Blue40,
    surfaceTint = Blue80,
    scrim = Gray10
)

private val AmoledDarkColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Blue10,
    primaryContainer = Blue10,
    onPrimaryContainer = Blue95,
    secondary = Gray80,
    onSecondary = Gray20,
    secondaryContainer = Gray30,
    onSecondaryContainer = Gray90,
    tertiary = Gray70,
    onTertiary = Gray10,
    tertiaryContainer = Gray40,
    onTertiaryContainer = Gray95,
    background = Black,
    onBackground = Gray90,
    surface = Black,
    onSurface = Gray90,
    surfaceDim = Black,
    surfaceBright = Gray20,
    surfaceContainerLowest = Black,
    surfaceContainerLow = Black,
    surfaceContainer = Gray20,
    surfaceContainerHigh = Gray30,
    surfaceContainerHighest = Gray30,
    surfaceVariant = Gray20,
    onSurfaceVariant = Gray80,
    outline = Gray60,
    outlineVariant = Gray50,
    error = Red40,
    onError = Gray98,
    errorContainer = Red10,
    onErrorContainer = Red90,
    inverseSurface = Gray90,
    inverseOnSurface = Gray10,
    inversePrimary = Blue40,
    surfaceTint = Blue80,
    scrim = Black
)

private val LightColorScheme = lightColorScheme(
    primary = Blue40,
    onPrimary = Gray98,
    primaryContainer = Blue80,
    onPrimaryContainer = Blue10,
    secondary = Gray40,
    onSecondary = Gray98,
    secondaryContainer = Gray80,
    onSecondaryContainer = Gray10,
    tertiary = Gray50,
    onTertiary = Gray98,
    tertiaryContainer = Gray90,
    onTertiaryContainer = Gray10,
    background = Gray98,
    onBackground = Gray10,
    surface = Gray98,
    onSurface = Gray10,
    surfaceDim = Gray90,
    surfaceBright = Gray98,
    surfaceContainerLowest = Gray98,
    surfaceContainerLow = Gray95,
    surfaceContainer = Gray90,
    surfaceContainerHigh = Gray95,
    surfaceContainerHighest = Gray98,
    surfaceVariant = Gray90,
    onSurfaceVariant = Gray30,
    outline = Gray50,
    outlineVariant = Gray80,
    error = Red40,
    onError = Gray98,
    errorContainer = Red90,
    onErrorContainer = Red10,
    inverseSurface = Gray20,
    inverseOnSurface = Gray95,
    inversePrimary = Blue80,
    surfaceTint = Blue40,
    scrim = Gray10
)

@Composable
fun CubeTimerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    amoled: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme && amoled -> AmoledDarkColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
