package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.ahatem.qtranslate.plugins.google.common.DEFAULT_TRANSLATE_HOST
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.ahatem.qtranslate.plugins.google.common.TRANSLATE_HOSTS
import com.github.ahatem.qtranslate.plugins.google.common.normalizeTranslateHost
import com.github.ahatem.qtranslate.plugins.google.common.translateSingleUrl
import com.github.ahatem.qtranslate.plugins.google.common.translateTtsUrl
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The region setting exists because the same translation costs several times as long from the
 * wrong host. These cover the ways a stored host can arrive malformed and still have to work, and
 * that every Google service follows the one the user picked.
 */
class TranslateHostTest {

    @Test
    fun `a blank or missing host falls back to the default rather than to nothing`() {
        assertEquals(DEFAULT_TRANSLATE_HOST, normalizeTranslateHost(null))
        assertEquals(DEFAULT_TRANSLATE_HOST, normalizeTranslateHost(""))
        assertEquals(DEFAULT_TRANSLATE_HOST, normalizeTranslateHost("   "))
    }

    @Test
    fun `a host typed as a URL is accepted rather than doubled into an invalid one`() {
        // Naive concatenation would have built "https://https://translate.google.ru/..." and every
        // translation would have failed with a connection error nothing on screen could explain.
        assertEquals("translate.google.ru", normalizeTranslateHost("https://translate.google.ru"))
        assertEquals("translate.google.ru", normalizeTranslateHost("http://translate.google.ru/"))
        assertEquals("translate.google.ru", normalizeTranslateHost("  translate.google.ru  "))
    }

    @Test
    fun `the endpoints are built on the chosen host`() {
        assertEquals("https://translate.google.ru/translate_a/single", translateSingleUrl("translate.google.ru"))
        assertEquals("https://translate.google.ru/translate_tts", translateTtsUrl("translate.google.ru"))
    }

    @Test
    fun `the default is one of the offered regions, so the picker can show what is stored`() {
        assertTrue(
            DEFAULT_TRANSLATE_HOST in TRANSLATE_HOSTS,
            "A default missing from the dropdown would render as an empty selection"
        )
    }

    @Test
    fun `no offered region carries a scheme, a path or stray whitespace`() {
        TRANSLATE_HOSTS.forEach { host ->
            assertEquals(host, normalizeTranslateHost(host), "'$host' is not stored in its normal form")
            assertTrue(host.isNotBlank() && '/' !in host, "'$host' is not a bare hostname")
        }
    }

    @Test
    fun `the translator sends to the configured region`() = runBlocking {
        val client = UrlRecordingHttpClient()

        translator(client, "translate.google.ru").translate(
            TranslationRequest("Transkription", LanguageCode.GERMAN, LanguageCode.ENGLISH)
        )

        assertEquals("https://translate.google.ru/translate_a/single", client.lastUrl)
    }

    @Test
    fun `the dictionary and spell checker follow the same region as the translator`() = runBlocking {
        val settings = GoogleSettings(translateHost = "translate.google.by")

        val dictionaryClient = UrlRecordingHttpClient()
        GoogleDictionaryService(FakePluginContext(), settings, dictionaryClient, GoogleLanguageMapper, ApiConfig())
            .lookup(DictionaryRequest("book", LanguageCode.ENGLISH))

        val spellClient = UrlRecordingHttpClient()
        GoogleSpellCheckerService(FakePluginContext(), settings, spellClient, GoogleLanguageMapper, ApiConfig())
            .check(SpellCheckRequest("teh cat", LanguageCode.ENGLISH))

        // A region that applied to translation alone would leave the dictionary and the spell
        // checker crossing the world while the translator took the short route.
        assertEquals("https://translate.google.by/translate_a/single", dictionaryClient.lastUrl)
        assertEquals("https://translate.google.by/translate_a/single", spellClient.lastUrl)
    }

    @Test
    fun `settings stored before the region existed still resolve to the default`() = runBlocking {
        val client = UrlRecordingHttpClient()

        translator(client, "").translate(
            TranslationRequest("Transkription", LanguageCode.GERMAN, LanguageCode.ENGLISH)
        )

        assertEquals("https://$DEFAULT_TRANSLATE_HOST/translate_a/single", client.lastUrl)
    }

    private fun translator(client: UrlRecordingHttpClient, host: String) = GoogleTranslatorService(
        pluginContext = FakePluginContext(),
        settings = GoogleSettings(translateHost = host),
        httpClient = client,
        languageMapper = GoogleLanguageMapper,
        apiConfig = ApiConfig()
    )
}

private class UrlRecordingHttpClient : TextHttpClient() {
    var lastUrl: String? = null

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        lastUrl = url
        return Ok("""{"sentences":[{"trans":"transcription"}],"src":"de"}""")
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = get(url, headers, queryParams)
}
