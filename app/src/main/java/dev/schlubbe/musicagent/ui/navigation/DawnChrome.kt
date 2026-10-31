package dev.schlubbe.musicagent.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.ui.components.MiniPlayerBar
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.theme.glassSurface

data class DawnTab(val route: String, val label: String, val iconName: String)

private val BarShape = RoundedCornerShape(40.dp)
private val MiniShape = RoundedCornerShape(32.dp)

/** Dawn Music's bottom chrome: the mini player and a floating tab bar, both
 * glass panes over the screen content (see glassSurface). A soft dusk gradient
 * under them keeps the last list rows from fighting with the labels. */
@Composable
fun DawnBottomChrome(
    items: List<DawnTab>,
    currentRoute: String?,
    onMiniPlayerClick: () -> Unit,
    onTabClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Canopy.bg.copy(alpha = 0.55f)))),
        )
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 10.dp)) {
            MiniPlayerBar(onClick = onMiniPlayerClick, surface = { glassSurface(MiniShape) })
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .glassSurface(BarShape)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                items.forEach { tab ->
                    val selected = currentRoute == tab.route
                    val tint by animateColorAsState(if (selected) Canopy.accent700 else Canopy.neutral600, label = "tab")
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(34.dp))
                            .background(
                                if (selected) {
                                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), Canopy.accent100.copy(alpha = 0.55f)))
                                } else {
                                    Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                                },
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onTabClick(tab.route) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            phosphorIcon(tab.iconName, filled = selected),
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            tab.label,
                            color = tint,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
    }
}
