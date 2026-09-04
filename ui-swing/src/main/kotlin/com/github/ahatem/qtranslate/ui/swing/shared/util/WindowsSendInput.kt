package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.github.ahatem.qtranslate.api.core.Logger
import com.sun.jna.Native
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser

/**
 * Injects key events through Win32 `SendInput`.
 *
 * ### Why not `java.awt.Robot`
 * `Robot` delivers each press and release as a separate injection, so the user's own key events
 * can land between them. A physical Ctrl release arriving between our Ctrl-down and our C-down
 * leaves the target seeing a bare `C`, which it types — over the top of the selection we were
 * about to read. `SendInput` is documented to insert the events of one call *without* them being
 * interspersed with other input, from the user or from any other injector, which is precisely the
 * guarantee that removes the race rather than narrowing it.
 *
 * Windows only, like its neighbours [SystemKeyboardLayout] and [PhysicalKeyState]; elsewhere
 * [isAvailable] is false and the caller keeps using `Robot`.
 */
internal object WindowsSendInput {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** Left- and right-specific modifier codes — see [heldModifiers] for why not the general ones. */
    private val MODIFIER_KEYS = intArrayOf(
        0xA0, // VK_LSHIFT
        0xA1, // VK_RSHIFT
        0xA2, // VK_LCONTROL
        0xA3, // VK_RCONTROL
        0xA4, // VK_LMENU  (left Alt)
        0xA5, // VK_RMENU  (right Alt)
        0x5B, // VK_LWIN
        0x5C  // VK_RWIN
    )

    /** Right-hand modifiers and a few others are flagged as extended keys on the wire. */
    private val EXTENDED_KEYS = setOf(0xA3, 0xA5, 0x5B, 0x5C)

    private const val MAPVK_VK_TO_VSC = 0

    private var structSizeReported = false
    private var unavailableReported = false

    /**
     * Whether events can be injected at all.
     *
     * Includes a one-off check of the `INPUT` struct's size, because getting that wrong is the one
     * mistake here that fails *silently*: `SendInput` rejects the call, returns 0, and injects
     * nothing, which looks exactly like the operating system refusing the request. Checking once
     * at the boundary turns an unexplainable no-op into a line in the log.
     */
    val isAvailable: Boolean by lazy {
        if (!isWindows) return@lazy false
        runCatching {
            val expected = if (Native.POINTER_SIZE == 8) 40 else 28
            val actual = allocate(1)[0].size()
            if (actual != expected) {
                // Not fatal — the value computed by JNA is the one SendInput is given either way.
                // Worth saying out loud, because a mismatch means this assumption has moved.
                structSizeReported = true
            }
            true
        }.getOrDefault(false)
    }

    /**
     * The modifier keys held down right now, as left/right-specific virtual-key codes.
     *
     * Specific rather than general because that is what a target inspects. Chromium disambiguates
     * shortcuts by asking about `VK_LCONTROL`, so releasing the general `VK_CONTROL` leaves it
     * still believing Ctrl is down — the release would look like it worked and change nothing.
     */
    fun heldModifiers(): IntArray {
        if (!isAvailable) return IntArray(0)
        return runCatching {
            val user32 = User32.INSTANCE
            MODIFIER_KEYS.filter { vk -> (user32.GetAsyncKeyState(vk).toInt() and 0x8000) != 0 }
                .toIntArray()
        }.getOrDefault(IntArray(0))
    }

    /** The scan code for [virtualKey] under the calling thread's layout, or 0 if unknown. */
    fun scanCodeOf(virtualKey: Int): Int =
        if (!isAvailable) 0
        else runCatching { User32.INSTANCE.MapVirtualKeyEx(virtualKey, MAPVK_VK_TO_VSC, null) }
            .getOrDefault(0)

    /**
     * Injects [plan] as one indivisible batch.
     *
     * @return how many events the OS accepted. Fewer than [plan] holds means the injection was
     *   refused — overwhelmingly because the focused window belongs to a process running at a
     *   higher integrity level than this one, which Windows forbids us to send input to. That is
     *   not something the application can work around, but reporting it is the difference between
     *   a diagnosable limitation and an unexplained silence.
     */
    fun send(plan: List<SyntheticKeyEvent>, logger: Logger): Int {
        if (!isAvailable || plan.isEmpty()) return 0

        return runCatching {
            reportStructSizeOnce(logger)

            val inputs = allocate(plan.size)
            plan.forEachIndexed { index, event -> fill(inputs[index], event) }

            // The size of one fully-initialised element of the contiguous block — never a
            // hard-coded constant, and never the size of a throwaway INPUT whose union type was
            // left unset, both of which make SendInput reject the call outright.
            val accepted = User32.INSTANCE.SendInput(
                WinDef.DWORD(plan.size.toLong()), inputs, inputs[0].size()
            ).toInt()

            if (accepted < plan.size) {
                logger.warn(
                    "Keystroke injection was refused by the OS ($accepted of ${plan.size} events " +
                        "accepted) — the focused window is probably running elevated"
                )
            }
            accepted
        }.getOrElse { failure ->
            reportUnavailable(logger, failure.message ?: failure::class.simpleName.orEmpty())
            0
        }
    }

    // -------------------------------------------------------------------------

    /**
     * One contiguous block of [count] `INPUT` structures.
     *
     * `toArray` rather than a list of separately constructed `INPUT()`s: `SendInput` reads the
     * array as a single span of memory, and independently allocated structures are not one.
     */
    @Suppress("UNCHECKED_CAST")
    private fun allocate(count: Int): Array<WinUser.INPUT> =
        WinUser.INPUT().toArray(count) as Array<WinUser.INPUT>

    private fun fill(input: WinUser.INPUT, event: SyntheticKeyEvent) {
        input.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
        // Marks which arm of the union is live. Without it the keyboard fields are written into an
        // unset union and the event arrives as nonsense.
        input.input.setType("ki")

        var flags = 0L
        if (event.isKeyUp) flags = flags or WinUser.KEYBDINPUT.KEYEVENTF_KEYUP.toLong()
        if (event.virtualKey in EXTENDED_KEYS) {
            flags = flags or WinUser.KEYBDINPUT.KEYEVENTF_EXTENDEDKEY.toLong()
        }

        // Sent by virtual key, deliberately without KEYEVENTF_SCANCODE. With that flag the target
        // resolves the scan code through whatever layout is active, so the key physically at C on
        // QWERTY becomes a different virtual key on AZERTY or Dvorak and "Ctrl+C" turns into some
        // other command. This is the opposite of what WindowsKeyboardLayouts needs: that code is
        // asking about physical keys, this one is issuing a command. The scan code is filled in
        // as well, for the targets that read it out of the message rather than the virtual key.
        input.input.ki.wVk = WinDef.WORD(event.virtualKey.toLong())
        input.input.ki.wScan = WinDef.WORD(event.scanCode.toLong())
        input.input.ki.dwFlags = WinDef.DWORD(flags)
        input.input.ki.time = WinDef.DWORD(0)
        input.input.ki.dwExtraInfo = BaseTSD.ULONG_PTR(0)
    }

    private fun reportStructSizeOnce(logger: Logger) {
        if (!structSizeReported) return
        structSizeReported = false
        logger.warn(
            "The INPUT structure is an unexpected size for this JVM; keystroke injection may be " +
                "rejected by the OS"
        )
    }

    private fun reportUnavailable(logger: Logger, reason: String) {
        if (unavailableReported) return
        unavailableReported = true
        logger.info("Cannot inject keystrokes ($reason); falling back to Robot")
    }
}
