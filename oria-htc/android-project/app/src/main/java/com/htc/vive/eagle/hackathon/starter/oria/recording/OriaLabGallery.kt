package com.htc.vive.eagle.hackathon.starter.oria.recording

import android.util.JsonReader
import android.util.JsonToken
import com.htc.vive.eagle.hackathon.starter.oria.core.Box
import com.htc.vive.eagle.hackathon.starter.oria.core.Detection
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterReader
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

/** Original index position is preserved even when an entry cannot be displayed. */
data class OriaLabGalleryFrame(
    val lineNumber: Int,
    val videoSessionId: Long?,
    val frameId: Long?,
    val receivedAtMs: Long?,
    val image: File?,
    val dataIssues: List<String>,
    internal val inference: OriaLabGalleryRecord?,
    val decisionReason: String?,
    val imageIssues: List<String> = emptyList(),
) {
    /** Keep all defects visible; only data defects prevent inspecting the recorded inference. */
    val issues: List<String> get() = when {
        imageIssues.isEmpty() -> dataIssues
        dataIssues.isEmpty() -> imageIssues
        else -> dataIssues + imageIssues
    }
}

data class OriaLabGalleryRecord(val offset: Long, val length: Int, val observedAtMs: Long? = null)

data class OriaLabGalleryIndex(
    val frames: List<OriaLabGalleryFrame> = emptyList(),
    val expectedFrames: Long? = null,
    val availableImageFiles: Int = 0,
    val invalidEntries: Int = 0,
    val invalidEventLines: Int = 0,
    val issueCount: Int = 0,
    val issues: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val missingImageFiles: Int = 0,
)

data class OriaLabGalleryAnalysis(
    val detections: List<Detection> = emptyList(),
    val analyzed: Boolean = false,
    val error: String? = null,
)

/** A compact O(frame count) index; raw model tensors and all frame detections are never retained.
 * The only JSON buffer is bounded to 2 MiB. This bounds a malformed row, never capture duration.
 * Every original nonblank frames.jsonl row keeps its position, including invalid rows.
 */
object OriaLabGallery {
    const val MAX_LINE_BYTES = 2 * 1024 * 1024
    const val MAX_DIAGNOSTICS = 100
    private const val MAX_DETECTIONS = 300
    private data class Key(val session: Long, val frame: Long)
    private data class Decision(val reason: String?, val observedAtMs: Long?)
    private data class Meta(val type: String?, val video: Long?, val session: Long?, val frame: Long?,
                            val received: Long?, val observed: Long?, val image: String?, val reason: String?) {
        fun key(): Key? = if (frame != null && frame >= 0 && (video ?: session) != null && (video ?: session!!) >= 0 &&
            (video == null || session == null || video == session)) Key(video ?: session!!, frame) else null
        fun clock(): Long? {
            require(received == null || observed == null || received == observed) { "Horodatages reçus et observés contradictoires" }
            return (received ?: observed)?.also { require(it >= 0) { "Horodatage négatif" } }
        }
    }
    private data class Line(val number: Int, val offset: Long, val bytes: ByteArray?, val error: String?)

    fun load(directory: File, checkCancelled: () -> Unit = {}): OriaLabGalleryIndex {
        val diagnostics = mutableListOf<String>()
        var issueCount = 0
        fun issue(message: String) { issueCount++; if (diagnostics.size < MAX_DIAGNOSTICS) diagnostics += message }
        try {
            checkCancelled()
            require(!Files.isSymbolicLink(directory.toPath())) { "Capture liée symboliquement refusée" }
            val root = directory.canonicalFile
            val expected = expectedFrames(root, checkCancelled, ::issue)
            val inferences = mutableMapOf<Key, OriaLabGalleryRecord>()
            val decisions = mutableMapOf<Key, Decision>()
            val ambiguous = mutableSetOf<Key>()
            var badEvents = 0
            val events = ownedFile(root, "events.jsonl")
            if (!events.isFile) issue("events.jsonl absent : aucune analyse enregistrée n’est disponible.")
            else scan(events, checkCancelled) { line ->
                try {
                    val bytes = line.bytes ?: error(line.error ?: "Ligne illisible")
                    val meta = meta(bytes, checkCancelled)
                    if (meta.type == "inference" || meta.type == "decision") {
                        val key = meta.key() ?: error("Identité vidéo/image absente ou incohérente")
                        val observedAt = meta.clock()
                        val duplicate = if (meta.type == "inference") {
                            inferences.putIfAbsent(key, OriaLabGalleryRecord(line.offset, bytes.size, observedAt)) != null
                        } else {
                            decisions.putIfAbsent(key, Decision(meta.reason, observedAt)) != null
                        }
                        if (duplicate) {
                            ambiguous += key
                            error("Plusieurs événements ${meta.type} pour vidéo ${key.session}, image ${key.frame}")
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { badEvents++; issue("events.jsonl:${line.number} · ${e.message}") }
            }
            checkCancelled()
            val framesFile = ownedFile(root, "frames.jsonl")
            val frames = mutableListOf<OriaLabGalleryFrame>()
            val seen = mutableMapOf<Key, Int>()
            if (!framesFile.isFile) issue("frames.jsonl absent : aucune entrée d’image disponible.")
            else scan(framesFile, checkCancelled) { line ->
                val problems = mutableListOf<String>()
                val imageProblems = mutableListOf<String>()
                var parsed: Meta? = null
                var image: File? = null
                try {
                    parsed = meta(line.bytes ?: error(line.error ?: "Ligne illisible"), checkCancelled)
                    val value = parsed!!
                    if (value.key() == null) problems += "Identité vidéo/image absente ou incohérente"
                    if ((value.received ?: value.observed) == null || (value.received ?: value.observed)!! < 0)
                        problems += "Horodatage de réception absent ou invalide"
                    if (value.received != null && value.observed != null && value.received != value.observed)
                        problems += "Horodatages reçus et observés contradictoires"
                    val path = value.image ?: error("Chemin PNG absent")
                    require(path.endsWith(".png", ignoreCase = true)) { "Le chemin ne désigne pas une PNG" }
                    val candidate = ownedFile(root, path)
                    // A safe, recorded PNG path may be absent while its numeric inference stays coherent.
                    // Missing/unsafe paths and malformed metadata still enter the blocking data list.
                    if (candidate.isFile) image = candidate else imageProblems += "PNG absente : $path"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { problems += e.message ?: "Entrée invalide" }
                val key = parsed?.key()
                val previous = key?.let { seen.putIfAbsent(it, frames.size) }
                if (previous != null) {
                    val cause = "Identifiant vidéo/image dupliqué (première occurrence ligne ${frames[previous].lineNumber})"
                    problems += cause
                    val old = frames[previous]
                    if (cause !in old.dataIssues) {
                        frames[previous] = old.copy(dataIssues = old.dataIssues + cause, inference = null, decisionReason = null)
                        issue("frames.jsonl:${old.lineNumber} · $cause")
                    }
                }
                if (key in ambiguous) problems += "Analyse ambiguë : plusieurs événements pour cette identité"
                val received = parsed?.received ?: parsed?.observed
                if (key != null && received != null) {
                    val inferenceClock = inferences[key]?.observedAtMs
                    val decisionClock = decisions[key]?.observedAtMs
                    if (inferenceClock != null && inferenceClock != received) problems += "Horodatage de l’inférence différent de l’image"
                    if (decisionClock != null && decisionClock != received) problems += "Horodatage de la décision différent de l’image"
                }
                (problems + imageProblems).forEach { issue("frames.jsonl:${line.number} · $it") }
                frames += OriaLabGalleryFrame(line.number, parsed?.video ?: parsed?.session, parsed?.frame,
                    parsed?.received ?: parsed?.observed, image, problems.toList(),
                    if (problems.isEmpty() && key != null) inferences[key] else null,
                    if (problems.isEmpty() && key != null) decisions[key]?.reason else null,
                    imageIssues = imageProblems.toList())
            }
            if (expected != null && expected != frames.size.toLong())
                issue("Le manifeste annonce $expected images ; l’index contient ${frames.size} entrées non vides.")
            checkCancelled()
            return OriaLabGalleryIndex(frames, expected, frames.count { it.image != null },
                frames.count { it.dataIssues.isNotEmpty() }, badEvents, issueCount, diagnostics,
                missingImageFiles = frames.count { it.imageIssues.isNotEmpty() })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { return OriaLabGalleryIndex(issueCount = issueCount + 1, issues = diagnostics,
            error = "Relecture indisponible : ${e.message}") }
    }

    /** Reads only the selected inference. An invalid detection invalidates its analysis visibly. */
    fun readFrame(directory: File, frame: OriaLabGalleryFrame, checkCancelled: () -> Unit = {}): OriaLabGalleryAnalysis {
        val reference = frame.inference ?: return OriaLabGalleryAnalysis()
        return try {
            checkCancelled()
            val bytes = ByteArray(reference.length)
            RandomAccessFile(ownedFile(directory.canonicalFile, "events.jsonl"), "r").use {
                it.seek(reference.offset); it.readFully(bytes)
            }
            val header = meta(bytes, checkCancelled)
            require(header.type == "inference") { "Le type de l’analyse a changé" }
            val observedAt = header.clock()
            require(observedAt == reference.observedAtMs) { "L’horodatage de l’analyse a changé" }
            require(observedAt == null || observedAt == frame.receivedAtMs) { "L’horodatage de l’analyse ne correspond pas à l’image" }
            require(header.key() == Key(checkNotNull(frame.videoSessionId), checkNotNull(frame.frameId))) { "L’identité de l’analyse a changé" }
            val detections = mutableListOf<Detection>()
            var found = false
            reader(bytes, checkCancelled).use { input ->
                input.beginObject()
                while (input.hasNext()) {
                    checkCancelled()
                    if (input.nextName() != "detections") { input.skipValue(); continue }
                    require(!found) { "Champ detections dupliqué" }; found = true
                    input.beginArray()
                    while (input.hasNext()) {
                        checkCancelled()
                        require(detections.size < MAX_DETECTIONS) { "Plus de 300 détections dans cette analyse" }
                        detections += detection(input)
                    }
                    input.endArray()
                }
                input.endObject()
            }
            require(found) { "Champ detections absent dans l’analyse" }
            OriaLabGalleryAnalysis(detections, analyzed = true)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { OriaLabGalleryAnalysis(error = "Analyse non affichée : ${e.message}") }
    }

    private fun detection(input: JsonReader): Detection {
        var classId: Int? = null; var confidence: Double? = null; var box: Box? = null
        val fields = mutableSetOf<String>()
        input.beginObject()
        while (input.hasNext()) {
            val field = input.nextName(); require(fields.add(field)) { "Champ de détection dupliqué" }
            when (field) {
                "classId" -> classId = checkNotNull(long(input)).also { require(it in 0..5) }.toInt()
                "confidence" -> confidence = number(input)
                "box" -> {
                    val coordinates = mutableMapOf<String, Double>()
                    input.beginObject()
                    while (input.hasNext()) {
                        val name = input.nextName()
                        if (name in setOf("left", "top", "right", "bottom")) {
                            require(!coordinates.containsKey(name)) { "Coordonnée dupliquée" }; coordinates[name] = number(input)
                        } else input.skipValue()
                    }
                    input.endObject()
                    val left = checkNotNull(coordinates["left"]); val top = checkNotNull(coordinates["top"])
                    val right = checkNotNull(coordinates["right"]); val bottom = checkNotNull(coordinates["bottom"])
                    require(left in 0.0..1.0 && top in 0.0..1.0 && right in 0.0..1.0 && bottom in 0.0..1.0 && left < right && top < bottom) { "Boîte normalisée invalide" }
                    box = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                }
                else -> input.skipValue()
            }
        }
        input.endObject()
        val confidenceValue = checkNotNull(confidence) { "Confiance absente" }
        require(confidenceValue in 0.0..1.0) { "Confiance invalide" }
        return Detection(checkNotNull(classId) { "Classe absente" }, confidenceValue.toFloat(), checkNotNull(box) { "Boîte absente" })
    }

    private fun meta(bytes: ByteArray, checkCancelled: () -> Unit): Meta = reader(bytes, checkCancelled).use { input ->
        var type: String? = null; var video: Long? = null; var session: Long? = null; var frame: Long? = null
        var legacyFrame: Long? = null
        var received: Long? = null; var observed: Long? = null; var image: String? = null; var reason: String? = null
        val fields = mutableSetOf<String>()
        input.beginObject()
        while (input.hasNext()) {
            val name = input.nextName()
            require(name.length <= 128) { "Nom de champ JSON supérieur à 128 caractères" }
            require(fields.add(name)) { "Champ JSON dupliqué : $name" }
            when (name) {
                "type" -> type = string(input, 128, "Type")
                "videoSessionId" -> video = long(input)
                "sessionId" -> session = long(input)
                "frameId" -> frame = long(input)
                "frame" -> legacyFrame = long(input)
                "receivedAtMs" -> received = long(input)
                "observedAtMs" -> observed = long(input)
                "imagePath" -> image = string(input, 4096, "Chemin PNG")
                "reason" -> reason = if (input.peek() == JsonToken.NULL) { input.nextNull(); null } else string(input, 512, "Motif")
                else -> input.skipValue()
            }
        }
        input.endObject(); require(input.peek() == JsonToken.END_DOCUMENT) { "Données après l’objet JSON" }
        require(frame == null || legacyFrame == null || frame == legacyFrame) { "Identifiants frame et frameId contradictoires" }
        Meta(type, video, session, frame ?: legacyFrame, received, observed, image, reason)
    }

    private fun expectedFrames(root: File, checkCancelled: () -> Unit, issue: (String) -> Unit): Long? = try {
        val file = ownedFile(root, "manifest.json")
        require(file.isFile && file.length() <= MAX_LINE_BYTES) { "Manifeste absent ou trop volumineux" }
        var count: Long? = null
        reader(file.readBytes(), checkCancelled).use { input ->
            val fields = mutableSetOf<String>()
            input.beginObject()
            while (input.hasNext()) {
                val name = input.nextName()
                require(name.length <= 128 && fields.add(name)) { "Champ de manifeste invalide ou dupliqué" }
                if (name != "counts") { input.skipValue(); continue }
                val counts = mutableSetOf<String>()
                input.beginObject()
                while (input.hasNext()) {
                    val counter = input.nextName()
                    require(counter.length <= 128 && counts.add(counter)) { "Compteur de manifeste invalide ou dupliqué" }
                    if (counter == "frames") count = long(input) else input.skipValue()
                }
                input.endObject()
            }
            input.endObject()
            require(input.peek() == JsonToken.END_DOCUMENT) { "Données après le manifeste" }
        }
        require(count != null && count!! >= 0) { "Nombre d’images absent ou invalide dans le manifeste" }
        count
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { issue("manifest.json · ${e.message}"); null }

    private fun string(input: JsonReader, max: Int, label: String): String {
        require(input.peek() == JsonToken.STRING) { "$label : une chaîne JSON est attendue" }
        return input.nextString().also { require(it.length <= max) { "$label supérieur à $max caractères" } }
    }
    private fun long(input: JsonReader): Long? {
        if (input.peek() == JsonToken.NULL) { input.nextNull(); return null }
        require(input.peek() == JsonToken.NUMBER) { "Un entier JSON est attendu" }
        return input.nextString().toLongOrNull() ?: error("Entier hors limites ou fractionnaire")
    }
    private fun number(input: JsonReader): Double {
        require(input.peek() == JsonToken.NUMBER) { "Un nombre JSON est attendu" }
        return input.nextDouble().also { require(it.isFinite()) { "Nombre non fini" } }
    }
    private fun reader(bytes: ByteArray, checkCancelled: () -> Unit) = JsonReader(object : FilterReader(
        InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            // JsonReader refills even while skipValue walks large raw tensors.
            checkCancelled()
            return super.read(buffer, offset, length)
        }
        override fun read(): Int { checkCancelled(); return super.read() }
    })

    private fun ownedFile(root: File, path: String): File {
        require(!File(path).isAbsolute && '\\' !in path && path.split('/').none { it == ".." }) { "Chemin hors capture refusé" }
        var cursor = root
        path.split('/').forEach { part -> cursor = File(cursor, part); require(!Files.isSymbolicLink(cursor.toPath())) { "Lien symbolique refusé" } }
        return cursor.canonicalFile.also { require(it.path.startsWith(root.path + File.separator)) { "Chemin hors capture refusé" } }
    }

    private fun scan(file: File, checkCancelled: () -> Unit, accept: (Line) -> Unit) {
        file.inputStream().buffered().use { input ->
            val block = ByteArray(8192); var offset = 0L; var lineStart = 0L; var lineNumber = 1
            var line = ByteArrayOutputStream(); var oversized = false
            fun emit() {
                checkCancelled()
                val bytes = if (oversized) null else line.toByteArray()
                if (oversized || bytes!!.any { it != 32.toByte() && it != 9.toByte() && it != 13.toByte() })
                    accept(Line(lineNumber, lineStart, bytes, if (oversized) "Ligne supérieure à 2 Mio : non interprétée" else null))
                line = ByteArrayOutputStream(); oversized = false; lineNumber++
            }
            while (true) {
                checkCancelled()
                val length = input.read(block); if (length < 0) break
                var start = 0
                for (i in 0 until length) {
                    if (block[i] == 10.toByte()) {
                        if (!oversized) {
                            if (line.size() + i - start <= MAX_LINE_BYTES) line.write(block, start, i - start) else oversized = true
                        }
                        emit(); lineStart = offset + i + 1; start = i + 1
                    }
                }
                if (!oversized) {
                    if (line.size() + length - start <= MAX_LINE_BYTES) line.write(block, start, length - start) else oversized = true
                }
                offset += length
            }
            if (line.size() > 0 || oversized) emit()
        }
    }
}
