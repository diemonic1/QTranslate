package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.ahatem.qtranslate.plugins.google.common.TRANSLATE_CLIENT_ID
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class GoogleTranslatorServiceTest {

    private fun service(client: ScriptedHttpClient) =
        GoogleTranslatorService(
            pluginContext = FakePluginContext(),
            settings = GoogleSettings(),
            httpClient = client,
            languageMapper = GoogleLanguageMapper,
            apiConfig = ApiConfig()
        )

    private fun request(source: LanguageCode = LanguageCode.GERMAN) = TranslationRequest(
        text = "Transkription",
        sourceLanguage = source,
        targetLanguage = LanguageCode.ENGLISH
    )

    @Test
    fun `the primary endpoint is asked under the client identity Google still answers`() = runBlocking {
        val client = ScriptedHttpClient(listOf(Ok(PRIMARY_BODY)))

        service(client).translate(request()).fold(
            success = { assertEquals("transcription", it.translatedText) },
            failure = { fail(it.message) }
        )

        // The identity is the whole point of the constant: gtx is refused with 429 for entire IP
        // ranges, and a request that names it never gets as far as being parsed.
        assertEquals(TRANSLATE_CLIENT_ID, client.calls.first().queryParams["client"])
        assertTrue(client.calls.first().url.endsWith("/translate_a/single"))
    }

    @Test
    fun `the fallback reads the nested shape auto-detection returns`() = runBlocking {
        // sl=auto answers [["transcription","de"]] — the translation with the language it detected.
        val client = ScriptedHttpClient(
            listOf(
                Err(ServiceError.RateLimitError("primary refused")),
                Ok("""[["transcription","de"]]""")
            )
        )

        service(client).translate(request(LanguageCode.AUTO)).fold(
            success = {
                assertEquals("transcription", it.translatedText)
                assertEquals(LanguageCode.GERMAN, it.detectedLanguage)
            },
            failure = { fail(it.message) }
        )
    }

    @Test
    fun `the fallback reads the flat shape an explicit source language returns`() = runBlocking {
        // sl=de answers ["transcription"] instead, which used to be a parse failure — so naming a
        // source language cost the user the fallback at the moment it was the last thing standing.
        val client = ScriptedHttpClient(
            listOf(
                Err(ServiceError.RateLimitError("primary refused")),
                Ok("""["transcription"]""")
            )
        )

        service(client).translate(request(LanguageCode.GERMAN)).fold(
            success = {
                assertEquals("transcription", it.translatedText)
                assertEquals(null, it.detectedLanguage, "The flat shape carries no language to report")
            },
            failure = { fail("The flat shape must be accepted, but failed with: ${it.message}") }
        )
    }

    @Test
    fun `an empty fallback body is an error rather than an empty translation`() = runBlocking {
        val client = ScriptedHttpClient(
            listOf(
                Err(ServiceError.RateLimitError("primary refused")),
                Ok("""[]""")
            )
        )

        service(client).translate(request()).fold(
            success = { fail("An empty array is not a translation, but was accepted as '${it.translatedText}'") },
            failure = { assertTrue(it is ServiceError.InvalidResponseError, "Got ${it::class.simpleName}") }
        )
        Unit
    }

    @Test
    fun `a fallback body of the wrong shape fails instead of yielding a JSON fragment`() = runBlocking {
        val client = ScriptedHttpClient(
            listOf(
                Err(ServiceError.RateLimitError("primary refused")),
                Ok("""[[null]]""")
            )
        )

        service(client).translate(request()).fold(
            success = { fail("Expected a failure, got '${it.translatedText}'") },
            failure = { assertTrue(it is ServiceError.InvalidResponseError, "Got ${it::class.simpleName}") }
        )
        Unit
    }

    @Test
    fun `the fallback is only reached after the primary has failed`() = runBlocking {
        val client = ScriptedHttpClient(listOf(Ok(PRIMARY_BODY)))

        service(client).translate(request())

        assertEquals(1, client.calls.size, "A working primary must not cost a second request")
    }

    private companion object {
        val PRIMARY_BODY = """{"sentences":[{"trans":"transcription","orig":"Transkription"}],"src":"de"}"""
    }
}

private class ScriptedHttpClient(
    private val responses: List<Result<String, ServiceError>>
) : TextHttpClient() {

    data class Call(val url: String, val queryParams: Map<String, Any?>)

    val calls = mutableListOf<Call>()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        calls += Call(url, queryParams)
        return responses.getOrNull(calls.size - 1)
            ?: error("Unscripted request #${calls.size} to $url")
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = get(url, headers, queryParams)
}
