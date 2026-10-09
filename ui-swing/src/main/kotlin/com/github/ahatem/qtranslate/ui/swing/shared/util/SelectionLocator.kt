package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.github.ahatem.qtranslate.api.core.Logger
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Where the text selected in the focused application is on screen, when that application will say.
 *
 * ### How
 * Windows UI Automation: the focused element's TextPattern hands over the current selection, and
 * the selection's GetBoundingRectangles gives one screen rectangle per visible line. Chrome, Edge,
 * Firefox, Word, the modern Notepad and WPF applications all answer. Applications without
 * accessibility support — games, some older or custom-drawn programs — do not, and the caller
 * falls back to the pointer.
 *
 * ### Why by hand
 * There are no UI Automation bindings to lean on, so the five interfaces used are called through their
 * vtables. The slot numbers below are the declaration order in UIAutomationClient.h, counting the
 * three IUnknown methods first; a wrong one fails as an HRESULT or a null, never silently.
 *
 * ### Threading
 * Everything runs on one dedicated thread that joined the COM multithreaded apartment once, with a
 * deadline. UI Automation calls into the other process, and a busy or hung application must not
 * hold up the popup.
 */
object SelectionLocator {

    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private val CLSID_CUIAutomation = nativeGuid("{FF48DBA4-60EF-4201-AA87-54103EEF594E}")
    private val IID_IUIAutomation = nativeGuid("{30CBE57D-D9D0-452A-AB13-7AC5AC4825EE}")
    private val IID_IUIAutomationTextPattern = nativeGuid("{32EBA289-3583-42C9-9C59-3B6D9A1E9B6A}")
    private const val UIA_TEXT_PATTERN_ID = 10014

    // vtable slots (IUnknown: 0 QueryInterface, 1 AddRef, 2 Release)
    private const val RELEASE = 2
    private const val AUTOMATION_GET_FOCUSED_ELEMENT = 8
    private const val ELEMENT_GET_CURRENT_PATTERN_AS = 14
    private const val TEXT_PATTERN_GET_SELECTION = 5
    private const val RANGE_ARRAY_GET_LENGTH = 3
    private const val RANGE_ARRAY_GET_ELEMENT = 4
    private const val RANGE_GET_BOUNDING_RECTANGLES = 10

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            Ole32Native.INSTANCE.CoInitializeEx(null, Ole32Native.COINIT_MULTITHREADED)
            task.run()
        }, "selection-locator").apply { isDaemon = true }
    }

    /** Created once and kept: building the automation object costs more than one query. */
    private var automation: Pointer? = null

    /**
     * The selection's bounds in AWT screen coordinates, or null when it cannot be known.
     *
     * Blocks for at most [timeoutMs]; call it off the event dispatch thread.
     */
    fun locate(logger: Logger, timeoutMs: Long = 400): Rectangle? {
        if (!isWindows) return null
        val startedAt = System.nanoTime()
        val pending = worker.submit<List<Rectangle>> { physicalLineRects(logger) }
        val lines = try {
            pending.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.cancel(false)
            logger.info("Selection bounds: no answer within ${timeoutMs}ms")
            return null
        } catch (e: Exception) {
            logger.info("Selection bounds unavailable: ${e.cause?.message ?: e.message}")
            return null
        }
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000
        if (lines.isEmpty()) {
            logger.info("Selection bounds: the focused application reported none (${elapsed}ms)")
            return null
        }
        val union = lines.reduce { acc, r -> acc.union(r) }
        val logical = toLogical(union)
        logger.info("Selection bounds: ${lines.size} line(s), physical $union -> screen $logical (${elapsed}ms)")
        return logical
    }

    private fun physicalLineRects(logger: Logger): List<Rectangle> {
        val uia = automation ?: createAutomation(logger)?.also { automation = it } ?: return emptyList()

        val element = PointerByReference()
        if (call(uia, AUTOMATION_GET_FOCUSED_ELEMENT, element) != 0 || element.value == null) return emptyList()
        try {
            val pattern = PointerByReference()
            val hr = call(element.value, ELEMENT_GET_CURRENT_PATTERN_AS, UIA_TEXT_PATTERN_ID, IID_IUIAutomationTextPattern, pattern)
            if (hr != 0 || pattern.value == null) return emptyList() // no text pattern: not a text control
            try {
                val ranges = PointerByReference()
                if (call(pattern.value, TEXT_PATTERN_GET_SELECTION, ranges) != 0 || ranges.value == null) return emptyList()
                try {
                    val count = IntByReference()
                    if (call(ranges.value, RANGE_ARRAY_GET_LENGTH, count) != 0) return emptyList()
                    return (0 until count.value).flatMap { index -> rangeRects(ranges.value, index) }
                } finally {
                    release(ranges.value)
                }
            } finally {
                release(pattern.value)
            }
        } finally {
            release(element.value)
        }
    }

    private fun rangeRects(ranges: Pointer, index: Int): List<Rectangle> {
        val range = PointerByReference()
        if (call(ranges, RANGE_ARRAY_GET_ELEMENT, index, range) != 0 || range.value == null) return emptyList()
        try {
            val array = PointerByReference()
            if (call(range.value, RANGE_GET_BOUNDING_RECTANGLES, array) != 0 || array.value == null) return emptyList()
            val oleAut = OleAut32Native.INSTANCE
            val safeArray = array.value
            try {
                val lower = IntByReference()
                val upper = IntByReference()
                if (oleAut.SafeArrayGetLBound(safeArray, 1, lower) != 0) return emptyList()
                if (oleAut.SafeArrayGetUBound(safeArray, 1, upper) != 0) return emptyList()
                val count = upper.value - lower.value + 1
                if (count < 4) return emptyList() // an empty or collapsed range has no rectangle
                val data = PointerByReference()
                if (oleAut.SafeArrayAccessData(safeArray, data) != 0 || data.value == null) return emptyList()
                val values = try { data.value.getDoubleArray(0, count) } finally { oleAut.SafeArrayUnaccessData(safeArray) }
                // left, top, width, height per line
                return values.toList().chunked(4).filter { it.size == 4 && it[2] > 0 && it[3] > 0 }.map {
                    Rectangle(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt())
                }
            } finally {
                oleAut.SafeArrayDestroy(safeArray)
            }
        } finally {
            release(range.value)
        }
    }

    private fun createAutomation(logger: Logger): Pointer? {
        val out = PointerByReference()
        val hr = Ole32Native.INSTANCE.CoCreateInstance(
            CLSID_CUIAutomation, null, Ole32Native.CLSCTX_INPROC_SERVER, IID_IUIAutomation, out
        )
        if (hr != 0 || out.value == null) {
            logger.warn("UI Automation is unavailable (HRESULT 0x${Integer.toHexString(hr)})")
            return null
        }
        return out.value
    }

    private fun call(target: Pointer, slot: Int, vararg args: Any?): Int {
        val vtable = target.getPointer(0)
        val function = Function.getFunction(vtable.getPointer(slot.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        return function.invokeInt(arrayOf(target, *args))
    }

    private fun release(target: Pointer?) {
        if (target != null) runCatching { call(target, RELEASE) }
    }

    /**
     * Physical screen pixels, which is what UI Automation reports, to the coordinates AWT places
     * windows in.
     *
     * On a scaled display the two differ. AWT keeps each monitor's origin in device pixels and
     * scales distances from it, so a point is mapped by the monitor it falls on.
     */
    private fun toLogical(physical: Rectangle): Rectangle {
        val cx = physical.centerX
        val cy = physical.centerY
        val configs = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
        val config = configs.firstOrNull { gc ->
            val b = gc.bounds
            val sx = gc.defaultTransform.scaleX
            val sy = gc.defaultTransform.scaleY
            cx >= b.x && cx < b.x + b.width * sx && cy >= b.y && cy < b.y + b.height * sy
        } ?: configs.firstOrNull() ?: return physical
        val b = config.bounds
        val sx = config.defaultTransform.scaleX
        val sy = config.defaultTransform.scaleY
        return Rectangle(
            (b.x + (physical.x - b.x) / sx).toInt(),
            (b.y + (physical.y - b.y) / sy).toInt(),
            (physical.width / sx).toInt().coerceAtLeast(1),
            (physical.height / sy).toInt().coerceAtLeast(1)
        )
    }
}
