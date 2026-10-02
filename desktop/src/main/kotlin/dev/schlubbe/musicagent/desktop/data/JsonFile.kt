package dev.schlubbe.musicagent.desktop.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.StandardCopyOption

val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

/** Default data directory, shared with earlier Grooveo desktop builds. */
fun defaultDataDir(): File {
    val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
    val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".local/share")
    return File(base, "grooveo").apply { mkdirs() }
}

/** Reads [file] as JSON; null when missing or corrupt (corrupt files are kept aside as `.bad`). */
fun <T> readJson(file: File, type: Type): T? {
    if (!file.isFile) return null
    return runCatching { gson.fromJson<T>(file.readText(), type) }.getOrElse {
        file.copyTo(File(file.path + ".bad"), overwrite = true)
        null
    }
}

/** Atomic write: temp file + rename, so a crash never leaves a half-written file. */
fun writeJson(file: File, value: Any) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    tmp.writeText(gson.toJson(value))
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
