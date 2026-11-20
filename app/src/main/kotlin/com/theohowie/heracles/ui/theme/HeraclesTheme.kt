package com.theohowie.heracles.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.theohowie.heracles.R
import com.theohowie.heracles.i18n.LocalStrings
import com.theohowie.heracles.i18n.stringsFor
import com.theohowie.heracles.model.AppAppearance
import com.theohowie.heracles.model.AppLanguage
import com.theohowie.heracles.model.AppThemeStyle

val InterFontFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold),
    Font(R.font.inter, FontWeight.Bold),
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
)

@Immutable
data class HeraclesColors(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val cardBorder: Color,
    val accent: Color,
    val onAccent: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val glowColors: List<Color>,
    val isDark: Boolean,
)

val LocalHeraclesColors = staticCompositionLocalOf { bwDarkColors() }

private fun bwDarkColors() = HeraclesColors(
    bg = Color(0xFF0A0A0B),
    surface = Color(0xFF141415),
    surfaceAlt = Color(0xFF1E1E21),
    cardBorder = Color(0xFF2B2B30),
    accent = Color(0xFFF2F2F2),
    onAccent = Color(0xFF0A0A0B),
    textPrimary = Color(0xFFF4F4F5),
    textMuted = Color(0xFF9CA3AF),
    success = Color(0xFF22C55E),
    warning = Color(0xFFF59E0B),
    danger = Color(0xFFEF4444),
    userBubble = Color(0xFF262629),
    userBubbleText = Color(0xFFF4F4F5),
    glowColors = emptyList(),
    isDark = true,
)

private fun bwLightColors() = HeraclesColors(
    bg = Color(0xFFF7F7F7),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEFEFEF),
    cardBorder = Color(0xFFE2E2E5),
    accent = Color(0xFF161616),
    onAccent = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF141416),
    textMuted = Color(0xFF6B7280),
    success = Color(0xFF16A34A),
    warning = Color(0xFFD97706),
    danger = Color(0xFFDC2626),
    userBubble = Color(0xFF18181B),
    userBubbleText = Color(0xFFFFFFFF),
    glowColors = emptyList(),
    isDark = false,
)

private fun mistLightColors() = HeraclesColors(
    bg = Color(0xFFFAF6EF),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF3ECE0),
    cardBorder = Color(0xFFE5DCCB),
    accent = Color(0xFF7C6AE8),
    onAccent = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF231F2E),
    textMuted = Color(0xFF6D677E),
    success = Color(0xFF16A34A),
    warning = Color(0xFFD97706),
    danger = Color(0xFFDC2626),
    userBubble = Color(0xFF7C6AE8),
    userBubbleText = Color(0xFFFFFFFF),
    glowColors = listOf(Color(0xFFDCD6FA), Color(0xFFEBDCCB)),
    isDark = false,
)

private fun mistDarkColors() = HeraclesColors(
    bg = Color(0xFF211D2E),
    surface = Color(0xFF2B263B),
    surfaceAlt = Color(0xFF352F47),
    cardBorder = Color(0xFF453E5C),
    accent = Color(0xFF9B8AFB),
    onAccent = Color(0xFF161224),
    textPrimary = Color(0xFFF5F3FA),
    textMuted = Color(0xFFB3ACC8),
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
    danger = Color(0xFFF87171),
    userBubble = Color(0xFF4C3F8C),
    userBubbleText = Color(0xFFF5F3FA),
    glowColors = listOf(Color(0xFF4C3F8C), Color(0xFF2D3A54)),
    isDark = true,
)

private fun auroraDarkColors() = HeraclesColors(
    bg = Color(0xFF0B0B16),
    surface = Color(0xFF131325),
    surfaceAlt = Color(0xFF1B1B34),
    cardBorder = Color(0xFF2A2A4D),
    accent = Color(0xFF22D3EE),
    onAccent = Color(0xFF07131A),
    textPrimary = Color(0xFFF0F6FF),
    textMuted = Color(0xFF9AA4C4),
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    danger = Color(0xFFFB7185),
    userBubble = Color(0xFF1C2947),
    userBubbleText = Color(0xFFF0F6FF),
    glowColors = listOf(Color(0xFF8B5CF6), Color(0xFF22D3EE), Color(0xFFEC4899)),
    isDark = true,
)

private fun auroraLightColors() = HeraclesColors(
    bg = Color(0xFFF0F0FA),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFE7E7F7),
    cardBorder = Color(0xFFD6D6ED),
    accent = Color(0xFF7C3AED),
    onAccent = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF17162B),
    textMuted = Color(0xFF606080),
    success = Color(0xFF10B981),
    warning = Color(0xFFD97706),
    danger = Color(0xFFE11D48),
    userBubble = Color(0xFF6D28D9),
    userBubbleText = Color(0xFFFFFFFF),
    glowColors = listOf(Color(0xFFC4B5FD), Color(0xFF67E8F9), Color(0xFFF9A8D4)),
    isDark = false,
)

private fun vitalityLightColors() = HeraclesColors(
    bg = Color(0xFFFDF3E7),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF9E8D4),
    cardBorder = Color(0xFFECD5BC),
    accent = Color(0xFFFF6B4A),
    onAccent = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF2B1A12),
    textMuted = Color(0xFF7D5D4F),
    success = Color(0xFF16A34A),
    warning = Color(0xFFD97706),
    danger = Color(0xFFDC2626),
    userBubble = Color(0xFFFF6B4A),
    userBubbleText = Color(0xFFFFFFFF),
    glowColors = listOf(Color(0xFFFFB08A), Color(0xFFFFC46B), Color(0xFFFF8A7A)),
    isDark = false,
)

private fun vitalityDarkColors() = HeraclesColors(
    bg = Color(0xFF1F1712),
    surface = Color(0xFF2A1F18),
    surfaceAlt = Color(0xFF36281F),
    cardBorder = Color(0xFF4A372B),
    accent = Color(0xFFFF7A59),
    onAccent = Color(0xFF1C0E08),
    textPrimary = Color(0xFFFDF3EC),
    textMuted = Color(0xFFC8A998),
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
    danger = Color(0xFFF87171),
    userBubble = Color(0xFF7C321E),
    userBubbleText = Color(0xFFFDF3EC),
    glowColors = listOf(Color(0xFFFF6B4A), Color(0xFFFF9E5C), Color(0xFFB45309)),
    isDark = true,
)

private val HeraclesTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun HeraclesTheme(
    themeStyle: AppThemeStyle,
    appearance: AppAppearance,
    language: AppLanguage,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (appearance) {
        AppAppearance.DARK -> true
        AppAppearance.LIGHT -> false
        AppAppearance.SYSTEM -> systemDark
    }
    val palette = when (themeStyle) {
        AppThemeStyle.BW -> if (useDark) bwDarkColors() else bwLightColors()
        AppThemeStyle.MIST -> if (useDark) mistDarkColors() else mistLightColors()
        AppThemeStyle.AURORA -> if (useDark) auroraDarkColors() else auroraLightColors()
        AppThemeStyle.VITALITY -> if (useDark) vitalityDarkColors() else vitalityLightColors()
    }
    val scheme: ColorScheme = if (useDark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.bg,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.textMuted,
            outline = palette.cardBorder,
            error = palette.danger,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.bg,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.textMuted,
            outline = palette.cardBorder,
            error = palette.danger,
        )
    }

    CompositionLocalProvider(
        LocalHeraclesColors provides palette,
        LocalStrings provides stringsFor(language),
        LocalLayoutDirection provides if (language == AppLanguage.AR) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = HeraclesTypography,
            content = content,
        )
    }
}

@Composable
fun HeraclesBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalHeraclesColors.current
    Box(modifier = modifier.fillMaxSize().background(colors.bg)) {
        if (colors.glowColors.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.maxDimension * 0.55f
                val alpha = if (colors.isDark) 0.16f else 0.28f
                val centers = listOf(
                    Offset(size.width * 0.15f, size.height * 0.12f),
                    Offset(size.width * 0.88f, size.height * 0.35f),
                    Offset(size.width * 0.45f, size.height * 0.88f),
                )
                colors.glowColors.forEachIndexed { idx, c ->
                    val center = centers[idx % centers.size]
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(c.copy(alpha = alpha), Color.Transparent),
                            center = center,
                            radius = r,
                        ),
                        radius = r,
                        center = center,
                    )
                }
            }
        }
        content()
    }
}
