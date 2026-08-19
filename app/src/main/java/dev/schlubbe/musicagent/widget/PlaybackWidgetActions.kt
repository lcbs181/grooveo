package dev.schlubbe.musicagent.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Glance runs ActionCallback.onAction() on a background dispatcher thread (confirmed
// live via logcat: androidx.media3.session.MediaController.verifyApplicationThread
// threw "MediaController method is called from a wrong thread" from exactly this
// callback) - but Media3's MediaController requires every call to happen on
// whichever thread first connected/created it, which for this app is always the
// main thread (every other caller goes through a ViewModel's viewModelScope, which
// defaults to Dispatchers.Main.immediate). This is the actual reason the widget's
// buttons "didn't control the app" - not a missing feature, a wrong-thread crash
// swallowed by Glance's own action-error handling before it could surface anywhere
// visible. Forcing these three calls onto Main fixes it regardless of whether the
// app's UI is currently open - PlayerController/PlaybackService don't care who
// asked, only what thread asked.
//
// The explicit updateAll() after each call is a second, separate fix: an
// ActionCallback's own coroutine is not the same session as the one
// PlaybackWidget.provideGlance's collectAsState() is running in, so a state change
// this action itself caused doesn't reliably repaint the widget on its own (the
// play/pause icon staying stuck was exactly this - the tap DID toggle playback,
// the widget just never re-rendered to show it). Forcing a repaint here is the
// standard, documented way Glance widgets are expected to react to their own
// actions rather than relying solely on the ambient state-flow subscription.

class TogglePlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) { widgetPlayerController(context).togglePlayPause() }
        PlaybackWidget().updateAll(context)
    }
}

class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) { widgetPlayerController(context).skipToNext() }
        PlaybackWidget().updateAll(context)
    }
}

class SkipPreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.Main) { widgetPlayerController(context).skipToPrevious() }
        PlaybackWidget().updateAll(context)
    }
}
