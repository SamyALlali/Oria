package com.htc.vive.eagle.hackathon.starter.oria.audio

/** Physical stereo channels; the caller supplies the detected zone, never parsed speech text. */
enum class SpeechPan { LEFT, CENTER, RIGHT }

/** Signed PCM16 little-endian. Each frame contains [channels] interleaved samples. */
internal data class SpeechPcm(val sampleRate: Int, val channels: Int, val bytes: ByteArray) {
    val frameBytes: Int get() = channels * 2
}

/** Pure PCM transforms, shared by cached/manual speech and testable without Android. */
internal object SpeechPcmTransforms {
    /** Mono is duplicated. Stereo is downmixed without gain, then duplicated to create a centre. */
    fun normalizeStereo(source: SpeechPcm): SpeechPcm {
        validate(source)
        val frames = source.bytes.size / source.frameBytes
        val stereo = ByteArray(frames * 4)
        repeat(frames) { frame ->
            val input = frame * source.frameBytes
            val left = readSample(source.bytes, input)
            // Int addition cannot overflow for two signed 16-bit samples; /2 rounds toward zero.
            val center = if (source.channels == 1) left else (left + readSample(source.bytes, input + 2)) / 2
            writeSample(stereo, frame * 4, center)
            writeSample(stereo, frame * 4 + 2, center)
        }
        return SpeechPcm(source.sampleRate, 2, stereo)
    }

    /**
     * Amplitude gains: LEFT=(0.7, 0.3), RIGHT=(0.3, 0.7), CENTER=(1, 1).
     * Centre keeps its historical volume; these are not equal-power or loudness-normalized gains.
     * Always returns an owned copy, leaving the unpanned, centred cache unchanged.
     */
    fun panned(centered: SpeechPcm, pan: SpeechPan): SpeechPcm {
        validate(centered)
        require(centered.channels == 2) { "Le panoramique nécessite un PCM stéréo préparé" }
        val output = centered.bytes.copyOf()
        for (frame in output.indices step 4) {
            require(centered.bytes[frame] == centered.bytes[frame + 2] &&
                centered.bytes[frame + 1] == centered.bytes[frame + 3]) { "Le PCM préparé doit être centré" }
            when (pan) {
                SpeechPan.LEFT, SpeechPan.RIGHT -> {
                    val sample = readSample(centered.bytes, frame)
                    val dominant = gainTenths(sample, 7)
                    val residual = gainTenths(sample, 3)
                    writeSample(output, frame, if (pan == SpeechPan.LEFT) dominant else residual)
                    writeSample(output, frame + 2, if (pan == SpeechPan.LEFT) residual else dominant)
                }
                SpeechPan.CENTER -> Unit
            }
        }
        return SpeechPcm(centered.sampleRate, 2, output)
    }

    /** Exact decimal gain, nearest integer with half steps away from zero, symmetric for signs. */
    private fun gainTenths(sample: Int, tenths: Int): Int {
        // Signed PCM16 * 7 fits Int; gains <= 1 cannot amplify or overflow a PCM16 sample.
        val numerator = sample * tenths
        return (numerator + if (numerator < 0) -5 else 5) / 10
    }

    private fun validate(pcm: SpeechPcm) {
        require(pcm.sampleRate in 8_000..48_000 && pcm.channels in 1..2) { "Format PCM vocal non pris en charge" }
        require(pcm.bytes.isNotEmpty() && pcm.bytes.size % pcm.frameBytes == 0) { "Trame PCM incomplète" }
        require(pcm.bytes.size.toLong() * 1_000 <= pcm.sampleRate.toLong() * pcm.frameBytes * 8_000) {
            "Phrase trop longue (8 secondes maximum)"
        }
    }

    private fun readSample(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)).toShort().toInt()

    private fun writeSample(bytes: ByteArray, offset: Int, sample: Int) {
        bytes[offset] = sample.toByte()
        bytes[offset + 1] = (sample shr 8).toByte()
    }
}
