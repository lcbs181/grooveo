package dev.schlubbe.musicagent.playback

import com.google.gson.JsonObject
import dev.schlubbe.musicagent.data.sync.SyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Auf dem Handy hören": the PC's finished mix - DJ transitions, effects, EQ - played live on
 * the phone (see the desktop's LiveStream). While it plays, the title shown here follows the
 * PC every few seconds, and Next/Previous on the phone skip on the PC.
 *
 * With [start]`(muteDevice = true)` the phone is the only listener: the PC goes quiet, and
 * pausing on the phone pauses the PC too (resuming starts the stream afresh, so it is live
 * again rather than seconds behind).
 */
@Singleton
class PcLivePlayer @Inject constructor(
    private val sync: SyncClient,
    private val player: PlayerController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var followJob: Job? = null
    @Volatile private var muteDevice = false

    init {
        player.pcLiveSkip = { forward -> scope.launch { sync.tool("playback", action(if (forward) "next" else "previous")) } }
    }

    /** Starts the PC's mix on the phone; the error message when the PC cannot stream. */
    suspend fun start(muteDevice: Boolean): Result<Unit> = runCatching {
        val uri = sync.liveStreamUri(muteDevice).getOrThrow()
        val np = sync.pcNowPlaying()
        this.muteDevice = muteDevice
        player.playPcLive(uri, np?.title, np?.artist, np?.artworkUrl)
        follow()
    }

    /** Stops listening (the PC plays aloud again). */
    suspend fun stop() {
        followJob?.cancel()
        sync.stopLiveStream()
    }

    private fun follow() {
        followJob?.cancel()
        followJob = scope.launch {
            launch {
                // pause and resume on the phone (app, notification, headset) reach the PC
                var last: Boolean? = null
                player.playbackState.map { it.isPcLive to it.playWhenReady }.distinctUntilChanged().collect { (live, wants) ->
                    if (!live) { last = null; return@collect }
                    val changed = last != null && last != wants
                    last = wants
                    if (!changed || !muteDevice) return@collect
                    sync.tool("playback", action(if (wants) "play" else "pause"))
                    if (wants) player.restartPcLive()
                }
            }
            while (isActive && player.isPlayingPcLive) {
                sync.pcNowPlaying()?.let { player.updatePcLive(it.title, it.artist, it.artworkUrl) }
                delay(POLL_MS)
            }
            // something else plays now: the PC need not stream any more
            sync.stopLiveStream()
            followJob?.cancel()
        }
    }

    private fun action(a: String) = JsonObject().apply { addProperty("action", a) }

    private companion object {
        const val POLL_MS = 4_000L
    }
}
