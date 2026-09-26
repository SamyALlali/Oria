package com.htc.vive.eagle.hackathon.starter.oria.recording

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Normalizer

/** File operations independent of Android. The recorder serializes every call with capture/export. */
internal class OriaLabStorage(private val root: File) {
    companion object {
        const val LABEL_FILE = "session-label.json"

        /** Shared with Oria Lab for Mac: NFC, invisible controls removed, Unicode spaces collapsed. */
        fun normalizeDisplayName(input: String): String {
            val cleaned = StringBuilder()
            var space = false
            Normalizer.normalize(input, Normalizer.Form.NFC).codePoints().forEach { point ->
                when {
                    Character.isWhitespace(point) || Character.isSpaceChar(point) -> space = cleaned.isNotEmpty()
                    Character.getType(point) in setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt()) -> Unit
                    else -> {
                        if (space) cleaned.append(' ')
                        cleaned.appendCodePoint(point)
                        space = false
                    }
                }
            }
            val name = cleaned.toString()
            require(name.codePointCount(0, name.length) in 1..80) { "Le nom doit contenir de 1 à 80 caractères." }
            return name
        }

        private fun quoted(text: String): String = buildString {
            append('"')
            text.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    else -> append(character)
                }
            }
            append('"')
        }

        /** No symbolic links are followed when counting capture storage. */
        fun bytesIn(directory: File): Long = if (!directory.exists() || Files.isSymbolicLink(directory.toPath())) 0 else
            directory.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.sumOf { it.length() }
    }

    fun sessionDirectories(inTrash: Boolean = false): List<File> {
        val parent = parent(inTrash)
        return parent.listFiles()?.filter {
            it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("_") &&
                !Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == parent.canonicalFile
        }.orEmpty()
    }

    fun renameSession(id: String, displayName: String): String {
        val name = normalizeDisplayName(displayName)
        val directory = session(id, false)
        val destination = File(directory, LABEL_FILE)
        check(!Files.isSymbolicLink(destination.toPath())) { "Fichier de nom invalide" }
        val temporary = Files.createTempFile(directory.toPath(), ".session-label-", ".tmp")
        try {
            Files.write(temporary, ("{\"schemaVersion\":1,\"displayName\":" + quoted(name) + "}\n").toByteArray(Charsets.UTF_8))
            Files.move(temporary, destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return name
    }

    fun archiveSession(id: String): File = moveSession(id, fromTrash = false)
    fun restoreSession(id: String): File = moveSession(id, fromTrash = true)
    fun totalBytes(): Long = bytesIn(root)
    fun trashBytes(): Long = bytesIn(parent(true))

    private fun moveSession(id: String, fromTrash: Boolean): File {
        val source = session(id, fromTrash)
        val destinationParent = parent(!fromTrash)
        check(destinationParent.mkdirs() || destinationParent.isDirectory) { "Impossible de créer la corbeille locale" }
        val destination = checkedChild(destinationParent, id)
        check(!destination.exists()) { "Une capture avec cet identifiant existe déjà à destination" }
        // Directory move on the same filesystem. No copy, rewrite or deletion of captured payloads.
        Files.move(source.toPath(), destination.toPath())
        return destination
    }

    private fun session(id: String, inTrash: Boolean): File = checkedChild(parent(inTrash), id).also {
        check(it.isDirectory) { "Capture introuvable" }
    }

    private fun checkedChild(parent: File, id: String): File {
        require(id.isNotBlank() && !id.startsWith(".") && !id.startsWith("_") && !id.contains('/') && !id.contains('\\')) { "Identifiant de capture invalide" }
        return File(parent, id).also {
            check(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == parent.canonicalFile) { "Chemin de capture invalide" }
        }
    }

    private fun parent(inTrash: Boolean): File {
        check(!Files.isSymbolicLink(root.toPath())) { "Stockage de captures invalide" }
        return (if (inTrash) File(root, ".trash") else root).also {
            check(!Files.isSymbolicLink(it.toPath())) { "Corbeille locale invalide" }
        }
    }
}
