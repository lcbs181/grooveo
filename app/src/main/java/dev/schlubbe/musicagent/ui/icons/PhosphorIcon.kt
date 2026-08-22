package dev.schlubbe.musicagent.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.Pause
import com.adamglin.phosphoricons.regular.Play
import com.adamglin.phosphoricons.regular.DotsThree
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.Stack
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.CaretLeft
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.PencilSimple
import com.adamglin.phosphoricons.regular.GearSix
import com.adamglin.phosphoricons.regular.User
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.WarningCircle
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.ArrowCircleDown
import com.adamglin.phosphoricons.regular.ClockCounterClockwise
import com.adamglin.phosphoricons.regular.Moon
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.SkipBack
import com.adamglin.phosphoricons.regular.SkipForward
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.PlusCircle
import com.adamglin.phosphoricons.regular.ListPlus
import com.adamglin.phosphoricons.regular.UserCircle
import com.adamglin.phosphoricons.regular.X
import com.adamglin.phosphoricons.regular.Waveform
import com.adamglin.phosphoricons.regular.EnvelopeSimple
import com.adamglin.phosphoricons.regular.Camera
import com.adamglin.phosphoricons.regular.MapTrifold
import com.adamglin.phosphoricons.regular.ChatCircle
import com.adamglin.phosphoricons.regular.Prohibit
import com.adamglin.phosphoricons.regular.FilmSlate
import com.adamglin.phosphoricons.regular.House
import com.adamglin.phosphoricons.regular.MicrophoneStage
import com.adamglin.phosphoricons.regular.VinylRecord
import com.adamglin.phosphoricons.regular.Church
import com.adamglin.phosphoricons.regular.HandSwipeLeft
import com.adamglin.phosphoricons.regular.CloudArrowUp
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.PlayCircle
import com.adamglin.phosphoricons.regular.PauseCircle
import com.adamglin.phosphoricons.regular.ShareNetwork
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.LockSimple
import com.adamglin.phosphoricons.regular.ArrowRight
import com.adamglin.phosphoricons.regular.CellSignalSlash
import com.adamglin.phosphoricons.regular.Cloud
import com.adamglin.phosphoricons.regular.YoutubeLogo
import com.adamglin.phosphoricons.regular.UserPlus
import com.adamglin.phosphoricons.regular.CircleHalf
import com.adamglin.phosphoricons.regular.PlugsConnected
import com.adamglin.phosphoricons.regular.Plugs
import com.adamglin.phosphoricons.regular.Books
import com.adamglin.phosphoricons.regular.GameController
import com.adamglin.phosphoricons.regular.DotsThreeVertical
import com.adamglin.phosphoricons.regular.DotsSixVertical
import com.adamglin.phosphoricons.regular.SealCheck
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.Globe
import com.adamglin.phosphoricons.regular.Key
import com.adamglin.phosphoricons.regular.SquaresFour
import com.adamglin.phosphoricons.regular.DesktopTower
import com.adamglin.phosphoricons.regular.Desktop
import com.adamglin.phosphoricons.regular.Backspace
import com.adamglin.phosphoricons.regular.ArrowCounterClockwise
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Timer
import com.adamglin.phosphoricons.regular.Equals
import com.adamglin.phosphoricons.regular.CirclesThree
import com.adamglin.phosphoricons.regular.ChartBar
import com.adamglin.phosphoricons.regular.Pulse
import com.adamglin.phosphoricons.regular.Sphere
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.Link
import com.adamglin.phosphoricons.regular.WifiHigh
import com.adamglin.phosphoricons.regular.HardDrives
import com.adamglin.phosphoricons.regular.ShuffleAngular
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.Broadcast
import com.adamglin.phosphoricons.fill.Cloud
import com.adamglin.phosphoricons.fill.YoutubeLogo
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.User
import com.adamglin.phosphoricons.fill.UserPlus
import com.adamglin.phosphoricons.fill.House
import com.adamglin.phosphoricons.fill.MagnifyingGlass
import com.adamglin.phosphoricons.fill.Stack
import com.adamglin.phosphoricons.fill.DownloadSimple
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.fill.PlayCircle
import com.adamglin.phosphoricons.fill.PauseCircle
import com.adamglin.phosphoricons.fill.Waveform
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play

/**
 * Maps the kebab-case names used by the design's `ph`/`ph-fill` classes
 * (design_handoff_grooveo/Grooveo*.dc.html) straight onto
 * com.adamglin:phosphor-icon's generated ImageVectors, so a screen can be
 * checked directly against `class="ph[-fill] ph-<name>"` in the markup
 * instead of guessing a Material equivalent.
 */
fun phosphorIcon(name: String, filled: Boolean = false): ImageVector = if (filled) {
    when (name) {
        "heart" -> PhosphorIcons.Fill.Heart
        "check-circle" -> PhosphorIcons.Fill.CheckCircle
        "play-circle" -> PhosphorIcons.Fill.PlayCircle
        "pause-circle" -> PhosphorIcons.Fill.PauseCircle
        "waveform" -> PhosphorIcons.Fill.Waveform
        "pause" -> PhosphorIcons.Fill.Pause
        "play" -> PhosphorIcons.Fill.Play
        "cloud" -> PhosphorIcons.Fill.Cloud
        "youtube-logo" -> PhosphorIcons.Fill.YoutubeLogo
        "user" -> PhosphorIcons.Fill.User
        "user-plus" -> PhosphorIcons.Fill.UserPlus
        "house" -> PhosphorIcons.Fill.House
        "magnifying-glass" -> PhosphorIcons.Fill.MagnifyingGlass
        "stack" -> PhosphorIcons.Fill.Stack
        "download-simple" -> PhosphorIcons.Fill.DownloadSimple
        else -> phosphorIcon(name, filled = false)
    }
} else {
    when (name) {
        "arrow-right" -> PhosphorIcons.Regular.ArrowRight
        "cell-signal-slash" -> PhosphorIcons.Regular.CellSignalSlash
        "user-plus" -> PhosphorIcons.Regular.UserPlus
        "circle-half" -> PhosphorIcons.Regular.CircleHalf
        "plugs-connected" -> PhosphorIcons.Regular.PlugsConnected
        "plugs" -> PhosphorIcons.Regular.Plugs
        "books" -> PhosphorIcons.Regular.Books
        "game-controller" -> PhosphorIcons.Regular.GameController
        "dots-three-vertical" -> PhosphorIcons.Regular.DotsThreeVertical
        "dots-six-vertical" -> PhosphorIcons.Regular.DotsSixVertical
        "seal-check" -> PhosphorIcons.Regular.SealCheck
        "info" -> PhosphorIcons.Regular.Info
        "globe" -> PhosphorIcons.Regular.Globe
        "key" -> PhosphorIcons.Regular.Key
        "squares-four" -> PhosphorIcons.Regular.SquaresFour
        "desktop-tower" -> PhosphorIcons.Regular.DesktopTower
        "desktop" -> PhosphorIcons.Regular.Desktop
        "backspace" -> PhosphorIcons.Regular.Backspace
        "arrow-counter-clockwise" -> PhosphorIcons.Regular.ArrowCounterClockwise
        "queue" -> PhosphorIcons.Regular.Queue
        "timer" -> PhosphorIcons.Regular.Timer
        "equals" -> PhosphorIcons.Regular.Equals
        "circles-three" -> PhosphorIcons.Regular.CirclesThree
        "chart-bar" -> PhosphorIcons.Regular.ChartBar
        "pulse" -> PhosphorIcons.Regular.Pulse
        "sphere" -> PhosphorIcons.Regular.Sphere
        "sparkle" -> PhosphorIcons.Regular.Sparkle
        "arrow-left" -> PhosphorIcons.Regular.ArrowLeft
        "link" -> PhosphorIcons.Regular.Link
        "wifi-high" -> PhosphorIcons.Regular.WifiHigh
        "hard-drives" -> PhosphorIcons.Regular.HardDrives
        "shuffle-angular" -> PhosphorIcons.Regular.ShuffleAngular
        "speaker-high" -> PhosphorIcons.Regular.SpeakerHigh
        "broadcast" -> PhosphorIcons.Regular.Broadcast
        "cloud" -> PhosphorIcons.Regular.Cloud
        "youtube-logo" -> PhosphorIcons.Regular.YoutubeLogo
        "magnifying-glass" -> PhosphorIcons.Regular.MagnifyingGlass
        "check" -> PhosphorIcons.Regular.Check
        "shuffle" -> PhosphorIcons.Regular.Shuffle
        "pause" -> PhosphorIcons.Regular.Pause
        "play" -> PhosphorIcons.Regular.Play
        "dots-three" -> PhosphorIcons.Regular.DotsThree
        "download-simple" -> PhosphorIcons.Regular.DownloadSimple
        "stack" -> PhosphorIcons.Regular.Stack
        "plus" -> PhosphorIcons.Regular.Plus
        "caret-left" -> PhosphorIcons.Regular.CaretLeft
        "caret-right" -> PhosphorIcons.Regular.CaretRight
        "caret-down" -> PhosphorIcons.Regular.CaretDown
        "pencil-simple" -> PhosphorIcons.Regular.PencilSimple
        "gear-six" -> PhosphorIcons.Regular.GearSix
        "user" -> PhosphorIcons.Regular.User
        "heart" -> PhosphorIcons.Regular.Heart
        "warning-circle" -> PhosphorIcons.Regular.WarningCircle
        "sparkle" -> PhosphorIcons.Regular.Sparkle
        "arrow-circle-down" -> PhosphorIcons.Regular.ArrowCircleDown
        "clock-counter-clockwise" -> PhosphorIcons.Regular.ClockCounterClockwise
        "moon" -> PhosphorIcons.Regular.Moon
        "sliders-horizontal" -> PhosphorIcons.Regular.SlidersHorizontal
        "skip-back" -> PhosphorIcons.Regular.SkipBack
        "skip-forward" -> PhosphorIcons.Regular.SkipForward
        "repeat" -> PhosphorIcons.Regular.Repeat
        "repeat-once" -> PhosphorIcons.Regular.RepeatOnce
        "check-circle" -> PhosphorIcons.Regular.CheckCircle
        "plus-circle" -> PhosphorIcons.Regular.PlusCircle
        "list-plus" -> PhosphorIcons.Regular.ListPlus
        "user-circle" -> PhosphorIcons.Regular.UserCircle
        "x" -> PhosphorIcons.Regular.X
        "waveform" -> PhosphorIcons.Regular.Waveform
        "envelope-simple" -> PhosphorIcons.Regular.EnvelopeSimple
        "camera" -> PhosphorIcons.Regular.Camera
        "map-trifold" -> PhosphorIcons.Regular.MapTrifold
        "chat-circle" -> PhosphorIcons.Regular.ChatCircle
        "prohibit" -> PhosphorIcons.Regular.Prohibit
        "film-slate" -> PhosphorIcons.Regular.FilmSlate
        "house" -> PhosphorIcons.Regular.House
        "microphone-stage" -> PhosphorIcons.Regular.MicrophoneStage
        "vinyl-record" -> PhosphorIcons.Regular.VinylRecord
        "church" -> PhosphorIcons.Regular.Church
        "hand-swipe-left" -> PhosphorIcons.Regular.HandSwipeLeft
        "cloud-arrow-up" -> PhosphorIcons.Regular.CloudArrowUp
        "trash" -> PhosphorIcons.Regular.Trash
        "play-circle" -> PhosphorIcons.Regular.PlayCircle
        "pause-circle" -> PhosphorIcons.Regular.PauseCircle
        "share-network" -> PhosphorIcons.Regular.ShareNetwork
        "arrow-clockwise" -> PhosphorIcons.Regular.ArrowClockwise
        "lock-simple" -> PhosphorIcons.Regular.LockSimple
        else -> PhosphorIcons.Regular.WarningCircle
    }
}
