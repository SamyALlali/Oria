package com.htc.vive.eagle.hackathon.starter.oria.recording

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.os.SystemClock
import com.htc.vive.eagle.hackathon.starter.oria.video.OriaVideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class OriaLabPhase { OFF, RECORDING, FINALIZING, ERROR }

data class OriaLabSession(
    val id: String,
    val directory: File,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val complete: Boolean,
    val packets: Long,
    val frames: Long,
    val bytes: Long,
    val reason: String,
    val usableForReplay: Boolean = frames > 0,
    val displayName: String? = null,
)

data class OriaLabRecorderState(
    val phase: OriaLabPhase = OriaLabPhase.OFF,
    val sessionId: String? = null,
    val packets: Long = 0,
    val frames: Long = 0,
    val bytesWritten: Long = 0,
    val queuedBytes: Long = 0,
    val elapsedMs: Long = 0,
    val incomplete: Boolean = false,
    val detail: String = "Enregistrement désactivé",
    val savedSessions: List<OriaLabSession> = emptyList(),
    val trashedSessions: List<OriaLabSession> = emptyList(),
    val totalStorageBytes: Long = 0,
    val trashBytes: Long = 0,
    val storageBusy: Boolean = true,
    val availableStorageBytes: Long = 0,
)

/** Explicit opt-in capture. SDK/pixel callbacks never perform file I/O or wait for the writer. */
class OriaLabRecorder(
    context: Context,
    private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
    private val availableSpaceBytes: (File) -> Long = { it.usableSpace },
) : Closeable {
    companion object {
        const val SCHEMA_VERSION = 2
        const val OLDEST_READABLE_SCHEMA_VERSION = 1
        const val RESERVED_FREE_BYTES = 512L * 1024 * 1024
        const val MAX_QUEUED_BYTES = 20L * 1024 * 1024
        private const val MAX_TASKS = 160
        private const val MAX_JSON_BYTES = 256 * 1024
        private const val MANIFEST_RESERVE = 1024L * 1024
    }

    // Persisted location shared with existing captures; independent of the source directory name.
    private val root = File(context.applicationContext.filesDir, "oria-lab")
    private val lock = Object()
    private val queue = ArrayDeque<Work>()
    private val storageMutex = Mutex()
    private val storage = OriaLabStorage(root)
    private val _state = MutableStateFlow(OriaLabRecorderState())
    val state: StateFlow<OriaLabRecorderState> = _state.asStateFlow()
    private var saved = emptyList<OriaLabSession>()
    private var totalStorageBytes = 0L
    private var trashStorageBytes = 0L
    @Volatile private var availableStorageBytes = 0L
    private var active: Run? = null
    private var closed = false
    private var storageBusy = true
    private val worker = Thread(::writerLoop, "oria-lab-writer").apply { start() }

    private class Run(val id: String, val directory: File, val metadata: JSONObject,
                      val epochMs: Long, val originMs: Long, val videoSessionId: Long, val diskBudget: OriaLabDiskBudget) {
        var accepting = true
        var outstanding = 0
        var queuedBytes = 0L
        var packetSequence = 0L
        var frameSequence = 0L
        var endedAtMs = 0L
        var stopReason = ""
        var incompleteReason: String? = null
        var ioFailed = false
        var ignoredOtherSessionEvents = 0L
        @Volatile var packets = 0L
        @Volatile var frames = 0L
        @Volatile var events = 0L
        @Volatile var bytes = 0L
        var videoOffset = 0L
        var video: OutputStream? = null
        var packetIndex: OutputStream? = null
        var frameIndex: OutputStream? = null
        var eventIndex: OutputStream? = null
    }

    private sealed class Work(val run: Run, val memoryBytes: Long) {
        class Begin(run: Run) : Work(run, 0)
        class Packet(run: Run, memoryBytes: Long, val bytes: ByteArray, val index: JSONObject) : Work(run, memoryBytes)
        class Frame(run: Run, memoryBytes: Long, val bitmap: Bitmap, val index: JSONObject) : Work(run, memoryBytes)
        class Event(run: Run, memoryBytes: Long, val bytes: ByteArray) : Work(run, memoryBytes)
        fun dispose() { if (this is Frame && !bitmap.isRecycled) bitmap.recycle() }
    }

    /** Caller must restart video after arming capture, so its codec configuration is captured too. */
    fun start(metadata: JSONObject): Boolean {
        val frozen = try { metadata.toString().also { require(it.toByteArray().size <= MAX_JSON_BYTES) }.let(::JSONObject) }
        catch (_: Exception) { return false }
        val videoSessionId = frozen.optLong("videoSessionId", Long.MIN_VALUE)
        if (videoSessionId == Long.MIN_VALUE) return false
        synchronized(lock) {
            if (closed || storageBusy || active != null) return false
            val id = UUID.randomUUID().toString()
            val run = Run(id, File(root, id), frozen, System.currentTimeMillis(), now(), videoSessionId,
                OriaLabDiskBudget(RESERVED_FREE_BYTES, { availableSpaceBytes(root) }))
            active = run
            run.outstanding = 1
            queue.addLast(Work.Begin(run))
            publishLocked()
            lock.notifyAll()
            return true
        }
    }

    /** Preserve an armed matching capture; a replacement stream cannot enter an older recording. */
    fun onVideoStarting(videoSessionId: Long) = synchronized(lock) {
        active?.takeIf { it.videoSessionId != videoSessionId }?.let { stopLocked(it, "video_session_replaced", null) }
    }

    fun stop(reason: String) = synchronized(lock) {
        active?.let { stopLocked(it, reason, null) }
    }

    /** Copies only the indicated SDK bytes while its borrowed ByteBuffer is valid.
     * Serializing this bounded memory copy also preserves callback order if the SDK calls concurrently.
     * The writer never performs file I/O under this lock.
     */
    fun recordPacket(buffer: ByteBuffer, info: MediaCodec.BufferInfo, receivedAtMs: Long, videoSessionId: Long) = synchronized(lock) {
        val current = active?.takeIf { it.accepting && it.videoSessionId == videoSessionId } ?: return@synchronized
        val bytesCount = info.size
        val sourceOffset = info.offset
        val ptsUs = info.presentationTimeUs
        val flags = info.flags
        if (bytesCount < 0 || sourceOffset < 0 || sourceOffset.toLong() + bytesCount > buffer.capacity()) {
            failRun(current, "invalid_sdk_buffer")
            return@synchronized
        }
        val memory = bytesCount.toLong() + 512
        val run = reserve(memory, videoSessionId, current) ?: return@synchronized
        try {
            // BufferInfo defines the valid slice. Duplicating leaves position/limit/mark untouched.
            val duplicate = buffer.duplicate().apply { clear(); position(sourceOffset); limit(sourceOffset + bytesCount) }
            val bytes = ByteArray(bytesCount).also { duplicate.get(it) }
            val index = JSONObject().put("packetId", ++run.packetSequence).put("videoSessionId", videoSessionId)
                .put("ptsUs", ptsUs).put("receivedAtMs", receivedAtMs)
                .put("recordedAtMs", now()).put("length", bytesCount).put("flags", flags)
                .put("sdkBufferOffset", sourceOffset)
            commit(Work.Packet(run, memory, bytes, index))
        } catch (e: Exception) { abandon(run, memory, "packet_copy_failed: ${e.message}") }
        catch (_: OutOfMemoryError) { abandon(run, memory, "packet_copy_out_of_memory") }
    }

    /** Borrowed bitmap is copied synchronously; only this owned copy reaches the I/O worker. */
    fun recordFrame(frame: OriaVideoFrame) {
        val memory = frame.bitmap.width.toLong() * frame.bitmap.height * 4 + 1024
        val run = reserve(memory, frame.sessionId) ?: return
        var owned: Bitmap? = null
        try {
            val began = SystemClock.elapsedRealtimeNanos()
            val copy = checkNotNull(frame.bitmap.copy(Bitmap.Config.ARGB_8888, false)) { "Bitmap copy unavailable" }
            owned = copy
            val copyMs = (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000.0
            val captureFrameId = synchronized(lock) { ++run.frameSequence }
            val index = JSONObject().put("captureFrameId", captureFrameId).put("frameId", frame.frameId)
                .put("videoSessionId", frame.sessionId).put("receivedAtMs", frame.receivedAtMs)
                .put("ptsUs", frame.ptsUs).put("deliveredAtMs", frame.deliveredAtMs).put("recordedAtMs", now())
                .put("imagePath", "frames/%010d.png".format(java.util.Locale.ROOT, captureFrameId))
                .put("width", copy.width).put("height", copy.height)
                .put("sourceWidth", frame.decodedWidth).put("sourceHeight", frame.decodedHeight)
                .put("rotationDegrees", frame.rotationAppliedDegrees).put("mirrored", frame.mirrorApplied)
                .put("conversionMs", frame.conversionMs).put("copyMs", copyMs)
            commit(Work.Frame(run, memory, copy, index))
            owned = null
        } catch (e: Exception) { abandon(run, memory, "frame_copy_failed: ${e.message}") }
        catch (_: OutOfMemoryError) { abandon(run, memory, "frame_copy_out_of_memory") }
        finally { owned?.recycle() }
    }

    fun recordEvent(event: JSONObject) {
        val run: Run
        val copied: JSONObject
        synchronized(lock) {
            run = active?.takeIf { it.accepting } ?: return
            val eventSession = when {
                event.has("videoSessionId") -> event.optLong("videoSessionId", Long.MIN_VALUE)
                event.has("sessionId") -> event.optLong("sessionId", Long.MIN_VALUE)
                else -> run.videoSessionId
            }
            if (eventSession != run.videoSessionId) { run.ignoredOtherSessionEvents++; return }
        }
        var reserved = false
        var bytesCount = 0L
        try {
            copied = OriaLabPrivacy.sanitize(event).put("schemaVersion", SCHEMA_VERSION)
                .put("recordedAtMs", now()).put("recordingSessionId", run.id)
            val bytes = (copied.toString() + "\n").toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_JSON_BYTES) { failRun(run, "event_too_large"); return }
            bytesCount = bytes.size.toLong()
            reserve(bytesCount, run.videoSessionId, run) ?: return
            reserved = true
            commit(Work.Event(run, bytesCount, bytes))
            reserved = false
        } catch (e: Exception) {
            if (reserved) abandon(run, bytesCount, "event_copy_failed: ${e.message}")
            else failRun(run, "event_copy_failed: ${e.message}")
        } catch (_: OutOfMemoryError) {
            if (reserved) abandon(run, bytesCount, "event_copy_out_of_memory")
            else failRun(run, "event_copy_out_of_memory")
        }
    }

    fun sessions(): List<OriaLabSession> = synchronized(lock) { saved.toList() }

    /** Mutations touch only a label sidecar or move the entire session; captured payloads stay intact. */
    suspend fun renameSession(sessionId: String, displayName: String) = storageOperation {
        storage.renameSession(sessionId, displayName)
        loadSessions(recoverInterrupted = false)
    }

    suspend fun archiveSession(sessionId: String) = storageOperation {
        storage.archiveSession(sessionId)
        loadSessions(recoverInterrupted = false)
    }

    suspend fun restoreSession(sessionId: String) = storageOperation {
        storage.restoreSession(sessionId)
        loadSessions(recoverInterrupted = false)
    }

    /** The lease covers ZIP construction AND the caller's destination copy. */
    suspend fun <T> withExport(sessionId: String, consume: suspend (File) -> T): T = storageOperation {
        val session = synchronized(lock) { saved.firstOrNull { it.id == sessionId } ?: error("Capture introuvable") }
        val exports = File(root, "_exports").apply { check(mkdirs() || isDirectory) }
        val destination = File(exports, "${session.id}.zip")
        check(availableSpaceBytes(exports) >= session.bytes + RESERVED_FREE_BYTES + MANIFEST_RESERVE * 2) { "Espace insuffisant pour le ZIP en conservant 512 Mio libres" }
        val temporary = File(exports, "${session.id}.zip.part")
        try {
            // Rebuild instead of returning a stale ZIP after a display-name change.
            val budget = OriaLabDiskBudget(RESERVED_FREE_BYTES, { availableSpaceBytes(exports) })
            val guarded = DiskGuardStream(temporary.outputStream(), budget)
            ZipOutputStream(guarded.buffered()).use { zip ->
                zip.setLevel(1)
                session.directory.walkTopDown()
                    .onEnter { !Files.isSymbolicLink(it.toPath()) }
                    .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && !it.name.endsWith(".tmp") }
                    .sortedBy { it.relativeTo(session.directory).path }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.relativeTo(session.directory).invariantSeparatorsPath))
                        file.inputStream().use { it.copyTo(zip, 64 * 1024) }
                        zip.closeEntry()
                    }
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            consume(destination)
        } finally {
            temporary.delete()
            refreshStorageSizes()
        }
    }

    /** For callers that only need a private archive. UI export must use withExport for its full copy. */
    suspend fun export(sessionId: String): File = withExport(sessionId) { it }

    private suspend fun <T> storageOperation(action: suspend () -> T): T = storageMutex.withLock {
        synchronized(lock) {
            check(!closed) { "Oria Lab est fermé" }
            check(active == null && !storageBusy) { "Terminez la capture ou l’opération en cours" }
            storageBusy = true
            _state.value = _state.value.copy(storageBusy = true)
        }
        try { withContext(Dispatchers.IO) { action() } }
        finally { synchronized(lock) { storageBusy = false; _state.value = _state.value.copy(storageBusy = false) } }
    }

    private fun reserve(bytes: Long, videoSessionId: Long, expected: Run? = null): Run? = synchronized(lock) {
        val run = active?.takeIf { it.accepting } ?: return@synchronized null
        if (expected != null && expected !== run) return@synchronized null
        if (run.videoSessionId != videoSessionId) { run.ignoredOtherSessionEvents++; return@synchronized null }
        if (bytes > MAX_QUEUED_BYTES || run.queuedBytes + bytes > MAX_QUEUED_BYTES || run.outstanding >= MAX_TASKS) {
            stopLocked(run, "queue_overflow", "Bounded writer queue exhausted; capture is incomplete")
            return@synchronized null
        }
        run.queuedBytes += bytes
        run.outstanding++
        run
    }

    private fun commit(work: Work) = synchronized(lock) {
        queue.addLast(work)
        lock.notifyAll()
    }

    private fun abandon(run: Run, bytes: Long, reason: String) = synchronized(lock) {
        run.outstanding--
        run.queuedBytes -= bytes
        stopLocked(run, reason, reason)
        lock.notifyAll()
    }

    private fun failRun(run: Run, reason: String) = synchronized(lock) {
        if (active === run) stopLocked(run, reason, reason)
    }

    private fun stopLocked(run: Run, reason: String, incomplete: String?) {
        if (incomplete != null && run.incompleteReason == null) run.incompleteReason = incomplete
        if (run.accepting) {
            run.accepting = false
            run.endedAtMs = now()
            run.stopReason = reason
        }
        publishLocked()
        lock.notifyAll()
    }

    private fun publishLocked() {
        val run = active ?: return
        _state.value = OriaLabRecorderState(
            if (run.accepting) OriaLabPhase.RECORDING else OriaLabPhase.FINALIZING,
            run.id, run.packets, run.frames, run.bytes, run.queuedBytes,
            ((if (run.endedAtMs > 0) run.endedAtMs else now()) - run.originMs).coerceAtLeast(0),
            run.incompleteReason != null,
            run.incompleteReason ?: if (run.accepting) "Capture explicite en cours · sans limite de durée" else "Finalisation : ${run.stopReason}", saved,
            _state.value.trashedSessions, totalStorageBytes + run.bytes, trashStorageBytes, storageBusy, availableStorageBytes)
    }

    private fun writerLoop() {
        try { loadSessions(recoverInterrupted = true) }
        finally { synchronized(lock) { storageBusy = false; _state.value = _state.value.copy(storageBusy = false) } }
        while (true) {
            var work: Work? = null
            var finishing: Run? = null
            synchronized(lock) {
                while (true) {
                    if (queue.isNotEmpty()) { work = queue.removeFirst(); break }
                    val run = active
                    if (run != null && !run.accepting && run.outstanding == 0) { finishing = run; break }
                    if (closed && run == null) return
                    if (run != null) publishLocked()
                    lock.wait(if (run?.accepting == true) 250L else 0L)
                }
            }
            work?.let { job ->
                try { if (!job.run.ioFailed) write(job) }
                catch (e: OriaLabStorageFullException) {
                    synchronized(lock) { job.run.ioFailed = true; stopLocked(job.run, "storage_reserve_reached", e.message) }
                } catch (e: Exception) {
                    synchronized(lock) { job.run.ioFailed = true; stopLocked(job.run, "write_failed", "${e.javaClass.simpleName}: ${e.message}") }
                } catch (_: OutOfMemoryError) {
                    synchronized(lock) { job.run.ioFailed = true; stopLocked(job.run, "write_failed", "writer_out_of_memory") }
                } finally {
                    job.dispose()
                    synchronized(lock) { job.run.outstanding--; job.run.queuedBytes -= job.memoryBytes; publishLocked(); lock.notifyAll() }
                }
            }
            finishing?.let(::finish)
        }
    }

    private open inner class DiskGuardStream(
        private val delegate: OutputStream,
        private val budget: OriaLabDiskBudget,
    ) : OutputStream() {
        override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            try { budget.reserveWrite(length) }
            finally { availableStorageBytes = budget.availableBytes }
            delegate.write(bytes, offset, length)
        }
        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    private inner class BudgetStream(private val run: Run, delegate: OutputStream) : DiskGuardStream(delegate, run.diskBudget) {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            super.write(bytes, offset, length)
            run.bytes += length
        }
    }

    private fun write(work: Work) {
        val run = work.run
        when (work) {
            is Work.Begin -> {
                check(root.mkdirs() || root.isDirectory) { "Cannot create recording directory" }
                check(File(run.directory, "frames").mkdirs()) { "Cannot create recording session" }
                writeManifest(run, "recording")
                try { run.diskBudget.reserveWrite(0) }
                finally { availableStorageBytes = run.diskBudget.availableBytes }
                run.video = BudgetStream(run, FileOutputStream(File(run.directory, "video.h264")))
                run.packetIndex = BudgetStream(run, FileOutputStream(File(run.directory, "packets.jsonl")))
                run.frameIndex = BudgetStream(run, FileOutputStream(File(run.directory, "frames.jsonl")))
                run.eventIndex = BudgetStream(run, FileOutputStream(File(run.directory, "events.jsonl")))
            }
            is Work.Packet -> {
                work.index.put("offset", run.videoOffset)
                checkNotNull(run.video).write(work.bytes)
                run.videoOffset += work.bytes.size
                jsonLine(checkNotNull(run.packetIndex), work.index)
                run.packets++
            }
            is Work.Frame -> {
                val image = File(run.directory, work.index.getString("imagePath"))
                try {
                    BudgetStream(run, FileOutputStream(image)).use { check(work.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG compression failed" } }
                    jsonLine(checkNotNull(run.frameIndex), work.index)
                    run.frames++
                } catch (e: Exception) { image.delete(); throw e }
            }
            is Work.Event -> { checkNotNull(run.eventIndex).write(work.bytes); run.events++ }
        }
    }

    private fun finish(run: Run) {
        listOf(run.video, run.packetIndex, run.frameIndex, run.eventIndex).forEach { stream ->
            try { stream?.close() } catch (e: Exception) { synchronized(lock) { run.incompleteReason = run.incompleteReason ?: "close_failed: ${e.message}" } }
        }
        try { writeManifest(run, if (run.incompleteReason == null) "complete" else "incomplete") }
        catch (e: Exception) { synchronized(lock) { run.incompleteReason = run.incompleteReason ?: "manifest_failed: ${e.message}" } }
        loadSessions(recoverInterrupted = false)
        val session = saved.firstOrNull { it.id == run.id } ?: OriaLabSession(run.id, run.directory, run.epochMs,
            (run.endedAtMs - run.originMs).coerceAtLeast(0), run.incompleteReason == null,
            run.packets, run.frames, run.bytes, run.incompleteReason ?: run.stopReason)
        synchronized(lock) {
            saved = (listOf(session) + saved).distinctBy { it.id }.sortedByDescending { it.startedAtEpochMs }
            active = null
            _state.value = OriaLabRecorderState(if (session.complete) OriaLabPhase.OFF else OriaLabPhase.ERROR,
                session.id, session.packets, session.frames, session.bytes, 0, session.durationMs, !session.complete,
                if (!session.complete) "Capture incomplète : ${session.reason}"
                else if (!session.usableForReplay) "Capture terminée sans image exploitable : ${run.stopReason}"
                else "Capture sauvegardée : ${run.stopReason}", saved, _state.value.trashedSessions,
                totalStorageBytes, trashStorageBytes, storageBusy, availableStorageBytes)
            lock.notifyAll()
        }
    }

    private fun writeManifest(run: Run, status: String) {
        val json = JSONObject().put("schemaVersion", SCHEMA_VERSION).put("kind", "oria-lab-session")
            .put("sessionId", run.id).put("status", status).put("startedAtEpochMs", run.epochMs)
            .put("usableForReplay", run.frames > 0).put("noMedia", run.frames == 0L)
            .put("monotonicOriginMs", run.originMs).put("endedAtMonotonicMs", if (run.endedAtMs == 0L) JSONObject.NULL else run.endedAtMs)
            .put("clock", "android.os.SystemClock.elapsedRealtime milliseconds; device boot origin")
            .put("durationMs", if (run.endedAtMs == 0L) 0L else run.endedAtMs - run.originMs)
            .put("stopReason", run.stopReason).put("incompleteReason", run.incompleteReason ?: JSONObject.NULL)
            .put("metadata", run.metadata).put("unavailable", JSONArray(listOf("microphone", "depth", "pose", "glasses_capture_timestamp")))
            .put("systemFields", JSONArray(listOf("generation", "relativeDepth", "tracks", "candidates",
                "stabilization", "audioArbitration", "navigation")))
            .put("privacy", JSONObject().put("capture", "explicit_opt_in")
                .put("persistentMediaByDefault", false).put("destinationData", "omitted"))
            .put("limits", JSONObject().put("durationMs", JSONObject.NULL).put("durationPolicy", "unlimited")
                .put("sessionBytes", JSONObject.NULL).put("totalBytes", JSONObject.NULL)
                .put("reservedFreeBytes", RESERVED_FREE_BYTES).put("queuedBytes", MAX_QUEUED_BYTES))
            .put("counts", JSONObject().put("packets", run.packets).put("frames", run.frames).put("events", run.events)
                .put("bytes", run.bytes).put("ignoredOtherSessionEvents", run.ignoredOtherSessionEvents))
            .put("pixelContract", "PNG lossless copy of the selected Bitmap offered to the controller; frame_delivery and inference events indicate whether it was accepted/analysed; rotation and mirror already applied; normalized boxes refer to these pixels")
            .put("videoContract", "Concatenated original SDK buffer bytes, including codec config; packet offsets/lengths/PTS retained in packets.jsonl; no transcoding")
        val temporary = File(run.directory, "manifest.json.tmp")
        temporary.writeText(json.toString(2), Charsets.UTF_8)
        check(temporary.renameTo(File(run.directory, "manifest.json"))) { "Manifest rename failed" }
    }

    private fun loadSessions(recoverInterrupted: Boolean) {
        fun load(directory: File, recover: Boolean): OriaLabSession {
            val label = runCatching {
                val file = File(directory, OriaLabStorage.LABEL_FILE)
                check(file.length() <= 4096 && !Files.isSymbolicLink(file.toPath()))
                val json = JSONObject(file.readText())
                check(json.getInt("schemaVersion") == 1)
                OriaLabStorage.normalizeDisplayName(json.getString("displayName"))
            }.getOrNull()
            return try {
                val file = File(directory, "manifest.json")
                check(file.isFile) { "manifest_missing" }
                val json = JSONObject(file.readText())
                check(json.optInt("schemaVersion", OLDEST_READABLE_SCHEMA_VERSION) in
                    OLDEST_READABLE_SCHEMA_VERSION..SCHEMA_VERSION) { "unsupported_schema" }
                check(json.getString("sessionId") == directory.name) { "session_id_mismatch" }
                if (recover && json.optString("status") == "recording") {
                    json.put("status", "incomplete").put("incompleteReason", "process_interrupted_before_finalization")
                        .put("stopReason", "process_interrupted").put("endedAtMonotonicMs", JSONObject.NULL)
                    file.writeText(json.toString(2))
                }
                val counts = json.optJSONObject("counts") ?: JSONObject()
                OriaLabSession(directory.name, directory, json.optLong("startedAtEpochMs"),
                    json.optLong("durationMs"), json.optString("status") == "complete", counts.optLong("packets"),
                    counts.optLong("frames"), OriaLabStorage.bytesIn(directory),
                    if (!json.isNull("incompleteReason")) json.optString("incompleteReason") else json.optString("stopReason"),
                    displayName = label)
            } catch (e: Exception) {
                // Keep damaged captures visible/exportable for diagnosis, never claim completeness.
                OriaLabSession(directory.name, directory, directory.lastModified(), 0, false, 0, 0,
                    OriaLabStorage.bytesIn(directory), "invalid_manifest: ${e.message}", displayName = label)
            }
        }
        val loaded = storage.sessionDirectories().map { load(it, recoverInterrupted) }.sortedByDescending { it.startedAtEpochMs }
        val trashed = storage.sessionDirectories(inTrash = true).map { load(it, false) }.sortedByDescending { it.startedAtEpochMs }
        val total = storage.totalBytes()
        val trash = storage.trashBytes()
        val available = availableSpaceBytes(root.takeIf { it.exists() } ?: root.parentFile).coerceAtLeast(0)
        synchronized(lock) {
            saved = loaded
            totalStorageBytes = total
            trashStorageBytes = trash
            availableStorageBytes = available
            _state.value = _state.value.copy(savedSessions = saved, trashedSessions = trashed,
                totalStorageBytes = total, trashBytes = trash, availableStorageBytes = available)
        }
    }

    private fun refreshStorageSizes() {
        val total = storage.totalBytes()
        val trash = storage.trashBytes()
        val available = availableSpaceBytes(root.takeIf { it.exists() } ?: root.parentFile).coerceAtLeast(0)
        synchronized(lock) {
            totalStorageBytes = total
            trashStorageBytes = trash
            availableStorageBytes = available
            _state.value = _state.value.copy(totalStorageBytes = total, trashBytes = trash, availableStorageBytes = available)
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        active?.let { stopLocked(it, "recorder_closed", null) }
        lock.notifyAll()
    }

    private fun jsonLine(stream: OutputStream, value: JSONObject) = stream.write((value.toString() + "\n").toByteArray(Charsets.UTF_8))
    private fun now() = monotonicClock()
}
