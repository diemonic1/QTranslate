package com.github.ahatem.qtranslate.core.keyboard

import com.github.ahatem.qtranslate.api.language.LanguageCode

/**
 * Rewrites text typed on the wrong keyboard layout into what the typist meant.
 *
 * Typing `ghbdtn` when you meant `привет` is not a spelling mistake and not a translation problem:
 * the right keys were pressed and the wrong layout was listening. The fix is mechanical — read
 * each character as the key that produced it, then write out what the other layout puts on that
 * key.
 *
 * ### Why a direction has to be chosen
 * The obvious design is one map covering both directions, so nothing needs to decide which way the
 * text is going. It does not survive contact with the layouts: `.` and `,` exist in *both*, on
 * different keys. English `.` is the Russian `ю` key, while Russian `.` is the English `/` key —
 * so a single map has two answers for `.` and keeps whichever was written last, silently mangling
 * ordinary punctuation.
 *
 * ### How the direction is chosen
 * By asking which map *exclusively* claims the characters in the text. A character that is a key
 * in both directions — `.` and `,` in an English/Russian pair — cancels itself out and gets no
 * vote, which is the general form of the older hand-written rule that punctuation does not count.
 * The same rule falls out for a pair sharing a script: between English and German only `y`, `z`
 * and the umlauts differ, and those are exactly the characters that carry information about which
 * layout produced the text.
 *
 * The layout the OS reports as active is deliberately *not* the deciding vote. People often fix
 * the layout by hand and only then reach for this feature, which makes the active layout the
 * destination rather than the source; trusting it would convert the wrong way and overwrite the
 * selection with nothing useful.
 */
object KeyboardLayoutText {

    /**
     * The retyped text, and whether the system layout has to move for the user to carry on
     * typing in the language the text is now in.
     *
     * [switchLayout] is false when the text came *back* into the layout that is already
     * active — someone who fixed the layout by hand before pressing the hotkey is already in
     * the right one, and moving them out of it would undo their own correction.
     */
    data class Conversion(val text: String, val switchLayout: Boolean)

    /**
     * Retypes [text] with whichever of [transitions] and whichever direction best matches it.
     *
     * Returns null when there is nothing to choose from, which tells the caller to fall back to
     * [convertWithBuiltInTable].
     *
     * With more than two layouts installed every candidate is scored rather than assuming the next
     * one in the list is meant. Ties go to the first candidate and, within a candidate, to the
     * forward direction — so with exactly two layouts this reduces to "convert into the other one",
     * and the tie-break honours the order the OS reported.
     */
    fun convert(text: String, transitions: List<KeyboardLayoutTransition>): Conversion? {
        if (transitions.isEmpty()) return null

        var bestScore = Int.MIN_VALUE
        var best: Conversion? = null

        for (transition in transitions) {
            // Forward means the text was typed under the active layout but meant for the
            // other one, so it ends up somewhere the system is not yet — hence the switch.
            val forwardScore = score(text, transition.forward, transition.backward)
            if (forwardScore > bestScore) {
                bestScore = forwardScore
                best = Conversion(apply(text, transition.forward), switchLayout = true)
            }

            // Backward puts the text into the active layout, which the user is already in.
            val backwardScore = score(text, transition.backward, transition.forward)
            if (backwardScore > bestScore) {
                bestScore = backwardScore
                best = Conversion(apply(text, transition.backward), switchLayout = false)
            }
        }

        return best
    }

    /**
     * Retypes [text] with the built-in English/Russian table, for when the OS cannot be asked.
     *
     * Gated by [supports] at the call site: applied to text it was not written for, this table is
     * destructive rather than merely useless — it would turn a French `bonjour` into `ищтощги`,
     * overwriting a real selection.
     */
    fun convertWithBuiltInTable(text: String): String {
        val forward = score(text, EN_RU.forward, EN_RU.backward)
        val backward = score(text, EN_RU.backward, EN_RU.forward)
        return apply(text, if (backward > forward) EN_RU.backward else EN_RU.forward)
    }

    /**
     * Whether the built-in table covers the pair of languages the user has configured.
     *
     * Only consulted on the fallback path. The layouts themselves answer this question wherever
     * the OS can be asked, and asking the translation settings which scripts somebody writes in is
     * a guess — one that returns the wrong answer whenever both settings happen to name the same
     * language. It survives here because the fallback has nothing better to go on.
     */
    internal fun supports(first: LanguageCode, second: LanguageCode): Boolean =
        setOf(first.tag.lowercase(), second.tag.lowercase()) == BUILT_IN_PAIR

    // -------------------------------------------------------------------------

    /**
     * How strongly [text] reads as having been typed for [candidate] rather than [rival].
     *
     * Only characters one map claims and the other does not can vote, so anything both layouts
     * agree on — punctuation, digits, shared letters — stays silent instead of drowning out the
     * few characters that actually distinguish the two.
     */
    private fun score(text: String, candidate: Map<Char, Char>, rival: Map<Char, Char>): Int =
        text.count { character -> character in candidate && character !in rival }

    private fun apply(text: String, mapping: Map<Char, Char>): String =
        buildString(text.length) {
            for (character in text) append(mapping[character] ?: character)
        }

    /** Two directed maps between one pair of layouts. */
    private class Table(val forward: Map<Char, Char>, val backward: Map<Char, Char>)

    /**
     * Builds both directions from rows holding the same keys in the same order.
     *
     * Written as parallel legends rather than as pairs because that is the form in which the
     * mapping can be checked: a mistake shows up as a length mismatch instead of as a quietly
     * wrong letter.
     */
    private fun table(vararg rows: Pair<String, String>): Table {
        val forward = HashMap<Char, Char>()
        val backward = HashMap<Char, Char>()
        for ((left, right) in rows) {
            require(left.length == right.length) {
                "Layout rows must describe the same keys: \"$left\" and \"$right\" differ in length"
            }
            for (index in left.indices) {
                forward[left[index]] = right[index]
                backward[right[index]] = left[index]
            }
        }
        return Table(forward, backward)
    }

    private val BUILT_IN_PAIR = setOf("en", "ru")

    /**
     * US QWERTY against Russian ЙЦУКЕН, key for key.
     *
     * Both the unshifted and the shifted legends are listed, because the shifted pairs are not
     * derivable from the unshifted ones: the layouts disagree about which key carries which
     * punctuation. `,` and `.` sit on the `<`/`>` keys on QWERTY but land on Russian letters, and
     * the Russian comma is a shifted key where the English one is not.
     */
    private val EN_RU = table(
        "qwertyuiop[]" to "йцукенгшщзхъ",
        "asdfghjkl;'" to "фывапролджэ",
        "zxcvbnm,./" to "ячсмитьбю.",
        "QWERTYUIOP{}" to "ЙЦУКЕНГШЩЗХЪ",
        "ASDFGHJKL:\"" to "ФЫВАПРОЛДЖЭ",
        "ZXCVBNM<>?" to "ЯЧСМИТЬБЮ,",
        "`~" to "ёЁ"
    )
}
