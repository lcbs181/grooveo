package dev.schlubbe.musicagent.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Coarse screen-width bucket used to nudge a handful of dimensions (thumbnail
 * sizes, edge padding, the Player screen's album art) up or down per physical
 * phone size, without pulling in Compose Material3's `windowsizeclass`
 * artifact -- this app only ever needs 3 buckets keyed on one signal (width),
 * not the full width x height x posture matrix that library models.
 *
 * Breakpoints mirror Android's own generalized device buckets: COMPACT is
 * roughly a Pixel 4a-class small phone (~5.8"), NORMAL covers the bulk of
 * modern phones (~6.1-6.5", and is where every dp constant in this codebase
 * was originally tuned), LARGE covers phablets (~6.7"+) and also catches
 * tablets/unfolded foldables on the way up, even though neither is a primary
 * target here.
 */
enum class ScreenSizeClass {
    COMPACT, // screenWidthDp < 360
    NORMAL, // 360..411
    LARGE, // > 411
}

@Composable
fun rememberScreenSizeClass(): ScreenSizeClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) {
        when {
            widthDp < 360 -> ScreenSizeClass.COMPACT
            widthDp <= 411 -> ScreenSizeClass.NORMAL
            else -> ScreenSizeClass.LARGE
        }
    }
}

/** Centralized set of dimensions that scale by [ScreenSizeClass], so screens
 * don't each hand-roll their own compact/normal/large branching. Every NORMAL
 * value below is deliberately identical to this codebase's pre-existing
 * hardcoded constant (e.g. [dev.schlubbe.musicagent.ui.components.TrackThumbnail]'s
 * 48.dp default, or the 20.dp screen-edge padding Home/Search/Library all
 * used) -- so a NORMAL-bucket phone (the original reference size) renders
 * pixel-identical to before this change, and only COMPACT/LARGE devices see
 * the scaled values. */
data class ResponsiveDimens(
    /** Screen-edge horizontal padding used by Home/Search/Library's headers, shelves, and rows. */
    val horizontalPadding: Dp,
    /** Wider horizontal padding around the Player screen's centered content column. */
    val playerContentPadding: Dp,
    /** Hard cap on the Player screen's album art width, so tablet/foldable-width screens
     * don't blow a fraction-of-screen-width album art up to an unreasonable size. */
    val playerAlbumArtMaxWidth: Dp,
    /** Default row thumbnail (Search/Library list rows, Player's "Als Nächstes" queue). */
    val listThumbnail: Dp,
    /** Home's "Weiter hören" resume card thumbnail. */
    val resumeThumbnail: Dp,
    /** Home's "Empfehlung des Tages" daily-pick card thumbnail. */
    val dailyPickThumbnail: Dp,
    /** Home's square shelf tiles (Charts, Für dich, Zuletzt gehört, Deine Likes). */
    val shelfThumbnail: Dp,
    /** Home's small "Weiter hören" 2x2 continue-grid tiles. */
    val continueTileThumbnail: Dp,
    /** The persistent mini-player bar's thumbnail. */
    val miniPlayerThumbnail: Dp,
    /** The persistent mini-player bar's horizontal edge padding. */
    val miniPlayerPadding: Dp,
    /** Bottom nav bar icon size. */
    val bottomNavIconSize: Dp,
)

private val CompactDimens = ResponsiveDimens(
    horizontalPadding = 16.dp,
    playerContentPadding = 24.dp,
    playerAlbumArtMaxWidth = 280.dp,
    listThumbnail = 44.dp,
    resumeThumbnail = 46.dp,
    dailyPickThumbnail = 50.dp,
    shelfThumbnail = 92.dp,
    continueTileThumbnail = 34.dp,
    miniPlayerThumbnail = 36.dp,
    miniPlayerPadding = 10.dp,
    bottomNavIconSize = 17.dp,
)

private val NormalDimens = ResponsiveDimens(
    horizontalPadding = 20.dp,
    playerContentPadding = 32.dp,
    playerAlbumArtMaxWidth = 360.dp,
    listThumbnail = 48.dp,
    resumeThumbnail = 52.dp,
    dailyPickThumbnail = 56.dp,
    shelfThumbnail = 104.dp,
    continueTileThumbnail = 38.dp,
    miniPlayerThumbnail = 40.dp,
    miniPlayerPadding = 12.dp,
    bottomNavIconSize = 19.dp,
)

private val LargeDimens = ResponsiveDimens(
    horizontalPadding = 24.dp,
    playerContentPadding = 40.dp,
    playerAlbumArtMaxWidth = 420.dp,
    listThumbnail = 56.dp,
    resumeThumbnail = 60.dp,
    dailyPickThumbnail = 64.dp,
    shelfThumbnail = 120.dp,
    continueTileThumbnail = 44.dp,
    miniPlayerThumbnail = 46.dp,
    miniPlayerPadding = 14.dp,
    bottomNavIconSize = 21.dp,
)

@Composable
fun rememberResponsiveDimens(): ResponsiveDimens {
    val sizeClass = rememberScreenSizeClass()
    return remember(sizeClass) {
        when (sizeClass) {
            ScreenSizeClass.COMPACT -> CompactDimens
            ScreenSizeClass.NORMAL -> NormalDimens
            ScreenSizeClass.LARGE -> LargeDimens
        }
    }
}
