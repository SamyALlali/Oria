package com.htc.vive.eagle.hackathon.starter.oria.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Hardware-codec test of the bundled replay: this is not a live glasses/audio validation. */
@RunWith(AndroidJUnit4::class)
class VideoDecoderInstrumentedTest {
    private data class FrameMeasurement(val ptsUs: Long, val dispatchAgeMs: Long, val callbackAgeMs: Long,
        val conversionMs: Double, val width: Int)
    @Test fun bundledH264DecodesWithoutPreviewOrMicrophone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resource = context.resources.getIdentifier("video_sample", "raw", context.packageName)
        assertTrue("Bundled HTC simulator video exists", resource != 0)
        val extractor = MediaExtractor()
        context.resources.openRawResourceFd(resource).use { fd ->
            extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
        }
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val error = AtomicReference<String?>(null)
        val frames = CopyOnWriteArrayList<FrameMeasurement>()
        val status = AtomicReference(OriaVideoStatus())
        val report = JSONObject().put("source", "bundled_htc_mp4_hardware_decode_no_sdk")
        fun saveReport() {
            val measurements = JSONArray()
            frames.forEach { f -> measurements.put(JSONObject().put("ptsUs", f.ptsUs).put("dispatchAgeMs", f.dispatchAgeMs)
                .put("callbackAgeMs", f.callbackAgeMs).put("conversionMs", f.conversionMs).put("width", f.width)) }
            val current = status.get()
            report.put("frames", measurements).put("deliveredCount", frames.size).put("error", error.get() ?: JSONObject.NULL)
                .put("receivedPackets", current.receivedPackets).put("decodedFrames", current.decodedFrames)
                .put("staleBeforeConversion", current.staleBeforeConversion).put("staleAfterConversion", current.staleAfterConversion)
                .put("codec", current.codecName ?: JSONObject.NULL)
            context.filesDir.resolve("video-decoder-report.json").writeText(report.toString(2))
            Log.i("OriaVideoTest", report.toString())
        }
        val firstPixels = AtomicReference<Pair<Long, Bitmap>?>(null)
        val decoder = OriaVideoDecoder(42, 0, false, onFrame = { frame ->
            val callbackAtMs = SystemClock.elapsedRealtime()
            assertEquals(42L, frame.sessionId)
            assertTrue(frame.bitmap.width > 0 && frame.bitmap.height > 0)
            frames.add(FrameMeasurement(frame.ptsUs, frame.deliveredAtMs - frame.receivedAtMs,
                callbackAtMs - frame.receivedAtMs, frame.conversionMs, frame.bitmap.width))
            if (firstPixels.get() == null) firstPixels.set(frame.ptsUs to frame.bitmap.copy(Bitmap.Config.ARGB_8888, false))
            false // Decoder retains ownership and recycles after the callback.
        }, onStatus = { status.set(it) }, onFatalError = { error.set(it) })
        try {
            for (key in listOf("csd-0", "csd-1")) {
                format.getByteBuffer(key)?.duplicate()?.let { buffer ->
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val data = ByteBuffer.wrap(bytes)
                    decoder.submit(data, MediaCodec.BufferInfo().apply { set(0, bytes.size, -1, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) })
                }
            }
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val inputPts = HashSet<Long>()
            val startMs = SystemClock.elapsedRealtime()
            val firstPts = extractor.sampleTime
            repeat(100) {
                if (error.get() != null || extractor.sampleTime < 0) return@repeat
                val pts = extractor.sampleTime
                val due = startMs + (pts - firstPts) / 1000
                val delay = due - SystemClock.elapsedRealtime()
                if (delay > 0) Thread.sleep(delay.coerceAtMost(100))
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size > 0) {
                    val bytes = ByteArray(size)
                    buffer.position(0); buffer.get(bytes)
                    // Extractor output is codec-ready; normalize length-prefixed AVC for our SDK contract.
                    val annexB = runCatching { lengthPrefixedToAnnexB(bytes) }.getOrElse {
                        require(H264Parameters.nals(bytes).isNotEmpty()) { "Unknown extractor AVC framing" }
                        bytes
                    }
                    inputPts.add(pts)
                    decoder.submit(ByteBuffer.wrap(annexB), MediaCodec.BufferInfo().apply { set(0, annexB.size, pts, 0) })
                }
                extractor.advance()
            }
            Thread.sleep(300)
            saveReport()
            assertNull("Decoder error: ${error.get()}; frames=$frames", error.get())
            assertTrue("At least five sampled frames; got ${frames.size}", frames.size >= 5)
            assertTrue("Every output has an exact input PTS", frames.all { it.ptsUs in inputPts })
            assertTrue("Delivered frames are fresh: $frames", frames.all { it.dispatchAgeMs in 0..500 })
            assertTrue("Conversion bounds image size", frames.all { it.width <= 832 })
            assertTrue("Sample rate remains bounded", frames.size <= 18)
            val sample = firstPixels.get()!!
            val retriever = MediaMetadataRetriever()
            try {
                context.resources.openRawResourceFd(resource).use { fd ->
                    retriever.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
                val expected = retriever.getFrameAtTime(sample.first, MediaMetadataRetriever.OPTION_CLOSEST)
                assertNotNull("Independent decoder returns the same source frame", expected)
                expected!!.let {
                    assertTrue("Aspect ratio preserved", kotlin.math.abs(it.width.toFloat() / it.height - sample.second.width.toFloat() / sample.second.height) < .02f)
                    // Sparse patch comparison tolerates scaler/color rounding, catches gross UV/crop mistakes.
                    val colorError = meanColorError(sample.second, it)
                    report.put("colorMeanAbsoluteError", colorError)
                    saveReport()
                    assertTrue("RGB difference versus platform decoder is plausible: $colorError", colorError < 35.0)
                    it.recycle()
                }
            } finally { retriever.release() }
            decoder.close()
            val countAfterStop = frames.size
            Thread.sleep(300)
            assertEquals("No frame delivery after close returns", countAfterStop, frames.size)
        } finally { decoder.close(); extractor.release(); firstPixels.get()?.second?.recycle(); saveReport() }
    }

    private fun meanColorError(actual: Bitmap, expected: Bitmap): Double {
        var error = 0L
        var count = 0
        for (row in 1..15) for (column in 1..15) {
            val a = actual.getPixel(column * actual.width / 16, row * actual.height / 16)
            val b = expected.getPixel(column * expected.width / 16, row * expected.height / 16)
            for (shift in listOf(0, 8, 16)) {
                error += kotlin.math.abs(((a shr shift) and 255) - ((b shr shift) and 255))
                count++
            }
        }
        return error.toDouble() / count
    }

    private fun lengthPrefixedToAnnexB(bytes: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset + 4 <= bytes.size) {
            val length = ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or
                ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
            require(length > 0 && length <= bytes.size - offset - 4)
            output.write(byteArrayOf(0, 0, 0, 1)); output.write(bytes, offset + 4, length)
            offset += 4 + length
        }
        require(offset == bytes.size)
        return output.toByteArray()
    }
}
