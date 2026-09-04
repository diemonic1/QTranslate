package com.github.ahatem.qtranslate.core.keyboard

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which way text gets retyped, and whether the system layout has to follow it.
 *
 * The two answers come from the same decision. Text that travelled into the other layout leaves the
 * user in the wrong one for the next word they type, so the system moves with it; text that came
 * back into the layout already active means they had fixed it by hand first, and moving them again
 * would undo their own correction.
 */
class KeyboardLayoutTextTest {

    // ---- the OS-derived path -------------------------------------------------

    @Test
    fun `latin keystrokes become the Cyrillic they were meant to be`() {
        assertEquals("привет", convert("ghbdtn"))
    }

    @Test
    fun `Cyrillic keystrokes become the Latin they were meant to be`() {
        // The active layout is English, so Cyrillic text can only have been typed under the other
        // one — it is travelling back.
        assertEquals("Hello", convert("Руддщ"))
    }

    @Test
    fun `direction follows the text, not the layout that happens to be active`() {
        // Someone types ghbdtn, notices, fixes the layout by hand, and only then presses the
        // hotkey. Russian is active by now, so a rule of "always convert into the other layout"
        // would send this the wrong way. The characters decide instead.
        assertEquals("привет", convert("ghbdtn", transitions = listOf(transitionFromRussian())))
    }

    @Test
    fun `text that moves into the other layout asks the system to follow`() {
        val conversion = conversionOf("ghbdtn")

        assertNotNull(conversion)
        assertEquals("привет", conversion.text)
        assertTrue(conversion.switchLayout, "The text left the active layout, so the system should too")
    }

    @Test
    fun `text that lands back in the active layout asks for no switch`() {
        // Someone fixed the layout by hand and only then pressed the hotkey. Russian is
        // already active and the text has just become Russian; switching now would put them
        // back in the layout that caused the mistake.
        val conversion = conversionOf("ghbdtn", transitions = listOf(transitionFromRussian()))

        assertNotNull(conversion)
        assertEquals("привет", conversion.text)
        assertFalse(conversion.switchLayout, "Already in the right layout; leave it alone")
    }

    @Test
    fun `capitals keep their case`() {
        assertEquals("Привет", convert("Ghbdtn"))
        assertEquals("ПРИВЕТ", convert("GHBDTN"))
    }

    @Test
    fun `converting twice returns the original`() {
        for (sample in listOf("ghbdtn", "привет", "Hello", "Руддщ", "Ghbdtn vbh", "привет мир")) {
            val once = convert(sample)
            assertNotNull(once)
            assertEquals(sample, convert(once), "Round trip changed \"$sample\"")
        }
    }

    @Test
    fun `digits spaces and unmapped characters are left alone`() {
        assertEquals("привет 42 ыыы", convert("ghbdtn 42 sss"))
        assertEquals("№ § €", convert("№ § €"))
    }

    @Test
    fun `a character both layouts agree on does not vote for either direction`() {
        // Built from scratch rather than from the English/Russian table, to show the rule is about
        // the maps and not about Cyrillic: "-" sits on the same key in both layouts, so it must
        // not be able to outvote the letter beside it.
        val transition = KeyboardLayoutTransition(
            forward = mapOf('a' to 'ä', '-' to '-'),
            backward = mapOf('ä' to 'a', '-' to '-')
        )

        assertEquals(
            "a-",
            KeyboardLayoutText.convert("ä-", listOf(transition))?.text,
            "The one letter decides; the shared dash abstains"
        )
    }

    @Test
    fun `the letters decide the direction, not the punctuation`() {
        // "." is a key in both directions of the English/Russian table, so it cannot vote. If it
        // could, this string would be read as Russian and sent the wrong way.
        assertEquals("привет.", convert("ghbdtn/"))
    }

    @Test
    fun `an even split honours the order the OS reported`() {
        // Six letters each way — nothing to choose between them, so the forward direction wins,
        // which is the layout the OS offered first.
        assertEquals("приветпривет", convert("приветghbdtn"))
    }

    @Test
    fun `the best scoring of several layouts wins`() {
        // German shares every letter in "ghbdtn" with English and so scores nothing; Russian
        // claims them all. Picking by score rather than by list order is what gets this right.
        val german = KeyboardLayoutTransition(
            forward = mapOf('y' to 'z', 'z' to 'y'),
            backward = mapOf('z' to 'y', 'y' to 'z')
        )

        assertEquals("привет", KeyboardLayoutText.convert("ghbdtn", listOf(german, transitionToRussian()))?.text)
    }

    @Test
    fun `no transitions at all means the caller must fall back`() {
        assertNull(KeyboardLayoutText.convert("ghbdtn", emptyList()))
    }

    @Test
    fun `an empty string is not a special case`() {
        assertEquals("", convert(""))
    }

    @Test
    fun `every letter survives a round trip`() {
        // A one-way entry would silently make some text unrecoverable. Checking every letter is
        // cheap and catches a typo that a spot-check of a few would miss. Punctuation is excluded
        // on purpose: "." and "," are in both layouts, so alone they have no direction to recover.
        val letters = ("qwertyuiopasdfghjklzxcvbnm" + "QWERTYUIOPASDFGHJKLZXCVBNM").toCharArray()
        for (character in letters) {
            val there = convert(character.toString())
            assertNotNull(there)
            assertEquals(character.toString(), convert(there), "'$character' -> '$there' does not come back")
        }
    }

    // ---- the built-in fallback table ----------------------------------------

    @Test
    fun `the built-in table converts in both directions`() {
        assertEquals("привет", KeyboardLayoutText.convertWithBuiltInTable("ghbdtn"))
        assertEquals("Hello", KeyboardLayoutText.convertWithBuiltInTable("Руддщ"))
    }

    @Test
    fun `the built-in table is offered only for the pair it was written for`() {
        // The gate that keeps it away from text it would destroy: applied to French, this table
        // turns "bonjour" into "ищтощги" and overwrites the user's selection with it.
        assertTrue(KeyboardLayoutText.supports(LanguageCode.ENGLISH, LanguageCode.RUSSIAN))
        assertTrue(KeyboardLayoutText.supports(LanguageCode.RUSSIAN, LanguageCode.ENGLISH))
        assertFalse(KeyboardLayoutText.supports(LanguageCode.FRENCH, LanguageCode.GERMAN))
        assertFalse(KeyboardLayoutText.supports(LanguageCode.ENGLISH, LanguageCode.ENGLISH))
    }

    // -------------------------------------------------------------------------

    private fun convert(
        text: String,
        transitions: List<KeyboardLayoutTransition> = listOf(transitionToRussian())
    ) = conversionOf(text, transitions)?.text

    private fun conversionOf(
        text: String,
        transitions: List<KeyboardLayoutTransition> = listOf(transitionToRussian())
    ) = KeyboardLayoutText.convert(text, transitions)

    /** English active, Russian on offer — the ordinary case. */
    private fun transitionToRussian() =
        KeyboardLayoutTransition(forward = EN_TO_RU, backward = RU_TO_EN)

    /** Russian active, English on offer — after the user fixed the layout by hand. */
    private fun transitionFromRussian() =
        KeyboardLayoutTransition(forward = RU_TO_EN, backward = EN_TO_RU)

    private companion object {
        /** The same legends the production table uses, so these assertions stay recognisable. */
        val ROWS = listOf(
            "qwertyuiop[]" to "йцукенгшщзхъ",
            "asdfghjkl;'" to "фывапролджэ",
            "zxcvbnm,./" to "ячсмитьбю.",
            "QWERTYUIOP{}" to "ЙЦУКЕНГШЩЗХЪ",
            "ASDFGHJKL:\"" to "ФЫВАПРОЛДЖЭ",
            "ZXCVBNM<>?" to "ЯЧСМИТЬБЮ,",
            "`~" to "ёЁ"
        )

        val EN_TO_RU: Map<Char, Char> = buildMap {
            for ((left, right) in ROWS) for (i in left.indices) put(left[i], right[i])
        }

        val RU_TO_EN: Map<Char, Char> = buildMap {
            for ((left, right) in ROWS) for (i in left.indices) put(right[i], left[i])
        }
    }
}
