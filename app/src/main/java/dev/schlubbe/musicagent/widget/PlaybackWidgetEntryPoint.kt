package dev.schlubbe.musicagent.widget

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.schlubbe.musicagent.data.repository.LikesRepository
import dev.schlubbe.musicagent.playback.PlayerController

/** Glance widget composition and its ActionCallbacks are instantiated by the
 * Glance/AppWidget framework via reflection, not by Hilt - this EntryPoint is how
 * they reach the app's singleton [PlayerController]/[LikesRepository] instances
 * instead of each getting their own disconnected copy. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaybackWidgetEntryPoint {
    fun playerController(): PlayerController
    fun likesRepository(): LikesRepository
}

fun widgetPlayerController(context: Context): PlayerController =
    EntryPointAccessors.fromApplication(context.applicationContext, PlaybackWidgetEntryPoint::class.java)
        .playerController()

fun widgetLikesRepository(context: Context): LikesRepository =
    EntryPointAccessors.fromApplication(context.applicationContext, PlaybackWidgetEntryPoint::class.java)
        .likesRepository()
