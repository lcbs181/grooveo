package dev.schlubbe.musicagent.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.schlubbe.musicagent.ui.artist.ArtistFollowersScreen
import dev.schlubbe.musicagent.ui.artist.ArtistScreen
import dev.schlubbe.musicagent.ui.components.MiniPlayerBar
import dev.schlubbe.musicagent.ui.home.HomeScreen
import dev.schlubbe.musicagent.ui.library.LibraryScreen
import dev.schlubbe.musicagent.ui.player.PlayerScreen
import dev.schlubbe.musicagent.ui.playlist.PlaylistDetailScreen
import dev.schlubbe.musicagent.ui.search.SearchScreen
import dev.schlubbe.musicagent.ui.settings.SettingsScreen
import dev.schlubbe.musicagent.ui.update.UpdateDialog
import dev.schlubbe.musicagent.ui.update.UpdateViewModel

// Backend-less variant: no login/register/Feed routes at all (see
// data/repository/AuthRepository's silent service-account login for how analytics
// events still reach the backend without a visible login screen).
object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val LIBRARY = "library"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    const val ARTIST_DETAIL = "artist/{source}/{sourceId}"
    const val ARTIST_FOLLOWERS = "artist/{source}/{sourceId}/followers"

    fun playlistDetail(playlistId: String) = "playlist/$playlistId"
    fun artistDetail(source: String, sourceId: String) = "artist/$source/${Uri.encode(sourceId)}"
    fun artistFollowers(source: String, sourceId: String) = "artist/$source/${Uri.encode(sourceId)}/followers"
}

private data class BottomNavItem(val route: String, val label: String, val icon: ImageVector)

private val BOTTOM_NAV_ITEMS = listOf(
    BottomNavItem(Routes.HOME, "Start", Icons.Filled.Home),
    BottomNavItem(Routes.SEARCH, "Suche", Icons.Filled.Search),
    BottomNavItem(Routes.LIBRARY, "Bibliothek", Icons.Filled.LibraryMusic),
    BottomNavItem(Routes.SETTINGS, "Konto", Icons.Filled.AccountCircle),
)

// The bottom mini-player + nav bar never appears on the full Player screen itself --
// its own controls already fill the screen, so a duplicate mini-player would be
// redundant.
private val BOTTOM_BAR_HIDDEN_ROUTES = setOf(Routes.PLAYER)

@Composable
fun MusicAgentNavGraph(
    navController: NavHostController = rememberNavController(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute !in BOTTOM_BAR_HIDDEN_ROUTES
    val haptic = LocalHapticFeedback.current

    // Best-effort against the optional service-account-backed real backend (see
    // AuthRepository) - silent no-op if unreachable/not configured, see UpdateDialog.
    LaunchedEffect(Unit) { updateViewModel.checkForUpdate(silent = true) }
    UpdateDialog(updateViewModel)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Column {
                    MiniPlayerBar(onClick = { navController.navigate(Routes.PLAYER) })
                    NavigationBar {
                        BOTTOM_NAV_ITEMS.forEach { item ->
                            NavigationBarItem(
                                selected = currentRoute == item.route,
                                onClick = {
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
                                },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
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
            composable(Routes.HOME) {
                HomeScreen(
                    onTrackSelected = { navController.navigate(Routes.PLAYER) },
                    onSearchClick = { navController.navigate(Routes.SEARCH) },
                    onPlaylistClick = { playlistId -> navController.navigate(Routes.playlistDetail(playlistId)) },
                    onSeeAllPlaylistsClick = { navController.navigate(Routes.LIBRARY) },
                    onSeeAllLikesClick = { navController.navigate(Routes.LIBRARY) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onTrackSelected = { navController.navigate(Routes.PLAYER) },
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                    onLibraryClick = { navController.navigate(Routes.LIBRARY) },
                    onArtistSelected = { source, sourceId ->
                        navController.navigate(Routes.artistDetail(source, sourceId))
                    },
                )
            }
            composable(Routes.PLAYER) {
                PlayerScreen(
                    onArtistSelected = { source, sourceId ->
                        navController.navigate(Routes.artistDetail(source, sourceId))
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onDownloadPlayed = { navController.navigate(Routes.PLAYER) },
                    onPlaylistClick = { playlistId -> navController.navigate(Routes.playlistDetail(playlistId)) },
                    onArtistSelected = { source, sourceId ->
                        navController.navigate(Routes.artistDetail(source, sourceId))
                    },
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
        }
    }
}
