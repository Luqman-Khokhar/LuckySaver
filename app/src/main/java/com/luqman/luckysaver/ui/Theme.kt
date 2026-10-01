package com.luqman.luckysaver.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.luqman.luckysaver.data.DarkMode
import com.luqman.luckysaver.data.Palette

/*
 * Jade carries the brand: primary actions and selection only. Gold has one meaning, "new", and is
 * kept out of the Material roles so nothing picks it up by accident. Neutrals lean green so the
 * greys belong to the palette rather than to a default.
 */

private val JadeLight = lightColorScheme(
    primary = Color(0xFF127A5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEFE2),
    onPrimaryContainer = Color(0xFF0C3D2F),
    secondary = Color(0xFF4D6359),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E6DE),
    onSecondaryContainer = Color(0xFF0B1F17),
    tertiary = Color(0xFF8A5A0B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBE3BC),
    onTertiaryContainer = Color(0xFF2C1A00),
    error = Color(0xFFC2412D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBDAD3),
    onErrorContainer = Color(0xFF410C03),
    background = Color(0xFFF7F8F6),
    onBackground = Color(0xFF16201C),
    surface = Color(0xFFF7F8F6),
    onSurface = Color(0xFF16201C),
    surfaceVariant = Color(0xFFECF0ED),
    onSurfaceVariant = Color(0xFF55625C),
    outline = Color(0xFF8A9690),
    outlineVariant = Color(0xFFD3DAD6),
    inverseSurface = Color(0xFF2B3330),
    inverseOnSurface = Color(0xFFEDF2EF),
    inversePrimary = Color(0xFF5FD3A9),
    surfaceTint = Color(0xFF127A5E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F8F6),
    surfaceDim = Color(0xFFD8DDDA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F5F3),
    surfaceContainer = Color(0xFFEDF1EE),
    surfaceContainerHigh = Color(0xFFE7ECE9),
    surfaceContainerHighest = Color(0xFFE1E7E3),
)

private val JadeDark = darkColorScheme(
    primary = Color(0xFF5FD3A9),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF0F5641),
    onPrimaryContainer = Color(0xFFCFF5E6),
    secondary = Color(0xFFB4CCC0),
    onSecondary = Color(0xFF1F352C),
    secondaryContainer = Color(0xFF354B42),
    onSecondaryContainer = Color(0xFFD0E8DC),
    tertiary = Color(0xFFF2B85B),
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF633F00),
    onTertiaryContainer = Color(0xFFFFDDB0),
    error = Color(0xFFFF8A73),
    onError = Color(0xFF5C1408),
    errorContainer = Color(0xFF7D2615),
    onErrorContainer = Color(0xFFFFDAD3),
    background = Color(0xFF0E1412),
    onBackground = Color(0xFFE3EAE6),
    surface = Color(0xFF0E1412),
    onSurface = Color(0xFFE3EAE6),
    surfaceVariant = Color(0xFF1F2925),
    onSurfaceVariant = Color(0xFFA3B1AA),
    outline = Color(0xFF6E7C75),
    outlineVariant = Color(0xFF2E3934),
    inverseSurface = Color(0xFFE3EAE6),
    inverseOnSurface = Color(0xFF2B3330),
    inversePrimary = Color(0xFF127A5E),
    surfaceTint = Color(0xFF5FD3A9),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF333B38),
    surfaceDim = Color(0xFF0E1412),
    surfaceContainerLowest = Color(0xFF090E0C),
    surfaceContainerLow = Color(0xFF141A18),
    surfaceContainer = Color(0xFF181F1C),
    surfaceContainerHigh = Color(0xFF1F2724),
    surfaceContainerHighest = Color(0xFF29322E),
)

/** Colors outside the Material roles. */
@Immutable
data class Accents(
    /** "New since you last looked": story rings and dots. Never used for anything else. */
    val fresh: Color,
)

private val LightAccents = Accents(fresh = Color(0xFFE8A33D))
private val DarkAccents = Accents(fresh = Color(0xFFF2B85B))

val LocalAccents = staticCompositionLocalOf { LightAccents }

/**
 * Roboto, the phone's own face: nothing to load, crisp at every size. Four sizes carry almost
 * everything: 28 for screen titles, 15 for reading, 13 for detail, 12 for labels.
 */
private val AppType = Typography().run {
    copy(
        titleLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
        headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun isAppInDarkTheme(mode: DarkMode): Boolean = when (mode) {
    DarkMode.SYSTEM -> isSystemInDarkTheme()
    DarkMode.LIGHT -> false
    DarkMode.DARK -> true
}

@Composable
fun LuckyTheme(palette: Palette, dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors: ColorScheme = when {
        palette == Palette.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> JadeDark
        else -> JadeLight
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalAccents provides if (dark) DarkAccents else LightAccents) {
        MaterialTheme(colorScheme = colors, typography = AppType, content = content)
    }
}
