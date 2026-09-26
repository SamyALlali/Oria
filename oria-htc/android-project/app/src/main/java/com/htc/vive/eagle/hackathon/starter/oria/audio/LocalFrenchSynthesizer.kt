package com.htc.vive.eagle.hackathon.starter.oria.audio

import android.content.Context
import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** File synthesis is deliberately never audible. The PCM callbacks avoid assuming a WAV header. */
internal class LocalFrenchSynthesizer(context: Context) : Closeable {
    private val app = context.applicationContext
    private val ready = CompletableFuture<Unit>()
    private val requests = ConcurrentHashMap<String, Capture>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private var tts: TextToSpeech? = null
    private class Capture {
        val result = CompletableFuture<SpeechPcm>()
        val bytes = ByteArrayOutputStream()
        var rate = 0
        var channels = 0
        var encoding = 0
    }

    init {
        tts = TextToSpeech(app) { status -> main.post { initialize(status) } }
    }

    private fun initialize(status: Int) {
        if (closed) return
        try {
            check(status == TextToSpeech.SUCCESS) { "Moteur de voix Android indisponible" }
            val engine = checkNotNull(tts)
            val voice = engine.voices.orEmpty()
                .filter { it.locale.language == "fr" && !it.isNetworkConnectionRequired &&
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == "FR" }
                    .thenByDescending { it.quality }.thenBy { it.name })
                .firstOrNull() ?: error("Aucune voix française locale installée")
            check(engine.setVoice(voice) == TextToSpeech.SUCCESS) { "Voix française locale refusée" }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onBeginSynthesis(id: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                    val c = id?.let { requests[it] } ?: return
                    synchronized(c) { c.rate = sampleRateInHz; c.encoding = audioFormat; c.channels = channelCount }
                }
                override fun onAudioAvailable(id: String?, audio: ByteArray?) {
                    val c = id?.let { requests[it] } ?: return
                    if (audio == null) return
                    synchronized(c) {
                        if (c.bytes.size() + audio.size > 4_000_000) c.result.completeExceptionally(IllegalStateException("Synthèse trop longue"))
                        else if (!c.result.isDone) c.bytes.write(audio)
                    }
                }
                override fun onDone(id: String?) {
                    val c = id?.let { requests[it] } ?: return
                    synchronized(c) {
                        try { c.result.complete(toPcm(c)) }
                        catch (e: Exception) { c.result.completeExceptionally(e) }
                    }
                }
                override fun onError(id: String?) = fail(id, "Échec de synthèse locale")
                override fun onError(id: String?, errorCode: Int) = fail(id, "Échec de synthèse locale ($errorCode)")
                override fun onStop(id: String?, interrupted: Boolean) = fail(id, "Synthèse annulée")
            })
            ready.complete(Unit)
        } catch (e: Exception) { ready.completeExceptionally(e) }
    }

    fun awaitReady() { ready.get(10, TimeUnit.SECONDS) }

    /** Called only on the backend worker. No default audio route is ever opened by TTS. */
    fun synthesize(text: String): SpeechPcm {
        awaitReady()
        check(!closed) { "Synthèse fermée" }
        val id = "oria-synthesis-${UUID.randomUUID()}"
        val capture = Capture()
        val file = java.io.File.createTempFile("oria-tts-", ".audio", app.cacheDir)
        requests[id] = capture
        var completed = false
        try {
            check(tts?.synthesizeToFile(text, Bundle(), file, id) == TextToSpeech.SUCCESS) { "Synthèse refusée" }
            return capture.result.get(6, TimeUnit.SECONDS).also { completed = true }
        } finally {
            requests.remove(id)
            if (!completed) tts?.stop()
            file.delete()
        }
    }

    fun cancel() {
        requests.values.forEach { it.result.completeExceptionally(IllegalStateException("Synthèse annulée")) }
        tts?.stop()
    }

    private fun fail(id: String?, detail: String) {
        id?.let { requests[it] }?.result?.completeExceptionally(IllegalStateException(detail))
    }

    private fun toPcm(c: Capture): SpeechPcm {
        require(c.rate in 8_000..48_000 && c.channels in 1..2) { "Format de voix non pris en charge" }
        val input = c.bytes.toByteArray()
        val sampleBytes = when (c.encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> error("Encodage vocal non pris en charge")
        }
        require(input.isNotEmpty() && input.size % (sampleBytes * c.channels) == 0) { "PCM vocal incomplet" }
        require(input.size.toLong() * 1000 / (sampleBytes * c.channels * c.rate) <= 8_000) { "Phrase trop longue (8 secondes maximum)" }
        val output = if (sampleBytes == 2) input else {
            val source = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
            val target = ByteBuffer.allocate(input.size / sampleBytes * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (source.hasRemaining()) {
                val sample = if (sampleBytes == 1) ((source.get().toInt() and 255) - 128) shl 8
                    else (source.float.let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f } * 32767f).toInt()
                target.putShort(sample.toShort())
            }
            target.array()
        }
        // All cache entries, including a mono TTS engine's output, share a stereo track format.
        return SpeechPcmTransforms.normalizeStereo(SpeechPcm(c.rate, c.channels, output))
    }

    override fun close() {
        closed = true
        ready.completeExceptionally(IllegalStateException("Synthèse fermée"))
        cancel()
        tts?.shutdown()
        tts = null
    }
}
