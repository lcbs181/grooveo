package dev.schlubbe.musicagent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.schlubbe.musicagent.R

// Canopy specifies Archivo for both heading and body, weights 400-800. Unlike
// the Nocturne system this replaced (which fell back to the platform sans
// because Inter wasn't bundled), the actual Archivo TTFs ship in res/font/ --
// Archivo is OFL-licensed, so bundling is fine, and it avoids the Google Fonts
// downloadable-font provider's Play Services dependency that this backend-less
// app otherwise has none of.
private val Archivo = FontFamily(
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
    Font(R.font.archivo_bold, FontWeight.Bold),
    Font(R.font.archivo_extrabold, FontWeight.ExtraBold),
)

// Letter-spacing note: the handoff gives tracking in em (-0.02em on the display
// sizes), but Compose's letterSpacing in .sp is an absolute length, so each is
// converted against its own font size (e.g. -0.02em x 44sp = -0.88sp) rather
// than reused across sizes.
val CanopyTypography = Typography(
    // display-lg 44/47/800, -0.02em
    displayLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 44.sp,
        lineHeight = 47.sp,
        letterSpacing = (-0.88).sp,
    ),
    // headline-lg 32/36/800, -0.02em
    headlineLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.64).sp,
    ),
    // headline-md 25/28/700, -0.015em
    headlineMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 25.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.375).sp,
    ),
    // headline-sm 20/22/700, -0.01em
    headlineSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp,
    ),
    // title-lg 17/20/700
    titleLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 20.sp,
    ),
    // title-md 16/18/600
    titleMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 18.sp,
    ),
    // title-sm 13/16/700, +0.06em, uppercase (section kickers). text-transform
    // isn't a TextStyle property in Compose, so callers using this for a kicker
    // apply .uppercase() to the string itself.
    titleSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.78.sp,
    ),
    // body-lg 15/23/400
    bodyLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    // body-md 14/20/400
    bodyMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    // body-sm 13/18/400
    bodySmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    // label-lg 14/17/600
    labelLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 17.sp,
    ),
    // label-md 12/15/500
    labelMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 15.sp,
    ),
    // label-sm 11/14/400
    labelSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)
