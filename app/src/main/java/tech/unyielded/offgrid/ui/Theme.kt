package tech.unyielded.offgrid.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object OffgridColors {
    val Ink = Color(0xFF080C14)
    val Surface = Color(0xFF0F1520)
    val SurfaceElevated = Color(0xFF161E2B)
    val Border = Color(0xFF1E2A3A)
    val BorderSubtle = Color(0xFF15202E)

    val Accent = Color(0xFF00C9A7)
    val AccentDim = Color(0xFF00A88A)
    val AccentMuted = Color(0xFF0A3D36)

    val TextPrimary = Color(0xFFE8EEF5)
    val TextSecondary = Color(0xFF8B9CB3)
    val TextTertiary = Color(0xFF5A6A7E)

    val Success = Color(0xFF00C9A7)
    val Warning = Color(0xFFE8B84A)
    val Danger = Color(0xFFFF6B6B)

    val BubbleMine = Color(0xFF0A2F2A)
    val BubblePeer = Color(0xFF161E2B)
}

val OffgridDarkScheme = darkColorScheme(
    primary = OffgridColors.Accent,
    onPrimary = OffgridColors.Ink,
    background = OffgridColors.Ink,
    surface = OffgridColors.Surface,
    onSurface = OffgridColors.TextPrimary,
    surfaceVariant = OffgridColors.SurfaceElevated,
    outline = OffgridColors.Border
)

object OffgridDimens {
    val ScreenPadding = 20.dp
    val CardRadius = 16.dp
    val ButtonRadius = 12.dp
    val SmallRadius = 10.dp
    val IconSize = 22.dp
    val StatusDot = 8.dp
}

object OffgridType {
    val Wordmark = 11.sp
    val Hero = 28.sp
    val Title = 18.sp
    val Body = 14.sp
    val Caption = 12.sp
    val Micro = 10.sp
}
