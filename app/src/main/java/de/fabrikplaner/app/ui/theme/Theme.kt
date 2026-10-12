package de.fabrikplaner.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Orange = Color(0xFFE8A317)
private val Dark = Color(0xFF121416)
private val Surface = Color(0xFF1C2024)
private val SurfaceVariant = Color(0xFF2A3036)

val StatusOk = Color(0xFF7DCEA0)
val StatusUnder = Color(0xFFFF8A80)
val StatusOver = Color(0xFFFFD180)
val BeltSolid = Color(0xFFE8A317)
val PipeLiquid = Color(0xFF64B5F6)

private val Colors = darkColorScheme(
    primary = Orange,
    onPrimary = Color(0xFF1A1200),
    secondary = Color(0xFF8FCAFF),
    background = Dark,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    onBackground = Color(0xFFE6E1E5),
    onSurface = Color(0xFFE6E1E5),
    error = Color(0xFFFFB4AB),
)

@Composable
fun FabrikplanerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
