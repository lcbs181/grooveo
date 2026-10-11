package dev.schlubbe.musicagent.desktop.ai

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writes a file that holds a secret (API key, access token) so that only the user can read it,
 * from the first byte on: a temp file created owner-only, then moved into place atomically.
 */
internal fun writeSecretFile(file: File, text: String) {
    val dir = (file.absoluteFile.parentFile ?: File(".")).apply { mkdirs() }.toPath()
    val tmp = try {
        Files.createTempFile(dir, file.name, ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } catch (_: UnsupportedOperationException) {
        Files.createTempFile(dir, file.name, ".tmp").also(::restrictToOwner)
    }
    try {
        Files.writeString(tmp, text)
        Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(tmp)
    }
}

/** Makes an existing secret file owner-only (files written by older versions were readable by all). */
internal fun tightenSecretFile(file: File) {
    if (!file.isFile) return
    runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        .onFailure { restrictToOwner(file.toPath()) }
}

/** Without POSIX permissions (Windows): an ACL for the owner alone, else the plain File flags. */
private fun restrictToOwner(p: Path) {
    val acl = Files.getFileAttributeView(p, AclFileAttributeView::class.java)
    val done = acl != null && runCatching {
        acl.acl = listOf(
            AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.owner).setPermissions(java.util.EnumSet.allOf(AclEntryPermission::class.java)).build(),
        )
    }.isSuccess
    if (!done) p.toFile().run {
        setReadable(false, false); setWritable(false, false)
        setReadable(true, true); setWritable(true, true)
    }
}
