package com.coverextractor.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CoverExtractorDarkColors = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378A),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCBC2DB),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCAC4CF),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

/** coverExtractor is always dark themed, regardless of the device's system theme. */
@Composable
fun CoverExtractorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CoverExtractorDarkColors, content = content)
}
