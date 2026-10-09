package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.github.ahatem.qtranslate.api.core.Logger

/**
 * Asks the operating system to move the focused window to its next keyboard layout.
 *
 * Repairing the text without this leaves the user one keystroke from making the same mistake
 * again: the layout that produced `ghbdtn` is still the active one. The two halves belong to the
 * same gesture.
 *
 * ### Windows only, deliberately quiet elsewhere
 * There is no portable way to do this. Windows has a documented message for it; the X11 and macOS
 * equivalents differ enough that guessing would be worse than declining. On any other platform
 * this does nothing and says so once in the log — the text conversion, which is the larger half of
 * the feature, still works everywhere.
 */
object SystemKeyboardLayout {

    private const val WM_INPUTLANGCHANGEREQUEST = 0x0050

    /** Tells Windows to pick the next layout in the list rather than a specific one. */
    private const val INPUTLANGCHANGE_FORWARD = 0x0002

    private var unavailableReported = false

    /**
     * Moves the focused window on to the next installed keyboard layout.
     *
     * The same request Win+Space makes, and deliberately so: with the two layouts this is
     * written for, "the next one" is always the other one, and asking the way the operating
     * system's own shortcut asks is the behaviour least likely to surprise anyone.
     *
     * The request is posted rather than sent, so a foreground application that is busy cannot
     * block the caller. It is addressed to the foreground window because the layout on Windows is
     * per-thread: sending it to our own window would switch the layout of a window the user is not
     * typing in.
     */
    fun switchToNext(logger: Logger) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            reportUnavailable(logger, "not supported on ${System.getProperty("os.name")}")
            return
        }

        runCatching {
            val user32 = User32Native.INSTANCE
            val foreground = user32.GetForegroundWindow()
                ?: error("no foreground window")

            user32.PostMessageW(
                foreground,
                WM_INPUTLANGCHANGEREQUEST,
                com.sun.jna.Pointer(INPUTLANGCHANGE_FORWARD.toLong()),
                null
            )
        }.onFailure { failure ->
            reportUnavailable(logger, failure.message ?: failure::class.simpleName.orEmpty())
        }
    }

    /**
     * Logged once rather than on every keystroke.
     *
     * This runs from a hotkey the user may hold down, and a platform that cannot do this cannot
     * start doing it later — so repeating the message would only bury whatever else is in the log.
     */
    private fun reportUnavailable(logger: Logger, reason: String) {
        if (unavailableReported) return
        unavailableReported = true
        logger.info("Cannot switch the system keyboard layout ($reason); only the text was converted")
    }
}
