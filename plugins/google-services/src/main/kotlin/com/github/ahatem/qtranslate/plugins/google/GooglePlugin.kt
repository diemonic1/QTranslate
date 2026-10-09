package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.settings.PluginAction
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.ahatem.qtranslate.plugins.google.common.DEFAULT_TRANSLATE_HOST
import com.github.ahatem.qtranslate.plugins.google.common.TRANSLATE_HOSTS
import com.github.ahatem.qtranslate.plugins.google.common.TRANSLATE_CLIENT_ID
import com.github.ahatem.qtranslate.plugins.google.common.TRANSLATE_HOST_OPTIONS
import com.github.ahatem.qtranslate.plugins.google.common.normalizeTranslateHost
import com.github.ahatem.qtranslate.plugins.google.common.translateSingleUrl
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.github.michaelbull.result.Result

class GooglePlugin : Plugin<GoogleSettings> {

    private lateinit var pluginContext: PluginContext
    private val httpClient: HttpClient get() = pluginContext.http

    private lateinit var settings: GoogleSettings
    private var activeServices: List<Service> = emptyList()

    private val languageMapper = GoogleLanguageMapper
    private val apiConfig = ApiConfig()

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        this.pluginContext = context
        // Restore persisted keys on first load so the user doesn't have to re-enter them.
        this.settings = GoogleSettings(
            visionApiKey = context.secrets.get("visionApiKey") ?: "",
            translateApiKey = context.secrets.get("translateApiKey") ?: "",
            // Not a secret, so it lives in plugin settings rather than the key store. Read back
            // through the same normalisation the services use, so a value stored by an older
            // build — or edited by hand — cannot leave the plugin pointed at nothing.
            translateHost = normalizeTranslateHost(context.settings.getString(KEY_TRANSLATE_HOST))
        )
        this.settings.attach { httpClient }
        pluginContext.logger.info("Google Plugin initialized")
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        pluginContext.logger.info("Enabling Google services")
        buildServices()
        return Ok(Unit)
    }

    override suspend fun onSettingsChanged(settings: GoogleSettings): Result<Unit, ServiceError> {
        pluginContext.logger.info("Applying new Google settings...")
        // Persist both keys so they survive app restarts.
        pluginContext.secrets.put("visionApiKey", settings.visionApiKey)
        pluginContext.secrets.put("translateApiKey", settings.translateApiKey)
        pluginContext.settings.put(KEY_TRANSLATE_HOST, normalizeTranslateHost(settings.translateHost))
        this.settings = settings
        this.settings.attach { httpClient }
        // Rebuild services — OCR is conditionally included based on the vision key.
        buildServices()
        return Ok(Unit)
    }

    private fun buildServices() {
        // One health tracker for the primary endpoint, shared by the translator and spell checker so
        // they do not each rediscover that it is throttled.
        val endpointHealth = GoogleEndpointHealth()
        activeServices = buildList {
            // OCR requires a Vision API key — only register the service when one is configured.
            if (settings.visionApiKey.isNotBlank()) {
                add(GoogleOCRService(pluginContext, settings, httpClient, languageMapper, apiConfig))
            }
            add(GoogleTranslatorService(pluginContext, settings, httpClient, languageMapper, apiConfig, endpointHealth))
            add(GoogleTTSService(pluginContext, settings, httpClient, languageMapper, apiConfig))
            add(GoogleDictionaryService(pluginContext, settings, httpClient, languageMapper, apiConfig))
            add(GoogleSpellCheckerService(pluginContext, settings, httpClient, languageMapper, apiConfig, endpointHealth))
        }
        pluginContext.logger.info("Built ${activeServices.size} active Google services.")
    }

    override suspend fun onDisable() {
        pluginContext.logger.info("Disabling Google services")
        activeServices = emptyList()
    }

    override suspend fun shutdown() {
        pluginContext.logger.info("Google Plugin shutting down")
    }

    override fun getServices(): List<Service> = activeServices

    override fun getSettings(): GoogleSettings = settings

    private companion object {
        const val KEY_TRANSLATE_HOST = "translateHost"
    }
}

/**
 * User-configurable settings for the Google Plugin.
 * Extends [PluginSettings.Configurable] so the core can reflect on the @Setting
 * annotations and auto-generate the settings UI — no Swing code needed here.
 *
 * Both keys are optional at the data-class level. The Vision key is required only
 * when the user wants OCR; its absence simply excludes that service from the registry.
 */
data class GoogleSettings(
    @field:Setting(
        label = "Vision API Key (for OCR)",
        description = "Your Google Cloud Vision API key. Required for image text recognition (OCR). Leave blank to disable OCR.",
        type = SettingType.PASSWORD,
        order = 10
    )
    var visionApiKey: String = "",

    @field:Setting(
        label = "Translation API Key (optional)",
        description = "An optional Google Cloud Translation API key. If provided, it grants higher usage quotas than the free endpoint.",
        type = SettingType.PASSWORD,
        order = 20
    )
    var translateApiKey: String = "",

    @field:Setting(
        label = "Server region",
        description = "Which Google host to send requests to. Every region returns the same " +
            "translation — only the round trip differs, and it can differ by several times. " +
            "Use Measure response time to find the quickest one from here.",
        type = SettingType.DROPDOWN,
        options = TRANSLATE_HOST_OPTIONS,
        defaultValue = DEFAULT_TRANSLATE_HOST,
        order = 30
    )
    var translateHost: String = DEFAULT_TRANSLATE_HOST
) : PluginSettings.Configurable() {

    /**
     * The HTTP client the measure action borrows.
     *
     * A settings object is built by reflection from the form and has no dependencies of its own,
     * so an action that needs the network has to be handed one. Kept as a provider rather than a
     * client because the host application replaces the client when proxy or timeout settings
     * change, and a captured instance would go on using the old configuration.
     */
    @Transient
    private var clientProvider: (() -> HttpClient)? = null

    internal fun attach(clientProvider: () -> HttpClient): GoogleSettings = apply {
        this.clientProvider = clientProvider
    }

    /**
     * Times every offered region and reports the quickest.
     *
     * Which region is fastest is a property of the user's network, not of Google, so there is no
     * right answer to bake in and no way for someone to find theirs except by measuring. Without
     * this the picker would be a list of seventeen indistinguishable hostnames.
     *
     * All regions are timed at once. Run one after another the slowest would decide how long the
     * whole thing takes, and a region that has to time out would hold up sixteen that already
     * answered.
     */
    @PluginAction(
        label = "Measure response time",
        order = 40,
        tooltip = "Sends one translation to every region and reports the quickest from this machine."
    )
    fun measureRegions(): String = runBlocking {
        val client = clientProvider?.invoke()
            ?: return@runBlocking "Google Services is not initialized yet."

        val timings = coroutineScope {
            TRANSLATE_HOSTS.map { host -> async { host to timeRegion(client, host) } }.awaitAll()
        }

        val (answered, unreachable) = timings.partition { it.second != null }
        if (answered.isEmpty()) {
            return@runBlocking "No region answered. The network or a proxy is likely blocking Google."
        }

        val ranked = answered.sortedBy { it.second }
        val current = normalizeTranslateHost(translateHost)

        buildString {
            appendLine("Quickest from here:")
            ranked.take(5).forEach { (host, ms) -> appendLine("    $host — $ms ms") }

            val currentEntry = ranked.firstOrNull { it.first == current }
            when {
                currentEntry == null -> appendLine("Currently selected: $current — no answer")
                currentEntry === ranked.first() -> appendLine("Currently selected: $current — already the quickest")
                else -> appendLine("Currently selected: $current — ${currentEntry.second} ms")
            }

            if (unreachable.isNotEmpty()) {
                appendLine("Unreachable from here: " + unreachable.joinToString { it.first })
            }
        }.trimEnd()
    }

    /** One real translation against [host], or null when it fails or takes too long. */
    private suspend fun timeRegion(client: HttpClient, host: String): Long? {
        val startedAt = System.nanoTime()
        val answered = withTimeoutOrNull(MEASURE_TIMEOUT_MS) {
            client.get(
                url = translateSingleUrl(host),
                queryParams = mapOf(
                    "client" to TRANSLATE_CLIENT_ID,
                    "dj" to 1,
                    "dt" to "t",
                    "sl" to "de",
                    "tl" to "en",
                    "q" to "Transkription"
                )
            ).component1() != null
        } ?: false

        return if (answered) (System.nanoTime() - startedAt) / 1_000_000 else null
    }

    private companion object {
        /**
         * Short on purpose. This measures which region is quick, and a region that needs longer
         * than this has already lost — waiting the full request timeout for it would only make
         * the user sit through the answer they were never going to pick.
         */
        const val MEASURE_TIMEOUT_MS = 5_000L
    }
}