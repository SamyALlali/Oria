package com.htc.vive.eagle.hackathon.starter.oria.video

import android.graphics.Bitmap

/** Ownership transfers only when the callback returns true; the consumer must recycle bitmap. */
data class OriaVideoFrame(
    val bitmap: Bitmap,
    val sessionId: Long,
    val frameId: Long,
    val receivedAtMs: Long,
    val ptsUs: Long,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val rotationAppliedDegrees: Int,
    val mirrorApplied: Boolean,
    val deliveredAtMs: Long = 0L,
    val conversionMs: Double = 0.0,
) {
    val generation: Long get() = sessionId
}

data class OriaVideoStatus(
    val sessionId: Long = 0,
    val phase: String = "stopped",
    val receivedPackets: Long = 0,
    val decodedFrames: Long = 0,
    val deliveredFrames: Long = 0,
    val lastReceivedAtMs: Long? = null,
    val lastDecodedAtMs: Long? = null,
    val codecName: String? = null,
    val detail: String? = null,
    val staleBeforeConversion: Long = 0,
    val staleAfterConversion: Long = 0,
    val lastConversionMs: Double = 0.0,
)

/** SDK event is anonymous. Epoch is a transport observation, not a request correlation ID. */
data class OriaSynthesisEvent(
    val event: String,
    val transportEpoch: Long,
    val receivedAtMs: Long,
)

/** A bounded exact PTS lookup. Repeated slices use the earliest reception time. */
internal class FrameReceptionIndex(private val capacity: Int = 120) {
    private val times = LinkedHashMap<Long, Long>()
    fun record(ptsUs: Long, receivedAtMs: Long): Boolean {
        if (!times.containsKey(ptsUs) && times.size >= capacity) return false
        times[ptsUs] = minOf(times[ptsUs] ?: receivedAtMs, receivedAtMs)
        return true
    }
    fun take(ptsUs: Long): Long? = times.remove(ptsUs)
    fun clear() = times.clear()
}
