package dev.schlubbe.musicagent.desktop.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdaterTest {
    private val dir = Files.createTempDirectory("grooveo-upd").toFile()
    private val info = UpdateInfo("9.9.9", "", "", listOf(
        UpdateAsset("Grooveo-9.9.9-linux-x64.deb", "u1", 1), UpdateAsset("Grooveo-9.9.9-linux-x64.tar.gz", "u2", 1),
        UpdateAsset("Grooveo-9.9.9-windows-x64.msi", "u3", 1), UpdateAsset("Grooveo-9.9.9.apk", "u4", 1),
    ))

    @Test fun `install kind and asset follow the installation`() {
        val u = UpdateChecker(OkHttpClient(), "0.6.2", dir)
        assertEquals(InstallKind.Browser, UpdateChecker.installKind(null, "Linux"))
        assertEquals(InstallKind.Msi, UpdateChecker.installKind("C:\\Grooveo\\Grooveo.exe", "Windows 11"))
        assertEquals(InstallKind.Deb, UpdateChecker.installKind("/opt/grooveo/bin/Grooveo", "Linux"))
        val root = File(dir, "grooveo").apply { File(this, "bin").mkdirs() }
        assertEquals(InstallKind.Tarball(root), UpdateChecker.installKind(File(root, "bin/Grooveo").path, "Linux"))
        assertEquals("Grooveo-9.9.9-windows-x64.msi", u.assetFor(info, InstallKind.Msi)!!.name)
        assertEquals("Grooveo-9.9.9-linux-x64.deb", u.assetFor(info, InstallKind.Deb)!!.name)
        assertEquals("Grooveo-9.9.9-linux-x64.tar.gz", u.assetFor(info, InstallKind.Tarball(root))!!.name)
    }

    @Test fun `downloads with progress and swaps a tarball installation in place`() = runBlocking {
        // build a release tarball: Grooveo/bin/Grooveo + Grooveo/lib/new.txt
        val src = File(dir, "src/Grooveo").apply { File(this, "bin").mkdirs(); File(this, "lib").mkdirs() }
        File(src, "bin/Grooveo").apply { writeText("#!/bin/sh\ntrue\n"); setExecutable(true) }
        File(src, "lib/new.txt").writeText("new")
        val tgz = File(dir, "release.tar.gz")
        assertEquals(0, ProcessBuilder("tar", "-czf", tgz.path, "-C", src.parent, "Grooveo").start().waitFor())
        val bytes = tgz.readBytes()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/r.tgz") { ex -> ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
            start()
        }
        try {
            val root = File(dir, "install/grooveo").apply { File(this, "bin").mkdirs(); File(this, "lib/old.txt").apply { parentFile.mkdirs(); writeText("old") } }
            val u = UpdateChecker(OkHttpClient(), "0.6.2", File(dir, "dl"))
            var last = 0f
            val file = u.download(UpdateAsset("Grooveo-9.9.9-linux-x64.tar.gz", "http://127.0.0.1:${server.address.port}/r.tgz", bytes.size.toLong())) { last = it }
            assertEquals(1f, last)
            assertTrue(u.install(file, InstallKind.Tarball(root)))
            assertEquals("new", File(root, "lib/new.txt").readText())
            assertTrue(File(root, "bin/Grooveo").canExecute())
            assertEquals("old", File(dir, "install/grooveo.old/lib/old.txt").readText())
            assertFalse(File(dir, "install/.grooveo.update").exists())
        } finally { server.stop(0) }
    }
}
