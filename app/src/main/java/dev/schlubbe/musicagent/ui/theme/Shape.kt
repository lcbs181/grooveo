package dev.schlubbe.musicagent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Nocturne's radius scale is deliberately compact (sm 4 / md 8 / lg 14) --
// three steps, not M3's five, so the ramp is compressed rather than invented:
// md covers both M3's small+medium slots, lg covers large+extraLarge.
val NocturneShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(14.dp),
)
