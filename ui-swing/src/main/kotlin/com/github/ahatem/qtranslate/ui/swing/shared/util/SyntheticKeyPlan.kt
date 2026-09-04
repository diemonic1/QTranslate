package com.github.ahatem.qtranslate.ui.swing.shared.util

/**
 * One synthetic key event, described independently of any platform API.
 *
 * @param virtualKey the virtual-key code to deliver.
 * @param scanCode the physical key's scan code. Carried alongside [virtualKey] rather than instead
 *   of it — see [SyntheticKeyPlan] for why the virtual key is the one that decides the outcome.
 */
internal data class SyntheticKeyEvent(
    val virtualKey: Int,
    val scanCode: Int = 0,
    val isKeyUp: Boolean = false
)

/**
 * Works out the exact sequence of key events a synthetic shortcut needs.
 *
 * Split from the code that actually injects them because this is the only part that can be tested:
 * the injection itself is a Win32 call whose whole value — that the events arrive without anything
 * interleaving — cannot be observed from a test. Same reasoning as `ReleaseAssets` next to
 * `Updater`, and the same payoff: the ordering rules below are pinned by assertions instead of by
 * hope.
 */
internal object SyntheticKeyPlan {

    /**
     * Key-up events for every modifier in [heldModifierVks], to lead a shortcut with.
     *
     * ### Why a shortcut may need this at all
     * These are triggered by global hotkeys, so the keys that fired the hotkey can still be
     * physically down when the synthetic shortcut goes out. `Ctrl+Shift+T` still held while a
     * synthetic `Ctrl+C` is delivered does not produce `Ctrl+C` — it produces `Ctrl+Shift+C`,
     * which in Chrome opens the developer tools inspector rather than copying anything.
     *
     * ### Why the caller passes left/right-specific codes
     * Releasing the general `VK_CONTROL` leaves the target's left- and right-specific key states
     * set, and applications that disambiguate shortcuts by asking about `VK_LCONTROL` — Chromium
     * does — go on believing Ctrl is down.
     *
     * ### Why nothing is pressed again afterwards
     * Re-pressing a modifier at the end, to put the OS back in step with a key the user is still
     * physically holding, is tempting and strictly worse. If they let go during the batch, the
     * target believes that modifier is held until the next physical press of it — every
     * subsequent keystroke arrives as a shortcut. A briefly out-of-step modifier costs one
     * character; a permanently stuck one costs the session.
     */
    fun releaseHeld(heldModifierVks: IntArray): List<SyntheticKeyEvent> =
        heldModifierVks.map { SyntheticKeyEvent(virtualKey = it, isKeyUp = true) }

    /**
     * [modifierVk] held down over a press and release of [keyVk], preceded by the release of
     * anything in [heldModifierVks].
     *
     * The four events of the shortcut proper are meant to be delivered as one indivisible batch:
     * sent separately, the user's own key events can land between them, and a physical modifier
     * release arriving between the modifier press and the letter press turns the shortcut into the
     * bare letter — typed over whatever the user had selected.
     */
    fun shortcut(
        modifierVk: Int,
        keyVk: Int,
        heldModifierVks: IntArray = IntArray(0),
        scanCodeOf: (Int) -> Int = { 0 }
    ): List<SyntheticKeyEvent> =
        releaseHeld(heldModifierVks) + listOf(
            SyntheticKeyEvent(modifierVk, scanCodeOf(modifierVk)),
            SyntheticKeyEvent(keyVk, scanCodeOf(keyVk)),
            SyntheticKeyEvent(keyVk, scanCodeOf(keyVk), isKeyUp = true),
            SyntheticKeyEvent(modifierVk, scanCodeOf(modifierVk), isKeyUp = true)
        )
}
