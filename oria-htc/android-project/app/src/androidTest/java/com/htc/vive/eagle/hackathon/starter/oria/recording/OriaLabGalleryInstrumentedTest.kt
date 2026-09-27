package com.htc.vive.eagle.hackathon.starter.oria.recording

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.htc.vive.eagle.hackathon.starter.oria.ui.decodeGalleryPreview
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real Android JsonReader / PNG decoder, temporary synthetic captures except the opt-in read-only test. */
@RunWith(AndroidJUnit4::class)
class OriaLabGalleryInstrumentedTest {
    @Test fun everySourcePositionSurvivesMalformedRowAndMissingPng() = fixture { root ->
        png(root, "a.png"); png(root, "c.png")
        manifest(root, 4)
        rows(root, "frames.jsonl", frame(1, 101, "a.png", 1100), "{broken", frame(1, 102, "missing.png", 2200), frame(1, 103, "c.png", 3300))
        rows(root, "events.jsonl", inference(1, 101, 0), inference(1, 102, 1), inference(1, 103, 2), decision(1, 103, "FRESH"))
        val before = hashes(root)
        val gallery = OriaLabGallery.load(root)
        assertNull(gallery.error)
        assertEquals(4L, gallery.expectedFrames)
        assertEquals(listOf(1, 2, 3, 4), gallery.frames.map { it.lineNumber })
        assertEquals(listOf(101L, null, 102L, 103L), gallery.frames.map { it.frameId })
        assertEquals(2, gallery.availableImageFiles)
        assertEquals(1, gallery.invalidEntries)
        assertEquals(1, gallery.missingImageFiles)
        assertTrue(gallery.frames[2].issues.any { it.contains("PNG absente") })
        assertNull(gallery.frames[1].videoSessionId)
        assertNull(gallery.frames[1].receivedAtMs)
        val missingImage = gallery.frames[2]
        assertNull(missingImage.image)
        assertTrue(missingImage.dataIssues.isEmpty())
        assertEquals(listOf("PNG absente : missing.png"), missingImage.imageIssues)
        val textOnly = OriaLabGallery.readFrame(root, missingImage)
        assertTrue(textOnly.analyzed)
        assertNull(textOnly.error)
        assertEquals(1, textOnly.detections.single().classId)
        assertEquals(0.875f, textOnly.detections.single().confidence, 0f)
        assertEquals(2200L, missingImage.receivedAtMs)
        val last = OriaLabGallery.readFrame(root, gallery.frames[3])
        assertTrue(last.analyzed)
        assertEquals(2, last.detections.single().classId)
        assertEquals(3300L, gallery.frames[3].receivedAtMs)
        assertEquals("FRESH", gallery.frames[3].decisionReason)
        assertEquals(before, hashes(root))
    }

    @Test fun absentPngDoesNotWeakenIdentityClockDetectionOrPathGuards() = fixture { root ->
        manifest(root, 7)
        rows(root, "frames.jsonl", frame(7, 1, "missing.png", 1000),
            frame(7, 2, "missing.png", 2000), frame(7, 3, "missing.png", 3000),
            frame(7, 4, "../outside.png", 4000), frame(7, 5, "missing.png", 5000),
            frame(7, 5, "missing.png", 5000), frame(7, 6, "missing.png", 6000).put("sessionId", 8))
        val invalidBox = inference(7, 3, 2)
        invalidBox.getJSONArray("detections").getJSONObject(0).getJSONObject("box").put("right", -1)
        rows(root, "events.jsonl", inference(7, 1, 0).put("receivedAtMs", 99),
            inference(7, 2, 0), inference(7, 2, 1), invalidBox, inference(7, 4, 3),
            inference(7, 5, 4), inference(7, 6, 5))
        val before = hashes(root)
        val gallery = OriaLabGallery.load(root)
        assertEquals(7, gallery.frames.size)
        assertEquals(0, gallery.availableImageFiles)
        for (entry in gallery.frames) {
            assertNull(entry.image)
            val analysis = OriaLabGallery.readFrame(root, entry)
            assertFalse("Bad data must remain blocked at line ${entry.lineNumber}", analysis.analyzed)
            assertTrue(analysis.detections.isEmpty())
        }
        assertTrue(gallery.frames[0].dataIssues.any { it.contains("Horodatage") })
        assertTrue(gallery.frames[1].dataIssues.any { it.contains("ambiguë") })
        assertNotNull(OriaLabGallery.readFrame(root, gallery.frames[2]).error)
        assertTrue(gallery.frames[3].dataIssues.any { it.contains("hors capture") })
        assertTrue(gallery.frames[4].dataIssues.any { it.contains("dupliqué") })
        assertTrue(gallery.frames[5].dataIssues.any { it.contains("dupliqué") })
        assertTrue(gallery.frames[6].dataIssues.any { it.contains("Identité") })
        assertEquals(before, hashes(root))
    }

    @Test fun blockedDecisionReasonsAreNotAttributedToMissingImages() = fixture { root ->
        manifest(root, 4)
        rows(root, "frames.jsonl", frame(9, 1, "missing.png", 1000), frame(9, 2, "missing.png", 2000),
            frame(9, 3, "missing.png", 3000), frame(9, 3, "missing.png", 3000))
        rows(root, "events.jsonl", inference(9, 1, 0), decision(9, 1, "FIRST"), decision(9, 1, "SECOND"),
            inference(9, 2, 1), decision(9, 2, "WRONG_CLOCK").put("receivedAtMs", 99),
            inference(9, 3, 2), decision(9, 3, "DUPLICATE_FRAME"))
        val before = hashes(root)
        val gallery = OriaLabGallery.load(root)
        assertEquals(4, gallery.missingImageFiles)
        assertEquals(4, gallery.invalidEntries)
        for (entry in gallery.frames) {
            assertTrue(entry.dataIssues.isNotEmpty())
            assertNull("Never attribute a decision reason on a blocked entry", entry.decisionReason)
            assertFalse(OriaLabGallery.readFrame(root, entry).analyzed)
        }
        assertEquals(before, hashes(root))
    }

    @Test fun absentPngDistinguishesRecordedZeroDetectionsFromNoInference() = fixture { root ->
        manifest(root, 2)
        rows(root, "frames.jsonl", frame(1, 1, "missing.png", 1000), frame(1, 2, "missing.png", 2000))
        rows(root, "events.jsonl", inference(1, 1, 0).put("detections", JSONArray()))
        val before = hashes(root)
        val gallery = OriaLabGallery.load(root)
        val recordedEmpty = OriaLabGallery.readFrame(root, gallery.frames[0])
        val absent = OriaLabGallery.readFrame(root, gallery.frames[1])
        assertTrue(recordedEmpty.analyzed)
        assertTrue(recordedEmpty.detections.isEmpty())
        assertNull(recordedEmpty.error)
        assertFalse(absent.analyzed)
        assertTrue(absent.detections.isEmpty())
        assertEquals(listOf(1L, 2L), gallery.frames.map { it.frameId })
        assertEquals(before, hashes(root))
    }

    @Test fun sameImageAndFrameNumberAcrossVideoSessionsRemainIndependent() = fixture { root ->
        png(root, "same.png"); manifest(root, 2)
        rows(root, "frames.jsonl", frame(7, 99, "same.png", 100), frame(8, 99, "same.png", 200))
        rows(root, "events.jsonl", inference(7, 99, 1), inference(8, 99, 4))
        val gallery = OriaLabGallery.load(root)
        assertEquals(0, gallery.invalidEntries)
        assertEquals(listOf(7L, 8L), gallery.frames.map { it.videoSessionId })
        assertEquals(listOf(100L, 200L), gallery.frames.map { it.receivedAtMs })
        assertEquals(listOf(1, 4), gallery.frames.map { OriaLabGallery.readFrame(root, it).detections.single().classId })
    }

    @Test fun badIdentifiersAndDuplicateKeysAreExplicitInsteadOfDefaultingOrLastWinning() = fixture { root ->
        png(root, "shared.png"); manifest(root, 7)
        rows(root, "frames.jsonl", frame(1, 4, "shared.png", 10), frame(1, 4, "shared.png", 11),
            frame(1, 5, "shared.png", 12).put("frameId", "5"),
            frame(1, 6, "shared.png", 13).apply { remove("videoSessionId") },
            frame(1, 7, "shared.png", 14).put("sessionId", 2), "{bad", "{bad too")
        rows(root, "events.jsonl", inference(1, 4, 0))
        val gallery = OriaLabGallery.load(root)
        assertEquals(7, gallery.frames.size)
        assertEquals(7, gallery.invalidEntries)
        assertTrue(gallery.frames.take(2).all { it.issues.any { cause -> cause.contains("dupliqué") } })
        assertTrue(gallery.frames.all { !OriaLabGallery.readFrame(root, it).analyzed })
        assertNull(gallery.frames[2].frameId)
        assertNull(gallery.frames[3].videoSessionId)
        assertEquals(listOf(6, 7), gallery.frames.takeLast(2).map { it.lineNumber })
    }

    @Test fun duplicateInferenceAndMalformedDetectionNeverLeakAnArbitraryAnalysis() = fixture { root ->
        png(root, "shared.png"); manifest(root, 3)
        rows(root, "frames.jsonl", frame(1, 1, "shared.png", 1), frame(1, 2, "shared.png", 2), frame(1, 3, "shared.png", 3))
        val invalid = inference(1, 2, 2)
        invalid.getJSONArray("detections").getJSONObject(0).getJSONObject("box").put("right", -1)
        rows(root, "events.jsonl", inference(1, 1, 0), inference(1, 1, 1), "{truncated", invalid, inference(1, 3, 3))
        val gallery = OriaLabGallery.load(root)
        assertEquals(2, gallery.invalidEventLines)
        assertTrue(gallery.frames[0].issues.any { it.contains("ambiguë") })
        assertFalse(OriaLabGallery.readFrame(root, gallery.frames[0]).analyzed)
        val malformed = OriaLabGallery.readFrame(root, gallery.frames[1])
        assertFalse(malformed.analyzed); assertNotNull(malformed.error); assertTrue(malformed.detections.isEmpty())
        assertEquals(3, OriaLabGallery.readFrame(root, gallery.frames[2]).detections.single().classId)
    }

    @Test fun inconsistentObservationClocksAndChangedEventTypesAreRejected() = fixture { root ->
        png(root, "same.png"); manifest(root, 3)
        rows(root, "frames.jsonl", frame(7, 1, "same.png", 1000), frame(7, 2, "same.png", 2000), frame(7, 3, "same.png", 3000))
        rows(root, "events.jsonl", inference(7, 1, 0).put("receivedAtMs", 99).put("observedAtMs", 99),
            inference(7, 2, 1).put("receivedAtMs", 2000).put("observedAtMs", 88), inference(7, 3, 2))
        val gallery = OriaLabGallery.load(root)
        assertTrue(gallery.frames[0].issues.any { it.contains("Horodatage") })
        assertFalse(OriaLabGallery.readFrame(root, gallery.frames[0]).analyzed)
        assertEquals(1, gallery.invalidEventLines)
        assertFalse(OriaLabGallery.readFrame(root, gallery.frames[1]).analyzed)
        val eventFile = File(root, "events.jsonl")
        eventFile.writeText(eventFile.readText().replace("inference", "not_valid"))
        val changed = OriaLabGallery.readFrame(root, gallery.frames[2])
        assertFalse(changed.analyzed)
        assertTrue(changed.error!!.contains("type"))
    }

    @Test fun oversizeRowsAndEscapingPathsAreReportedWithoutLosingFollowingRows() = fixture { root ->
        png(root, "last.png"); manifest(root, 3)
        rows(root, "frames.jsonl", "{\"padding\":\"${"x".repeat(OriaLabGallery.MAX_LINE_BYTES)}\"}",
            frame(1, 2, "../outside.png", 2), frame(1, 3, "last.png", 3))
        rows(root, "events.jsonl", inference(1, 3, 3))
        val gallery = OriaLabGallery.load(root)
        assertEquals(listOf(1, 2, 3), gallery.frames.map { it.lineNumber })
        assertEquals(2, gallery.invalidEntries)
        assertTrue(gallery.frames[0].issues.any { it.contains("2 Mio") })
        assertTrue(gallery.frames[1].issues.any { it.contains("hors capture") })
        assertEquals(3, OriaLabGallery.readFrame(root, gallery.frames.last()).detections.single().classId)
    }

    @Test fun cancellationInsideRawTensorSkipPropagatesAndNeverChangesCapture() = fixture { root ->
        png(root, "one.png"); manifest(root, 1)
        rows(root, "frames.jsonl", frame(1, 1, "one.png", 1))
        val event = inference(1, 1, 0).toString().dropLast(1) + ",\"rawOutput\":[" + "0.1,".repeat(100_000) + "0.1]}"
        rows(root, "events.jsonl", event)
        val before = hashes(root)
        var insideSkip = false
        try {
            OriaLabGallery.load(root) {
                if (Thread.currentThread().stackTrace.any { it.className == "android.util.JsonReader" && it.methodName == "skipValue" }) {
                    insideSkip = true
                    throw CancellationException("Galerie fermée pendant les tenseurs")
                }
            }
            fail("Cancellation must escape the loader")
        } catch (_: CancellationException) { }
        assertTrue("Cancellation callback must run from JsonReader.skipValue", insideSkip)
        assertEquals(before, hashes(root))
    }

    @Test fun missingManifestAndCorruptPresentPngHaveExplicitDifferentStates() = fixture { root ->
        File(root, "broken.png").writeText("not a PNG")
        rows(root, "frames.jsonl", frame(1, 1, "broken.png", 1))
        rows(root, "events.jsonl", inference(1, 1, 0))
        val gallery = OriaLabGallery.load(root)
        assertNull(gallery.expectedFrames)
        assertEquals(1, gallery.availableImageFiles)
        assertEquals(0, gallery.missingImageFiles)
        assertEquals(0, gallery.invalidEntries)
        assertTrue(OriaLabGallery.readFrame(root, gallery.frames.single()).analyzed)
        assertTrue(gallery.issues.any { it.contains("manifest.json") })
        val preview = decodeGalleryPreview(gallery.frames.single().image!!)
        assertNull(preview.bitmap)
        assertTrue(preview.error!!.contains("indécodable"))
    }

    @Test fun thirtyMinuteAndTwoHourSyntheticIndexesSkipRawWithoutTruncation() = fixture { root ->
        png(root, "synthetic.png")
        val measurements = JSONArray()
        for (count in listOf(5_406, 21_622)) {
            manifest(root, count)
            val raw = "0.123456,".repeat(1799) + "0.123456"
            File(root, "frames.jsonl").bufferedWriter().use { frames ->
                File(root, "events.jsonl").bufferedWriter().use { events ->
                    for (i in 1..count) {
                        frames.append(frame(66, i.toLong(), "synthetic.png", i * 333L).toString()).append('\n')
                        val event = inference(66, i.toLong(), i % 6).toString().dropLast(1)
                        events.append(event).append(",\"rawOutput\":[").append(raw).append("]}\n")
                    }
                }
            }
            System.gc()
            val heapBefore = usedHeap()
            val began = SystemClock.elapsedRealtime()
            val gallery = OriaLabGallery.load(root)
            val duration = SystemClock.elapsedRealtime() - began
            System.gc()
            val heapAfter = usedHeap()
            assertNull(gallery.error)
            assertEquals(count, gallery.frames.size)
            assertEquals(count, gallery.availableImageFiles)
            assertEquals(0, gallery.invalidEntries)
            assertEquals(0, gallery.invalidEventLines)
            for (i in listOf(0, count / 2, count - 1)) {
                val entry = gallery.frames[i]
                assertEquals(i + 1L, entry.frameId)
                assertEquals((i + 1) * 333L, entry.receivedAtMs)
                assertEquals((i + 1) % 6, OriaLabGallery.readFrame(root, entry).detections.single().classId)
            }
            measurements.put(JSONObject().put("syntheticFrames", count).put("durationMs", duration)
                .put("eventBytes", File(root, "events.jsonl").length()).put("heapBeforeBytes", heapBefore)
                .put("heapAfterGcBytes", heapAfter).put("heapDeltaAfterGcBytes", heapAfter - heapBefore)
                .put("note", "Approximate retained JVM heap, not peak RSS. One shared synthetic PNG. No HTC camera transport."))
        }
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "gallery-synthetic-validation.json")
            .writeText(JSONObject().put("measurements", measurements).toString(2))
    }

    /** Optional: parent selects a real finalized UUID. Output comes solely from the production loader. */
    @Test fun selectedRealCaptureProducesReadOnlyComparisonReceipt() {
        val id = InstrumentationRegistry.getArguments().getString("gallerySessionId")
        assumeTrue("Non exécuté : fournir gallerySessionId pour une validation réelle en lecture seule", id != null)
        require(UUID.fromString(id).toString() == id) { "gallerySessionId must be an exact UUID" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "oria-lab/$id")
        require(directory.isDirectory) { "Selected real capture does not exist" }
        val before = hashes(directory)
        val began = SystemClock.elapsedRealtime()
        val index = OriaLabGallery.load(directory)
        val entries = JSONArray()
        for (frame in index.frames) {
            val analysis = OriaLabGallery.readFrame(directory, frame)
            val detections = JSONArray()
            analysis.detections.forEach { detection ->
                detections.put(JSONObject().put("classId", detection.classId).put("confidence", detection.confidence.toDouble())
                    .put("box", JSONObject().put("left", detection.box.left.toDouble()).put("top", detection.box.top.toDouble())
                        .put("right", detection.box.right.toDouble()).put("bottom", detection.box.bottom.toDouble())))
            }
            entries.put(JSONObject().put("sourceLine", frame.lineNumber).put("videoSessionId", frame.videoSessionId ?: JSONObject.NULL)
                .put("frameId", frame.frameId ?: JSONObject.NULL).put("receivedAtMs", frame.receivedAtMs ?: JSONObject.NULL)
                .put("imagePath", frame.image?.relativeTo(directory.canonicalFile)?.invariantSeparatorsPath ?: JSONObject.NULL)
                .put("analyzed", analysis.analyzed).put("detections", detections)
                .put("reason", frame.decisionReason ?: "Sans décision fraîche").put("issues", JSONArray(frame.issues))
                .put("analysisError", analysis.error ?: JSONObject.NULL))
        }
        val unchanged = before == hashes(directory)
        val receipt = JSONObject().put("schemaVersion", 1).put("sessionId", id).put("source", "real-capture-read-only")
            .put("durationMs", SystemClock.elapsedRealtime() - began).put("expectedFrames", index.expectedFrames ?: JSONObject.NULL)
            .put("indexedEntries", index.frames.size).put("availableImageFiles", index.availableImageFiles)
            .put("invalidEntries", index.invalidEntries).put("invalidEventLines", index.invalidEventLines)
            .put("issueCount", index.issueCount).put("issues", JSONArray(index.issues)).put("error", index.error ?: JSONObject.NULL)
            .put("unchanged", unchanged).put("sha256Before", JSONObject(before)).put("frames", entries)
        File(context.filesDir, "gallery-validation.json").writeText(receipt.toString(2))
        assertTrue("Every original payload must remain byte-identical", unchanged)
        assertNull(index.error)
        assertEquals(0, index.invalidEntries)
        assertEquals(0, index.invalidEventLines)
        assertEquals(index.expectedFrames, index.frames.size.toLong())
    }

    private fun fixture(block: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "gallery-fixture-${UUID.randomUUID()}")
        check(root.mkdirs())
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun png(root: File, name: String) {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try { File(root, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun manifest(root: File, count: Int) = File(root, "manifest.json").writeText(JSONObject()
        .put("schemaVersion", 1).put("counts", JSONObject().put("frames", count)).toString())
    private fun rows(root: File, name: String, vararg rows: Any) = File(root, name).writeText(rows.joinToString("\n", postfix = "\n"))
    private fun frame(session: Long, id: Long, path: String, at: Long) = JSONObject().put("videoSessionId", session)
        .put("frameId", id).put("receivedAtMs", at).put("imagePath", path)
    private fun inference(session: Long, id: Long, klass: Int) = JSONObject().put("type", "inference")
        .put("videoSessionId", session).put("frameId", id).put("detections", JSONArray().put(JSONObject().put("classId", klass)
            .put("confidence", 0.875).put("box", JSONObject().put("left", 0.1).put("top", 0.2).put("right", 0.8).put("bottom", 0.9))))
    private fun decision(session: Long, id: Long, reason: String) = JSONObject().put("type", "decision")
        .put("videoSessionId", session).put("frameId", id).put("reason", reason)
    private fun hashes(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate { file ->
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        file.relativeTo(root).invariantSeparatorsPath to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}
