package dev.schlubbe.musicagent.ui.spotify

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

private const val EXPORTIFY_URL = "https://exportify.app/"
private const val EXPORTIFY_ORIGIN = "https://exportify.app"
private const val MAX_EXPORT_BYTES = 60 * 1024 * 1024

/** Exportify (open source, runs entirely in the browser) inside a WebView. The
 * person logs in with Spotify's official login and taps "Alles exportieren";
 * Exportify then saves a ZIP through a blob link, which a WebView cannot download
 * on its own - [DOWNLOAD_HOOK] reads that blob instead and hands it to
 * [onExport]. Spotify credentials never pass through the app.
 *
 * Cookies and web storage are wiped when the view goes away, so neither the
 * Spotify nor the Exportify session lingers in the app. */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ExportifyWebView(onExport: (fileName: String, bytes: ByteArray) -> Unit, modifier: Modifier = Modifier) {
    val currentOnExport by rememberUpdatedState(onExport)
    val main = Handler(Looper.getMainLooper())

    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // AndroidView's default WRAP_CONTENT gives the page a zero-height
                // viewport (100vh = 0) and Spotify's login then draws nothing.
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                // A web message listener instead of addJavascriptInterface: the latter is
                // callable from every page and frame the WebView ever shows (Spotify's
                // login, links out of Exportify, ads), so any site could have fed the
                // import a fake library or an endless stream of chunks. This object only
                // exists in frames from EXPORTIFY_ORIGIN. Without the feature (WebView
                // older than 2020) there is no bridge; the export file can still be opened.
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    val bridge = ExportBridge { name, bytes -> main.post { currentOnExport(name, bytes) } }
                    WebViewCompat.addWebMessageListener(this, "DawnExport", setOf(EXPORTIFY_ORIGIN)) { _, message, _, isMainFrame, _ ->
                        if (isMainFrame) message.data?.let(bridge::onMessage)
                    }
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(this, DOWNLOAD_HOOK, setOf(EXPORTIFY_ORIGIN))
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme != "https"

                    override fun onPageFinished(view: WebView, url: String?) {
                        // Fallback for WebViews without document-start scripts; the
                        // hook guards against running twice.
                        if (url?.startsWith(EXPORTIFY_ORIGIN) == true) view.evaluateJavascript(DOWNLOAD_HOOK, null)
                    }
                }
                loadUrl(EXPORTIFY_URL)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}

/** Receives the export from [DOWNLOAD_HOOK] as web messages: "B<size>|<name>",
 * then base64 chunks "C<data>", then "E". The base64 text is capped at what
 * MAX_EXPORT_BYTES encodes to, so a page cannot grow it without bound. */
private class ExportBridge(private val deliver: (String, ByteArray) -> Unit) {
    private var name: String? = null
    private val data = StringBuilder()

    fun onMessage(message: String) {
        when (message.firstOrNull()) {
            'B' -> synchronized(this) {
                data.clear()
                val size = message.substring(1).substringBefore('|').toIntOrNull() ?: 0
                name = if (size in 1..MAX_EXPORT_BYTES) message.substringAfter('|', "").take(120).ifEmpty { "export.zip" } else null
            }
            'C' -> synchronized(this) {
                if (name == null) return
                if (data.length + message.length - 1 > MAX_BASE64_CHARS) { name = null; data.clear(); return }
                data.append(message, 1, message.length)
            }
            'E' -> {
                val (fileName, encoded) = synchronized(this) {
                    val n = name ?: return
                    name = null
                    n to data.toString().also { data.clear() }
                }
                val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return
                deliver(fileName, bytes)
            }
        }
    }
}

private const val MAX_BASE64_CHARS = (MAX_EXPORT_BYTES / 3 + 1) * 4

/** Catches Exportify's downloads: remembers every Blob turned into an object URL,
 * and when a download link pointing at one is clicked (FileSaver uses
 * `dispatchEvent(new MouseEvent('click'))`, others `a.click()`), reads the blob
 * and sends it to [ExportBridge] instead of letting the WebView ignore it. */
private val DOWNLOAD_HOOK = """
(function(){
  if (window.__dawnExportHook) return; window.__dawnExportHook = true;
  var blobs = {};
  var create = URL.createObjectURL;
  URL.createObjectURL = function(b){
    var u = create.apply(URL, arguments);
    try { if (b instanceof Blob) blobs[u] = b; } catch(e){}
    return u;
  };
  var grab = function(a){
    try {
      if (!a || String(a.tagName).toUpperCase() !== 'A' || !a.download) return false;
      var b = blobs[a.href]; if (!b) return false;
      var name = a.download;
      var r = new FileReader();
      r.onload = function(){
        var s = String(r.result); s = s.substring(s.indexOf(',') + 1);
        if (typeof DawnExport === 'undefined') return;
        DawnExport.postMessage('B' + b.size + '|' + name);
        for (var i = 0; i < s.length; i += 524288) DawnExport.postMessage('C' + s.substring(i, i + 524288));
        DawnExport.postMessage('E');
      };
      r.readAsDataURL(b);
      return true;
    } catch(e){ return false; }
  };
  var click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){ if (grab(this)) return; return click.apply(this, arguments); };
  var dispatch = EventTarget.prototype.dispatchEvent;
  EventTarget.prototype.dispatchEvent = function(ev){
    if (ev && ev.type === 'click' && grab(this)) return true;
    return dispatch.apply(this, arguments);
  };
})();
""".trimIndent()
