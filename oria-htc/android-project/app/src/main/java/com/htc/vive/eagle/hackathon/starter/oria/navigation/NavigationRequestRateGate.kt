package com.htc.vive.eagle.hackathon.starter.oria.navigation

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide provider instance shares this gate; cancellation does not consume the next slot. */
internal class NavigationRequestRateGate(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var lastStartedAt: Long? = null
    suspend fun awaitTurn() = mutex.withLock {
        lastStartedAt?.let { previous ->
            val remaining = 1_000L - (clock() - previous)
            if (remaining > 0) wait(remaining)
        }
        lastStartedAt = clock()
    }
}
