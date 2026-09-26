package com.htc.vive.eagle.hackathon.starter.oria.recording

import java.io.IOException

internal class OriaLabStorageFullException(message: String) : IOException(message)

/** Disk guard, never a time or capture-size limit. One filesystem query per MiB at most,
 * except when a write may exhaust the last measured budget. No directory scan is required.
 */
internal class OriaLabDiskBudget(
    private val reservedFreeBytes: Long,
    private val probeAvailableBytes: () -> Long,
    private val probeIntervalBytes: Long = 1024L * 1024,
) {
    var availableBytes: Long = 0
        private set
    private var bytesSinceProbe = probeIntervalBytes

    fun reserveWrite(length: Int) {
        require(length >= 0)
        if (bytesSinceProbe >= probeIntervalBytes || length.toLong() >= probeIntervalBytes - bytesSinceProbe ||
            availableBytes - reservedFreeBytes < length) {
            availableBytes = probeAvailableBytes().coerceAtLeast(0)
            bytesSinceProbe = 0
        }
        if (availableBytes < reservedFreeBytes || availableBytes - reservedFreeBytes < length) {
            throw OriaLabStorageFullException("Réserve de stockage atteinte : conserver ${reservedFreeBytes / (1024 * 1024)} Mio libres sur le téléphone")
        }
        availableBytes -= length
        bytesSinceProbe += length
    }
}
