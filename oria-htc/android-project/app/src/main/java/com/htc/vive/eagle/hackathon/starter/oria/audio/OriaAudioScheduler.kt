package com.htc.vive.eagle.hackathon.starter.oria.audio

enum class OriaAudioKind { DANGER, NAVIGATION, COMMAND_RESPONSE }

data class OriaInstruction(
    val id: String,
    val generation: Long,
    val kind: OriaAudioKind,
    val text: String,
    val observedAtMs: Long,
    val expiresAtMs: Long,
    val pan: SpeechPan = SpeechPan.CENTER,
) {
    init {
        require(id.isNotBlank() && text.isNotBlank() && text.length <= 240)
        require(kind != OriaAudioKind.DANGER && observedAtMs >= 0 && expiresAtMs >= observedAtMs)
    }
}

sealed interface OriaAudioPlan {
    data object Wait : OriaAudioPlan
    data object Danger : OriaAudioPlan
    data class Instruction(val value: OriaInstruction) : OriaAudioPlan
    data class CancelInstruction(val id: String) : OriaAudioPlan
}

/** Main-thread owner. Adapted from Rayan's priority lane, preserving the live danger policies.
 * A cancellation is a request, never a playback completion: no new dispatch until the
 * real backend terminal and its idle gate. At most one queued instruction of each kind.
 */
class OriaAudioScheduler {
    private var generation = 0L
    private data class Active(val id: String, val kind: OriaAudioKind, var cancelling: Boolean = false)
    private var active: Active? = null
    private val queued = linkedMapOf<OriaAudioKind, OriaInstruction>()

    fun reset(newGeneration: Long) {
        generation = newGeneration
        active = null
        queued.clear()
    }

    fun offer(request: OriaInstruction, nowMs: Long): Boolean {
        if (request.generation != generation || nowMs !in request.observedAtMs..request.expiresAtMs ||
            request.id == active?.id) return false
        queued[request.kind] = request
        return true
    }

    fun plan(nowMs: Long, dangerReady: Boolean, backendIdle: Boolean, automaticAllowed: Boolean = true): OriaAudioPlan {
        queued.entries.removeAll { nowMs !in it.value.observedAtMs..it.value.expiresAtMs }
        active?.let {
            if (dangerReady && it.kind != OriaAudioKind.DANGER && !it.cancelling) {
                it.cancelling = true
                return OriaAudioPlan.CancelInstruction(it.id)
            }
            return OriaAudioPlan.Wait
        }
        // stop()/reset() may precede an asynchronous terminal. Keep the transport barrier.
        if (!backendIdle) return OriaAudioPlan.Wait
        if (dangerReady) return OriaAudioPlan.Danger
        val next = queued[OriaAudioKind.NAVIGATION]?.takeIf { automaticAllowed } ?: queued[OriaAudioKind.COMMAND_RESPONSE]
            ?: return OriaAudioPlan.Wait
        queued.remove(next.kind)
        active = Active(next.id, next.kind)
        return OriaAudioPlan.Instruction(next)
    }

    fun dangerStarted(id: String) {
        check(active == null) { "Audio lane already reserved" }
        active = Active(id, OriaAudioKind.DANGER)
    }

    fun finished(id: String) {
        if (active?.id == id) active = null
    }

    /** Returns a current instruction to cancel. Its terminal must still call finished(). */
    fun invalidate(kind: OriaAudioKind): String? {
        queued.remove(kind)
        return active?.takeIf { it.kind == kind && !it.cancelling }?.also { it.cancelling = true }?.id
    }

    fun queuedCount(): Int = queued.size
}
