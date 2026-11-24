package com.bdfiles.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Paleta de BD Files: rosa, violeta, púrpura, morado claro y cian. */
object BD {
    val Pink = Color(0xFFFF5FC8)
    val PinkSoft = Color(0xFFFF9AD9)
    val Violet = Color(0xFFB06BFF)
    val Purple = Color(0xFF8E3BE8)
    val LightPurple = Color(0xFFD8B4FF)
    val Cyan = Color(0xFF22E3FF)
    val CyanSoft = Color(0xFF8BF1FF)

    // Fondos oscuros con un punto morado, para que no se vea gris muerto.
    val Night = Color(0xFF0B0712)
    val Surface = Color(0xFF171024)
    val SurfaceHigh = Color(0xFF221733)
    val SurfaceCard = Color(0xFF1D1430)
    val Outline = Color(0xFF3A2B55)
    val TextHigh = Color(0xFFF2EAFC)
    val TextLow = Color(0xFFAE9ECB)
    val Danger = Color(0xFFFF5A7A)
    val Success = Color(0xFF4BE3A0)
    val Warning = Color(0xFFFFC24B)

    /** Degradado principal: rosa → violeta → cian. Se usa en la barra de progreso y los acentos. */
    val gradient = Brush.horizontalGradient(listOf(Pink, Violet, Cyan))

    val gradientSoft = Brush.horizontalGradient(
        listOf(Pink.copy(alpha = 0.30f), Violet.copy(alpha = 0.30f), Cyan.copy(alpha = 0.30f)),
    )

    fun headerGradient() = Brush.verticalGradient(
        listOf(Color(0xFF241337), Color(0xFF150E24)),
    )
}

private val DarkColors = darkColorScheme(
    primary = BD.Violet,
    onPrimary = Color(0xFF1A0B2E),
    primaryContainer = BD.Purple,
    onPrimaryContainer = Color(0xFFF4E9FF),
    secondary = BD.Pink,
    onSecondary = Color(0xFF330A22),
    secondaryContainer = Color(0xFF3D1636),
    onSecondaryContainer = BD.PinkSoft,
    tertiary = BD.Cyan,
    onTertiary = Color(0xFF00242B),
    tertiaryContainer = Color(0xFF0E3B44),
    onTertiaryContainer = BD.CyanSoft,
    background = BD.Night,
    onBackground = BD.TextHigh,
    surface = BD.Surface,
    onSurface = BD.TextHigh,
    surfaceVariant = BD.SurfaceHigh,
    onSurfaceVariant = BD.TextLow,
    surfaceContainer = BD.SurfaceCard,
    surfaceContainerHigh = BD.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF2A1D3E),
    outline = BD.Outline,
    outlineVariant = Color(0xFF2B2040),
    error = BD.Danger,
    onError = Color(0xFF3B0410),
    scrim = Color(0xCC07040D),
)

private val LightColors = lightColorScheme(
    primary = BD.Purple,
    onPrimary = Color.White,
    secondary = Color(0xFFD6219B),
    tertiary = Color(0xFF0098B5),
    background = Color(0xFFFBF7FF),
    onBackground = Color(0xFF1C1428),
    surface = Color.White,
    onSurface = Color(0xFF1C1428),
    surfaceVariant = Color(0xFFF1E8FA),
    onSurfaceVariant = Color(0xFF5E5172),
    outline = Color(0xFFCBB8E4),
    error = Color(0xFFC01B3C),
)

private val BDTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = t.bodyMedium.copy(fontSize = 15.sp),
        bodySmall = t.bodySmall.copy(fontSize = 13.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun BDFilesTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = BDTypography,
        content = content,
    )
}
