package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorAppearance
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle

data class TranslatorSelectorState(
    val availableTranslators: List<ServiceInfo>,
    val selectedTranslatorId: String?,
    val isLoading: Boolean,
    val availableServices: List<ServiceInfo> = availableTranslators,
    val selectedServices: Map<ServiceRole, String?> = selectedTranslatorId?.let {
        mapOf(ServiceRole.TRANSLATOR to it)
    }.orEmpty(),
    val style: ServiceSelectorStyle = ServiceSelectorStyle.CLASSIC,
    val appearance: ServiceSelectorAppearance = ServiceSelectorAppearance.ICONS_AND_TEXT,
    /**
     * The already-localized latency note to show beside each translator's name, keyed by service
     * id. A service absent from the map gets no note.
     *
     * Held as finished text rather than as
     * [com.github.ahatem.qtranslate.core.main.domain.model.ServiceLatency] so this widget needs no
     * localizer of its own: the one place that knows how to phrase "Response time: 342 ms" in the
     * user's language is the view that already holds the localization manager.
     */
    val latencyNotes: Map<String, String> = emptyMap()
) : UiState
