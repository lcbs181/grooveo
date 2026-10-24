package dev.schlubbe.musicagent.ui.util

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A failed network load in plain German, instead of an exception's raw (often English) text. */
fun friendlyError(e: Throwable): String {
    val msg = e.message.orEmpty()
    return when {
        e is UnknownHostException || "Unable to resolve host" in msg -> "Keine Internetverbindung"
        e is SocketTimeoutException || "timeout" in msg.lowercase() -> "Der Server antwortet nicht – bitte später erneut versuchen"
        "HTTP 404" in msg || "404" == msg -> "Nicht gefunden – vielleicht wurde es entfernt"
        "HTTP 429" in msg -> "Zu viele Anfragen – bitte kurz warten"
        Regex("""HTTP 5\d\d""").containsMatchIn(msg) -> "Der Dienst hat gerade Probleme – bitte später erneut versuchen"
        e is IOException -> "Verbindung fehlgeschlagen – bitte erneut versuchen"
        else -> "Laden fehlgeschlagen – bitte erneut versuchen"
    }
}
