package dev.schlubbe.musicagent.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether the user is typing in a text field. Compose text fields are no AWT text components
 * and let space and the arrow keys bubble on, so the window's shortcuts ask this instead:
 * every focused, editable text field holds a text input session, which [Track] counts.
 */
object TypingFocus {
    private val sessions = AtomicInteger()

    val active: Boolean get() = sessions.get() > 0

    @OptIn(ExperimentalComposeUiApi::class)
    private val counter = PlatformTextInputInterceptor { request, next ->
        sessions.incrementAndGet()
        try {
            next.startInputMethod(request)
        } finally {
            sessions.decrementAndGet()
        }
    }

    /** Counts the text input sessions of [content] (dialogs and popups inside it included). */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun Track(content: @Composable () -> Unit) = InterceptPlatformTextInput(counter, content)
}
