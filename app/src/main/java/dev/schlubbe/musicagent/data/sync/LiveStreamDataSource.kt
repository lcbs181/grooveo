package dev.schlubbe.musicagent.data.sync

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import dev.schlubbe.musicagent.data.backup.SyncProtocol
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Reads the PC's live mix ([SyncProtocol.LIVE_PATH]) for the player. The address looks like
 * `grooveo-live://192.168.1.20:47821/sync/live/<ticket>`.
 *
 * A plain HTTP GET over a raw socket, like [dev.schlubbe.musicagent.data.backup.SyncTransport]:
 * Android blocks cleartext HTTP for the normal HTTP stacks, and the PC is only ever reached in
 * the local network. The host must be a private address ([SyncProtocol.isLocalAddress]). The
 * body has no end; the stream is not seekable.
 */
@UnstableApi
class LiveStreamDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val host = dataSpec.uri.host ?: throw IOException("no host")
        val port = dataSpec.uri.port.takeIf { it > 0 } ?: SyncProtocol.PORT
        val path = dataSpec.uri.encodedPath ?: throw IOException("no path")
        val address = InetAddress.getByName(host)
        if (!SyncProtocol.isLocalAddress(address) || !path.startsWith(SyncProtocol.LIVE_PATH + "/")) {
            throw HttpDataSource.HttpDataSourceException(
                "not a local live stream", dataSpec, PlaybackException.ERROR_CODE_IO_NO_PERMISSION, HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        val s = Socket()
        try {
            s.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            s.soTimeout = READ_TIMEOUT_MS
            s.tcpNoDelay = true
            val hostHeader = if (':' in host) "[$host]:$port" else "$host:$port"
            s.getOutputStream().apply {
                write("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val inp = BufferedInputStream(s.getInputStream(), 16 * 1024)
            val status = readLine(inp)
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad answer: $status")
            while (readLine(inp).isNotEmpty()) Unit // headers
            if (code != 200) {
                throw HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), dataSpec, ByteArray(0))
            }
            socket = s
            input = inp
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = (input ?: throw IOException("not open")).read(buffer, offset, length)
        if (n == -1) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun readLine(inp: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c == -1) throw IOException("connection closed")
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) throw IOException("header too long")
            sb.append(c.toChar())
        }
    }

    /** Opens [SCHEME] addresses with [LiveStreamDataSource], everything else with [fallback]. */
    class Factory(private val fallback: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = Routing(fallback.createDataSource())
    }

    private class Routing(private val fallback: DataSource) : DataSource by fallback {
        private var live: LiveStreamDataSource? = null
        private val listeners = ArrayList<androidx.media3.datasource.TransferListener>()

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            listeners += transferListener
            fallback.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            if (dataSpec.uri.scheme != SCHEME) return fallback.open(dataSpec)
            val l = LiveStreamDataSource().also { s -> listeners.forEach(s::addTransferListener) }
            live = l
            return l.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            live?.read(buffer, offset, length) ?: fallback.read(buffer, offset, length)

        override fun getUri(): Uri? = live?.uri ?: fallback.uri

        override fun getResponseHeaders(): Map<String, List<String>> = if (live != null) emptyMap() else fallback.responseHeaders

        override fun close() {
            val l = live
            live = null
            if (l != null) l.close() else fallback.close()
        }
    }

    companion object {
        const val SCHEME = "grooveo-live"
        private const val CONNECT_TIMEOUT_MS = 5_000
        // the PC sends silence while paused, so a long gap means it is gone
        private const val READ_TIMEOUT_MS = 8_000
    }
}
