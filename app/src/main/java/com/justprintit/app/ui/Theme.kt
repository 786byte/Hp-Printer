package com.justprintit.app.ui

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

private val Light = lightColorScheme(primary = Color(0xFF1F5FAD), secondary = Color(0xFF00838F), tertiary = Color(0xFF6A4FA3))
private val Dark = darkColorScheme(primary = Color(0xFF9EC3FF), secondary = Color(0xFF6FD6E0), tertiary = Color(0xFFCDB8FF))

@Composable
fun JustPrintItTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Ink colours used for counters (fixed so colour vs black & white reads the same in any theme). */
object InkColors {
    val color = Color(0xFF00A3D9)
    val mono = Color(0xFF5F6368)
}
