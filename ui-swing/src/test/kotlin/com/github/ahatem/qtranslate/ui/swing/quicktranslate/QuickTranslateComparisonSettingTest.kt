package com.github.ahatem.qtranslate.ui.swing.quicktranslate

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The translate popup compares translators only when asked to.
 *
 * Switching the primary translator keeps the previous one as a comparison member, so a user who
 * never configured comparison can still end up with two translators in their preset. Eligibility
 * alone used to be enough to turn the popup into a comparison board for them.
 */
class QuickTranslateComparisonSettingTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-quick-comparison").toFile(), LanguageTomlParser(), logger)
    }

    /** Two usable translators: enough for comparison to be eligible. */
    private val state = MainState(
        availableServices = listOf(
            ServiceInfo("google", "Google", null, ServiceRole.TRANSLATOR),
            ServiceInfo("bing", "Bing", null, ServiceRole.TRANSLATOR)
        )
    )

    private fun config(comparisonInPopup: Boolean) = Configuration.DEFAULT.copy(
        servicePresets = listOf(
            ServicePreset(
                id = "preset",
                name = "preset",
                selectedServices = mapOf(ServiceRole.TRANSLATOR to "google"),
                comparisonTranslatorIds = listOf("bing")
            )
        ),
        activeServicePresetId = "preset",
        isQuickTranslateComparisonEnabled = comparisonInPopup
    )

    @Test
    fun `comparison in the popup is off unless turned on`() {
        assertFalse(Configuration.DEFAULT.isQuickTranslateComparisonEnabled)
    }

    @Test
    fun `two eligible translators are not enough on their own`() {
        assertFalse(
            buildQuickTranslateDialogState(state, config(comparisonInPopup = false), localizer).comparisonsEnabled,
            "With the setting off the popup shows a single translation, however many are configured"
        )
    }

    @Test
    fun `turned on, an eligible preset compares`() {
        assertTrue(buildQuickTranslateDialogState(state, config(comparisonInPopup = true), localizer).comparisonsEnabled)
    }
}
