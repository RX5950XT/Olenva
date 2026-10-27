package app.olenva.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Paper & ink: warm paper ground, deep ink text, one ink-blue accent.
private val Light = lightColorScheme(
    primary = Color(0xFF2B4A6F),
    onPrimary = Color(0xFFFBF8F1),
    primaryContainer = Color(0xFFDCE3EC),
    onPrimaryContainer = Color(0xFF14273D),
    secondary = Color(0xFF6B6256),
    onSecondary = Color(0xFFFBF8F1),
    secondaryContainer = Color(0xFFEAE1CF),
    onSecondaryContainer = Color(0xFF2C261F),
    background = Color(0xFFF6F1E7),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFF6F1E7),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFEAE1CF),
    onSurfaceVariant = Color(0xFF5A5247),
    surfaceContainerLowest = Color(0xFFFFFDF8),
    surfaceContainerLow = Color(0xFFF9F5EC),
    surfaceContainer = Color(0xFFF1EBDF),
    surfaceContainerHigh = Color(0xFFECE5D7),
    surfaceContainerHighest = Color(0xFFE6DECE),
    outline = Color(0xFF8A8072),
    outlineVariant = Color(0xFFE0D6C4),
    error = Color(0xFF8C2F2B),
    onError = Color.White,
    errorContainer = Color(0xFFF3DAD5),
    onErrorContainer = Color(0xFF4A120F),
    inverseSurface = Color(0xFF2E2A25),
    inverseOnSurface = Color(0xFFF2ECE1),
    scrim = Color.Black,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DB8D9),
    onPrimary = Color(0xFF102438),
    primaryContainer = Color(0xFF2A3D55),
    onPrimaryContainer = Color(0xFFD6E3F2),
    secondary = Color(0xFFA39989),
    onSecondary = Color(0xFF221E1A),
    secondaryContainer = Color(0xFF2E2A25),
    onSecondaryContainer = Color(0xFFE6DECF),
    background = Color(0xFF191715),
    onBackground = Color(0xFFECE5D8),
    surface = Color(0xFF191715),
    onSurface = Color(0xFFECE5D8),
    surfaceVariant = Color(0xFF2A2622),
    onSurfaceVariant = Color(0xFFC9BFAF),
    surfaceContainerLowest = Color(0xFF141210),
    surfaceContainerLow = Color(0xFF1E1B18),
    surfaceContainer = Color(0xFF221F1C),
    surfaceContainerHigh = Color(0xFF2A2622),
    surfaceContainerHighest = Color(0xFF332E29),
    outline = Color(0xFF8A8072),
    outlineVariant = Color(0xFF36312B),
    error = Color(0xFFE59A93),
    onError = Color(0xFF3A0D0A),
    errorContainer = Color(0xFF5A1D19),
    onErrorContainer = Color(0xFFF7D9D4),
    inverseSurface = Color(0xFFECE5D8),
    inverseOnSurface = Color(0xFF2A2622),
    scrim = Color.Black,
)

val Serif = FontFamily.Serif

private val base = Typography()
private val OlenvaType = base.copy(
    displaySmall = base.displaySmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

/** Reading preferences that both Compose and the WebView transcript follow. */
data class Reading(val dark: Boolean, val fontScale: Float, val compact: Boolean)

val LocalReading = staticCompositionLocalOf { Reading(false, 1f, false) }

@Composable
fun OlenvaTheme(theme: String, fontScale: Float, compact: Boolean, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    // The reading scale applies to conversation text only (transcript + composer); app chrome follows the
    // system font scale alone, so enlarged reading text can't push toolbar buttons into each other.
    CompositionLocalProvider(LocalReading provides Reading(dark, fontScale, compact)) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = OlenvaType, content = content)
    }
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace)

/** Full-screen dark surfaces (camera, image editor) need light system-bar icons whatever the theme. */
@Composable
fun DarkSystemBars() {
    val view = LocalView.current
    val dark = LocalReading.current.dark
    DisposableEffect(Unit) {
        var ctx = view.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val window = (ctx as? Activity)?.window
        val c = window?.let { WindowCompat.getInsetsController(it, view) }
        c?.isAppearanceLightStatusBars = false
        c?.isAppearanceLightNavigationBars = false
        onDispose {
            c?.isAppearanceLightStatusBars = !dark
            c?.isAppearanceLightNavigationBars = !dark
        }
    }
}
