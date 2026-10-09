package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.output.CompareBoard
import com.github.ahatem.qtranslate.ui.swing.main.output.CompareBoardState
import com.github.ahatem.qtranslate.ui.swing.main.output.ProviderPresentation
import com.github.ahatem.qtranslate.ui.swing.main.output.ProviderRole
import com.github.ahatem.qtranslate.ui.swing.main.output.ProviderStatus
import com.github.ahatem.qtranslate.ui.swing.main.output.TranslationProviderState
import java.awt.Component
import java.awt.Container
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The single-translation popup asks for the room its text needs and no more.
 *
 * The popup sizes itself to what its results view reports, so an inflated report is a window with
 * empty space under one line, and a report the window cannot meet exactly is a scroll bar beside
 * text that fits.
 */
class QuickPopupResultsSizeTest {

    private val font = FontConfig("Dialog", 15)

    private fun layoutTopDown(c: Component) {
        c.doLayout()
        if (c is Container) c.components.forEach(::layoutTopDown)
    }

    private fun singleLine(): Pair<CompareBoard, QuickTranslateResultsView> {
        val board = CompareBoard()
        val view = QuickTranslateResultsView(board)
        SwingUtilities.invokeAndWait {
            board.render(
                CompareBoardState(
                    primary = TranslationProviderState(
                        serviceId = "google", serviceName = "Google", iconPath = null,
                        role = ProviderRole.PRIMARY, presentation = ProviderPresentation.QUICK,
                        status = ProviderStatus.SUCCESS, text = "Hello boar, how are you?",
                        loadingText = "Translating...", placeholderTitle = "Translation failed",
                        placeholderSubtitle = "Try another service",
                        fontConfig = font, fallbackFontConfig = font,
                        showHeader = false
                    ),
                    secondaries = emptyList()
                )
            )
        }
        SwingUtilities.invokeAndWait {}
        return board to view
    }

    private fun layout(view: QuickTranslateResultsView, width: Int, height: Int) =
        SwingUtilities.invokeAndWait {
            view.setSize(width, height)
            repeat(3) { layoutTopDown(view) }
        }

    @Test
    fun `given the height it asks for, the results show no scroll bar`() {
        val (_, view) = singleLine()
        layout(view, 420, 400)
        val asked = view.preferredSize.height
        layout(view, 420, asked)

        assertFalse(
            view.viewport.verticalScrollBar.isVisible,
            "The popup sizes itself to this height; a scroll bar here appears beside text that fits"
        )
    }

    @Test
    fun `one headerless line asks for little more than the line itself`() {
        val (_, view) = singleLine()
        layout(view, 420, 400)
        val text = view.viewport.viewport.view.let { board ->
            generateSequence(listOf(board)) { level -> level.filterIsInstance<Container>().flatMap { it.components.toList() } }
                .takeWhile { it.isNotEmpty() }
                .flatten()
                .first { it.javaClass.simpleName == "AdvancedTextPane" }
        }
        val slack = view.preferredSize.height - text.preferredSize.height

        // Board padding, the body's small bottom gap and the scroll pane's border. The hidden
        // two-line placeholder and the loading card used to count as well, and the gaps meant to
        // separate the text from a header that is not there.
        assertTrue(slack <= 24, "${view.preferredSize.height}px asked for ${text.preferredSize.height}px of text")
    }
}
