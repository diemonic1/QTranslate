package com.github.ahatem.qtranslate.ui.swing.shared.util

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Clipboard contents that Windows should not keep a copy of.
 *
 * ### The problem this exists for
 * Handing text to another application means putting it on the clipboard — there is no other route.
 * The contents can be put back afterwards, and are, but Windows clipboard *history* is a different
 * thing: it records every value the clipboard ever held. Restoring what was there does not remove
 * the entry the write already created, so every translation pasted over a selection, and every
 * sentinel used to detect whether a copy worked, stayed in the user's Win+V list for good — a
 * transcript of their work accumulating somewhere they never asked anything to be written.
 *
 * Windows offers a way to opt out. A clipboard payload carrying these formats is skipped by the
 * history, by cloud sync, and by well-behaved third-party clipboard managers:
 *
 * - `ExcludeClipboardContentFromMonitorProcessing` — asks monitors to ignore the payload entirely
 * - `CanIncludeInClipboardHistory` — a DWORD 0 meaning "not in the history"
 * - `CanUploadToCloudClipboard` — a DWORD 0 meaning "do not sync to other devices"
 *
 * They are advisory. Windows itself honours them; a third-party clipboard manager is free not to.
 *
 * ### Why it is done through the flavour map
 * AWT has no notion of these formats, but it does have a documented way to say "this flavour is
 * that native format": [SystemFlavorMap.addUnencodedNativeForFlavor]. Registering the three names
 * once and offering them alongside the text is enough for AWT's Windows clipboard to write them,
 * with no native code of our own.
 */
object ClipboardPrivacy {

    private val NATIVE_FORMATS = listOf(
        "ExcludeClipboardContentFromMonitorProcessing",
        "CanIncludeInClipboardHistory",
        "CanUploadToCloudClipboard"
    )

    /** A DWORD 0 — "no" to both of the questions the last two formats ask. */
    private val DENIED = byteArrayOf(0, 0, 0, 0)

    private val markerFlavors: List<DataFlavor> by lazy {
        val flavorMap = runCatching {
            SystemFlavorMap.getDefaultFlavorMap() as? SystemFlavorMap
        }.getOrNull() ?: return@lazy emptyList()

        NATIVE_FORMATS.mapNotNull { native ->
            runCatching {
                // A byte stream rather than a String: these formats carry raw bytes, and a text
                // flavour would have AWT encode them.
                val flavor = DataFlavor("application/x-qtranslate-$native; class=java.io.InputStream")
                flavorMap.addUnencodedNativeForFlavor(flavor, native)
                flavorMap.addFlavorForUnencodedNative(native, flavor)
                flavor
            }.getOrNull()
        }
    }

    /**
     * [text] as clipboard contents that ask not to be recorded.
     *
     * Falls back to offering the text alone if the flavour registration failed, so a clipboard
     * write never fails on account of a privacy hint that could not be attached.
     */
    fun unrecordedText(text: String): Transferable = MarkedText(text, markerFlavors)

    private class MarkedText(
        private val text: String,
        private val markers: List<DataFlavor>
    ) : Transferable {

        override fun getTransferDataFlavors(): Array<DataFlavor> =
            (listOf(DataFlavor.stringFlavor) + markers).toTypedArray()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            flavor == DataFlavor.stringFlavor || flavor in markers

        override fun getTransferData(flavor: DataFlavor): Any = when {
            flavor == DataFlavor.stringFlavor -> text
            flavor in markers -> ByteArrayInputStream(DENIED) as InputStream
            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}
