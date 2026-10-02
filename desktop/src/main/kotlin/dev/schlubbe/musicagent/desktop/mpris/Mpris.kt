package dev.schlubbe.musicagent.desktop.mpris

import dev.schlubbe.musicagent.desktop.data.key
import dev.schlubbe.musicagent.desktop.playback.PlayerController
import dev.schlubbe.musicagent.desktop.playback.RepeatMode
import dev.schlubbe.musicagent.desktop.ui.AppUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.mpris.MediaPlayer2")
@Suppress("FunctionName")
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
@Suppress("FunctionName")
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)

    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}

/** Pure mapping of player state to MPRIS property values (unit-tested). */
object MprisMapping {
    fun playbackStatus(playing: Boolean, hasTrack: Boolean) = when { playing -> "Playing"; hasTrack -> "Paused"; else -> "Stopped" }
    fun loopStatus(r: RepeatMode) = when (r) { RepeatMode.OFF -> "None"; RepeatMode.ALL -> "Playlist"; RepeatMode.ONE -> "Track" }
    fun repeatFor(loop: String) = when (loop) { "Playlist" -> RepeatMode.ALL; "Track" -> RepeatMode.ONE; else -> RepeatMode.OFF }
    fun trackPath(key: String?) = DBusPath(if (key == null) "/org/mpris/MediaPlayer2/TrackList/NoTrack" else "/dev/schlubbe/grooveo/track/" + key.hashCode().toUInt().toString(16))
}

/**
 * MPRIS2 server on the session bus ("org.mpris.MediaPlayer2.grooveo"): media keys,
 * desktop media widgets (GNOME, KDE, Hyprland bars via playerctl) and lock screens.
 */
class Mpris(private val player: PlayerController, private val ui: AppUi, private val onRaise: () -> Unit = {}) :
    MediaPlayer2, MediaPlayer2Player, Properties {

    private val conn: DBusConnection = DBusConnectionBuilder.forSessionBus().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        conn.requestBusName("org.mpris.MediaPlayer2.grooveo")
        conn.exportObject(PATH, this)
        scope.launch {
            combine(player.state, player.engineState, ui.graph.settings.state) { q, e, s ->
                listOf(q.current?.key, e.playing, e.currentId != null, q.shuffle, q.repeat, s.volume, e.durationSec?.toLong())
            }.distinctUntilChanged().collect {
                runCatching {
                    conn.sendMessage(Properties.PropertiesChanged(PATH, PLAYER_IFACE, playerProperties().filterKeys { it != "Position" }, emptyList()))
                }
            }
        }
    }

    override fun getObjectPath() = PATH

    // org.mpris.MediaPlayer2
    override fun Raise() = onRaise()
    override fun Quit() {}

    // org.mpris.MediaPlayer2.Player
    override fun Next() = player.next()
    override fun Previous() = player.previous()
    override fun Pause() { if (player.engineState.value.playing) player.togglePlay() }
    override fun PlayPause() = player.togglePlay()
    override fun Stop() = player.stop()
    override fun Play() { if (!player.engineState.value.playing) player.togglePlay() }
    override fun Seek(offset: Long) {
        val target = (player.engineState.value.positionSec + offset / 1e6).coerceAtLeast(0.0)
        player.seek(target)
        runCatching { conn.sendMessage(MediaPlayer2Player.Seeked(PATH, (target * 1e6).toLong())) }
    }
    override fun SetPosition(trackId: DBusPath, position: Long) {
        if (trackId.path == MprisMapping.trackPath(player.state.value.current?.key).path) player.seek(position / 1e6)
    }
    override fun OpenUri(uri: String) {}

    // org.freedesktop.DBus.Properties
    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        (GetAll(interfaceName)[propertyName]?.value ?: throw IllegalArgumentException("Unknown property $propertyName")) as A

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        val v = (value as? Variant<*>)?.value ?: value
        when (propertyName) {
            "Volume" -> (v as? Double)?.let { player.setVolume(it.toFloat()) }
            "Shuffle" -> if (v is Boolean && v != player.state.value.shuffle) player.toggleShuffle()
            "LoopStatus" -> (v as? String)?.let { target -> repeat(3) { if (player.state.value.repeat != MprisMapping.repeatFor(target)) player.cycleRepeat() } }
        }
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
        ROOT_IFACE -> mapOf(
            "CanQuit" to Variant(false), "CanRaise" to Variant(true), "HasTrackList" to Variant(false),
            "Identity" to Variant("Grooveo"), "DesktopEntry" to Variant("grooveo"),
            "SupportedUriSchemes" to Variant(arrayOf<String>(), "as"), "SupportedMimeTypes" to Variant(arrayOf<String>(), "as"),
        )
        PLAYER_IFACE -> playerProperties()
        else -> emptyMap()
    }

    private fun playerProperties(): Map<String, Variant<*>> {
        val q = player.state.value
        val e = player.engineState.value
        val t = q.current
        val meta = mutableMapOf<String, Variant<*>>("mpris:trackid" to Variant(MprisMapping.trackPath(t?.key)))
        if (t != null) {
            meta["xesam:title"] = Variant(t.title)
            meta["xesam:artist"] = Variant(arrayOf(t.artist ?: ""), "as")
            t.album?.let { meta["xesam:album"] = Variant(it) }
            t.thumbnailUrl?.let { meta["mpris:artUrl"] = Variant(it) }
            meta["xesam:url"] = Variant(t.webpageUrl)
            ((e.durationSec ?: t.durationSec?.toDouble())?.times(1e6))?.toLong()?.let { meta["mpris:length"] = Variant(it) }
        }
        return mapOf(
            "PlaybackStatus" to Variant(MprisMapping.playbackStatus(e.playing, t != null)),
            "LoopStatus" to Variant(MprisMapping.loopStatus(q.repeat)),
            "Rate" to Variant(1.0), "MinimumRate" to Variant(1.0), "MaximumRate" to Variant(1.0),
            "Shuffle" to Variant(q.shuffle),
            "Metadata" to Variant(meta, "a{sv}"),
            "Volume" to Variant(ui.graph.settings.current.volume.toDouble()),
            "Position" to Variant((e.positionSec * 1e6).toLong()),
            "CanGoNext" to Variant(true), "CanGoPrevious" to Variant(true), "CanPlay" to Variant(t != null),
            "CanPause" to Variant(t != null), "CanSeek" to Variant(e.durationSec != null), "CanControl" to Variant(true),
        )
    }

    fun close() {
        scope.cancel()
        runCatching { conn.releaseBusName("org.mpris.MediaPlayer2.grooveo") }
        runCatching { conn.close() }
    }

    companion object {
        const val PATH = "/org/mpris/MediaPlayer2"
        const val ROOT_IFACE = "org.mpris.MediaPlayer2"
        const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"
    }
}
