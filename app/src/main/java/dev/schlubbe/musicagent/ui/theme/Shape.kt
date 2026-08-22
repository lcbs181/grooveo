package dev.schlubbe.musicagent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Canopy's radius scale: sm 8 / md 14 / lg 24, plus a pill (999px). Three steps
// rather than M3's five, so the ramp is compressed onto M3's slots rather than
// invented: sm covers extraSmall+small, md covers medium, lg covers
// large+extraLarge.
val CanopyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** `--radius-pill: 999px`. Not part of M3's [Shapes] slots (which cap at
 * extraLarge), but Canopy uses it heavily -- every chip, the primary buttons,
 * and the mini player bar. */
val CanopyPillShape = RoundedCornerShape(999.dp)
