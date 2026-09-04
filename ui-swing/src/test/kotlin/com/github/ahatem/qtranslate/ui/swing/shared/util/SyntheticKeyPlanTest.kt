package com.github.ahatem.qtranslate.ui.swing.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The order and content of a synthetic shortcut's key events.
 *
 * These rules exist because getting them wrong types a letter into the user's document over the
 * top of their selection, rather than merely failing. None of them is self-evident from reading
 * the injection code, and two of them look like mistakes to a reader who does not know the
 * history — which is exactly why they are pinned here.
 */
class SyntheticKeyPlanTest {

    private companion object {
        const val VK_CONTROL = 0x11
        const val VK_C = 0x43
        const val VK_LSHIFT = 0xA0
        const val VK_LCONTROL = 0xA2
    }

    @Test
    fun `a shortcut is the modifier held across the key, and nothing else`() {
        val plan = SyntheticKeyPlan.shortcut(VK_CONTROL, VK_C)

        assertEquals(
            listOf(
                SyntheticKeyEvent(VK_CONTROL),
                SyntheticKeyEvent(VK_C),
                SyntheticKeyEvent(VK_C, isKeyUp = true),
                SyntheticKeyEvent(VK_CONTROL, isKeyUp = true)
            ),
            plan
        )
    }

    @Test
    fun `held modifiers are released before the shortcut, not after`() {
        // A hotkey the user is still holding turns the synthetic Ctrl+C into Ctrl+Shift+C, which
        // is the Chrome inspector rather than a copy. Releasing has to happen first to be of any
        // use at all.
        val plan = SyntheticKeyPlan.shortcut(
            VK_CONTROL, VK_C, heldModifierVks = intArrayOf(VK_LSHIFT, VK_LCONTROL)
        )

        assertEquals(
            listOf(
                SyntheticKeyEvent(VK_LSHIFT, isKeyUp = true),
                SyntheticKeyEvent(VK_LCONTROL, isKeyUp = true)
            ),
            plan.take(2)
        )
        assertEquals(6, plan.size, "Two releases plus the four events of the shortcut")
    }

    @Test
    fun `a released modifier is never pressed again at the end`() {
        // Guards a decision that reads like an oversight: it is tempting to restore a modifier the
        // user is still physically holding, so the OS and the keyboard agree again. Doing so is
        // worse — if they let go mid-batch, the target believes that modifier is down until its
        // next physical press, and every keystroke after that arrives as a shortcut.
        val plan = SyntheticKeyPlan.shortcut(
            VK_CONTROL, VK_C, heldModifierVks = intArrayOf(VK_LSHIFT)
        )

        val shiftEvents = plan.filter { it.virtualKey == VK_LSHIFT }
        assertEquals(1, shiftEvents.size, "Shift should be touched once")
        assertTrue(shiftEvents.single().isKeyUp, "and only to release it")
    }

    @Test
    fun `nothing held means nothing added`() {
        assertEquals(emptyList(), SyntheticKeyPlan.releaseHeld(IntArray(0)))
        assertEquals(4, SyntheticKeyPlan.shortcut(VK_CONTROL, VK_C).size)
    }

    @Test
    fun `scan codes are carried alongside the virtual keys`() {
        // Some targets read the scan code out of the message rather than the virtual key. The
        // virtual key still decides the command; the scan code is there so those targets are not
        // handed a zero.
        val plan = SyntheticKeyPlan.shortcut(VK_CONTROL, VK_C, scanCodeOf = { vk ->
            if (vk == VK_C) 0x2E else 0x1D
        })

        assertEquals(listOf(0x1D, 0x2E, 0x2E, 0x1D), plan.map { it.scanCode })
        assertEquals(listOf(VK_CONTROL, VK_C, VK_C, VK_CONTROL), plan.map { it.virtualKey })
    }
}
