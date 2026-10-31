package com.seema.assistant

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Signals that SEEMA is doing machine work the user can watch.
 *
 * The home screen keeps its background code stream strictly hidden until this is active, because
 * a permanently streaming canvas is decoration; a stream that only appears while code is being
 * generated, a page is being scraped, or a file is being written is feedback.
 *
 * Reference counted: several tools can be open at once, and the stream must stay on until the
 * last of them finishes. A stall guard clears the flag if a task somehow never reports back, so
 * a crashed tool cannot leave the stream running forever.
 */
object SeemaWorkStream {

    private val depth = AtomicInteger(0)
    private val startedAt = AtomicInteger(0)

    private val _active = MutableStateFlow(false)

    /** True while at least one autonomous task is running. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _label = MutableStateFlow("")

    /** Short description of what is running, shown in the HUD while the stream is visible. */
    val label: StateFlow<String> = _label.asStateFlow()

    fun begin(label: String) {
        _label.value = label
        if (depth.incrementAndGet() == 1) {
            startedAt.set((System.currentTimeMillis() / 1000L).toInt())
            _active.value = true
        }
    }

    fun end() {
        if (depth.decrementAndGet() <= 0) {
            depth.set(0)
            startedAt.set(0)
            _active.value = false
            _label.value = ""
        }
    }

    /** True when a task has been running longer than [STALL_SECONDS]; used by the HUD watchdog. */
    fun stalled(): Boolean {
        val started = startedAt.get()
        return started != 0 &&
            (System.currentTimeMillis() / 1000L).toInt() - started > STALL_SECONDS
    }

    /** Force-clears the stream; called by the watchdog and when the service shuts down. */
    fun reset() {
        depth.set(0)
        startedAt.set(0)
        _active.value = false
        _label.value = ""
    }

    private const val STALL_SECONDS = 180
}
