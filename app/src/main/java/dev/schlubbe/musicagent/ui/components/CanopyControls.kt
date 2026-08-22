package dev.schlubbe.musicagent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.CanopyPillShape
import dev.schlubbe.musicagent.ui.theme.CanopyShapes

// Canopy's action components, ported from the handoff's own component source
// (_ds/copy-of-canopy-*/_ds_bundle.js) rather than from prose, since the
// previous design pass went wrong by inferring styling from a summary.
//
// Note how different this is from the Nocturne system it replaces: Canopy's
// primary Button is *filled* and *pill*-shaped, where Nocturne's was outlined
// and radius-md. Chips are pills too. Don't "correct" these toward Material
// defaults.

enum class CanopyButtonVariant { Primary, Secondary, Ghost }

/** Canopy `Button`. Pill radius, Archivo 700/14, 12x22 padding (ghost 10x8),
 * 8dp gap to an optional 16dp icon. Primary is a filled accent block with
 * accent-900 text; secondary is a surface block with a 1px divider border;
 * ghost is transparent with accent text. */
@Composable
fun CanopyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: CanopyButtonVariant = CanopyButtonVariant.Primary,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    block: Boolean = false,
) {
    val background = when (variant) {
        CanopyButtonVariant.Primary -> Canopy.accent
        CanopyButtonVariant.Secondary -> Canopy.surface
        CanopyButtonVariant.Ghost -> Color.Transparent
    }
    val contentColor = when (variant) {
        CanopyButtonVariant.Primary -> Canopy.accent900
        CanopyButtonVariant.Secondary -> Canopy.text
        CanopyButtonVariant.Ghost -> Canopy.accent
    }
    val horizontal = if (variant == CanopyButtonVariant.Ghost) 8.dp else 22.dp
    val vertical = if (variant == CanopyButtonVariant.Ghost) 10.dp else 12.dp

    Row(
        modifier = modifier
            .let { if (block) it.fillMaxWidth() else it }
            .clip(CanopyPillShape)
            .background(background)
            .let {
                if (variant == CanopyButtonVariant.Secondary) {
                    it.border(1.dp, Canopy.divider, CanopyPillShape)
                } else {
                    it
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = horizontal, vertical = vertical),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        }
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
        if (trailingIcon != null) {
            Icon(trailingIcon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        }
    }
}

/** Canopy `IconButton`. Circular by default; the glyph is 48% of the button
 * size. Canopy names its variants ghost/filled/surface, which are the same
 * three concepts as [CanopyButtonVariant]'s Ghost/Primary/Secondary, so the one
 * enum covers both components rather than introducing a parallel vocabulary.
 *
 * [badge] draws the coral notification dot (8dp, ringed in surface so it reads
 * against a busy background) that the design puts on Home's downloads action.
 * [shape] and [iconSize] override the circular/48% defaults for the few places
 * the screens call for a squared-off or differently-weighted target. */
@Composable
fun CanopyIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: CanopyButtonVariant = CanopyButtonVariant.Ghost,
    size: Dp = 40.dp,
    shape: Shape = CircleShape,
    iconSize: Dp = size * 0.48f,
    badge: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val background = when (variant) {
        CanopyButtonVariant.Ghost -> Color.Transparent
        CanopyButtonVariant.Primary -> Canopy.accent
        CanopyButtonVariant.Secondary -> Canopy.surface
    }
    val contentColor = when (variant) {
        CanopyButtonVariant.Ghost, CanopyButtonVariant.Secondary -> Canopy.text
        CanopyButtonVariant.Primary -> Color.White
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(background)
            .let {
                if (variant == CanopyButtonVariant.Secondary) {
                    it.border(1.dp, Canopy.divider, shape)
                } else {
                    it
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
        if (badge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(size * 0.06f)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Canopy.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Canopy.accent2),
            )
        }
    }
}

/** Canopy `Chip`: a pill filter/genre control, Archivo 700/13, 9x18 padding.
 * Active is a filled accent pill with white text and no border; inactive is a
 * surface pill with a 1px divider border. */
@Composable
fun CanopyChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(if (active) Canopy.accent else Canopy.surface, label = "chipBg")
    Box(
        modifier = modifier
            .clip(CanopyPillShape)
            .background(background)
            .let { if (!active) it.border(1.dp, Canopy.divider, CanopyPillShape) else it }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            color = if (active) Color.White else Canopy.text,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

/** Canopy `Toggle`: the pill switch that replaces Nocturne's segmented
 * "Aus/An" control for boolean settings. Track is 1.7x the knob height;
 * accent when on, neutral-300 when off. */
@Composable
fun CanopyToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    enabled: Boolean = true,
) {
    val track by animateColorAsState(if (checked) Canopy.accent else Canopy.neutral300, label = "toggleTrack")
    val knob = size - 4.dp
    // The design expresses knob position as flex-start/flex-end; animating the
    // leading inset gives the same result with a slide instead of a jump.
    val offset by animateDpAsState(if (checked) size * 1.7f - knob - 4.dp else 0.dp, label = "toggleKnob")
    Box(
        modifier = modifier
            .size(width = size * 1.7f, height = size)
            .clip(CanopyPillShape)
            .background(track)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = offset)
                .size(knob)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

enum class CanopyBadgeTone { Accent, Accent2, Neutral }
enum class CanopyBadgeVariant { Solid, Soft, Outline }

/** Canopy `Badge`: a pill label at label-sm/700, 4x10 padding. Replaces
 * Nocturne's squarer `Tag`. Solid fills with the tone; soft uses the tone at
 * 16% over the surface with tone-colored text; outline is a 1px tone ring. */
@Composable
fun CanopyBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: CanopyBadgeTone = CanopyBadgeTone.Accent2,
    variant: CanopyBadgeVariant = CanopyBadgeVariant.Soft,
) {
    val toneColor = when (tone) {
        CanopyBadgeTone.Accent -> Canopy.accent
        CanopyBadgeTone.Accent2 -> Canopy.accent2
        CanopyBadgeTone.Neutral -> Canopy.neutral600
    }
    val background = when (variant) {
        CanopyBadgeVariant.Solid -> toneColor
        CanopyBadgeVariant.Soft -> toneColor.copy(alpha = 0.16f)
        CanopyBadgeVariant.Outline -> Color.Transparent
    }
    val contentColor = if (variant == CanopyBadgeVariant.Solid) Color.White else toneColor
    Box(
        modifier = modifier
            .clip(CanopyPillShape)
            .background(background)
            .let {
                if (variant == CanopyBadgeVariant.Outline) {
                    it.border(1.dp, toneColor, CanopyPillShape)
                } else {
                    it
                }
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

/** Canopy `Avatar`: a circle on accent-200, initials in accent-800 at 36% of
 * the size. [ring] draws the 2dp accent ring the design uses on station cards
 * and the artist header. */
@Composable
fun CanopyAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    ring: Boolean = false,
    content: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Canopy.accent200)
            .let { if (ring) it.border(2.dp, Canopy.accent, CircleShape) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) {
            content()
        } else {
            Text(
                text = initials.take(2).uppercase(),
                color = Canopy.accent800,
                // Initials scale with the circle rather than sitting on the type
                // ramp, so this size is computed rather than taken from Typography.
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.36f).sp,
                ),
            )
        }
    }
}

/** Canopy `SectionHeader`: an uppercase title-sm kicker in neutral-400 with an
 * optional accent action on the right. */
@Composable
fun CanopySectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            color = Canopy.neutral400,
            style = MaterialTheme.typography.titleSmall,
        )
        if (action != null && onActionClick != null) {
            Text(
                text = action,
                color = Canopy.accent,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable(onClick = onActionClick),
            )
        }
    }
}

/** A flat, unelevated surface card at radius-md. Unlike Material `Card()`,
 * never carries shadow or tonal elevation. */
@Composable
fun Modifier.canopyCard(padding: Dp = 8.dp): Modifier = this
    .clip(CanopyShapes.medium)
    .background(Canopy.surface)
    .padding(padding)

// TODO(canopy): Nocturne leftover. Canopy's inventory has no segmented control
// -- boolean settings use CanopyToggle and filters use CanopyChip. Kept only so
// the not-yet-redesigned screens still compile; remove as each screen is ported.
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
            .clip(CanopyShapes.medium)
            .border(1.dp, Canopy.divider, CanopyShapes.medium),
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) {
                VerticalDivider(color = Canopy.divider, thickness = 1.dp, modifier = Modifier.fillMaxHeight())
            }
            val isSelected = option == selected
            Row(
                modifier = (if (fillWidth) Modifier.weight(1f) else Modifier)
                    .clickable { onSelect(option) }
                    .then(if (isSelected) Modifier.border(1.dp, Canopy.accent) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = if (fillWidth) Arrangement.Center else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) Canopy.accent else Canopy.text,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
