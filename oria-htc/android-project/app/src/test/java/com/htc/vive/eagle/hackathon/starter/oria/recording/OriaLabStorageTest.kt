package com.htc.vive.eagle.hackathon.starter.oria.recording

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class OriaLabStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun renameWritesOnlyPortableSidecarAndPreservesOriginalPayload() {
        val root = temporary.newFolder("captures")
        val directory = capture(root, "scene-a")
        val before = snapshot(directory)
        val storage = OriaLabStorage(root)
        val normalized = storage.renameSession("scene-a", "  Cafe\u0301\t\"A\" \\ B\n ")
        assertEquals("Café \"A\" \\ B", normalized)
        assertEquals(before, snapshot(directory).filterKeys { it != OriaLabStorage.LABEL_FILE })
        assertEquals("{\"schemaVersion\":1,\"displayName\":\"Café \\\"A\\\" \\\\ B\"}\n",
            File(directory, OriaLabStorage.LABEL_FILE).readText())
        val named = snapshot(directory)
        assertThrows(IllegalArgumentException::class.java) { storage.renameSession("scene-a", " \n\u200b") }
        assertEquals("Invalid rename leaves the previous name and payload intact", named, snapshot(directory))
    }

    @Test fun archiveAndRestoreMoveEveryByteWithoutChangingTheSessionIdentity() {
        val root = temporary.newFolder("captures")
        val directory = capture(root, "scene-a")
        val storage = OriaLabStorage(root)
        storage.renameSession("scene-a", "Hall 1")
        val before = snapshot(directory)
        val total = storage.totalBytes()
        val archived = storage.archiveSession("scene-a")
        assertFalse(directory.exists())
        assertTrue(storage.sessionDirectories().isEmpty())
        assertEquals(listOf(archived), storage.sessionDirectories(inTrash = true))
        assertEquals(before, snapshot(archived))
        assertEquals(total, storage.totalBytes())
        assertEquals(total, storage.trashBytes())
        val restored = storage.restoreSession("scene-a")
        assertEquals(directory, restored)
        assertEquals(before, snapshot(restored))
        assertTrue(storage.sessionDirectories(inTrash = true).isEmpty())
        assertEquals(0L, storage.trashBytes())
    }

    @Test fun restoreConflictLeavesBothCapturesUntouched() {
        val root = temporary.newFolder("captures")
        capture(root, "scene-a")
        val storage = OriaLabStorage(root)
        val archived = storage.archiveSession("scene-a")
        val existing = capture(root, "scene-a").apply { resolve("events.jsonl").appendText("another-session") }
        val original = snapshot(archived)
        val replacement = snapshot(existing)
        assertThrows(IllegalStateException::class.java) { storage.restoreSession("scene-a") }
        assertEquals(original, snapshot(archived))
        assertEquals(replacement, snapshot(existing))
    }

    @Test fun storageCountsTrashAndExportsWithoutFollowingExternalLinks() {
        val root = temporary.newFolder("captures")
        capture(root, "scene-a")
        capture(root, "scene-b")
        val exports = File(root, "_exports").apply { mkdir() }
        File(exports, "scene-b.zip").writeBytes(ByteArray(17))
        val storage = OriaLabStorage(root)
        val initial = storage.totalBytes()
        val external = temporary.newFolder("elsewhere")
        File(external, "private.bin").writeBytes(ByteArray(4_096))
        Files.createSymbolicLink(File(root, "linked-scene").toPath(), external.toPath())
        Files.createSymbolicLink(File(root, "scene-b/linked-payload").toPath(), external.toPath())
        assertEquals(initial, storage.totalBytes())
        assertEquals(2, storage.sessionDirectories().size)
        val archived = storage.archiveSession("scene-a")
        assertEquals(OriaLabStorage.bytesIn(archived), storage.trashBytes())
        assertEquals(initial, storage.totalBytes())
    }

    @Test fun identifiersAndSymbolicLinksCannotEscapeOwnedStorage() {
        val root = temporary.newFolder("captures")
        val storage = OriaLabStorage(root)
        listOf("../elsewhere", "a/b", "a\\b", ".trash", "_exports", " ").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { storage.archiveSession(id) }
        }
        val external = temporary.newFolder("elsewhere")
        File(external, "manifest.json").writeText("original")
        Files.createSymbolicLink(File(root, "scene-link").toPath(), external.toPath())
        assertThrows(IllegalStateException::class.java) { storage.renameSession("scene-link", "No") }
        assertThrows(IllegalStateException::class.java) { storage.archiveSession("scene-link") }
        assertEquals("original", File(external, "manifest.json").readText())
    }

    @Test fun labelCannotOverwriteAnExternalSymbolicLink() {
        val root = temporary.newFolder("captures")
        val directory = capture(root, "scene-a")
        val external = temporary.newFile("external-label.json").apply { writeText("original") }
        Files.createSymbolicLink(File(directory, OriaLabStorage.LABEL_FILE).toPath(), external.toPath())
        assertThrows(IllegalStateException::class.java) { OriaLabStorage(root).renameSession("scene-a", "No") }
        assertEquals("original", external.readText())
    }

    @Test fun sharedNameNormalizationCountsUnicodeCharactersAndRemovesInvisibleControls() {
        assertEquals("Étage 2 sortie", OriaLabStorage.normalizeDisplayName("\u0000E\u0301tage\u00a0 2\u200b\n sortie\t"))
        assertEquals("🦉".repeat(80), OriaLabStorage.normalizeDisplayName("🦉".repeat(80)))
        assertThrows(IllegalArgumentException::class.java) { OriaLabStorage.normalizeDisplayName("🦉".repeat(81)) }
        assertThrows(IllegalArgumentException::class.java) { OriaLabStorage.normalizeDisplayName("\u0000\u200b\t") }
    }

    private fun capture(root: File, id: String): File = File(root, id).apply {
        check(mkdirs())
        resolve("manifest.json").writeText("{\"sessionId\":\"$id\",\"status\":\"complete\"}")
        resolve("events.jsonl").writeText("{\"type\":\"decision\",\"reason\":\"fixture\"}\n")
        resolve("video.h264").writeBytes(byteArrayOf(0, 0, 0, 1, 103, -1))
        resolve("frames").mkdir()
        resolve("frames/0001.png").writeBytes(byteArrayOf(137.toByte(), 80, 78, 71, 0, 1))
    }

    private fun snapshot(directory: File): Map<String, List<Byte>> = directory.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes().toList() }
}
