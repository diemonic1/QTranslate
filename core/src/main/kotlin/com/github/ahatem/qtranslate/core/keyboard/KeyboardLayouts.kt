package com.github.ahatem.qtranslate.core.keyboard

/**
 * A way to retype text between the keyboard layout currently in use and one other installed layout.
 *
 * Both directions are carried because which one is wanted cannot be decided here: the layout that
 * is active is a hint, not an answer — people routinely fix the layout by hand *before* reaching
 * for this feature, at which point the active layout is the destination rather than the source.
 * The text itself settles it; see `KeyboardLayoutText.convert`.
 *
 * @param forward characters of the current layout mapped to the target layout's.
 * @param backward the reverse. Only keys whose two layouts disagree appear in either map, so a
 *   character present in one map and absent from the other is evidence about which layout produced
 *   the text.
 */
class KeyboardLayoutTransition(
    val forward: Map<Char, Char>,
    val backward: Map<Char, Char>
)

/**
 * The keyboard layouts installed in the operating system.
 *
 * Exists so the character tables can come from the layouts a person actually has rather than from
 * a table written by hand. A hand-written table has to be authored per language pair — around
 * seventeen of them to cover the scripts this technique applies to — and is still wrong for anyone
 * using a non-standard variant, such as a phonetic Russian layout instead of ЙЦУКЕН. The OS knows
 * the real answer for every installed layout at once.
 *
 * Implemented outside this module because reading it needs platform APIs; `core` has no access to
 * those. The same shape as [com.github.ahatem.qtranslate.core.audio.AudioPlayer]: an interface
 * here, the platform work elsewhere, wired together in the application module.
 */
interface KeyboardLayouts {

    /**
     * Every transition available from the layout active in the window that currently has focus,
     * best guess first.
     *
     * Empty when the OS cannot answer — not Windows, fewer than two layouts installed, or the
     * query failed. Callers fall back to the built-in table rather than treating this as an error.
     *
     * Ordered so that the caller can break a scoring tie by preferring the first entry without
     * knowing anything about what a layout handle is.
     *
     * Must not be called on the AWT event thread: implementations may touch per-thread keyboard
     * state, and the event thread is the one that processes real keystrokes.
     */
    fun readTransitions(): List<KeyboardLayoutTransition>

    /** For platforms with no implementation, and for tests that want the fallback path. */
    object Unavailable : KeyboardLayouts {
        override fun readTransitions(): List<KeyboardLayoutTransition> = emptyList()
    }
}
