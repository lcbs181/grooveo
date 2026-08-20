package dev.schlubbe.musicagent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Nocturne specifies Inter for both heading/body, capped at weight 500 for
// headings (hierarchy via size/space, never bolder) -- Inter itself isn't
// bundled (would need either shipped .ttf assets or the Google Fonts
// downloadable-font provider, which pulls in a Play Services dependency this
// backend-less app otherwise has none of), so this uses the platform's default
// sans-serif (Roboto on stock Android), which is visually close enough that the
// README's own "platform convention over literal pixel-matching" guidance applies.
private val headingFamily = FontFamily.Default
private val bodyFamily = FontFamily.Default
private val headingWeight = FontWeight.Medium // capped at 500

val NocturneTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = headingFamily,
        fontWeight = headingWeight,
        fontSize = 42.sp,
        lineHeight = 47.sp,
        letterSpacing = (-0.015).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = headingFamily,
        fontWeight = headingWeight,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.015).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = headingFamily,
        fontWeight = headingWeight,
        fontSize = 25.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.015).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = headingFamily,
        fontWeight = headingWeight,
        fontSize = 20.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.015).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = headingFamily,
        fontWeight = headingWeight,
        fontSize = 17.sp,
        lineHeight = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = headingFamily,
        fontWeight = headingWeight,
        fontSize = 16.sp,
        lineHeight = 18.sp,
    ),
    titleSmall = TextStyle(
        // h6: uppercase + 0.08em letter-spacing -- text-transform isn't a TextStyle
        // property in Compose, so callers using this style for a section kicker
        // should apply .uppercase() to the string itself (see e.g. HomeScreen's
        // shelf headers).
        fontFamily = headingFamily,
        fontWeight = headingWeight,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.08.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = headingFamily,
        fontWeight = headingWeight,
        fontSize = 14.sp,
        lineHeight = 17.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 15.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = bodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)
