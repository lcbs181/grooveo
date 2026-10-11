package dev.schlubbe.musicagent.desktop.data

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateAsset(val name: String, val url: String, val size: Long)

data class UpdateInfo(val version: String, val notes: String, val url: String, val assets: List<UpdateAsset> = emptyList())

/** How this installation updates itself. */
sealed interface InstallKind {
    /** Windows: download the .msi and start it. */
    data object Msi : InstallKind
    /** Linux package in a root-owned directory: download the .deb and open it with the system installer. */
    data object Deb : InstallKind
    /** Linux tarball in a user-writable directory: unpack the new version in place, keep the old one as `<dir>.old`. */
    data class Tarball(val root: File) : InstallKind
    /** Not a packaged build (running from Gradle): open the release page. */
    data object Browser : InstallKind
}

/** Polls the same public GitHub Releases feed as the Android app's UpdateRepository and installs desktop releases. */
class UpdateChecker(private val http: OkHttpClient, private val currentVersion: String, private val downloadDir: File) {
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/lcbs181/grooveo/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val o = JsonParser.parseString(resp.body.string()).asJsonObject
            // tags are the Android versionCode ("v22"); the release title is the
            // shared versionName ("0.6.0") that both apps report
            val version = o.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: return@withContext null
            if (!isNewer(version, currentVersion)) return@withContext null
            val assets = o.getAsJsonArray("assets")?.mapNotNull { a ->
                val j = a.asJsonObject
                UpdateAsset(j.get("name")?.asString ?: return@mapNotNull null, j.get("browser_download_url")?.asString ?: return@mapNotNull null, j.get("size")?.asLong ?: -1)
            }.orEmpty()
            UpdateInfo(version.removePrefix("v"), o.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty(), o.get("html_url")?.asString.orEmpty(), assets)
        }
    }

    /** The release file this installation needs, or null when it has to go through the browser. */
    fun assetFor(info: UpdateInfo, kind: InstallKind = installKind()): UpdateAsset? = when (kind) {
        InstallKind.Msi -> info.assets.firstOrNull { it.name.endsWith("-windows-x64.msi") }
        InstallKind.Deb -> info.assets.firstOrNull { it.name.endsWith("-linux-x64.deb") }
        is InstallKind.Tarball -> info.assets.firstOrNull { it.name.endsWith("-linux-x64.tar.gz") }
        InstallKind.Browser -> null
    }

    /** Downloads [asset] (resumable files are not worth it at this size: a partial file is restarted). */
    suspend fun download(asset: UpdateAsset, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        downloadDir.mkdirs()
        val target = File(downloadDir, asset.name)
        if (asset.size > 0 && target.length() == asset.size) return@withContext target.also { onProgress(1f) }
        val part = File(downloadDir, asset.name + ".part")
        val client = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(asset.url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "Download fehlgeschlagen (HTTP ${resp.code})" }
            val total = resp.body.contentLength().takeIf { it > 0 } ?: asset.size
            var done = 0L
            resp.body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            check(asset.size <= 0 || done == asset.size) { "Download unvollständig" }
        }
        target.delete()
        check(part.renameTo(target)) { "Download konnte nicht gespeichert werden" }
        target
    }

    /**
     * Installs a downloaded [file]. Returns true when Grooveo has to quit now (the installer
     * or the new version takes over), false when the user finishes in another program.
     */
    suspend fun install(file: File, kind: InstallKind = installKind()): Boolean = withContext(Dispatchers.IO) {
        when (kind) {
            InstallKind.Msi -> { ProcessBuilder("msiexec", "/i", file.absolutePath).start(); true }
            InstallKind.Deb -> { ProcessBuilder("xdg-open", file.absolutePath).start(); false }
            is InstallKind.Tarball -> { replaceInstallation(file, kind.root); true }
            InstallKind.Browser -> false
        }
    }

    /** Unpacks [tarball] next to [root], swaps it in (old version kept as `<root>.old`) and starts it once this process exits. */
    private fun replaceInstallation(tarball: File, root: File) {
        val parent = root.absoluteFile.parentFile
        val staging = File(parent, ".${root.name}.update").apply { deleteRecursively(); mkdirs() }
        val tar = ProcessBuilder("tar", "-xzf", tarball.absolutePath, "-C", staging.absolutePath).redirectErrorStream(true).start()
        val out = tar.inputStream.bufferedReader().readText()
        check(tar.waitFor() == 0) { "Entpacken fehlgeschlagen: ${out.take(200)}" }
        val unpacked = staging.listFiles()?.singleOrNull { File(it, "bin/Grooveo").isFile }
            ?: error("Unerwarteter Inhalt im Update-Archiv")
        val old = File(parent, root.name + ".old")
        old.deleteRecursively()
        check(root.renameTo(old)) { "Alte Version konnte nicht verschoben werden" }
        if (!unpacked.renameTo(root)) {
            old.renameTo(root) // put the running version back
            error("Neue Version konnte nicht installiert werden")
        }
        staging.deleteRecursively()
        tarball.delete()
        // restart once this process has quit (one instance at a time: MPRIS, sync server ports)
        ProcessBuilder("sh", "-c", "sleep 2; exec \"$1\" >/dev/null 2>&1 &", "sh", File(root, "bin/Grooveo").absolutePath).start()
    }

    companion object {
        /** Semantic comparison of "v1.2.3"-style tags; non-numeric parts count as 0. */
        fun isNewer(tag: String, current: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val a = parts(tag); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** Detected from the jpackage launcher path (`<root>/bin/Grooveo`, or `<root>\Grooveo.exe` on Windows). */
        fun installKind(
            appPath: String? = System.getProperty("jpackage.app-path"),
            os: String = System.getProperty("os.name").orEmpty(),
        ): InstallKind {
            val launcher = appPath?.let(::File) ?: return InstallKind.Browser
            if (os.lowercase().startsWith("win")) return InstallKind.Msi
            val root = launcher.absoluteFile.parentFile?.parentFile ?: return InstallKind.Browser
            val systemDir = root.path.startsWith("/opt/") || root.path.startsWith("/usr/")
            return if (!systemDir && root.canWrite() && root.parentFile?.canWrite() == true) InstallKind.Tarball(root) else InstallKind.Deb
        }
    }
}
