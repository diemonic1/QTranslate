package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceLatency
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The probe exists to answer one question — "will this service translate the next thing I type,
 * and how long will I wait" — and every rule here is a way of getting that answer wrong.
 */
class MeasureServiceLatencyUseCaseTest {

    @Test
    fun `a service that returns the expected translation is timed`() = runTest {
        val results = probe("good" to FakeTranslator { "transcription" })

        val latency = results.getValue("good")
        assertIs<ServiceLatency.Answered>(latency)
        assertTrue(latency.millis >= 0, "A measured latency cannot be negative")
    }

    @Test
    fun `casing and surrounding whitespace do not condemn a working service`() = runTest {
        val results = probe(
            "capitalised" to FakeTranslator { "Transcription" },
            "padded" to FakeTranslator { "  transcription\n" }
        )

        assertIs<ServiceLatency.Answered>(results.getValue("capitalised"))
        assertIs<ServiceLatency.Answered>(results.getValue("padded"))
    }

    @Test
    fun `a fast reply with the wrong word is a failure, not a good score`() = runTest {
        val results = probe("wrong" to FakeTranslator { "transcript" })

        assertEquals(ServiceLatency.Failed, results.getValue("wrong"))
    }

    @Test
    fun `a service that errors fails rather than reporting the time it took to refuse`() = runTest {
        val results = probe("broken" to FakeTranslator { error("unreachable") })

        assertEquals(ServiceLatency.Failed, results.getValue("broken"))
    }

    @Test
    fun `a service slower than the deadline fails`() = runTest {
        // runTest's virtual clock skips the wait, so the seven seconds cost nothing to assert.
        val results = probe("slow" to FakeTranslator(delayMs = 7_001) { "transcription" })

        assertEquals(ServiceLatency.Failed, results.getValue("slow"))
    }

    @Test
    fun `an id with no translator behind it fails instead of throwing`() = runTest {
        val useCase = MeasureServiceLatencyUseCase(MutableStateFlow(emptyMap()), NoOpLoggerFactory)
        val results = ConcurrentHashMap<String, ServiceLatency>()

        useCase(listOf("absent")) { id, latency -> results[id] = latency }

        assertEquals(ServiceLatency.Failed, results.getValue("absent"))
    }

    @Test
    fun `the probe asks German to English, which is the pair the expected word belongs to`() = runTest {
        val translator = FakeTranslator { "transcription" }
        probe("recorder" to translator)

        val request = translator.lastRequest!!
        assertEquals("Transkription", request.text)
        assertEquals(LanguageCode.GERMAN, request.sourceLanguage)
        assertEquals(LanguageCode.ENGLISH, request.targetLanguage)
    }

    @Test
    fun `one slow service does not hold up the results of the others`() = runTest {
        val order = mutableListOf<String>()
        val useCase = MeasureServiceLatencyUseCase(
            MutableStateFlow(
                mapOf(
                    "slow" to FakeTranslator(delayMs = 3_000) { "transcription" },
                    "quick" to FakeTranslator(delayMs = 10) { "transcription" }
                )
            ),
            NoOpLoggerFactory
        )

        useCase(listOf("slow", "quick")) { id, _ -> order += id }

        assertEquals(listOf("quick", "slow"), order, "Results should arrive as they land, not in call order")
    }

    // -------------------------------------------------------------------------

    private suspend fun probe(vararg services: Pair<String, Service>): Map<String, ServiceLatency> {
        val useCase = MeasureServiceLatencyUseCase(MutableStateFlow(services.toMap()), NoOpLoggerFactory)
        val results = ConcurrentHashMap<String, ServiceLatency>()
        useCase(services.map { it.first }) { id, latency -> results[id] = latency }
        return results
    }

    private class FakeTranslator(
        private val delayMs: Long = 0,
        private val reply: () -> String
    ) : Translator {
        override val key = "fake-translator"
        override val name = "Fake Translator"
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All

        var lastRequest: TranslationRequest? = null
            private set

        override suspend fun translate(
            request: TranslationRequest
        ): Result<TranslationResponse, ServiceError> {
            lastRequest = request
            if (delayMs > 0) delay(delayMs)
            return runCatching { reply() }
                .fold(
                    onSuccess = { Ok(TranslationResponse(it)) },
                    onFailure = { Err(ServiceError.NetworkError(it.message.orEmpty())) }
                )
        }
    }

    private object NoOpLoggerFactory : LoggerFactory {
        override fun getLogger(name: String): Logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, error: Throwable?) = Unit
        }
    }
}
