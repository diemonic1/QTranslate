package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.sync.Mutex
import java.awt.Robot
import java.awt.event.KeyEvent

/**
 * Sends the copy and paste shortcuts to whatever application has focus.
 *
 * Reading a selection out of another application, and putting text back over it, can only be done
 * by pretending to be the keyboard — no portable interface exists for either. This is the one
 * place that pretence lives, so the rules about how to do it safely are stated once.
 *
 * On Windows the events go through [WindowsSendInput], whose single-call injection cannot be
 * interleaved with the user's own typing. Everywhere else `java.awt.Robot` remains the only
 * option, with the race it brings; see [WindowsSendInput] for what that race does.
 */
object SyntheticInput {

    /**
     * Serializes everything that touches the clipboard or the global input stream.
     *
     * Capture and replacement are two halves of one exchange with a minute or more between them,
     * and both temporarily own state — the clipboard — that is shared with the whole desktop. Two
     * of them running at once would interleave their sentinel writes and their restores, and each
     * would read what the other left behind.
     *
     * Callers choose how to lose the race: capture uses `tryLock` and gives up, because a second
     * capture would read text the first has already replaced, while replacement waits, because by
     * then the translation has cost a network round trip that should not be thrown away.
     *
     * Deliberately *not* held across the translation itself, which would block every hotkey for
     * as long as the service takes to answer.
     */
    val exchange = Mutex()

    private val isMac: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    /**
     * The key that means "command" on this platform.
     *
     * The one place the choice is made. It used to be made twice, and the copy path chose Cmd on
     * macOS while the paste path always chose Ctrl — and `Ctrl+V` in a Cocoa text view is the
     * emacs binding for page-down, so replacing a selection with its translation scrolled the
     * document instead.
     */
    internal fun commandKeyVk(): Int = if (isMac) KeyEvent.VK_META else KeyEvent.VK_CONTROL

    /** Sends the platform's copy shortcut. Returns whether it was delivered. */
    fun sendCopyShortcut(logger: Logger): Boolean = sendShortcut(KeyEvent.VK_C, logger)

    /** Sends the platform's paste shortcut. Returns whether it was delivered. */
    fun sendPasteShortcut(logger: Logger): Boolean = sendShortcut(KeyEvent.VK_V, logger)

    // -------------------------------------------------------------------------

    private fun sendShortcut(keyVk: Int, logger: Logger): Boolean {
        val modifier = commandKeyVk()

        if (WindowsSendInput.isAvailable) {
            // Any modifier still physically held from the hotkey that triggered this is released
            // first, in the same batch — otherwise a hotkey held a moment too long turns Ctrl+C
            // into Ctrl+Shift+C, which is the developer-tools inspector rather than a copy.
            val plan = SyntheticKeyPlan.shortcut(
                modifierVk = modifier,
                keyVk = keyVk,
                heldModifierVks = WindowsSendInput.heldModifiers(),
                scanCodeOf = WindowsSendInput::scanCodeOf
            )
            return WindowsSendInput.send(plan, logger) == plan.size
        }

        return sendWithRobot(modifier, keyVk, logger)
    }

    /**
     * The fallback for platforms with no `SendInput`.
     *
     * Each press and release is a separate injection here, so the user's own key events can land
     * between them and produce a bare letter instead of a shortcut. Nothing can be done about that
     * from Java; it is the reason the Windows path exists.
     */
    private fun sendWithRobot(modifierVk: Int, keyVk: Int, logger: Logger): Boolean =
        runCatching {
            val robot = Robot()
            robot.autoDelay = 10
            robot.keyPress(modifierVk)
            robot.keyPress(keyVk)
            robot.keyRelease(keyVk)
            robot.keyRelease(modifierVk)
            robot.waitForIdle()
            true
        }.getOrElse {
            logger.warn("Key synthesis failed: ${it.message}")
            false
        }
}
