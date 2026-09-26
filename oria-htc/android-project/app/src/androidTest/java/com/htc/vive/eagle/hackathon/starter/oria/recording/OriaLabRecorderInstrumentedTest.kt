package com.htc.vive.eagle.hackathon.starter.oria.recording

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaCodec
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.htc.vive.eagle.hackathon.starter.oria.video.OriaVideoFrame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Real Android Bitmap/files/ZIP test; synthetic inputs, not a live HTC transport proof. */
@RunWith(AndroidJUnit4::class)
class OriaLabRecorderInstrumentedTest {
    @Test fun ownedPixelsAndExactBufferSliceSurviveStopAndZipExport() = fixture { recorder ->
        assertTrue(recorder.start(metadata(101)))
        val input = ByteBuffer.wrap(byteArrayOf(99, 98, 0, 0, 0, 1, 103, 42, 97))
        input.position(1); input.limit(8)
        val at = SystemClock.elapsedRealtime()
        recorder.recordPacket(input, MediaCodec.BufferInfo().apply {
            set(2, 6, 123_456, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        }, at, 101)
        assertEquals("Borrowed position preserved", 1, input.position())
        assertEquals("Borrowed limit preserved", 8, input.limit())
        // Overwriting the SDK buffer after the callback must not change captured bytes.
        input.put(2, 55)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)
        bitmap.setPixels(colors, 0, 2, 0, 0, 2, 2)
        recorder.recordFrame(frame(bitmap, 101, 7, at))
        bitmap.eraseColor(Color.BLACK)
        bitmap.recycle()
        recorder.recordEvent(JSONObject().put("type", "frame_delivery").put("sessionId", 101)
            .put("frameId", 7).put("accepted", true))
        recorder.recordEvent(JSONObject().put("type", "stop").put("sessionId", 101))
        recorder.stop("test_stop")
        val session = awaitSaved(recorder)
        assertTrue(session.complete)
        assertTrue(session.usableForReplay)
        assertEquals(1L, session.packets)
        assertEquals(1L, session.frames)
        val exported = runBlocking { recorder.export(session.id) }
        ZipFile(exported).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(names.containsAll(setOf("manifest.json", "video.h264", "packets.jsonl", "frames.jsonl", "events.jsonl")))
            zip.entries().asSequence().forEach { entry ->
                assertFalse("Only relative bundle paths", entry.name.startsWith("/") || entry.name.split('/').contains(".."))
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                assertEquals("ZIP size: ${entry.name}", entry.size, bytes.size.toLong())
                assertEquals("ZIP CRC: ${entry.name}", entry.crc, CRC32().apply { update(bytes) }.value)
            }
            fun text(path: String) = zip.getInputStream(zip.getEntry(path)).bufferedReader().use { it.readText() }
            val manifest = JSONObject(text("manifest.json"))
            assertEquals(1, manifest.getInt("schemaVersion"))
            assertEquals("complete", manifest.getString("status"))
            assertTrue(manifest.getBoolean("usableForReplay"))
            assertEquals(101L, manifest.getJSONObject("metadata").getLong("videoSessionId"))
            assertArrayEquals(byteArrayOf(0, 0, 0, 1, 103, 42), zip.getInputStream(zip.getEntry("video.h264")).use { it.readBytes() })
            val packet = JSONObject(text("packets.jsonl").trim())
            assertEquals(0L, packet.getLong("offset"))
            assertEquals(6, packet.getInt("length"))
            assertEquals(2, packet.getInt("sdkBufferOffset"))
            assertEquals(123_456L, packet.getLong("ptsUs"))
            assertEquals(at, packet.getLong("receivedAtMs"))
            val frame = JSONObject(text("frames.jsonl").trim())
            assertEquals(7L, frame.getLong("frameId"))
            assertEquals(101L, frame.getLong("videoSessionId"))
            val decoded = zip.getInputStream(zip.getEntry(frame.getString("imagePath"))).use { BitmapFactory.decodeStream(it) }
            assertNotNull(decoded)
            val pixels = IntArray(4)
            decoded!!.getPixels(pixels, 0, 2, 0, 0, 2, 2)
            decoded.recycle()
            assertArrayEquals("Lossless PNG owns the exact pre-recycle pixels", colors, pixels)
            assertEquals(listOf("frame_delivery", "stop"), text("events.jsonl").lineSequence().filter { it.isNotBlank() }.map { JSONObject(it).getString("type") }.toList())
        }
    }

    @Test fun boundedOverflowIsIncompleteAndLateGenerationCannotPolluteNextCapture() = fixture { recorder ->
        assertTrue(recorder.start(metadata(201)))
        // One packet over the queue budget: rejected before copying, deterministically.
        val oversized = ByteBuffer.allocate(OriaLabRecorder.MAX_QUEUED_BYTES.toInt())
        recorder.recordPacket(oversized, MediaCodec.BufferInfo().apply { set(0, oversized.capacity(), 9, 0) },
            SystemClock.elapsedRealtime(), 201)
        val first = awaitSaved(recorder)
        assertFalse(first.complete)
        assertTrue(first.reason.contains("Bounded writer queue"))
        assertEquals(0L, first.packets)
        assertEquals(OriaLabPhase.ERROR, recorder.state.value.phase)
        assertTrue(recorder.start(metadata(202)))
        recorder.recordEvent(JSONObject().put("type", "old_result").put("sessionId", 201))
        val wrong = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        recorder.recordFrame(frame(wrong, 201, 1, SystemClock.elapsedRealtime()))
        wrong.recycle()
        recorder.recordPacket(ByteBuffer.wrap(byteArrayOf(1, 2)), MediaCodec.BufferInfo().apply { set(0, 2, 10, 0) },
            SystemClock.elapsedRealtime(), 201)
        recorder.recordPacket(ByteBuffer.wrap(byteArrayOf(3, 4)), MediaCodec.BufferInfo().apply { set(0, 2, 11, 0) },
            SystemClock.elapsedRealtime(), 202)
        recorder.recordEvent(JSONObject().put("type", "current_result").put("sessionId", 202))
        recorder.stop("second_stop")
        val second = awaitSaved(recorder, previousId = first.id)
        assertTrue(second.complete)
        assertEquals(1L, second.packets)
        assertEquals(0L, second.frames)
        assertFalse(second.usableForReplay)
        assertArrayEquals(byteArrayOf(3, 4), File(second.directory, "video.h264").readBytes())
        val events = File(second.directory, "events.jsonl").readLines().map(::JSONObject)
        assertEquals(1, events.size)
        assertEquals("current_result", events.single().getString("type"))
        assertEquals(202L, events.single().getLong("sessionId"))
        assertEquals(0L, recorder.state.value.queuedBytes)
    }

    @Test fun armedCaptureSurvivesMatchingVideoStartAndEmptyStopIsNotReplayable() = fixture { recorder ->
        assertTrue(recorder.start(metadata(301)))
        recorder.onVideoStarting(301)
        assertEquals(OriaLabPhase.RECORDING, recorder.state.value.phase)
        recorder.stop("permission_denied")
        val session = awaitSaved(recorder)
        assertFalse(session.usableForReplay)
        val manifest = JSONObject(File(session.directory, "manifest.json").readText())
        assertFalse(manifest.getBoolean("usableForReplay"))
        assertTrue(manifest.getBoolean("noMedia"))
        assertTrue(recorder.state.value.detail.contains("sans image"))
    }

    @Test fun renamedCaptureExportsSidecarAndArchiveRestorePreserveEveryPayload() = fixture { recorder ->
        assertTrue(recorder.start(metadata(401)))
        recorder.recordEvent(JSONObject().put("type", "fixture").put("sessionId", 401))
        recorder.stop("storage_fixture")
        val session = awaitSaved(recorder)
        val original = session.directory.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(session.directory).path to it.readBytes().toList() }
        runBlocking {
            recorder.renameSession(session.id, "  Hall\t1  ")
            assertEquals("Hall 1", recorder.sessions().single().displayName)
            recorder.withExport(session.id) { zipFile ->
                ZipFile(zipFile).use { zip ->
                    val label = JSONObject(zip.getInputStream(zip.getEntry("session-label.json")).bufferedReader().use { it.readText() })
                    assertEquals(1, label.getInt("schemaVersion"))
                    assertEquals("Hall 1", label.getString("displayName"))
                }
                assertTrue(recorder.state.value.storageBusy)
                assertFalse("A capture cannot start while the destination is still being copied", recorder.start(metadata(402)))
            }
            // A second export cannot silently reuse a ZIP containing the previous name.
            recorder.renameSession(session.id, "Hall 2")
            recorder.withExport(session.id) { zipFile ->
                ZipFile(zipFile).use { zip ->
                    val label = JSONObject(zip.getInputStream(zip.getEntry("session-label.json")).bufferedReader().use { it.readText() })
                    assertEquals("Hall 2", label.getString("displayName"))
                }
            }
            recorder.archiveSession(session.id)
            assertTrue(recorder.sessions().isEmpty())
            assertEquals(session.id, recorder.state.value.trashedSessions.single().id)
            recorder.restoreSession(session.id)
        }
        val restored = recorder.sessions().single()
        original.forEach { (path, bytes) -> assertEquals("Unchanged original $path", bytes, restored.directory.resolve(path).readBytes().toList()) }
        assertEquals("Hall 2", restored.displayName)
        assertTrue(recorder.state.value.trashedSessions.isEmpty())
        assertFalse(recorder.state.value.storageBusy)
    }

    @Test fun mutationWaitsForFullExportLeaseAndActiveCaptureRejectsMutation() = fixture { recorder ->
        assertTrue(recorder.start(metadata(501)))
        recorder.stop("lease_fixture")
        val session = awaitSaved(recorder)
        runBlocking {
            var rename: kotlinx.coroutines.Deferred<Unit>? = null
            recorder.withExport(session.id) {
                rename = async(start = CoroutineStart.UNDISPATCHED) { recorder.renameSession(session.id, "After export") }
                yield()
                assertFalse("Mutation remains queued until the destination consumer finishes", rename!!.isCompleted)
                assertNull(recorder.sessions().single().displayName)
            }
            rename!!.await()
            assertEquals("After export", recorder.sessions().single().displayName)
            assertTrue(recorder.start(metadata(502)))
            val failed = runCatching { recorder.archiveSession(session.id) }
            assertTrue(failed.exceptionOrNull() is IllegalStateException)
            assertTrue(session.directory.isDirectory)
            recorder.stop("lease_fixture_done")
        }
        awaitSaved(recorder, previousId = session.id)
    }

    @Test fun captureContinuesBeyondOneMinuteAndFifteenMinutesUntilExplicitStop() {
        val origin = 10_000L
        val clock = AtomicLong(origin)
        fixture(monotonicClock = { clock.get() }) { recorder ->
            assertTrue(recorder.start(metadata(601)))
            recorder.recordPacket(ByteBuffer.wrap(byteArrayOf(1, 2, 3)),
                MediaCodec.BufferInfo().apply { set(0, 3, 100, 0) }, clock.get(), 601)
            clock.set(origin + 60_001)
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            recorder.recordFrame(frame(bitmap, 601, 1, clock.get()))
            bitmap.recycle()
            recorder.recordEvent(JSONObject().put("type", "after_one_minute").put("sessionId", 601))
            clock.set(origin + 15 * 60_000 + 1)
            recorder.recordPacket(ByteBuffer.wrap(byteArrayOf(4, 5, 6)),
                MediaCodec.BufferInfo().apply { set(0, 3, 900_001_000, 0) }, clock.get(), 601)
            recorder.recordEvent(JSONObject().put("type", "after_fifteen_minutes").put("sessionId", 601))
            assertEquals("Advancing the clock cannot end the capture", OriaLabPhase.RECORDING, recorder.state.value.phase)
            recorder.stop("explicit_user_stop")
            val session = awaitSaved(recorder)
            assertTrue(session.complete)
            assertEquals(900_001L, session.durationMs)
            assertEquals(2L, session.packets)
            assertEquals(1L, session.frames)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), session.directory.resolve("video.h264").readBytes())
            val manifest = JSONObject(session.directory.resolve("manifest.json").readText())
            val limits = manifest.getJSONObject("limits")
            assertTrue(limits.has("durationMs") && limits.isNull("durationMs"))
            assertEquals("unlimited", limits.getString("durationPolicy"))
            assertTrue(limits.has("sessionBytes") && limits.isNull("sessionBytes"))
            assertTrue(limits.has("totalBytes") && limits.isNull("totalBytes"))
            assertEquals(OriaLabRecorder.RESERVED_FREE_BYTES, limits.getLong("reservedFreeBytes"))
            assertEquals("explicit_user_stop", manifest.getString("stopReason"))
            assertEquals(listOf("after_one_minute", "after_fifteen_minutes"),
                session.directory.resolve("events.jsonl").readLines().map { JSONObject(it).getString("type") })
        }
    }

    @Test fun diskReserveStopsActualWriterAndRetainsAlreadyWrittenPackets() {
        val free = AtomicLong(2L * 1024 * 1024 * 1024)
        fixture(availableSpaceBytes = { free.get() }) { recorder ->
            assertTrue(recorder.start(metadata(701)))
            val firstPacket = byteArrayOf(0, 0, 0, 1, 103, 42)
            recorder.recordPacket(ByteBuffer.wrap(firstPacket),
                MediaCodec.BufferInfo().apply { set(0, firstPacket.size, 100, 0) }, SystemClock.elapsedRealtime(), 701)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (recorder.state.value.packets != 1L && SystemClock.elapsedRealtime() < deadline) Thread.yield()
            assertEquals("The first packet reached the real file writer", 1L, recorder.state.value.packets)
            free.set(OriaLabRecorder.RESERVED_FREE_BYTES - 1)
            val next = ByteArray(1024 * 1024)
            recorder.recordPacket(ByteBuffer.wrap(next),
                MediaCodec.BufferInfo().apply { set(0, next.size, 200, 0) }, SystemClock.elapsedRealtime(), 701)
            val session = awaitSaved(recorder)
            assertFalse(session.complete)
            assertEquals(OriaLabPhase.ERROR, recorder.state.value.phase)
            assertEquals(1L, session.packets)
            assertArrayEquals("Disk guard preserves the previously written H264 prefix", firstPacket,
                session.directory.resolve("video.h264").readBytes())
            val manifest = JSONObject(session.directory.resolve("manifest.json").readText())
            assertEquals("storage_reserve_reached", manifest.getString("stopReason"))
            assertEquals("incomplete", manifest.getString("status"))
            assertTrue(manifest.getString("incompleteReason").contains("512 Mio"))
            assertEquals(1, session.directory.resolve("packets.jsonl").readLines().size)
            assertEquals(0L, recorder.state.value.queuedBytes)
            runBlocking {
                assertTrue(runCatching { recorder.export(session.id) }.exceptionOrNull() is IllegalStateException)
            }
            assertArrayEquals(firstPacket, session.directory.resolve("video.h264").readBytes())
            assertFalse(recorder.state.value.storageBusy)
        }
    }

    private fun metadata(session: Long) = JSONObject().put("videoSessionId", session).put("source", "instrumented_synthetic")
    private fun frame(bitmap: Bitmap, session: Long, id: Long, at: Long) = OriaVideoFrame(
        bitmap, session, id, at, 123_456, 2, 2, 0, false, at + 1, 0.1)

    private fun awaitSaved(recorder: OriaLabRecorder, previousId: String? = null): OriaLabSession {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = recorder.state.value
            if (state.phase in setOf(OriaLabPhase.OFF, OriaLabPhase.ERROR)) {
                state.savedSessions.firstOrNull { it.id != previousId }?.let { return it }
            }
            Thread.sleep(10)
        }
        throw AssertionError("Recorder did not finalize: ${recorder.state.value}")
    }

    private fun fixture(
        monotonicClock: () -> Long = SystemClock::elapsedRealtime,
        availableSpaceBytes: (File) -> Long = { it.usableSpace },
        test: (OriaLabRecorder) -> Unit,
    ) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "recorder-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val recorder = OriaLabRecorder(context, monotonicClock, availableSpaceBytes)
        try {
            val readyDeadline = SystemClock.elapsedRealtime() + 10_000
            while (recorder.state.value.storageBusy && SystemClock.elapsedRealtime() < readyDeadline) Thread.sleep(10)
            assertFalse("Initial storage scan completed", recorder.state.value.storageBusy)
            test(recorder)
        } finally {
            recorder.stop("test_cleanup")
            if (recorder.state.value.phase in setOf(OriaLabPhase.RECORDING, OriaLabPhase.FINALIZING)) awaitSaved(recorder)
            recorder.close()
            directory.deleteRecursively()
        }
    }
}
