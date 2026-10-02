package dev.schlubbe.musicagent.desktop.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.StandardCopyOption

val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

private val isWindows = System.getProperty("os.name").lowercase().startsWith("win")
private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
private val home get() = System.getProperty("user.home")

/** Default data directory: %APPDATA%\Grooveo on Windows, XDG data home (~/.local/share/grooveo) elsewhere. */
fun defaultDataDir(): File {
    val dir = if (isWindows) File(env("APPDATA") ?: "$home\\AppData\\Roaming", "Grooveo")
    else File(env("XDG_DATA_HOME") ?: "$home/.local/share", "grooveo")
    return dir.apply { mkdirs() }
}

/** Image cache: %LOCALAPPDATA%\Grooveo\cache\images on Windows, ~/.cache/grooveo/images elsewhere. */
fun imageCacheDir(): File =
    if (isWindows) File(env("LOCALAPPDATA") ?: "$home\\AppData\\Local", "Grooveo\\cache\\images")
    else File(env("XDG_CACHE_HOME") ?: "$home/.cache", "grooveo/images")

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
