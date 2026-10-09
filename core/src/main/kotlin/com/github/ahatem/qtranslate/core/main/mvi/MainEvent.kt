package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.arch.UiEvent
import java.io.File

/**
 * One-shot events emitted by [MainStore] to be consumed exactly once by the UI.
 */
sealed interface MainEvent : UiEvent {

    /**
     * Instructs the UI to paste [translatedText] back, replacing the previously
     * selected text. Emitted after [MainIntent.ReplaceWithTranslation] completes.
     */
    data class PasteTranslation(val translatedText: String) : MainEvent

    /**
     * Instructs the UI to move the system keyboard layout on to the next installed one.
     *
     * Sent alongside a [PasteTranslation] carrying the retyped text, because fixing the text
     * without fixing the layout leaves the user about to carry on typing in the very layout
     * that produced the mistake.
     *
     * Deliberately names no layout to switch *to*. Picking one would be more precise with
     * three or more installed, but this is meant to be the same request Win+Space makes, and
     * with two layouts "the next one" is always the other one.
     */
    data object SwitchSystemKeyboardLayout : MainEvent

    data class ShowUpdateDialog(
        val newVersion: String,
        val currentVersion: String,
        val releaseNotes: String,
        val downloadUrl: String?,
        val releaseUrl: String? = null
    ) : MainEvent

    data class UpdateStatusBar(
        val code: StatusCode,
        val type: NotificationType = NotificationType.INFO,
        val isTemporary: Boolean = true
    ) : MainEvent

    /**
     * Instructs the UI to copy [text] to the system clipboard. The UI owns the clipboard.
     */
    data class CopyToClipboard(val text: String) : MainEvent

    data class DocumentTranslationCompleted(val outputFile: File) : MainEvent

    data class DocumentTranslationFailed(val message: String) : MainEvent
}
