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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
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

    private fun fixture(test: (OriaLabRecorder) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "recorder-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val recorder = OriaLabRecorder(context)
        try { test(recorder) } finally {
            recorder.stop("test_cleanup")
            if (recorder.state.value.phase in setOf(OriaLabPhase.RECORDING, OriaLabPhase.FINALIZING)) awaitSaved(recorder)
            recorder.close()
            directory.deleteRecursively()
        }
    }
}
