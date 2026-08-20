package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.ui.theme.Nocturne
import dev.schlubbe.musicagent.ui.theme.NocturneShapes

// Nocturne's ".btn" family (nocturne-tokens.css) is outlined/transparent, never
// filled -- the opposite of stock Material Button/FilledTonalButton, which is
// exactly what made the first redesign pass look like generic Material instead
// of this design system. Every variant here maps 1:1 to a CSS class so a
// screen can be checked against the real markup class-by-class.
enum class NocturneButtonVariant { Primary, Secondary, Ghost }

private fun NocturneButtonVariant.borderColor() = when (this) {
    NocturneButtonVariant.Primary -> Nocturne.accent
    NocturneButtonVariant.Secondary -> Nocturne.divider
    NocturneButtonVariant.Ghost -> Color.Transparent
}

private fun NocturneButtonVariant.contentColor() = when (this) {
    NocturneButtonVariant.Primary, NocturneButtonVariant.Ghost -> Nocturne.accent
    NocturneButtonVariant.Secondary -> Nocturne.text
}

@Composable
fun NocturneButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: NocturneButtonVariant = NocturneButtonVariant.Primary,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    block: Boolean = false,
) {
    val contentColor = variant.contentColor()
    Row(
        modifier = modifier
            .let { if (block) it.fillMaxWidth() else it }
            .clip(NocturneShapes.medium)
            .border(1.dp, variant.borderColor(), NocturneShapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = contentColor, modifier = Modifier.size(15.dp))
            Row(modifier = Modifier.padding(start = 6.dp)) {
                Text(text, color = contentColor, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Text(text, color = contentColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** ".btn-icon": a square icon-only tap target, 36x36 by default. Pass a
 * [RoundedCornerShape](50) for the circular icon buttons the design uses for
 * avatars/FAB-style actions (search icon, gear icon, playlist FAB). */
@Composable
fun NocturneIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: NocturneButtonVariant = NocturneButtonVariant.Ghost,
    shape: Shape = NocturneShapes.medium,
    size: androidx.compose.ui.unit.Dp = 36.dp,
    iconSize: androidx.compose.ui.unit.Dp = 17.dp,
    enabled: Boolean = true,
) {
    val contentColor = variant.contentColor()
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .border(1.dp, variant.borderColor(), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(iconSize))
    }
}

/** ".card": flat, unelevated, background=surface, radius-md, space-3 padding.
 * Unlike Material `Card()`, this never carries shadow/tonal elevation. */
fun Modifier.nocturneCard(padding: androidx.compose.ui.unit.Dp = 8.dp): Modifier = this
    .clip(NocturneShapes.medium)
    .background(Nocturne.surface)
    .padding(padding)

/** ".seg" / ".seg-opt": the pill segmented control Nocturne uses everywhere a
 * Material app would reach for a Switch or Tabs -- every boolean setting in
 * Einstellungen is an "Aus"/"An" 2-option segment, not a Switch, and mood/type
 * filters use the same control with more options. Selection is drawn as a 1dp
 * accent inset border on just that segment (CSS `box-shadow: inset 0 0 0 1px
 * accent`), not a filled pill -- keep it that way, a filled/pill selection
 * indicator is the M3 default and reads as generic Material again. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
) {
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .let { if (fillWidth) it.fillMaxWidth() else it }
            .clip(NocturneShapes.medium)
            .border(1.dp, Nocturne.divider, NocturneShapes.medium),
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) {
                VerticalDivider(color = Nocturne.divider, thickness = 1.dp, modifier = Modifier.fillMaxHeight())
            }
            val isSelected = option == selected
            Row(
                modifier = (if (fillWidth) Modifier.weight(1f) else Modifier)
                    .clickable { onSelect(option) }
                    .then(if (isSelected) Modifier.border(1.dp, Nocturne.accent) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = if (fillWidth) Arrangement.Center else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) Nocturne.accent else Nocturne.text,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

enum class NocturneTagStyle { Neutral, Accent, Accent2, Outline }

/** ".tag" / ".tag-neutral" / ".tag-accent" / ".tag-outline": small filled or
 * outlined pills for source labels (SoundCloud/YT Music), mood tags, etc. */
@Composable
fun NocturneTag(text: String, modifier: Modifier = Modifier, style: NocturneTagStyle = NocturneTagStyle.Neutral) {
    val (bg, fg) = when (style) {
        NocturneTagStyle.Neutral -> Nocturne.neutral800 to Nocturne.neutral100
        NocturneTagStyle.Accent -> Nocturne.accent800 to Nocturne.accent100
        NocturneTagStyle.Accent2 -> Nocturne.accent2_800 to Nocturne.accent2_100
        NocturneTagStyle.Outline -> Color.Transparent to Nocturne.accent
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .let { if (style == NocturneTagStyle.Outline) it.border(1.dp, Nocturne.accent, RoundedCornerShape(6.dp)) else it }
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}
