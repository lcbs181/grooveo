package dev.schlubbe.musicagent.ui.util

import android.content.Context
import android.content.Intent

/** Fires the system share sheet with plain text (a URL or a formatted blurb) -
 * every "Teilen" action in the app funnels through this one helper. */
fun Context.shareText(text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, null))
}
