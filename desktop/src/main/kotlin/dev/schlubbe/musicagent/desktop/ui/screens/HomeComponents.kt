package dev.schlubbe.musicagent.desktop.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.WarningCircle
import dev.schlubbe.musicagent.desktop.ui.C
import dev.schlubbe.musicagent.desktop.ui.Cover
import dev.schlubbe.musicagent.desktop.ui.SectionHeader
import dev.schlubbe.musicagent.desktop.ui.Shelf

/** Shelf card with an optional accent caption line (e.g. the "Für dich" reason) and a right-click menu. */
@Composable
fun HomeCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    width: Dp = 176.dp,
    caption: String? = null,
    badge: String? = null,
    circle: Boolean = false,
    menu: (() -> List<ContextMenuItem>)? = null,
    onPlay: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val c = C.c
    val body = @Composable {
        Column(
            modifier.width(width).clip(RoundedCornerShape(12.dp)).hoverable(hover)
                .background(if (hovered) c.surfaceHigh else Color.Transparent).clickable(onClick = onClick).padding(8.dp),
            horizontalAlignment = if (circle) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Box {
                Cover(imageUrl, width - 16.dp, radius = 10.dp, circle = circle)
                if (badge != null) Text(
                    badge, style = MaterialTheme.typography.labelSmall, color = Color.White,
                    modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(c.accent2.copy(alpha = 0.92f)).padding(horizontal = 7.dp, vertical = 2.dp),
                )
                if (onPlay != null && hovered) Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(44.dp).clip(CircleShape).background(c.accent2).clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) { Icon(PhosphorIcons.Fill.Play, "Abspielen", tint = Color.White, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = if (circle) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = c.accent, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
    if (menu != null) ContextMenuArea(items = menu) { body() } else body()
}

/** Pulsing placeholder block. */
@Composable
fun HomeSkeletonBox(modifier: Modifier, radius: Dp = 10.dp) {
    val t = rememberInfiniteTransition()
    val a by t.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    Box(modifier.alpha(a).clip(RoundedCornerShape(radius)).background(C.c.surfaceHigh))
}

@Composable
fun HomeSkeletonShelf(count: Int = 7, width: Dp = 176.dp, circle: Boolean = false) {
    Shelf {
        items(count) {
            Column(Modifier.width(width).padding(8.dp)) {
                HomeSkeletonBox(Modifier.size(width - 16.dp), radius = if (circle) width else 10.dp)
                Spacer(Modifier.height(10.dp))
                HomeSkeletonBox(Modifier.width(width * 0.7f).height(12.dp), 4.dp)
                Spacer(Modifier.height(6.dp))
                HomeSkeletonBox(Modifier.width(width * 0.45f).height(10.dp), 4.dp)
            }
        }
    }
}

@Composable
fun HomeSkeletonRows(count: Int = 4) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                HomeSkeletonBox(Modifier.size(44.dp), 6.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    HomeSkeletonBox(Modifier.width(220.dp).height(12.dp), 4.dp)
                    Spacer(Modifier.height(6.dp))
                    HomeSkeletonBox(Modifier.width(140.dp).height(10.dp), 4.dp)
                }
            }
        }
    }
}

/** Compact inline error for a single shelf, with retry. */
@Composable
fun HomeShelfError(message: String, onRetry: () -> Unit) {
    val c = C.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceHigh).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(PhosphorIcons.Regular.WarningCircle, null, tint = c.accent2, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text("Konnte nicht geladen werden", style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(message, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onRetry) { Text("Erneut versuchen", color = c.accent) }
    }
}

/** Section header + body that renders skeleton / error / content depending on [load]. */
@Composable
fun <T> HomeSection(
    title: String,
    load: HomeLoad<T>,
    onRetry: () -> Unit,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    hideWhenEmpty: (T) -> Boolean = { false },
    skeleton: @Composable () -> Unit = { HomeSkeletonShelf() },
    content: @Composable (T) -> Unit,
) {
    if (load is HomeLoad.Ready && hideWhenEmpty(load.value)) return
    Column {
        SectionHeader(title, subtitle = subtitle, action = action, onAction = onAction)
        when (load) {
            HomeLoad.Loading -> skeleton()
            is HomeLoad.Failed -> HomeShelfError(load.message, onRetry)
            is HomeLoad.Ready -> content(load.value)
        }
    }
}
