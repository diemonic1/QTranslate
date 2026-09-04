package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which language a translation comes out in.
 *
 * The rules feature makes this a decision rather than a setting, and the decision has to survive
 * being made repeatedly: each translation's target depends on what the last one did, so a mistake
 * here does not show up until the third or fourth thing somebody translates. That is exactly how
 * the bug these tests were written for reached users.
 */
class TranslateTargetResolutionTest {

    private val directory = Files.createTempDirectory("qtranslate-target-test").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    // -------------------------------------------------------------------------

    @Test
    fun `a rule's target does not become the standing choice for the next translation`() = runBlocking {
        // Default target Russian, plus the rule "Russian goes to English".
        val harness = harness(
            config(preferredTarget = "ru", rules = listOf(TranslationRule("ru", "en")))
        )

        // 1. English text — no rule matches, so it goes to the default target.
        harness.translate(detectedAs = LanguageCode.ENGLISH)
        assertEquals(LanguageCode.RUSSIAN, harness.lastTarget(), "English should go to the default target")

        // 2. Russian text — the rule matches and sends it to English.
        harness.translate(detectedAs = LanguageCode.RUSSIAN)
        assertEquals(LanguageCode.ENGLISH, harness.lastTarget(), "The rule should send Russian to English")

        // 3. English again. This is where it used to break: the target the rule left behind was
        //    still standing, so English was translated into English.
        harness.translate(detectedAs = LanguageCode.ENGLISH)
        assertEquals(
            LanguageCode.RUSSIAN,
            harness.lastTarget(),
            "The rule's target must not carry over — English should go back to the default target"
        )
    }

    @Test
    fun `a target the user picked does carry over`() = runBlocking {
        val harness = harness(config(preferredTarget = "ru"))

        // The user picks French by hand. Choosing a target in the app also saves it as the
        // preference, so both move together.
        harness.settings.value = config(preferredTarget = "fr")
        harness.state = harness.state.copy(targetLanguage = LanguageCode.FRENCH, isTargetFromRule = false)

        harness.translate(detectedAs = LanguageCode.ENGLISH)

        assertEquals(LanguageCode.FRENCH, harness.lastTarget(), "A hand-picked target is the standing choice")
    }

    @Test
    fun `text already in the target language goes to the secondary language instead`() = runBlocking {
        // Target Russian, secondary English, and no rules at all.
        val harness = harness(config(preferredTarget = "ru", secondaryTarget = "en"))

        harness.translate(detectedAs = LanguageCode.RUSSIAN)

        assertEquals(
            LanguageCode.ENGLISH,
            harness.lastTarget(),
            "Russian into Russian returns the input unchanged; the secondary language is the answer"
        )
    }

    @Test
    fun `the secondary language does not interfere when the source differs from the target`() = runBlocking {
        val harness = harness(config(preferredTarget = "ru", secondaryTarget = "en"))

        harness.translate(detectedAs = LanguageCode.FRENCH)

        assertEquals(LanguageCode.RUSSIAN, harness.lastTarget())
    }

    @Test
    fun `the secondary language is skipped when it is also the source`() = runBlocking {
        // Nothing sensible is left: both configured targets are the language being translated.
        // Better a visible no-op than silently picking a language nobody named.
        val harness = harness(config(preferredTarget = "ru", secondaryTarget = "ru"))

        harness.translate(detectedAs = LanguageCode.RUSSIAN)

        assertEquals(LanguageCode.RUSSIAN, harness.lastTarget())
    }

    @Test
    fun `a rule still wins over the standing choice`() = runBlocking {
        val harness = harness(
            config(preferredTarget = "ru", rules = listOf(TranslationRule("de", "fr")))
        )

        harness.translate(detectedAs = LanguageCode.GERMAN)

        assertEquals(LanguageCode.FRENCH, harness.lastTarget())
    }

    @Test
    fun `the secondary language applies to a rule target too`() = runBlocking {
        // A rule that points a language at itself is a user error, but it should not produce a
        // translation of a text into the language it is already in.
        val harness = harness(
            config(
                preferredTarget = "en",
                secondaryTarget = "fr",
                rules = listOf(TranslationRule("de", "de"))
            )
        )

        harness.translate(detectedAs = LanguageCode.GERMAN)

        assertEquals(LanguageCode.FRENCH, harness.lastTarget())
    }

    // -------------------------------------------------------------------------

    private fun config(
        preferredTarget: String,
        secondaryTarget: String = "en",
        rules: List<TranslationRule> = emptyList()
    ) = Configuration(
        preferredTargetLanguage = preferredTarget,
        secondaryTargetLanguage = secondaryTarget,
        preferredSourceLanguage = "auto",
        translationRules = rules,
        isHistoryEnabled = false
    )

    private fun harness(initial: Configuration): Harness {
        val settings = MutableStateFlow(initial)
        val translator = RecordingTranslator()
        val services = MutableStateFlow<Map<String, Service>>(mapOf("test" to translator))
        val activeServiceManager = ActiveServiceManager(services, settings)
        val loggerFactory = object : LoggerFactory {
            override fun getLogger(name: String): Logger = NoOpLogger
        }

        val useCase = TranslateTextUseCase(
            scope = CoroutineScope(Dispatchers.Unconfined),
            settingsState = settings,
            activeServiceManager = activeServiceManager,
            historyRepository = HistoryRepository(directory, NoOpLogger, Json),
            summarizeUseCase = SummarizeUseCase(activeServiceManager, loggerFactory),
            rewriteUseCase = RewriteUseCase(activeServiceManager, loggerFactory),
            loggerFactory = loggerFactory
        )

        return Harness(
            useCase = useCase,
            translator = translator,
            settings = settings,
            state = MainState(
                inputText = "text to translate",
                sourceLanguage = LanguageCode.AUTO,
                targetLanguage = LanguageCode(initial.preferredTargetLanguage)
            )
        )
    }

    private class Harness(
        private val useCase: TranslateTextUseCase,
        val translator: RecordingTranslator,
        val settings: MutableStateFlow<Configuration>,
        var state: MainState
    ) {
        /** Runs one translation of text the translator will claim is in [detectedAs]. */
        suspend fun translate(detectedAs: LanguageCode) {
            translator.detects = detectedAs
            useCase(
                getState = { state },
                updateState = { transform -> state = state.transform() },
                onStatusUpdate = { _: StatusCode, _: NotificationType, _: Boolean -> }
            )
        }

        /** The target of the last request the translator actually received. */
        fun lastTarget(): LanguageCode = translator.requests.last().targetLanguage
    }

    private class RecordingTranslator : Translator {
        override val key = "test-translator"
        override val name = "Test Translator"
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All

        var detects: LanguageCode = LanguageCode.ENGLISH
        val requests = mutableListOf<TranslationRequest>()

        override suspend fun translate(
            request: TranslationRequest
        ): Result<TranslationResponse, ServiceError> {
            requests += request
            return Ok(TranslationResponse("translated", detectedLanguage = detects))
        }
    }

    private object NoOpLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
}
