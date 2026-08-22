package dev.schlubbe.musicagent.ui.navigation

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.schlubbe.musicagent.ui.account.AccountScreen
import dev.schlubbe.musicagent.ui.artist.ArtistFollowersScreen
import dev.schlubbe.musicagent.ui.artist.ArtistScreen
import dev.schlubbe.musicagent.ui.components.CanopyOverlayHost
import dev.schlubbe.musicagent.ui.components.CanopyOverlayState
import dev.schlubbe.musicagent.ui.components.LocalCanopyOverlay
import dev.schlubbe.musicagent.ui.components.MiniPlayerBar
import dev.schlubbe.musicagent.ui.connect.ConnectScreen
import dev.schlubbe.musicagent.ui.downloads.DownloadsScreen
import dev.schlubbe.musicagent.ui.home.HomeScreen
import dev.schlubbe.musicagent.ui.library.LibraryScreen
import dev.schlubbe.musicagent.ui.player.PlayerScreen
import dev.schlubbe.musicagent.ui.playlist.PlaylistDetailScreen
import dev.schlubbe.musicagent.ui.playlist.RemotePlaylistDetailScreen
import dev.schlubbe.musicagent.ui.search.SearchScreen
import dev.schlubbe.musicagent.ui.icons.phosphorIcon
import dev.schlubbe.musicagent.ui.onboarding.OnboardingScreen
import dev.schlubbe.musicagent.ui.onboarding.OnboardingViewModel
import dev.schlubbe.musicagent.ui.settings.EqualizerScreen
import dev.schlubbe.musicagent.ui.settings.SettingsScreen
import dev.schlubbe.musicagent.ui.theme.Canopy
import dev.schlubbe.musicagent.ui.update.UpdateDialog
import dev.schlubbe.musicagent.ui.util.rememberResponsiveDimens
import dev.schlubbe.musicagent.ui.update.UpdateViewModel
import dev.schlubbe.musicagent.ui.whatsnew.WhatsNewScreen

// Backend-less variant: no login/register/Feed routes at all (see
// data/repository/AuthRepository's silent service-account login for how analytics
// events still reach the backend without a visible login screen).
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val SEARCH = "search"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val LIBRARY = "library"
    const val ACCOUNT = "account"
    const val DOWNLOADS = "downloads"
    const val CONNECT = "connect_pc"
    const val EQUALIZER = "equalizer"
    const val WHATS_NEW = "whats_new"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    const val ARTIST_DETAIL = "artist/{source}/{sourceId}"
    const val ARTIST_FOLLOWERS = "artist/{source}/{sourceId}/followers"
    const val REMOTE_PLAYLIST_DETAIL = "remote_playlist/{source}/{sourceId}"

    fun playlistDetail(playlistId: String) = "playlist/$playlistId"
    fun artistDetail(source: String, sourceId: String) = "artist/$source/${Uri.encode(sourceId)}"
    fun artistFollowers(source: String, sourceId: String) = "artist/$source/${Uri.encode(sourceId)}/followers"
    fun remotePlaylistDetail(source: String, sourceId: String) = "remote_playlist/$source/${Uri.encode(sourceId)}"
}

private data class BottomNavItem(val route: String, val label: String, val iconName: String)

// Canopy's TabBar. The redesign drops the Konto tab entirely -- this is a
// standalone player with no account -- and promotes Downloads to a top-level
// destination in its place.
private val BOTTOM_NAV_ITEMS = listOf(
    BottomNavItem(Routes.HOME, "Start", "house"),
    BottomNavItem(Routes.SEARCH, "Suche", "magnifying-glass"),
    BottomNavItem(Routes.LIBRARY, "Bibliothek", "stack"),
    BottomNavItem(Routes.DOWNLOADS, "Downloads", "download-simple"),
)

// The bottom mini-player + nav bar never appears on the full Player screen itself --
// its own controls already fill the screen, so a duplicate mini-player would be
// redundant. Settings and its sub-screens are excluded too: the design shows them
// as full-screen destinations reached by drilling in, with no tab bar.
private val BOTTOM_BAR_HIDDEN_ROUTES = setOf(
    Routes.PLAYER,
    Routes.WHATS_NEW,
    Routes.ONBOARDING,
    Routes.SETTINGS,
    Routes.EQUALIZER,
    Routes.CONNECT,
)

@Composable
fun MusicAgentNavGraph(
    navController: NavHostController = rememberNavController(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute !in BOTTOM_BAR_HIDDEN_ROUTES
    val haptic = LocalHapticFeedback.current
    val dimens = rememberResponsiveDimens()

    // Best-effort against the optional service-account-backed real backend (see
    // AuthRepository) - silent no-op if unreachable/not configured, see UpdateDialog.
    LaunchedEffect(Unit) { updateViewModel.checkForUpdate(silent = true) }
    UpdateDialog(updateViewModel)

    // Redirects to onboarding right after the start destination's first frame if this
    // install has never completed it - fires once the DataStore read resolves (null
    // means "still loading", so this deliberately waits rather than flashing Home).
    val shouldShowOnboarding by onboardingViewModel.shouldShowOnboarding.collectAsState()
    LaunchedEffect(shouldShowOnboarding) {
        if (shouldShowOnboarding == true && currentRoute != Routes.ONBOARDING) {
            navController.navigate(Routes.ONBOARDING) {
                popUpTo(Routes.HOME) { inclusive = true }
            }
        }
    }

    val overlayState = remember { CanopyOverlayState() }
    CompositionLocalProvider(LocalCanopyOverlay provides overlayState) {
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Canopy.bg,
        bottomBar = {
            if (showBottomBar) {
                Column {
                    // The mini player floats *above* the bar as its own pill rather
                    // than sitting flush against it, per the design.
                    MiniPlayerBar(onClick = { navController.navigate(Routes.PLAYER) })
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = Canopy.divider, thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Canopy.surface)
                            .padding(vertical = 8.dp),
                    ) {
                        BOTTOM_NAV_ITEMS.forEach { item ->
                            val selected = currentRoute == item.route
                            val tint = if (selected) Canopy.accent else Canopy.neutral400
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    // The clickable (and its default ripple) is bounded to
                                    // whatever it wraps. The top/bottom padding used to live
                                    // on the outer Row, *outside* this clickable, which left a
                                    // dead-space margin above/below each item that wasn't part
                                    // of its touch/ripple region (bug: "touch fields ...
                                    // don't fill the nav bar completely"). Moving the padding
                                    // to after .clickable() here makes it part of the bounds
                                    // clickable reports, so the ripple fills the full item
                                    // height with no visual layout change.
                                    .clickable {
                                        if (currentRoute != item.route) {
                                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        }
                                        navController.navigate(item.route) {
                                            launchSingleTop = true
                                            restoreState = true
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    phosphorIcon(item.iconName, filled = selected),
                                    contentDescription = item.label,
                                    tint = tint,
                                    modifier = Modifier.size(22.dp),
                                )
                                Text(
                                    item.label,
                                    color = tint,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { scaffoldPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(scaffoldPadding),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    viewModel = onboardingViewModel,
                    onFinished = {
                        onboardingViewModel.onFinished()
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onTrackSelected = { navController.navigate(Routes.PLAYER) },
                    onSearchClick = { navController.navigate(Routes.SEARCH) },
                    onSeeAllArtistsClick = { navController.navigate(Routes.LIBRARY) },
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                    onDownloadsClick = { navController.navigate(Routes.DOWNLOADS) },
                    onArtistClick = { source, sourceId ->
                        navController.navigate(Routes.artistDetail(source, sourceId))
                    },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onTrackSelected = { navController.navigate(Routes.PLAYER) },
                    onArtistSelected = { source, sourceId ->
                        navController.navigate(Routes.artistDetail(source, sourceId))
                    },
                    onPlaylistSelected = { source, sourceId ->
                        navController.navigate(Routes.remotePlaylistDetail(source, sourceId))
                    },
                )
            }
            composable(Routes.PLAYER) {
                PlayerScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onArtistSelected = { source, sourceId ->
                        navController.navigate(Routes.artistDetail(source, sourceId))
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onWhatsNewClick = { navController.navigate(Routes.WHATS_NEW) },
                    onNavigateToEqualizer = { navController.navigate(Routes.EQUALIZER) },
                    onConnectPcClick = { navController.navigate(Routes.CONNECT) },
                )
            }
            composable(Routes.WHATS_NEW) {
                WhatsNewScreen(onDone = { navController.popBackStack() })
            }
            composable(Routes.ACCOUNT) {
                AccountScreen(
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                    onArtistSelected = { source, sourceId ->
                        navController.navigate(Routes.artistDetail(source, sourceId))
                    },
                )
            }
            composable(Routes.DOWNLOADS) {
                DownloadsScreen()
            }
            composable(Routes.CONNECT) {
                ConnectScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Routes.EQUALIZER) {
                EqualizerScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onDownloadPlayed = { navController.navigate(Routes.PLAYER) },
                    onPlaylistClick = { playlistId -> navController.navigate(Routes.playlistDetail(playlistId)) },
                    onSavedPlaylistClick = { source, sourceId ->
                        navController.navigate(Routes.remotePlaylistDetail(source, sourceId))
                    },
                    onArtistSelected = { source, sourceId ->
                        navController.navigate(Routes.artistDetail(source, sourceId))
                    },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(
                Routes.PLAYLIST_DETAIL,
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
            ) {
                PlaylistDetailScreen(
                    onTrackSelected = { navController.navigate(Routes.PLAYER) },
                    onNavigateBack = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack() },
                    onArtistSelected = { source, sourceId ->
                        navController.navigate(Routes.artistDetail(source, sourceId))
                    },
                )
            }
            composable(
                Routes.ARTIST_DETAIL,
                arguments = listOf(
                    navArgument("source") { type = NavType.StringType },
                    navArgument("sourceId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val artistSource = backStackEntry.arguments?.getString("source").orEmpty()
                val artistSourceId = backStackEntry.arguments?.getString("sourceId").orEmpty()
                ArtistScreen(
                    source = artistSource,
                    sourceId = artistSourceId,
                    onTrackSelected = { navController.navigate(Routes.PLAYER) },
                    onFollowersSelected = {
                        navController.navigate(Routes.artistFollowers(artistSource, artistSourceId))
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(
                Routes.ARTIST_FOLLOWERS,
                arguments = listOf(
                    navArgument("source") { type = NavType.StringType },
                    navArgument("sourceId") { type = NavType.StringType },
                ),
            ) {
                ArtistFollowersScreen(
                    onArtistSelected = { source, sourceId ->
                        navController.navigate(Routes.artistDetail(source, sourceId))
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(
                Routes.REMOTE_PLAYLIST_DETAIL,
                arguments = listOf(
                    navArgument("source") { type = NavType.StringType },
                    navArgument("sourceId") { type = NavType.StringType },
                ),
            ) {
                RemotePlaylistDetailScreen(
                    onTrackSelected = { navController.navigate(Routes.PLAYER) },
                    onNavigateBack = { navController.popBackStack() },
                    onArtistSelected = { source, sourceId ->
                        navController.navigate(Routes.artistDetail(source, sourceId))
                    },
                )
            }
        }
    }
        // Window-level, above every screen and the bottom bar, so a follow spray
        // covers the whole display rather than being clipped to its button.
        CanopyOverlayHost(overlayState)
    }
    }
}
