package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinUser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That the `INPUT` structure is the size Win32 expects.
 *
 * `SendInput` is handed this size and rejects the whole call if it disagrees — returning zero,
 * injecting nothing, setting no exception. The failure is indistinguishable from the operating
 * system refusing the request on security grounds, so on a user's machine it would look like the
 * feature quietly not working, with nothing to go on. Checking here means a JNA upgrade that moves
 * the layout fails in CI instead.
 *
 * Windows-only: the structure is only laid out this way there, and the value is only used there.
 */
class WindowsSendInputStructTest {

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Test
    fun `an INPUT is 40 bytes on a 64-bit JVM and 28 on a 32-bit one`() {
        if (!isWindows) return

        @Suppress("UNCHECKED_CAST")
        val block = WinUser.INPUT().toArray(1) as Array<WinUser.INPUT>
        val expected = if (Native.POINTER_SIZE == 8) 40 else 28

        assertEquals(
            expected,
            block[0].size(),
            "SendInput is given this size and silently rejects the call when it is wrong"
        )
    }

    @Test
    fun `the size comes from the contiguous block, not from a loose structure`() {
        if (!isWindows) return

        // The distinction that matters: SendInput reads its argument as one span of memory, so the
        // size must come from an element of the block that is actually being sent. A structure
        // built on its own can report a different size, and passing that is the classic way to get
        // a silent rejection.
        @Suppress("UNCHECKED_CAST")
        val block = WinUser.INPUT().toArray(2) as Array<WinUser.INPUT>

        assertEquals(block[0].size(), block[1].size(), "Every element of the block is one stride")
    }
}
