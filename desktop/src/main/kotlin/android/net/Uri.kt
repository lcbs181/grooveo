package android.net

import java.net.URI
import java.net.URLDecoder

/** Minimal desktop stand-in for `android.net.Uri` covering what shared sources use. */
class Uri private constructor(private val uri: URI) {
    val host: String? get() = uri.host
    val path: String? get() = uri.path
    val scheme: String? get() = uri.scheme

    fun getQueryParameter(key: String): String? = uri.rawQuery
        ?.split('&')
        ?.map { it.split('=', limit = 2) }
        ?.firstOrNull { it[0] == key }
        ?.let { URLDecoder.decode(it.getOrElse(1) { "" }, Charsets.UTF_8) }

    override fun toString(): String = uri.toString()

    companion object {
        @JvmStatic
        fun parse(value: String): Uri = Uri(URI(value.trim()))
    }
}
