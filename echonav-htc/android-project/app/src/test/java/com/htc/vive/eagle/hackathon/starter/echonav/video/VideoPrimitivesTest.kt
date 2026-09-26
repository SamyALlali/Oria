package com.htc.vive.eagle.hackathon.starter.echonav.video

import org.junit.Assert.*
import org.junit.Test

class VideoPrimitivesTest {
    @Test fun ptsAreMatchedExactlyEvenWhenOutputsReorder() {
        val index = FrameReceptionIndex(3)
        assertTrue(index.record(20, 100))
        assertTrue(index.record(10, 110))
        assertTrue(index.record(20, 120))
        assertEquals(110L, index.take(10))
        assertEquals(100L, index.take(20))
        assertNull(index.take(30))
        assertNull(index.take(20))
    }

    @Test fun receptionMapFailsClosedInsteadOfInventingTimestamp() {
        val index = FrameReceptionIndex(1)
        assertTrue(index.record(1, 100))
        assertFalse(index.record(2, 200))
        assertNull(index.take(2))
        index.clear()
        assertTrue(index.record(2, 200))
    }

    @Test fun annexBSplitSupportsBothPrefixesAndDoesNotIncludeTheirBytes() {
        val nals = H264Parameters.nals(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 1, 0x68, 0x01))
        assertEquals(2, nals.size)
        assertArrayEquals(byteArrayOf(0x67, 0x42), nals[0])
        assertArrayEquals(byteArrayOf(0x68, 0x01), nals[1])
        assertTrue(H264Parameters.nals(byteArrayOf(0, 0, 0, 1)).isEmpty())
        assertTrue(H264Parameters.nals(byteArrayOf(5, 6, 7)).isEmpty())
    }

    @Test fun knownBaselineSpsProducesCroppedFrameDimensions() {
        // Baseline, one 32×16 frame, right crop of two pixels in 4:2:0.
        val bits = "01000010" + "00000000" + "00011110" + ue(0) + ue(0) + ue(0) + ue(0) +
            ue(1) + "0" + ue(1) + ue(0) + "1" + "1" + "1" + ue(0) + ue(1) + ue(0) + ue(0) + "0" + "1"
        val padded = bits.padEnd((bits.length + 7) / 8 * 8, '0')
        val bytes = byteArrayOf(0x67) + padded.chunked(8).map { it.toInt(2).toByte() }.toByteArray()
        val size = H264Parameters.parseSps(bytes)!!
        assertEquals(30, size.width)
        assertEquals(16, size.height)
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedSpsDoesNotReturnMadeUpDimensions() {
        H264Parameters.parseSps(byteArrayOf(0x67, 0x42))
    }

    @Test fun limitedAndFullRangeGrayAreCorrect() {
        assertEquals(0xff000000.toInt(), YuvPixels.argb(16, 128, 128, false, false))
        assertEquals(0xffffffff.toInt(), YuvPixels.argb(235, 128, 128, false, false))
        assertEquals(0xff808080.toInt(), YuvPixels.argb(128, 128, 128, true, true))
        val red = YuvPixels.argb(81, 90, 240, false, false)
        assertTrue((red shr 16 and 255) >= 250)
        assertTrue((red shr 8 and 255) <= 4)
        assertTrue((red and 255) <= 4)
    }

    @Test fun cachedColorConversionPreservesReferenceForAllProfiles() {
        for (full in listOf(false, true)) for (bt709 in listOf(false, true)) {
            for (y in 0..255 step 17) for (u in 0..255 step 17) for (v in 0..255 step 17) {
                assertEquals("YUV=$y,$u,$v full=$full bt709=$bt709",
                    YuvPixels.argb(y, u, v, full, bt709), YuvPixels.cachedArgb(y, u, v, full, bt709))
            }
        }
    }

    private fun ue(value: Int): String {
        val bits = (value + 1).toString(2)
        return "0".repeat(bits.length - 1) + bits
    }
}
