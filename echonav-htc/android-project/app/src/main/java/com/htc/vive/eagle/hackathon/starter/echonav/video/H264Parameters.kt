package com.htc.vive.eagle.hackathon.starter.echonav.video

/** SPS parser adapted from the HTC starter, with strict bounds on truncated input. */
internal object H264Parameters {
    fun nals(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < data.size) {
            val length = when {
                i + 3 < data.size && data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 0.toByte() && data[i+3] == 1.toByte() -> 4
                data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 1.toByte() -> 3
                else -> 0
            }
            if (length > 0) { starts.add(i to length); i += length } else i++
        }
        return starts.mapIndexedNotNull { index, (start, length) ->
            val end = starts.getOrNull(index + 1)?.first ?: data.size
            if (start + length >= end) null else data.copyOfRange(start + length, end)
        }
    }
    data class SpsInfo(
        val width: Int,
        val height: Int,
        val profileIdc: Int,
        val levelIdc: Int
    )

    fun parseSps(spsNal: ByteArray): SpsInfo? {
        // spsNal is NAL payload starting at NAL header byte (type=7)
        if (spsNal.isEmpty()) return null
        val nalType = (spsNal[0].toInt() and 0x1F)
        if (nalType != 7) return null

        // Remove NAL header (1 byte) and convert EBSP->RBSP (remove emulation prevention 0x03)
        val rbsp = ebspToRbsp(spsNal.copyOfRange(1, spsNal.size))

        val br = BitReader(rbsp)

        val profileIdc = br.readBits(8)
        br.readBits(8) // constraint flags + reserved
        val levelIdc = br.readBits(8)
        br.readUE()

        var chromaFormatIdc = 1
        if (profileIdc in setOf(100,110,122,244,44,83,86,118,128,138,139,134,135)) {
            chromaFormatIdc = br.readUE()
            if (chromaFormatIdc == 3) br.readBits(1)
            br.readUE()
            br.readUE()
            br.readBits(1)
            val seqScalingMatrixPresent = br.readBits(1) == 1
            if (seqScalingMatrixPresent) {
                val count = if (chromaFormatIdc != 3) 8 else 12
                repeat(count) {
                    val present = br.readBits(1) == 1
                    if (present) skipScalingList(br, if (it < 6) 16 else 64)
                }
            }
        }

        br.readUE()
        val picOrderCntType = br.readUE()
        if (picOrderCntType == 0) {
            br.readUE()
        } else if (picOrderCntType == 1) {
            br.readBits(1)
            br.readSE()
            br.readSE()
            val numRefFramesInCycle = br.readUE()
            require(numRefFramesInCycle in 0..255)
            repeat(numRefFramesInCycle) { br.readSE() }
        }

        br.readUE()
        br.readBits(1)

        val picWidthInMbsMinus1 = br.readUE()
        val picHeightInMapUnitsMinus1 = br.readUE()
        val frameMbsOnlyFlag = br.readBits(1) == 1
        if (!frameMbsOnlyFlag) br.readBits(1)

        br.readBits(1)

        var frameCropLeft = 0
        var frameCropRight = 0
        var frameCropTop = 0
        var frameCropBottom = 0
        val frameCroppingFlag = br.readBits(1) == 1
        if (frameCroppingFlag) {
            frameCropLeft = br.readUE()
            frameCropRight = br.readUE()
            frameCropTop = br.readUE()
            frameCropBottom = br.readUE()
        }

        val widthMbs = picWidthInMbsMinus1 + 1
        val heightMapUnits = picHeightInMapUnitsMinus1 + 1
        val frameHeightMbs = (2 - if (frameMbsOnlyFlag) 1 else 0) * heightMapUnits

        var width = widthMbs * 16
        var height = frameHeightMbs * 16

        val cropUnitX: Int
        val cropUnitY: Int
        when (chromaFormatIdc) {
            0 -> { // monochrome
                cropUnitX = 1
                cropUnitY = 2 - if (frameMbsOnlyFlag) 1 else 0
            }
            1 -> { // 4:2:0
                cropUnitX = 2
                cropUnitY = 2 * (2 - if (frameMbsOnlyFlag) 1 else 0)
            }
            2 -> { // 4:2:2
                cropUnitX = 2
                cropUnitY = 1 * (2 - if (frameMbsOnlyFlag) 1 else 0)
            }
            else -> { // 4:4:4
                cropUnitX = 1
                cropUnitY = 1 * (2 - if (frameMbsOnlyFlag) 1 else 0)
            }
        }

        if (frameCroppingFlag) {
            width -= (frameCropLeft + frameCropRight) * cropUnitX
            height -= (frameCropTop + frameCropBottom) * cropUnitY
        }

        require(width in 1..8192 && height in 1..8192) { "Invalid SPS dimensions" }
        return SpsInfo(width, height, profileIdc, levelIdc)
    }

    private fun ebspToRbsp(ebsp: ByteArray): ByteArray {
        val out = ByteArray(ebsp.size)
        var outLen = 0
        var zeros = 0
        for (b in ebsp) {
            val v = b.toInt() and 0xFF
            if (zeros == 2 && v == 0x03) {
                // skip emulation prevention byte
                zeros = 0
                continue
            }
            out[outLen++] = b
            zeros = if (v == 0x00) zeros + 1 else 0
        }
        return out.copyOf(outLen)
    }

    private fun skipScalingList(br: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (i in 0 until size) {
            if (nextScale != 0) {
                val delta = br.readSE()
                nextScale = (lastScale + delta + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var bytePos = 0
        private var bitPos = 0

        fun readBits(n: Int): Int {
            var v = 0
            repeat(n) {
                require(bytePos < data.size) { "Truncated H.264 SPS" }
                val bit = (data[bytePos].toInt() shr (7 - bitPos)) and 1
                v = (v shl 1) or bit
                bitPos++
                if (bitPos == 8) { bitPos = 0; bytePos++ }
            }
            return v
        }

        fun readUE(): Int {
            var zeros = 0
            while (readBits(1) == 0) {
                zeros++
                require(zeros < 31) { "Invalid SPS Exp-Golomb" }
            }
            var value = 1
            repeat(zeros) { value = (value shl 1) or readBits(1) }
            return value - 1
        }

        fun readSE(): Int {
            val ue = readUE()
            val sign = if (ue and 1 == 0) -1 else 1
            return sign * ((ue + 1) / 2)
        }
    }
}
