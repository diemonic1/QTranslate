package com.github.ahatem.qtranslate.ui.swing.shared.util

import kotlinx.coroutines.delay

/**
 * Whether a modifier key is still physically held down, read straight from the OS rather than
 * guessed at with a timer.
 *
 * ### Why this exists
 * Every feature that reads another application's selection does it by synthesizing Ctrl+C: there
 * is no portable way to ask "what is selected" directly. That synthetic Ctrl+C is only safe once
 * the modifier keys from the hotkey that triggered it have actually been released — pressing a
 * synthetic Ctrl while the user's own Ctrl is still physically down does not add a second Ctrl,
 * it can hand the target application a bare, unmodified `C`, which types over the selection with
 * the letter "c". A paste synthesized while a modifier is still down can misfire the same way.
 *
 * The previous code guessed at this with a fixed wait — 80ms before copying, 150ms before pasting
 * — long enough to usually be safe, and exactly the two things wrong with a guess: it is a fixed
 * tax paid even when the keys released in the first 5ms, and it is not actually a guarantee, so a
 * slower release still raced it. Asking the OS directly removes both problems: usually nothing is
 * held down at all and [awaitModifiersReleased] returns immediately, and on the rare occasion
 * something is still down, it waits for exactly as long as that takes and no longer.
 */
object PhysicalKeyState {
    // Virtual-key codes, from winuser.h. Left and right variants of Ctrl/Alt/Shift are not
    // tracked separately — VK_CONTROL etc. report the logical state regardless of which physical
    // key it came from, which is all a synthetic Ctrl+C cares about.
    private const val VK_SHIFT = 0x10
    private const val VK_CONTROL = 0x11
    private const val VK_MENU = 0x12 // Alt
    private const val VK_LWIN = 0x5B
    private const val VK_RWIN = 0x5C

    private val TRACKED = intArrayOf(VK_CONTROL, VK_MENU, VK_SHIFT, VK_LWIN, VK_RWIN)

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** Whether any of Ctrl, Alt, Shift or Win is down right now, according to the OS. */
    fun anyModifierDown(): Boolean {
        if (!isWindows) return false
        return runCatching {
            val user32 = com.sun.jna.platform.win32.User32.INSTANCE
            // The high bit of GetAsyncKeyState's return marks "currently down". A Short carries
            // that bit through sign-extension to Int, so the mask below reads it correctly
            // whether or not the value came out negative.
            TRACKED.any { vk -> (user32.GetAsyncKeyState(vk).toInt() and 0x8000) != 0 }
        }.getOrDefault(false)
    }

    /**
     * Returns as soon as no tracked modifier is held, or after [timeoutMs] — whichever comes
     * first.
     *
     * The timeout is a safety net, not the expected path: it exists for the platforms and the
     * rare stuck-key cases where the read itself is unavailable or never turns up "released", so
     * a hotkey action still completes instead of hanging on a key nobody is going to lift.
     */
    suspend fun awaitModifiersReleased(timeoutMs: Long, pollIntervalMs: Long = 4) {
        if (!isWindows) {
            // No portable equivalent of GetAsyncKeyState. Falling back to the fixed wait this
            // replaced on Windows, capped at the caller's timeout, rather than proceeding blind.
            delay(minOf(timeoutMs, FALLBACK_DELAY_MS))
            return
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (anyModifierDown() && System.nanoTime() < deadline) {
            delay(pollIntervalMs)
        }
    }

    private const val FALLBACK_DELAY_MS = 80L
}
