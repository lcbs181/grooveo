package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.schlubbe.musicagent.BuildConfig
import dev.schlubbe.musicagent.R
import dev.schlubbe.musicagent.ui.theme.Canopy

/** "Nothing here yet" line. The Dawn Music puts its watercolour dolphin above it, so an
 * empty list looks like a friendly start rather than a gap. */
@Composable
fun EmptyState(text: String, horizontalPadding: Dp = 16.dp) {
    if (!BuildConfig.DAWN) {
        Text(text, color = Canopy.neutral500, modifier = Modifier.padding(horizontalPadding))
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(painterResource(R.drawable.dawn_dolphin), contentDescription = null, modifier = Modifier.height(150.dp))
        Text(text, color = Canopy.neutral500, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
