package dev.schlubbe.musicagent.ui.spotify

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.JsonParser
import dev.schlubbe.musicagent.data.spotify.SpotifyTrack

private const val AMAZON_MUSIC_URL = "https://music.amazon.de/my/playlists"

// Amazon serves its phone site an "open the app" page; the desktop web player
// is the one that lists a playlist's songs.
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

/** One Amazon Music playlist read from the web player. */
data class AmazonPlaylist(val name: String, val tracks: List<SpotifyTrack>)

/** Amazon Music's web player inside a WebView. Amazon has no export, so the songs
 * are read off the open playlist page itself (the approach of the MIT-licensed
 * "Save Amazon Music playlist" userscript by projecteurlumiere): the person logs
 * in with Amazon's own login, opens a playlist, and each change of [scrapeRequest]
 * runs [SCRAPE_SCRIPT], which scrolls the list to its end so every row loads and
 * hands title/artist/album to [onPlaylist]. Amazon credentials never pass through
 * the app; cookies and web storage are wiped when the view goes away. */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun AmazonMusicWebView(
    scrapeRequest: Int,
    onPlaylist: (AmazonPlaylist?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnPlaylist by rememberUpdatedState(onPlaylist)
    val main = remember { Handler(Looper.getMainLooper()) }
    val webView = remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }
    LaunchedEffect(scrapeRequest) {
        if (scrapeRequest > 0) webView.value?.evaluateJavascript(SCRAPE_SCRIPT, null)
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = DESKTOP_USER_AGENT
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun playlist(json: String?) {
                            val parsed = json?.let { runCatching { parse(it) }.getOrNull() }
                            main.post { currentOnPlaylist(parsed) }
                        }
                    },
                    "DawnAmazon",
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme != "https"
                }
                loadUrl(AMAZON_MUSIC_URL)
                webView.value = this
            }
        },
        onRelease = { view ->
            webView.value = null
            view.stopLoading()
            view.removeJavascriptInterface("DawnAmazon")
            view.destroy()
        },
    )
}

private const val MAX_TRACKS = 10_000

private fun parse(json: String): AmazonPlaylist? {
    val root = JsonParser.parseString(json).asJsonObject
    val name = root.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty().ifEmpty { "Amazon Music" }
    val tracks = root.getAsJsonArray("tracks")?.take(MAX_TRACKS)?.mapNotNull { e ->
        val o = e.asJsonObject
        fun s(key: String) = o.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        val title = s("title") ?: return@mapNotNull null
        val artist = s("artist")
        SpotifyTrack(
            id = "amazon:${title.lowercase()}|${artist?.lowercase().orEmpty()}",
            title = title.take(300),
            artists = listOfNotNull(artist?.take(300)),
            album = s("album")?.take(300),
            durationMs = null,
        )
    }.orEmpty().distinctBy { it.id }
    return if (tracks.isEmpty()) null else AmazonPlaylist(name.take(120), tracks)
}

/** Scrolls the open playlist to its end (rows load in batches while scrolling),
 * then reads every `music-image-row`: title/artist/album from its attributes,
 * falling back to the column links the desktop layout shows. */
private val SCRAPE_SCRIPT = """
(async function(){
  const rows = () => Array.from(document.querySelectorAll('music-image-row, music-text-row'));
  const text = (el) => el ? (el.getAttribute('title') || el.textContent || '').trim() : '';
  const read = (r) => ({
    title: r.getAttribute('primary-text') || text(r.querySelector('.col1 music-link')),
    artist: r.getAttribute('secondary-text-1') || text(r.querySelector('.col2 music-link')),
    album: r.getAttribute('secondary-text-2') || text(r.querySelector('.col3 music-link'))
  });
  const seen = new Map();
  const collect = () => rows().forEach(r => {
    const t = read(r); if (!t.title) return;
    const key = (r.getAttribute('index') || r.querySelector('.index')?.textContent || '') + '|' + t.title + '|' + t.artist;
    if (!seen.has(key)) seen.set(key, t);
  });
  const scroller = document.scrollingElement || document.body;
  let last = -1, still = 0;
  for (let i = 0; i < 400 && still < 4; i++) {
    collect();
    if (seen.size === last) still++; else { still = 0; last = seen.size; }
    const lastRow = rows().pop(); if (lastRow) lastRow.scrollIntoView({block: 'end'});
    window.scrollBy(0, window.innerHeight); scroller.scrollTop = scroller.scrollHeight;
    await new Promise(r => setTimeout(r, 700));
  }
  collect();
  const header = document.querySelector('music-detail-header');
  let name = header ? (header.getAttribute('headline') || header.getAttribute('primary-text') || '') : '';
  if (!name && header && header.shadowRoot) name = text(header.shadowRoot.querySelector('[title]'));
  if (!name) name = document.title.replace(/\s*[|–-]\s*Amazon Music.*$/i, '');
  window.scrollTo(0, 0);
  DawnAmazon.playlist(JSON.stringify({name: name, tracks: Array.from(seen.values())}));
})();
"""
