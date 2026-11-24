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
    val Cyan = Color(0xFF00EAFF)
    val CyanSoft = Color(0xFF7DF4FF)
    /** Azul eléctrico, para que el azul tenga más peso junto al cian. */
    val Blue = Color(0xFF3D7BFF)
    val BlueSoft = Color(0xFF8FB4FF)
    val BlueDeep = Color(0xFF1B3B8F)

    // Fondos oscuros con un punto morado, para que no se vea gris muerto.
    val Night = Color(0xFF080A16)
    val Surface = Color(0xFF141428)
    val SurfaceHigh = Color(0xFF1E1E3A)
    val SurfaceCard = Color(0xFF191933)
    val Outline = Color(0xFF34356B)
    val TextHigh = Color(0xFFF2EAFC)
    val TextLow = Color(0xFFA6A6D0)
    val Danger = Color(0xFFFF5A7A)
    val Success = Color(0xFF4BE3A0)
    val Warning = Color(0xFFFFC24B)

    /** Degradado principal: rosa → violeta → cian. Se usa en la barra de progreso y los acentos. */
    val gradient = Brush.horizontalGradient(listOf(Pink, Violet, Blue, Cyan))

    val gradientSoft = Brush.horizontalGradient(
        listOf(
            Pink.copy(alpha = 0.30f), Violet.copy(alpha = 0.30f),
            Blue.copy(alpha = 0.35f), Cyan.copy(alpha = 0.40f),
        ),
    )

    /** Degradado azul, para las barras de espacio y los acentos fríos. */
    val blueGradient = Brush.horizontalGradient(listOf(Blue, Cyan))

    /** Un color propio para cada pestaña, hasta 14. */
    val tabColors = listOf(
        Color(0xFF3D7BFF), // azul
        Color(0xFF00EAFF), // cian
        Color(0xFFB06BFF), // violeta
        Color(0xFFFF5FC8), // rosa
        Color(0xFF2BE8C4), // turquesa
        Color(0xFFFFC24B), // ámbar
        Color(0xFF8E3BE8), // púrpura
        Color(0xFF4FC3F7), // azul cielo
        Color(0xFFFF7A6B), // coral
        Color(0xFFA8E05F), // lima
        Color(0xFFD8B4FF), // morado claro
        Color(0xFF6C5CE7), // índigo
        Color(0xFFFF4FA3), // magenta
        Color(0xFF4BE3A0), // verde menta
    )

    fun tabColor(index: Int): Color = tabColors[index % tabColors.size]

    fun headerGradient() = Brush.verticalGradient(
        listOf(Color(0xFF26173F), Color(0xFF121A2E)),
    )
}

private val DarkColors = darkColorScheme(
    primary = BD.Violet,
    onPrimary = Color(0xFF1A0B2E),
    primaryContainer = BD.Purple,
    onPrimaryContainer = Color(0xFFF4E9FF),
    secondary = BD.Pink,
    onSecondary = Color(0xFF330A22),
    secondaryContainer = Color(0xFF2B2A5E),
    onSecondaryContainer = BD.PinkSoft,
    tertiary = BD.Cyan,
    onTertiary = Color(0xFF002630),
    tertiaryContainer = Color(0xFF123A6B),
    onTertiaryContainer = BD.CyanSoft,
    background = BD.Night,
    onBackground = BD.TextHigh,
    surface = BD.Surface,
    onSurface = BD.TextHigh,
    surfaceVariant = BD.SurfaceHigh,
    onSurfaceVariant = BD.TextLow,
    surfaceContainer = BD.SurfaceCard,
    surfaceContainerHigh = BD.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF252552),
    outline = BD.Outline,
    outlineVariant = Color(0xFF26264A),
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
