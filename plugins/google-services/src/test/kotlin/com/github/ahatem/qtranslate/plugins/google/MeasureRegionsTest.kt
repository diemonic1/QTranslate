package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.ahatem.qtranslate.plugins.google.common.TRANSLATE_HOSTS
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The measure action is the only way anyone can tell which of seventeen hostnames to pick, so its
 * report has to be right about which one won and honest about the ones that did not answer.
 */
class MeasureRegionsTest {

    @Test
    fun `the quickest region is reported first`() {
        val report = GoogleSettings()
            .attach { StubClient(slowHosts = mapOf("translate.googleapis.com" to 120L)) }
            .measureRegions()

        val firstListed = report.lines().first { it.startsWith("    ") }.trim()
        assertTrue(
            firstListed.startsWith("translate.google.com"),
            "Expected the quickest region at the top, got: $firstListed"
        )
    }

    @Test
    fun `regions that do not answer are named rather than silently dropped`() {
        val report = GoogleSettings()
            .attach { StubClient(failingHosts = setOf("translate.google.tr", "translate.google.ua")) }
            .measureRegions()

        assertTrue("Unreachable from here:" in report, "Missing the unreachable line in:\n$report")
        assertTrue("translate.google.tr" in report.substringAfter("Unreachable from here:"))
        assertTrue("translate.google.ua" in report.substringAfter("Unreachable from here:"))
    }

    @Test
    fun `the selected region is always reported, even when it is not among the quickest`() {
        val slowest = TRANSLATE_HOSTS.last()
        val report = GoogleSettings(translateHost = slowest)
            .attach { StubClient(slowHosts = mapOf(slowest to 400L)) }
            .measureRegions()

        assertTrue(
            "Currently selected: $slowest" in report,
            "A user whose region is slow needs to see its number to know it is worth changing:\n$report"
        )
    }

    @Test
    fun `a region already at the top is said to be, rather than shown a redundant number`() {
        val report = GoogleSettings(translateHost = "translate.google.ru")
            .attach { StubClient(slowHosts = TRANSLATE_HOSTS.filter { it != "translate.google.ru" }.associateWith { 200L }) }
            .measureRegions()

        assertTrue(
            "already the quickest" in report,
            "Expected the no-change-needed wording:\n$report"
        )
    }

    @Test
    fun `every offered region is measured, not just the selected one`() {
        val client = StubClient()
        GoogleSettings().attach { client }.measureRegions()

        assertEquals(
            TRANSLATE_HOSTS.toSet(),
            client.asked.keys.toSet(),
            "A region left unmeasured is one the user can never discover"
        )
    }

    @Test
    fun `a total blackout says so instead of reporting an empty ranking`() {
        val report = GoogleSettings()
            .attach { StubClient(failingHosts = TRANSLATE_HOSTS.toSet()) }
            .measureRegions()

        assertTrue("No region answered" in report, "Got:\n$report")
    }

    @Test
    fun `an unattached settings object explains itself rather than throwing`() {
        val report = GoogleSettings().measureRegions()

        assertTrue("not initialized" in report, "Got: $report")
    }
}

/**
 * Answers every host, optionally slowly or not at all.
 *
 * The delays are real suspensions rather than a virtual clock because [GoogleSettings.measureRegions]
 * blocks internally and times with a wall clock; they are kept small enough not to slow the suite.
 */
private class StubClient(
    private val slowHosts: Map<String, Long> = emptyMap(),
    private val failingHosts: Set<String> = emptySet()
) : TextHttpClient() {

    val asked = ConcurrentHashMap<String, Int>()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        val host = url.removePrefix("https://").substringBefore('/')
        asked.merge(host, 1, Int::plus)

        if (host in failingHosts) return Err(ServiceError.NetworkError("unreachable"))
        slowHosts[host]?.let { delay(it) }
        return Ok("""{"sentences":[{"trans":"transcription"}],"src":"de"}""")
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = get(url, headers, queryParams)
}
