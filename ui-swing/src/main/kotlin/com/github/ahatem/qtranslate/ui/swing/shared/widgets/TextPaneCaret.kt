package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.UIScale
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.text.BadLocationException
import javax.swing.text.DefaultCaret
import javax.swing.text.JTextComponent
import javax.swing.text.Position
import kotlin.math.roundToInt

/**
 * A wider, vertically inset caret for comfortable reading.
 *
 * Only the appearance is custom. Blinking is left to [DefaultCaret], which starts and stops its
 * flasher with focus and editability; a second timer would run regardless of focus and compete
 * with it for visibility.
 *
 * Dimensions are in logical pixels and scaled for the display.
 */
class AdvancedCaret(
    private val caretWidth: kotlin.Float = 3f,
    blinkRate: Int = 600,
    private val verticalInset: kotlin.Float = 3f
) : DefaultCaret() {

    init { setBlinkRate(blinkRate) }

    override fun paint(g: Graphics?) {
        if (!isVisible) return
        val comp = component ?: return
        val g2 = g as? Graphics2D ?: return

        val oldStroke = g2.stroke
        val oldColor  = g2.color
        val oldHints  = g2.renderingHints

        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
            g2.stroke = BasicStroke(UIScale.scale(caretWidth))
            g2.color  = comp.caretColor

            val inset = UIScale.scale(verticalInset)
            val viewRect = comp.ui.modelToView2D(comp, dot, Position.Bias.Forward) ?: return
            val x      = viewRect.x.roundToInt()
            val yStart = (viewRect.y + inset).roundToInt()
            val yEnd   = (viewRect.y + viewRect.height - inset).roundToInt()
            g2.drawLine(x, yStart, x, yEnd)
        } catch (_: BadLocationException) {
        } finally {
            g2.stroke = oldStroke
            g2.color  = oldColor
            g2.setRenderingHints(oldHints)
        }
    }
}

/**
 * A caret that still tracks the selection but is never drawn.
 *
 * Swing offers no way to do without a caret: it is the caret that holds the dot and the mark, so
 * dragging out a selection with the mouse goes through it. What a read-only pane does not need is
 * to *see* one. There is nothing to insert at, so a blinking bar only claims the text is editable,
 * and every blink asks for a repaint of the line beneath it — which is where the flicker and the
 * smearing during a drag came from, the blink and the drag redrawing the same strip on schedules
 * that know nothing about each other.
 *
 * [damage] is deliberately empty. Its entire job is to mark the caret's old and new positions dirty
 * so the bar can be erased and redrawn somewhere else; with nothing ever drawn there is nothing to
 * erase, and those repaint requests were the artefacts.
 */
class InvisibleCaret : DefaultCaret() {

    override fun install(c: JTextComponent) {
        super.install(c)
        // No timer at all, rather than a timer whose ticks paint nothing.
        blinkRate = 0
    }

    override fun paint(g: Graphics?) = Unit

    override fun isVisible(): Boolean = false

    override fun damage(r: Rectangle?) = Unit
}
