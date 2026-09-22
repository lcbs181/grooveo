package dev.schlubbe.musicagent.ui.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Activity-scoped bridge for "open this route" requests coming from outside Compose
 * — currently just MainActivity's shared-link handling (see SharedLinkResolver). A
 * plain Hilt singleton rather than a ViewModel itself, since MainActivity (not a
 * Composable) is what writes to it; [SharedLinkNavViewModel] below is the thin
 * Compose-facing wrapper [MusicAgentNavGraph] actually collects, following the same
 * hiltViewModel() pattern every other screen already uses. */
@Singleton
class SharedLinkNavHolder @Inject constructor() {
    val pendingRoute = MutableStateFlow<String?>(null)
}

@HiltViewModel
class SharedLinkNavViewModel @Inject constructor(
    private val holder: SharedLinkNavHolder,
) : ViewModel() {
    val pendingRoute: StateFlow<String?> = holder.pendingRoute.asStateFlow()

    /** Called once [MusicAgentNavGraph] has actually navigated to [pendingRoute], so
     * the same target isn't re-navigated to again on the next recomposition/config
     * change. */
    fun consumed() {
        holder.pendingRoute.value = null
    }
}
