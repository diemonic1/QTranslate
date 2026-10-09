package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.keyboard.KeyboardLayoutTransition
import com.github.ahatem.qtranslate.core.keyboard.KeyboardLayouts
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the installed keyboard layouts from Windows and works out how their keys correspond.
 *
 * Replaces a table written by hand for one language pair. A hand-written table has to be authored
 * per pair, verified against a national standard, and is still wrong for anyone using a variant —
 * a phonetic Russian layout rather than ЙЦУКЕН, say. Windows already holds the exact answer for
 * every layout the user installed, so it is asked instead of guessed at.
 *
 * On anything but Windows this reports nothing and the caller falls back, which is the same shape
 * as the neighbouring [SystemKeyboardLayout].
 */
class WindowsKeyboardLayouts(private val logger: Logger) : KeyboardLayouts {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /**
     * Character maps by the pair of layouts they were built from.
     *
     * Building one costs a few hundred `ToUnicodeEx` calls. Layouts never change which character
     * sits on which key, so an entry stays valid for as long as both layouts remain installed —
     * see [installedSnapshot] for the one thing that does invalidate it.
     */
    private val cache = ConcurrentHashMap<Pair<Long, Long>, KeyboardLayoutTransition>()

    /** The layout list the cache was built against, so adding or removing one can clear it. */
    @Volatile
    private var installedSnapshot: List<Long> = emptyList()

    private var unavailableReported = false

    override fun readTransitions(): List<KeyboardLayoutTransition> {
        if (!isWindows) {
            reportUnavailable("not supported on ${System.getProperty("os.name")}")
            return emptyList()
        }

        return runCatching {
            val user32 = User32.INSTANCE
            val installed = installedLayouts(user32)
            if (installed.size < 2) return emptyList()

            // A layout being added or removed can also see Windows reuse a handle for a different
            // layout, which would leave a stale map keyed under the right pair. Clearing on any
            // change to the list is cheap and removes the whole question.
            if (installed.map { handleValue(it) } != installedSnapshot) {
                cache.clear()
                installedSnapshot = installed.map { handleValue(it) }
            }

            val current = foregroundLayout(user32, installed)
            val currentValue = handleValue(current)

            // Ordered so the layout after the current one comes first: the caller breaks a scoring
            // tie by preferring the head of this list, which is the sensible guess.
            val others = installed.filter { handleValue(it) != currentValue }
            val startAt = installed.indexOfFirst { handleValue(it) == currentValue }
            val rotated = if (startAt < 0) others else {
                (installed.drop(startAt + 1) + installed.take(startAt))
                    .filter { handleValue(it) != currentValue }
            }

            rotated.map { target -> transitionBetween(user32, current, target) }
        }.getOrElse { failure ->
            reportUnavailable(failure.message ?: failure::class.simpleName.orEmpty())
            emptyList()
        }
    }

    // -------------------------------------------------------------------------

    private fun installedLayouts(user32: User32): List<WinDef.HKL> {
        val count = user32.GetKeyboardLayoutList(0, null)
        if (count <= 0) return emptyList()
        val buffer = arrayOfNulls<WinDef.HKL>(count)
        @Suppress("UNCHECKED_CAST")
        val written = user32.GetKeyboardLayoutList(count, buffer as Array<WinDef.HKL>)
        return buffer.filterNotNull().take(maxOf(written, 0))
    }

    /**
     * The layout of the window the user is actually typing in.
     *
     * Keyboard layout on Windows is per-thread, so asking about our own thread would report the
     * layout of the translator window rather than of whatever the selection came from.
     */
    private fun foregroundLayout(user32: User32, installed: List<WinDef.HKL>): WinDef.HKL {
        val foreground = user32.GetForegroundWindow() ?: return installed.first()
        val threadId = user32.GetWindowThreadProcessId(foreground, IntByReference())
        val layout = user32.GetKeyboardLayout(threadId) ?: return installed.first()
        // A handle outside the installed list is not something we can pair anything with.
        return if (installed.any { handleValue(it) == handleValue(layout) }) layout else installed.first()
    }

    private fun transitionBetween(
        user32: User32,
        from: WinDef.HKL,
        to: WinDef.HKL
    ): KeyboardLayoutTransition {
        val key = handleValue(from) to handleValue(to)
        cache[key]?.let { return it }

        val forward = HashMap<Char, Char>()
        val backward = HashMap<Char, Char>()

        // The thread's dead-key state is shared and we did not necessarily start with it clean, so
        // it is flushed before the sweep as well as during it.
        flushDeadKey(user32, from)
        flushDeadKey(user32, to)

        for (scanCode in PRINTABLE_SCAN_CODES) {
            for (shifted in booleanArrayOf(false, true)) {
                val source = characterAt(user32, scanCode, shifted, from) ?: continue
                val target = characterAt(user32, scanCode, shifted, to) ?: continue
                if (source == target) continue
                forward.putIfAbsent(source, target)
                backward.putIfAbsent(target, source)
            }
        }

        val transition = KeyboardLayoutTransition(forward, backward)
        cache[key] = transition
        return transition
    }

    /**
     * The character [scanCode] produces on [layout], or null when there is no single one.
     *
     * Scan codes rather than virtual-key codes, because a virtual-key code is itself
     * layout-dependent: the physical key that reports `VK_Q` on QWERTY reports `VK_A` on AZERTY.
     * The physical key is the only thing the two layouts have in common, and the scan code is what
     * names it.
     */
    private fun characterAt(
        user32: User32,
        scanCode: Int,
        shifted: Boolean,
        layout: WinDef.HKL
    ): Char? {
        val virtualKey = user32.MapVirtualKeyEx(scanCode, MAPVK_VSC_TO_VK_EX, layout)
        if (virtualKey == 0) return null

        val buffer = CharArray(8)
        val produced = user32.ToUnicodeEx(
            virtualKey, scanCode, keyState(shifted), buffer, buffer.size, TOUNICODE_NO_KEY_STATE, layout
        )

        return when {
            // Exactly one printable character is the only case a Char-to-Char map can hold.
            produced == 1 && buffer[0].code >= 0x20 && buffer[0].code != 0x7F -> buffer[0]
            // A dead key produces no standalone character, so no character in the pasted text
            // could have come from it. It does leave state behind, which has to go.
            produced == -1 -> { flushDeadKey(user32, layout); null }
            // Ligatures need Char-to-String; the key produces nothing at all when 0.
            else -> null
        }
    }

    /**
     * A fresh keyboard state with only Shift optionally held.
     *
     * Built from scratch rather than read with `GetKeyboardState`, so the user's real modifiers
     * cannot leak in. Caps Lock and Ctrl are left clear deliberately: Caps Lock would invert every
     * letter's case and quietly produce a half-wrong table, and Ctrl would yield control
     * characters instead of letters.
     */
    private fun keyState(shifted: Boolean): ByteArray =
        ByteArray(256).also { if (shifted) it[VK_SHIFT] = KEY_DOWN }

    /**
     * Clears a dead key left pending on [layout] for this thread.
     *
     * `ToUnicodeEx` mutates the calling thread's kernel keyboard state. Left pending, a diacritic
     * composes with the next query and the table comes out wrong. [TOUNICODE_NO_KEY_STATE] asks
     * Windows not to touch that state at all, but it is ignored before Windows 10 1607, so the
     * flush stays as the belt to its braces. Twice, because the first call consumes the pending
     * dead key into a composed pair and the second returns to a clean state.
     */
    private fun flushDeadKey(user32: User32, layout: WinDef.HKL) {
        val buffer = CharArray(8)
        repeat(2) {
            user32.ToUnicodeEx(
                VK_SPACE, SPACE_SCAN_CODE, ByteArray(256), buffer, buffer.size, 0, layout
            )
        }
    }

    private fun handleValue(handle: WinDef.HKL): Long = Pointer.nativeValue(handle.pointer)

    private fun reportUnavailable(reason: String) {
        if (unavailableReported) return
        unavailableReported = true
        logger.info("Cannot read the installed keyboard layouts ($reason); using the built-in table")
    }

    private companion object {
        const val VK_SHIFT = 0x10
        const val VK_SPACE = 0x20
        const val SPACE_SCAN_CODE = 0x39
        const val KEY_DOWN = 0x80.toByte()

        /** Resolves a scan code to the virtual key it carries *on a given layout*. */
        const val MAPVK_VSC_TO_VK_EX = 3

        /**
         * `ToUnicodeEx`'s "do not change the kernel keyboard state" flag, honoured from Windows 10
         * version 1607 onwards and ignored harmlessly before it.
         */
        const val TOUNICODE_NO_KEY_STATE = 0x4

        /**
         * The character keys of the main block, by physical position.
         *
         * Fixed across layouts, which is the whole point of working in scan codes. `0x56` is the
         * extra key ISO keyboards carry beside the left Shift; ANSI boards do not have it, and
         * `MapVirtualKeyEx` returns 0 there so it is skipped.
         */
        val PRINTABLE_SCAN_CODES: IntArray = intArrayOf(0x29) +          // backtick
            (0x02..0x0D) +                                              // digit row, minus, equals
            (0x10..0x1B) +                                              // Q row, brackets
            (0x1E..0x28) + 0x2B +                                       // A row, semicolon, quote, backslash
            (0x2C..0x35) +                                              // Z row, comma, period, slash
            0x56                                                        // ISO extra key
    }
}

private operator fun IntArray.plus(range: IntRange): IntArray = this + range.toList().toIntArray()
