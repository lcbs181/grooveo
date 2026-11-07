package dev.schlubbe.musicagent.data.remote

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** Where the app's internet traffic goes: straight out, through Tor (Orbot), or a SOCKS5 proxy. */
data class ProxyConfig(val mode: String = "off", val host: String = "", val port: Int = 1080) {
    val enabled: Boolean get() = mode == "tor" || (mode == "custom" && host.isNotBlank() && port in 1..65535)

    fun proxy(): Proxy? = when {
        mode == "tor" -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", ORBOT_SOCKS_PORT))
        mode == "custom" && enabled -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host.trim(), port))
        else -> null
    }

    companion object {
        const val ORBOT_SOCKS_PORT = 9050
        private const val PREFS = "network_privacy"

        /** Plain SharedPreferences, not DataStore: read synchronously before anything else
         * in the process opens a connection. */
        fun load(context: android.content.Context): ProxyConfig {
            val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            return ProxyConfig(
                mode = prefs.getString("mode", "off") ?: "off",
                host = prefs.getString("host", "") ?: "",
                port = prefs.getInt("port", 1080),
            )
        }

        fun save(context: android.content.Context, config: ProxyConfig) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putString("mode", config.mode).putString("host", config.host.trim()).putInt("port", config.port)
                .commit()
        }
    }
}

/**
 * Installed as the process-wide [ProxySelector] at app start, so every OkHttp client
 * (extraction, playback, images, podcasts, updates check) takes the same route without
 * each one being wired up. Through SOCKS the host names are resolved by the proxy, so
 * DNS does not leak either. The local network (PC sync, the PC's live mix) always goes
 * direct: a proxy could not reach it anyway.
 *
 * Applied once per process: OkHttp keeps open connections in its pools, so switching
 * mid-run would let some requests go out the old way. Settings restart the app instead.
 */
class PrivacyProxySelector(private val config: ProxyConfig) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        val proxy = config.proxy() ?: return listOf(Proxy.NO_PROXY)
        val host = uri?.host ?: return listOf(proxy)
        return if (isLocal(host)) listOf(Proxy.NO_PROXY) else listOf(proxy)
    }

    // Deliberately no fallback to a direct connection: if the proxy is down, requests
    // fail instead of silently going out unprotected.
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    companion object {
        fun install(config: ProxyConfig) {
            if (config.enabled) setDefault(PrivacyProxySelector(config))
        }

        /** localhost, *.local and the private IPv4/IPv6 ranges. */
        fun isLocal(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (h == "localhost" || h.endsWith(".local") || h == "::1" || h.startsWith("fe80:") || h.startsWith("fd") && ':' in h) return true
            val parts = h.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4 || h.count { it == '.' } != 3) return false
            val (a, b) = parts[0] to parts[1]
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
        }
    }
}
