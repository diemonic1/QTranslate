package com.github.ahatem.qtranslate.plugins.google


import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.ahatem.qtranslate.plugins.common.fetchJson
import com.github.ahatem.qtranslate.plugins.common.sendJson
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.ahatem.qtranslate.plugins.google.common.translateSingleUrl
import com.github.ahatem.qtranslate.plugins.google.common.TRANSLATE_CLIENT_ID
import com.github.ahatem.qtranslate.plugins.google.common.OfficialTranslateResponse
import com.github.ahatem.qtranslate.plugins.google.common.TranslateResponse
import com.github.michaelbull.result.Result
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.toResultOr

class GoogleTranslatorService(
    private val pluginContext: PluginContext,
    private val settings: GoogleSettings,
    private val httpClient: HttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig
) : Translator {


    override val key: String = "google-translator"
    override val name: String = "Google Translate"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/google-translate-icon.svg"

    private val officialParser = createJsonParser<OfficialTranslateResponse>(pluginContext)
    private val translateParser = createJsonParser<TranslateResponse>(pluginContext)

    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    companion object {
        private const val TRANSLATE_FALLBACK = "https://clients5.google.com/translate_a/t"
        private const val TRANSLATE_OFFICIAL = "https://translation.googleapis.com/language/translate/v2"
        private val TRANSLATE_FEATURES = listOf("t", "bd", "at", "ex", "ld", "md", "rw", "rm", "ss", "qc")
    }

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        languageMapper.getSupportedLanguages()

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        if (settings.translateApiKey.isNotBlank()) {
            pluginContext.logger.info("Using official Google Translate API")
            val officialResult = translateWithOfficialAPI(request)
            if (officialResult.isOk) return officialResult
            pluginContext.logger.info("Official API failed, falling back to unofficial endpoint")
        }
        return translateWithUnofficialAPI(request)
    }

    private suspend fun translateWithOfficialAPI(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val sourceTag = languageMapper.toProviderCode(request.sourceLanguage)
        val targetTag = languageMapper.toProviderCode(request.targetLanguage)

        val requestBody = mapOf(
            "q" to request.text,
            "source" to sourceTag,
            "target" to targetTag,
            "format" to "text"
        )

        val responseString = httpClient.sendJson(
            url = TRANSLATE_OFFICIAL,
            headers = apiConfig.createJsonHeaders(),
            body = requestBody,
            queryParams = mapOf("key" to settings.translateApiKey)
        ).bind()

        val parsed = officialParser.parse(responseString).bind()
        val firstTranslation = parsed.data.translations.firstOrNull()
            .toResultOr { ServiceError.InvalidResponseError("No translation in response", null) }
            .bind()

        TranslationResponse(
            translatedText = firstTranslation.translatedText,
            detectedLanguage = firstTranslation.detectedSourceLanguage?.let {
                languageMapper.fromProviderCode(it)
            }
        )
    }

    private suspend fun translateWithUnofficialAPI(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> {
        val sourceTag = languageMapper.toProviderCode(request.sourceLanguage)
        val targetTag = languageMapper.toProviderCode(request.targetLanguage)

        val primaryResult = tryPrimaryEndpoint(request.text, sourceTag, targetTag)
        if (primaryResult.isOk) return primaryResult

        pluginContext.logger.info("Primary endpoint failed, trying fallback")
        return tryFallbackEndpoint(request.text, sourceTag, targetTag)
    }

    private suspend fun tryPrimaryEndpoint(
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val responseString = httpClient.get(
            url = translateSingleUrl(settings.translateHost),
            headers = apiConfig.createHeaders(),
            queryParams = mapOf(
                "client" to TRANSLATE_CLIENT_ID,
                "ie" to "UTF-8",
                "oe" to "UTF-8",
                "dj" to 1,
                "dt" to TRANSLATE_FEATURES,
                "sl" to sourceTag,
                "tl" to targetTag,
                "q" to text
            )
        ).bind()

        val parsed = translateParser.parse(responseString).bind()
        val translatedText = parsed.sentences.joinToString("") { it.text.orEmpty() }
        val detectedLang = languageMapper.fromProviderCode(parsed.sourceLanguage)
        val alternatives = parsed.dictionary?.firstOrNull()?.terms?.take(3) ?: emptyList()

        TranslationResponse(
            translatedText = translatedText.trim(),
            detectedLanguage = detectedLang,
            alternatives = alternatives
        )
    }

    private suspend fun tryFallbackEndpoint(
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val parsed: JsonArray = httpClient.fetchJson<JsonArray>(
            url = TRANSLATE_FALLBACK,
            headers = apiConfig.createHeaders(),
            queryParams = mapOf(
                "client" to TRANSLATE_CLIENT_ID,
                "dj" to 1,
                "sl" to sourceTag,
                "tl" to targetTag,
                "q" to text
            )
        ).bind()

        val (translated, detectedTag) = readFallbackTranslation(parsed)

        val translatedText = translated
            .toResultOr { ServiceError.InvalidResponseError("No translation in fallback response", null) }
            .bind()

        val detectedLang = detectedTag?.let { languageMapper.fromProviderCode(it) }

        TranslationResponse(
            translatedText = translatedText.trim(),
            detectedLanguage = detectedLang
        )
    }

    /**
     * Reads the translation and detected language out of whichever shape the fallback returned.
     *
     * This endpoint answers in two different shapes depending on the request, and the difference
     * is not documented anywhere:
     *
     * ```
     * sl=auto  ->  [["transcription","de"]]   nested, carries the detected language
     * sl=de    ->  ["transcription"]          flat, no language to report
     * ```
     *
     * Only the nested form was handled, and the type it was decoded into made the flat one a
     * parse failure rather than a missing field — so naming a source language explicitly, instead
     * of leaving it on auto-detect, silently cost the user the fallback entirely. It went
     * unnoticed because auto-detect is the default, and it only bites once the primary endpoint
     * has already failed, which is exactly when the fallback is the last thing standing.
     *
     * Decoded as a [JsonArray] rather than a typed shape because the two forms have no common
     * static type: the outer array holds a string in one and an array in the other.
     */
    private fun readFallbackTranslation(parsed: JsonArray): Pair<String?, String?> =
        when (val first = parsed.firstOrNull()) {
            is JsonArray -> first.getOrNull(0)?.contentOrNull() to first.getOrNull(1)?.contentOrNull()
            is JsonPrimitive -> first.contentOrNull() to null
            else -> null to null
        }

    /** The element's text, or null when it is not a plain string — `JSON null` included. */
    private fun JsonElement.contentOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

}