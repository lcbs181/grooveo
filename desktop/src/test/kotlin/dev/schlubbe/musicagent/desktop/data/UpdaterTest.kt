package dev.schlubbe.musicagent.desktop.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdaterTest {
    private val dir = Files.createTempDirectory("grooveo-upd").toFile()

    @kotlin.test.AfterTest fun cleanUp() { dir.deleteRecursively() }
    private val info = UpdateInfo("9.9.9", "", "", listOf(
        UpdateAsset("Grooveo-9.9.9-linux-x64.deb", "u1", 1), UpdateAsset("Grooveo-9.9.9-linux-x64.tar.gz", "u2", 1),
        UpdateAsset("Grooveo-9.9.9-windows-x64.msi", "u3", 1), UpdateAsset("Grooveo-9.9.9.apk", "u4", 1),
        UpdateAsset(UpdateChecker.SUMS_NAME, "u5", 1),
    ))

    /** A jpackage-like app image: bin/Grooveo and lib/app/Grooveo.cfg. */
    private fun appImage(root: File, marker: String = "old") = root.apply {
        File(this, "bin").mkdirs()
        File(this, "bin/Grooveo").apply { writeText("#!/bin/sh\ntrue\n"); setExecutable(true) }
        File(this, "lib/app").mkdirs()
        File(this, "lib/app/Grooveo.cfg").writeText("[Application]\n")
        File(this, "lib/$marker.txt").writeText(marker)
    }

    @Test fun `install kind and asset follow the installation`() {
        val u = UpdateChecker(OkHttpClient(), "0.6.2", dir)
        assertEquals(InstallKind.Browser, UpdateChecker.installKind(null, "Linux"))
        assertEquals(InstallKind.Msi, UpdateChecker.installKind("C:\\Grooveo\\Grooveo.exe", "Windows 11"))
        // system directories: .deb only when dpkg owns the launcher (not e.g. an Arch package)
        assertEquals(InstallKind.Deb, UpdateChecker.installKind("/opt/grooveo/bin/Grooveo", "Linux") { true })
        assertEquals(InstallKind.Browser, UpdateChecker.installKind("/usr/lib/grooveo/bin/Grooveo", "Linux") { false })
        val root = appImage(File(dir, "grooveo"))
        assertEquals(InstallKind.Tarball(root), UpdateChecker.installKind(File(root, "bin/Grooveo").path, "Linux") { false })
        assertEquals("Grooveo-9.9.9-windows-x64.msi", u.assetFor(info, InstallKind.Msi)!!.name)
        assertEquals("Grooveo-9.9.9-linux-x64.deb", u.assetFor(info, InstallKind.Deb)!!.name)
        assertEquals("Grooveo-9.9.9-linux-x64.tar.gz", u.assetFor(info, InstallKind.Tarball(root))!!.name)
        // without checksums nothing is installed automatically
        assertNull(u.assetFor(info.copy(assets = info.assets.filter { it.name != UpdateChecker.SUMS_NAME }), InstallKind.Msi))
    }

    @Test fun `a directory that is not exactly an app image is never used for a tarball update`() {
        // launcher at ~/.local/bin/Grooveo: root would be ~/.local
        val local = File(dir, "home/.local").apply { File(this, "bin").mkdirs(); File(this, "bin/Grooveo").writeText("x"); File(this, "share").mkdirs() }
        assertEquals(InstallKind.Browser, UpdateChecker.installKind(File(local, "bin/Grooveo").path, "Linux") { false })
        val withExtra = appImage(File(dir, "extra")).also { File(it, "Documents").mkdirs() }
        assertFalse(UpdateChecker.looksLikeAppImage(withExtra))
        val noApp = File(dir, "noapp").apply { File(this, "bin").mkdirs(); File(this, "bin/Grooveo").writeText("x"); File(this, "lib").mkdirs() }
        assertFalse(UpdateChecker.looksLikeAppImage(noApp))
        val jars = File(dir, "jars").apply { File(this, "bin").mkdirs(); File(this, "bin/Grooveo").writeText("x"); File(this, "lib/app").mkdirs(); File(this, "lib/app/desktop.jar").writeText("") }
        assertTrue(UpdateChecker.looksLikeAppImage(jars))
    }

    @Test fun `checksum lines are parsed`() {
        val sums = UpdateChecker.parseSums("${"a".repeat(64)}  Grooveo-1.tar.gz\n${"B".repeat(64)} *Grooveo-1.msi\ngarbage\n")
        assertEquals(mapOf("Grooveo-1.tar.gz" to "a".repeat(64), "Grooveo-1.msi" to "b".repeat(64)), sums)
    }

    private fun serve(files: Map<String, () -> ByteArray>): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        files.forEach { (path, body) ->
            createContext(path) { ex -> val b = body(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        }
        start()
    }

    @Test fun `downloads with progress, verifies and swaps a tarball installation in place`() = runBlocking<Unit> {
        // build a release tarball: Grooveo/bin/Grooveo + Grooveo/lib/new.txt
        val src = appImage(File(dir, "src/Grooveo"), "new")
        val tgz = File(dir, "release.tar.gz")
        assertEquals(0, ProcessBuilder("tar", "-czf", tgz.path, "-C", src.parent, "Grooveo").start().waitFor())
        val bytes = tgz.readBytes()
        val name = "Grooveo-9.9.9-linux-x64.tar.gz"
        var sums = "${UpdateChecker.sha256(tgz)}  $name\n"
        val server = serve(mapOf("/r.tgz" to { bytes }, "/SHA256SUMS" to { sums.toByteArray() }))
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val asset = UpdateAsset(name, "$base/r.tgz", bytes.size.toLong())
            val rel = UpdateInfo("9.9.9", "", "", listOf(asset, UpdateAsset(UpdateChecker.SUMS_NAME, "$base/SHA256SUMS", -1)))
            // a foreign "grooveo.old" next to the installation must survive
            val foreignOld = File(dir, "install/grooveo.old").apply { mkdirs(); File(this, "keep.txt").writeText("mine") }
            val root = appImage(File(dir, "install/grooveo"))
            val u = UpdateChecker(OkHttpClient(), "0.6.2", File(dir, "dl"))
            var last = 0f
            val file = u.download(rel, asset) { last = it }
            assertEquals(1f, last)
            assertTrue(u.install(file, InstallKind.Tarball(root)))
            assertEquals("new", File(root, "lib/new.txt").readText())
            assertTrue(File(root, "bin/Grooveo").canExecute())
            assertEquals("mine", File(foreignOld, "keep.txt").readText())
            assertEquals("old", File(dir, "install/grooveo.old-2/lib/old.txt").readText())
            assertFalse(File(dir, "install/.grooveo.update").exists())

            // a second update removes the old version it created, nothing else
            val file2 = u.download(rel, asset) {}
            assertTrue(u.install(file2, InstallKind.Tarball(root)))
            assertTrue(foreignOld.isDirectory)
            assertFalse(File(dir, "install/grooveo.old-2/lib/old.txt").exists())
            assertTrue(File(dir, "install/grooveo.old-2/lib/new.txt").isFile)
            assertFalse(File(dir, "install/grooveo.old-3").exists())

            // a wrong checksum stops the update, also for a file that is already there
            sums = "${"0".repeat(64)}  $name\n"
            assertFails { u.download(rel, asset) {} }
            assertFalse(File(dir, "dl/$name").exists())
            // no entry for the file
            sums = "${"0".repeat(64)}  other\n"
            assertFails { u.download(rel, asset) {} }
        } finally { server.stop(0) }
    }

    @Test fun `restart waits for the old process to exit`() {
        val out = File(dir, "started")
        val launcher = File(dir, "launch.sh").apply { writeText("#!/bin/sh\ntouch '${out.path}'\n"); setExecutable(true) }
        val old = ProcessBuilder("sleep", "1").start()
        ProcessBuilder(UpdateChecker.restartCommand(launcher, old.pid())).start().waitFor()
        Thread.sleep(400)
        assertFalse(out.exists())
        old.waitFor(5, TimeUnit.SECONDS)
        val until = System.currentTimeMillis() + 3000
        while (!out.exists() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(out.exists())
    }
}
