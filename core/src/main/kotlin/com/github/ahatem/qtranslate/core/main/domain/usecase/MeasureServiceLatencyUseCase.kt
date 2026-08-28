package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceLatency
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Times every translation service against one known translation, once, when the window opens.
 *
 * ### Why a real translation rather than a ping
 * Reaching the host proves nothing worth knowing. Most of these services answer their front page
 * instantly and their translation endpoint slowly, and several answer both promptly while
 * returning an error body — a missing key, an exhausted quota, a region block. The only question
 * the picker needs answered is "how long until this service gives me a translation", so that is
 * the question asked.
 *
 * [PROBE_TEXT] is German for a word whose English translation is unambiguous, short, and the same
 * across every provider, which is what makes the reply checkable at all. Anything idiomatic would
 * have marked honest services wrong for translating it differently but correctly.
 *
 * ### Why the check is loose
 * Case is ignored and the reply is trimmed, because capitalisation and trailing whitespace vary
 * between providers and neither says anything about whether the service works.
 */
class MeasureServiceLatencyUseCase(
    private val activeServices: StateFlow<Map<String, Service>>,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("MeasureServiceLatencyUseCase")

    /**
     * Probes each of [serviceIds] concurrently, reporting each result through [onResult] as it
     * lands rather than waiting for the slowest.
     *
     * Reported one at a time on purpose: a service that answers in 200ms should say so while a
     * service on its way to the seven-second deadline is still counting. Batching them would make
     * every reading as slow as the worst one.
     */
    suspend operator fun invoke(
        serviceIds: List<String>,
        onResult: (serviceId: String, latency: ServiceLatency) -> Unit
    ): Unit = coroutineScope {
        serviceIds.forEach { serviceId ->
            launch { onResult(serviceId, probe(serviceId)) }
        }
    }

    private suspend fun probe(serviceId: String): ServiceLatency {
        val translator = activeServices.value[serviceId] as? Translator
        if (translator == null) {
            logger.debug("No translator registered under '$serviceId' — not probed")
            return ServiceLatency.Failed
        }

        val request = TranslationRequest(
            text = PROBE_TEXT,
            sourceLanguage = LanguageCode.GERMAN,
            targetLanguage = LanguageCode.ENGLISH
        )

        val startedAt = System.nanoTime()
        val reply = runCatching {
            withTimeoutOrNull(TIMEOUT_MS) { translator.translate(request).get() }
        }.getOrElse { failure ->
            // A cancelled scope is the window closing, not a slow service, and swallowing it here
            // would report every translator as broken on the way out.
            if (failure is CancellationException) throw failure
            logger.warn("Latency probe for '$serviceId' threw: ${failure.message}")
            null
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        if (reply == null) {
            logger.info("Latency probe for '$serviceId' failed or timed out after ${elapsedMs}ms")
            return ServiceLatency.Failed
        }

        val translated = reply.translatedText.trim()
        if (!translated.equals(EXPECTED_TRANSLATION, ignoreCase = true)) {
            logger.info(
                "Latency probe for '$serviceId' answered in ${elapsedMs}ms with " +
                    "\"$translated\" instead of \"$EXPECTED_TRANSLATION\""
            )
            return ServiceLatency.Failed
        }

        logger.debug("Latency probe for '$serviceId' answered correctly in ${elapsedMs}ms")
        return ServiceLatency.Answered(elapsedMs)
    }

    private companion object {
        /**
         * Seven seconds, past which a service is reported as failed.
         *
         * Longer than any translator worth choosing needs, and short enough that the whole list
         * has settled before someone finishes reading it. A service slower than this is not
         * usable for the thing this application does, so there is nothing lost in not timing it
         * precisely.
         */
        const val TIMEOUT_MS = 7_000L

        const val PROBE_TEXT = "Transkription"
        const val EXPECTED_TRANSLATION = "transcription"
    }
}
