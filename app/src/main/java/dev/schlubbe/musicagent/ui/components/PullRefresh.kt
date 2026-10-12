package dev.schlubbe.musicagent.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.schlubbe.musicagent.ui.util.rememberPremiumHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shortest time the spinner stays, so a reload that answers at once still reads as one. */
private const val MIN_REFRESH_MS = 500L

/**
 * "Pull down to refresh" for a page. [content] must scroll vertically (LazyColumn,
 * verticalScroll, ...). The spinner stays until the job [onRefresh] returns has
 * finished; a null job (nothing to reload) only shows it briefly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefresh(
    onRefresh: () -> Job?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberPremiumHaptics()
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (refreshing) return@PullToRefreshBox
            refreshing = true
            haptics.select()
            scope.launch {
                val started = System.currentTimeMillis()
                try {
                    currentOnRefresh()?.join()
                    delay(MIN_REFRESH_MS - (System.currentTimeMillis() - started))
                } finally {
                    refreshing = false
                }
            }
        },
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        content = content,
    )
}
