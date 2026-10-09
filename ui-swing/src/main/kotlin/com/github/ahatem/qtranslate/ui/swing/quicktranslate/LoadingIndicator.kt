package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*

data class LoadingIndicatorState(
    val isVisible: Boolean
) : UiState

/**
 * The small progress marker that follows the pointer while a popup translation is being fetched.
 *
 * Owned by Swing's shared hidden frame rather than by the main window. It exists precisely for the
 * case where the main window is hidden in the tray, and an owned window is suppressed by the
 * platform while its owner is hidden — so owning it by the main window meant it never appeared at
 * the only time it was wanted.
 */
class LoadingIndicator(owner: Frame) : JWindow(), Renderable<LoadingIndicatorState> {

    private val positionUpdater = Timer(10) {
        val mouseLocation = MouseInfo.getPointerInfo().location
        setLocation(mouseLocation.x, mouseLocation.y + 20)
    }

    private val bar = PillMarqueeBar()

    init {
        isAlwaysOnTop = true
        modalExclusionType = Dialog.ModalExclusionType.APPLICATION_EXCLUDE
        setAutoRequestFocus(false)
        focusableWindowState = false
        type = Type.UTILITY

        contentPane = bar
        pack()

        // The window is exactly the bar's bounding box, so without this its square corners would
        // show around the rounded ends. A fully transparent window is the clean way; where the
        // platform cannot do per-pixel translucency, the window is cut to the bar's shape instead,
        // which costs smooth edges but keeps the corners from showing.
        val translucent = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
        }.getOrDefault(false)
        if (translucent) {
            background = Color(0, 0, 0, 0)
        } else {
            val arc = height.toDouble()
            shape = RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), arc, arc)
        }
    }

    /** When the marker went up, so a fast result cannot take it down before it was ever seen. */
    private var shownAt = 0L

    private var pendingHide: Timer? = null

    override fun render(state: LoadingIndicatorState) {
        if (state.isVisible) {
            pendingHide?.stop()
            pendingHide = null
            if (!isVisible) {
                // Positioned before showing, or the first frame lands wherever the window was
                // last left — usually the top-left corner of the screen.
                MouseInfo.getPointerInfo()?.location?.let { setLocation(it.x, it.y + 20) }
                shownAt = System.currentTimeMillis()
                bar.start()
                isVisible = true
                positionUpdater.start()
            }
            return
        }

        if (!isVisible) return

        // A translation that returns in 80ms would otherwise show and hide the marker inside a
        // single frame — the user sees a flicker, or nothing at all, and reports that the
        // indicator "doesn't work". Held for a moment so that appearing means something.
        val shownFor = System.currentTimeMillis() - shownAt
        if (shownFor >= MINIMUM_VISIBLE_MS) {
            hideNow()
            return
        }
        if (pendingHide != null) return
        pendingHide = Timer((MINIMUM_VISIBLE_MS - shownFor).toInt()) {
            (it.source as Timer).stop()
            pendingHide = null
            hideNow()
        }.apply { isRepeats = false; start() }
    }

    private fun hideNow() {
        isVisible = false
        positionUpdater.stop()
        bar.stop()
    }

    private companion object {
        /** Long enough to register as "something is happening" rather than as a glitch. */
        const val MINIMUM_VISIBLE_MS = 350L
    }
}

/**
 * A thin rounded track with a short rounded segment sweeping across it, left to right, on repeat —
 * the marquee the classic QTranslate shows.
 *
 * Painted by hand rather than with an indeterminate JProgressBar: the look-and-feel bar is tall,
 * square, carries a border, and its moving part spans a large share of the track, none of which
 * reads well at this size. The segment enters from beyond the left end and leaves past the right,
 * clipped to the track, so it never appears to jump.
 */
private class PillMarqueeBar : JComponent() {

    private val trackWidth = UIScale.scale(TRACK_WIDTH)
    private val trackHeight = UIScale.scale(TRACK_HEIGHT)
    private val segmentWidth = UIScale.scale(SEGMENT_WIDTH)

    private var startedAt = 0L

    private val ticker = Timer(FRAME_MS) { repaint() }

    init {
        isOpaque = false
        preferredSize = Dimension(trackWidth, trackHeight)
    }

    fun start() {
        startedAt = System.currentTimeMillis()
        ticker.start()
    }

    fun stop() = ticker.stop()

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width.toDouble()
            val h = height.toDouble()
            val track = RoundRectangle2D.Double(0.0, 0.0, w, h, h, h)

            g2.color = UIManager.getColor("ProgressBar.background") ?: Color(0xD0D0D0)
            g2.fill(track)

            // Position along one sweep, from fully off the left end to fully off the right.
            val travel = w + segmentWidth
            val phase = ((System.currentTimeMillis() - startedAt) % SWEEP_MS).toDouble() / SWEEP_MS
            val x = phase * travel - segmentWidth

            g2.clip(track)
            g2.color = UIManager.getColor("ProgressBar.foreground")
                ?: UIManager.getColor("Component.accentColor")
                ?: Color(0x2675BF)
            g2.fill(RoundRectangle2D.Double(x, 0.0, segmentWidth.toDouble(), h, h, h))
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val TRACK_WIDTH = 44
        const val TRACK_HEIGHT = 5
        const val SEGMENT_WIDTH = 14

        /** One pass across the track. Quick enough to read as busy, slow enough to follow. */
        const val SWEEP_MS = 1100L

        /** About 60 frames a second, and only while the marker is on screen. */
        const val FRAME_MS = 16
    }
}
