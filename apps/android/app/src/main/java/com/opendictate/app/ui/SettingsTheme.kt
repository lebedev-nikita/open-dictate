package com.opendictate.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.opendictate.app.model.AppTheme

internal val SettingsCanvas: Color @Composable get() = MaterialTheme.colorScheme.background
internal val SettingsSurface: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val SettingsInset: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
internal val SettingsText: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val SettingsSecondary: Color @Composable get() = MaterialTheme.colorScheme.secondary
internal val SettingsMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

/** Leading icon (24) plus the gaps around it; row text and inset dividers start here. */
internal val SettingsRowTextStart = 56.dp

private val LightSettingsColors = lightColorScheme(
    primary = Color(0xFF22252B), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EBEF), onPrimaryContainer = Color(0xFF22252B),
    secondary = Color(0xFF454B56), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EBEF), onSecondaryContainer = Color(0xFF22252B),
    background = Color(0xFFF3F4F6), onBackground = Color(0xFF202329),
    surface = Color.White, onSurface = Color(0xFF202329),
    surfaceVariant = Color(0xFFEEF0F3), onSurfaceVariant = Color(0xFF626975),
    surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainerHigh = Color(0xFFEEF0F3), surfaceContainerHighest = Color(0xFFE6E9EE),
    outline = Color(0xFF848B96), outlineVariant = Color(0xFFE4E7EC),
    surfaceBright = Color.White,
    surfaceTint = Color.Transparent,
)

private val DarkSettingsColors = darkColorScheme(
    primary = Color(0xFFF2F3F5), onPrimary = Color(0xFF16181C),
    primaryContainer = Color(0xFF30343C), onPrimaryContainer = Color(0xFFF2F3F5),
    secondary = Color(0xFFD0D4DC), onSecondary = Color(0xFF16181C),
    secondaryContainer = Color(0xFF30343C), onSecondaryContainer = Color(0xFFF2F3F5),
    background = Color(0xFF101114), onBackground = Color(0xFFF2F3F5),
    surface = Color(0xFF1B1D22), onSurface = Color(0xFFF2F3F5),
    surfaceVariant = Color(0xFF282B32), onSurfaceVariant = Color(0xFFA9B0BC),
    surfaceContainer = Color(0xFF1B1D22), surfaceContainerLow = Color(0xFF16181C),
    surfaceContainerHigh = Color(0xFF282B32), surfaceContainerHighest = Color(0xFF32363E),
    outline = Color(0xFF757D89), outlineVariant = Color(0xFF30343C),
    surfaceBright = Color(0xFF3A3F48),
    surfaceTint = Color.Transparent,
)

@Composable
internal fun OpenDictateTheme(theme: AppTheme = AppTheme.SYSTEM, content: @Composable () -> Unit) {
    val dark = theme.isDark(isSystemInDarkTheme())
    val activity = LocalActivity.current as? ComponentActivity
    SideEffect {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(0xE6FFFFFF.toInt(), 0x801B1B1B.toInt()) { dark },
        )
    }
    MaterialTheme(
        colorScheme = if (dark) DarkSettingsColors else LightSettingsColors,
        content = content,
    )
}
