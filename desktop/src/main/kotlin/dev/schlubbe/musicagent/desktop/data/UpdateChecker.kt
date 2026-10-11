package dev.schlubbe.musicagent.desktop.data

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateAsset(val name: String, val url: String, val size: Long)

data class UpdateInfo(val version: String, val notes: String, val url: String, val assets: List<UpdateAsset> = emptyList())

/** How this installation updates itself. */
sealed interface InstallKind {
    /** Windows: download the .msi and start it. */
    data object Msi : InstallKind
    /** Linux .deb installation (dpkg owns the launcher): download the .deb and open it with the system installer. */
    data object Deb : InstallKind
    /** Linux tarball in a user-writable directory that is exactly a Grooveo app image: unpack the new version in place, keep the old one as `<dir>.old`. */
    data class Tarball(val root: File) : InstallKind
    /** Not a packaged build (running from Gradle) or an installation the updater does not know: open the release page. */
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

    /**
     * The release file this installation needs, or null when it has to go through the browser
     * (also when the release has no [SUMS_NAME] to check the download against).
     */
    fun assetFor(info: UpdateInfo, kind: InstallKind = installKind()): UpdateAsset? =
        if (info.assets.none { it.name == SUMS_NAME }) null else when (kind) {
            InstallKind.Msi -> info.assets.firstOrNull { it.name.endsWith("-windows-x64.msi") }
            InstallKind.Deb -> info.assets.firstOrNull { it.name.endsWith("-linux-x64.deb") }
            is InstallKind.Tarball -> info.assets.firstOrNull { it.name.endsWith("-linux-x64.tar.gz") }
            InstallKind.Browser -> null
        }

    /**
     * Downloads [asset] of [info] and checks it against the release's [SUMS_NAME]; a file that was
     * downloaded before is only reused when it matches (resumable files are not worth it at this
     * size: a partial file is restarted).
     */
    suspend fun download(info: UpdateInfo, asset: UpdateAsset, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val expected = expectedSha256(info, asset.name)
        downloadDir.mkdirs()
        val target = File(downloadDir, asset.name)
        if (target.isFile && (asset.size <= 0 || target.length() == asset.size) && sha256(target) == expected) return@withContext target.also { onProgress(1f) }
        target.delete()
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
        if (sha256(part) != expected) {
            part.delete()
            error("Prüfsumme des Downloads stimmt nicht – Update abgebrochen")
        }
        check(part.renameTo(target)) { "Download konnte nicht gespeichert werden" }
        target
    }

    /** SHA-256 of [name] as listed in the release's [SUMS_NAME]. */
    private fun expectedSha256(info: UpdateInfo, name: String): String {
        val sums = info.assets.firstOrNull { it.name == SUMS_NAME }
            ?: error("Release ohne Prüfsummen – bitte über die Download-Seite aktualisieren")
        val text = http.newCall(Request.Builder().url(sums.url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "Prüfsummen konnten nicht geladen werden (HTTP ${resp.code})" }
            val bytes = resp.body.byteStream().use { it.readNBytes(MAX_SUMS_BYTES + 1) }
            check(bytes.size <= MAX_SUMS_BYTES) { "Prüfsummen-Datei ist zu groß" }
            bytes.decodeToString()
        }
        return parseSums(text)[name] ?: error("Keine Prüfsumme für $name – bitte über die Download-Seite aktualisieren")
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

    /**
     * Unpacks [tarball] next to [root], swaps it in (old version kept as `<root>.old`) and starts it
     * once this process exits. Only touches [root] when it is a Grooveo app image, and only deletes
     * old versions it moved aside itself (listed in [createdOldDirsFile]).
     */
    private fun replaceInstallation(tarball: File, root: File) {
        check(looksLikeAppImage(root)) { "Installation nicht erkannt – bitte über die Download-Seite aktualisieren" }
        val parent = root.absoluteFile.parentFile
        val staging = File(parent, ".${root.name}.update").apply { deleteRecursively(); mkdirs() }
        val tar = ProcessBuilder("tar", "-xzf", tarball.absolutePath, "-C", staging.absolutePath).redirectErrorStream(true).start()
        val out = tar.inputStream.bufferedReader().readText()
        check(tar.waitFor() == 0) { "Entpacken fehlgeschlagen: ${out.take(200)}" }
        val unpacked = staging.listFiles()?.singleOrNull { File(it, "bin/Grooveo").isFile }
            ?: error("Unerwarteter Inhalt im Update-Archiv")
        // remove the old version this updater moved aside last time, nothing else
        readCreatedOldDirs().map(::File).filter { it.exists() && looksLikeAppImage(it) }.forEach { it.deleteRecursively() }
        var old = File(parent, root.name + ".old")
        var n = 2
        while (old.exists()) old = File(parent, root.name + ".old-" + n++)
        check(root.renameTo(old)) { "Alte Version konnte nicht verschoben werden" }
        writeCreatedOldDirs(listOf(old.absolutePath))
        if (!unpacked.renameTo(root)) {
            old.renameTo(root) // put the running version back
            writeCreatedOldDirs(emptyList())
            error("Neue Version konnte nicht installiert werden")
        }
        staging.deleteRecursively()
        tarball.delete()
        // restart once this process has quit (one instance at a time: MPRIS, sync server ports)
        ProcessBuilder(restartCommand(File(root, "bin/Grooveo"), ProcessHandle.current().pid()))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }

    private val createdOldDirsFile get() = File(downloadDir, "created-old-dirs.txt")

    private fun readCreatedOldDirs(): List<String> =
        runCatching { createdOldDirsFile.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    private fun writeCreatedOldDirs(dirs: List<String>) {
        downloadDir.mkdirs()
        createdOldDirsFile.writeText(dirs.joinToString("\n"))
    }

    companion object {
        /** Release asset with the SHA-256 of every other asset, in `sha256sum` format. */
        const val SUMS_NAME = "SHA256SUMS"
        private const val MAX_SUMS_BYTES = 64 * 1024

        /** File name to lowercase hex SHA-256 from `sha256sum` output (`<hash>  <name>` or `<hash> *<name>`). */
        fun parseSums(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
            Regex("""^([0-9a-fA-F]{64}) [ *](.+)$""").matchEntire(line.trim())?.let { it.groupValues[2].trim() to it.groupValues[1].lowercase() }
        }.toMap()

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * A jpackage Linux app image of Grooveo and nothing else: `bin/Grooveo`, `lib/app` with
         * `Grooveo.cfg` or the app jars, and no other entries than `bin` and `lib` at the top.
         */
        fun looksLikeAppImage(root: File): Boolean {
            if (!File(root, "bin/Grooveo").isFile) return false
            val app = File(root, "lib/app")
            val hasApp = File(app, "Grooveo.cfg").isFile || app.listFiles()?.any { it.name.endsWith(".jar") } == true
            if (!hasApp) return false
            val top = root.list()?.toSet() ?: return false
            return top.all { it == "bin" || it == "lib" }
        }

        /** Whether dpkg installed [path] (a .deb installation). */
        fun isDpkgOwned(path: File): Boolean = runCatching {
            val p = ProcessBuilder("dpkg", "-S", path.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); false } else p.exitValue() == 0
        }.getOrDefault(false)

        /** Shell command that starts [launcher] once the process [pid] has exited (waits at most ~15 s). */
        fun restartCommand(launcher: File, pid: Long): List<String> = listOf(
            "sh", "-c",
            "( i=0; while [ \$i -lt 150 ] && kill -0 \"\$2\" 2>/dev/null; do sleep 0.1; i=\$((i+1)); done; exec \"\$1\" ) >/dev/null 2>&1 &",
            "sh", launcher.absolutePath, pid.toString(),
        )

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
            dpkgOwns: (File) -> Boolean = ::isDpkgOwned,
        ): InstallKind {
            val launcher = appPath?.let(::File) ?: return InstallKind.Browser
            if (os.lowercase().startsWith("win")) return InstallKind.Msi
            val root = launcher.absoluteFile.parentFile?.parentFile ?: return InstallKind.Browser
            val systemDir = root.path.startsWith("/opt/") || root.path.startsWith("/usr/")
            if (!systemDir && root.canWrite() && root.parentFile?.canWrite() == true && looksLikeAppImage(root)) return InstallKind.Tarball(root)
            // other Linux packages (e.g. Arch under /usr/lib) update through their own package manager
            return if (dpkgOwns(launcher.absoluteFile)) InstallKind.Deb else InstallKind.Browser
        }
    }
}
