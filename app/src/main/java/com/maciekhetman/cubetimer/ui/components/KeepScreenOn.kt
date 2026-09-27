package com.maciekhetman.cubetimer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keeps the display awake for as long as this is in the composition. Scoped to the screens that
 * need it (the timer: a hold, inspection or long solve easily outlasts the screen timeout) rather
 * than a window-wide FLAG_KEEP_SCREEN_ON, which also kept Stats/History/Settings lit.
 */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
