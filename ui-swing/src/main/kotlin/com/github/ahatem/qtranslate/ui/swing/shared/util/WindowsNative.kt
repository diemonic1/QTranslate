package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/*
 * The few Windows functions this application calls directly, bound with plain JNA.
 *
 * jna-platform has all of these and much more, and that much more is the problem: it put about
 * 1.1 MiB into a release with a size budget, to reach fourteen functions. Declaring them here
 * costs nothing at run time — JNA binds by name either way — and keeps the jar to what is used.
 *
 * Handles (HWND, HKL) are plain Pointers, pointer-sized parameters (WPARAM, LPARAM) too, so the
 * signatures are right on 32-bit and 64-bit alike. Each interface is loaded on first use only, so
 * nothing here touches a native library on another platform.
 */

internal interface User32Native : StdCallLibrary {
    fun GetForegroundWindow(): Pointer?
    fun GetWindowThreadProcessId(window: Pointer, processId: IntByReference?): Int
    fun GetKeyboardLayout(threadId: Int): Pointer?

    /** [list] is a buffer of [count] pointer-sized slots, or null to ask how many there are. */
    fun GetKeyboardLayoutList(count: Int, list: Pointer?): Int
    fun MapVirtualKeyExW(code: Int, mapType: Int, layout: Pointer): Int
    fun ToUnicodeEx(
        virtualKey: Int, scanCode: Int, keyState: ByteArray,
        buffer: CharArray, bufferSize: Int, flags: Int, layout: Pointer
    ): Int
    fun PostMessageW(window: Pointer, message: Int, wParam: Pointer?, lParam: Pointer?): Boolean

    companion object {
        val INSTANCE: User32Native by lazy { Native.load("user32", User32Native::class.java) }
    }
}

internal interface Ole32Native : StdCallLibrary {
    fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
    fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, result: PointerByReference): Int

    companion object {
        const val COINIT_MULTITHREADED = 0x0
        const val CLSCTX_INPROC_SERVER = 0x1
        val INSTANCE: Ole32Native by lazy { Native.load("ole32", Ole32Native::class.java) }
    }
}

internal interface OleAut32Native : StdCallLibrary {
    fun SafeArrayGetLBound(array: Pointer, dimension: Int, bound: IntByReference): Int
    fun SafeArrayGetUBound(array: Pointer, dimension: Int, bound: IntByReference): Int
    fun SafeArrayAccessData(array: Pointer, data: PointerByReference): Int
    fun SafeArrayUnaccessData(array: Pointer): Int
    fun SafeArrayDestroy(array: Pointer): Int

    companion object {
        val INSTANCE: OleAut32Native by lazy { Native.load("oleaut32", OleAut32Native::class.java) }
    }
}

/**
 * A GUID in native memory, laid out as Windows expects it, from its usual braced text form.
 *
 * The first three groups are little-endian integers; the last two are plain bytes in order.
 */
internal fun nativeGuid(text: String): Pointer {
    val hex = text.trim('{', '}').split('-')
    require(hex.size == 5) { "Not a GUID: $text" }
    val memory = Memory(16)
    memory.setInt(0, hex[0].toLong(16).toInt())
    memory.setShort(4, hex[1].toInt(16).toShort())
    memory.setShort(6, hex[2].toInt(16).toShort())
    val tail = hex[3] + hex[4]
    for (i in 0 until 8) memory.setByte(8L + i, tail.substring(i * 2, i * 2 + 2).toInt(16).toByte())
    return memory
}
