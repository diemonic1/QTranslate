package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger

/**
 * Upgrades persisted [Configuration] objects from older schema versions to the current one.
 *
 * ### How to add a migration
 * 1. Increment [CURRENT_VERSION] in [Configuration] (the `configVersion` field default).
 * 2. Add a `when` branch here that transforms the old shape into the new one and bumps
 *    `configVersion` to the next number.
 * 3. The branch runs automatically on next app launch for any user on the old version.
 *
 * ### Invariant
 * [migrate] is idempotent — running it on an already-current config returns it unchanged.
 */
object ConfigMigrator {

    /**
     * The schema version this build writes.
     *
     * Deliberately *not* the default of [Configuration.configVersion], which stays at 1: a file
     * written before the field existed deserializes to that default, and treating it as current
     * would skip every migration it needs. A configuration this build creates from scratch is
     * stamped with this value instead.
     */
    internal const val CURRENT_VERSION = 8

    /** The old defaults, frozen: this is what "the user never changed it" looked like. */
    private const val LEGACY_POPUP_IDLE_SECONDS = 3
    private const val LEGACY_DICTIONARY_IDLE_SECONDS = 8

    /**
     * Applies all pending migrations to [config] in order and returns the result.
     * If the config is already at [CURRENT_VERSION] it is returned as-is.
     */
    fun migrate(config: Configuration, logger: Logger): Configuration {
        var current = config
        while (current.configVersion < CURRENT_VERSION) {
            val from = current.configVersion
            current = applyMigration(current, logger)
            // Guard against a buggy migration that forgets to bump the version.
            if (current.configVersion == from) {
                logger.warn("ConfigMigrator: migration from v$from did not advance configVersion — stopping to prevent an infinite loop")
                break
            }
        }
        return current
    }

    // -------------------------------------------------------------------------
    // Private migration steps
    // -------------------------------------------------------------------------

    private fun applyMigration(config: Configuration, logger: Logger): Configuration =
        when (config.configVersion) {
            1 -> {
                // v1 → v2: inject default bindings for any HotkeyActions that were added after the
                // user's config was first saved (FOCUS_INPUT, FOCUS_OUTPUT, FOCUS_EXTRA_OUTPUT).
                // This is safe to run repeatedly — it only adds bindings that are missing.
                logger.info("ConfigMigrator: migrating v1 → v2 — patching missing hotkey defaults")
                val existingActions = config.hotkeys.map { it.action }.toSet()
                val missingDefaults = HotkeyBinding.DEFAULTS.filter { it.action !in existingActions }
                config.copy(
                    configVersion = 2,
                    hotkeys = config.hotkeys + missingDefaults
                )
            }
            2 -> {
                // v2 → v3: same patch for the actions added after v2 (COPY_TRANSLATION,
                // CLEAR_INPUT, SWAP_LANGUAGES, OPEN_SETTINGS, SHOW_HISTORY,
                // TRANSLATE_DOCUMENT). Without this step existing users would keep their
                // saved hotkey list and never receive the new bindings at all.
                logger.info("ConfigMigrator: migrating v2 → v3 — patching missing hotkey defaults")
                val existingActions = config.hotkeys.map { it.action }.toSet()
                val missingDefaults = HotkeyBinding.DEFAULTS.filter { it.action !in existingActions }
                config.copy(
                    configVersion = 3,
                    hotkeys = config.hotkeys + missingDefaults
                )
            }
            3 -> {
                // v3 → v4: service identifiers changed shape. A service used to declare a globally
                // unique id and the configuration stored it verbatim; the host now composes
                // `pluginId:instanceId:serviceKey`. Without rewriting them, every preset on an
                // existing installation names a service that no longer resolves, and the user's
                // chosen translator silently reverts to whichever one happens to load first.
                logger.info("ConfigMigrator: migrating v3 → v4 — rewriting service ids to their composed form")

                val upgradedPresets = config.servicePresets.map { preset ->
                    preset.copy(
                        selectedServices = preset.selectedServices.mapValues { (_, serviceId) ->
                            serviceId?.let(LegacyServiceIds::upgrade)
                        }
                    )
                }

                // Holds a mix of service ids and `type:CAPABILITY` sentinels; upgrade leaves the
                // sentinels alone.
                val upgradedDisabled = config.disabledServices.map(LegacyServiceIds::upgrade).toSet()

                val unrecognised = (
                    config.servicePresets.flatMap { it.selectedServices.values.filterNotNull() } +
                        config.disabledServices
                    ).filter { it == LegacyServiceIds.upgrade(it) && LegacyServiceIds.isKnownLegacyId(it).not() }
                    .filterNot { it.startsWith("type:") || it.contains(':') }
                    .distinct()
                if (unrecognised.isNotEmpty()) {
                    // Left as they were rather than guessed at — most likely a plugin the user
                    // removed, in which case the selection was already dead.
                    logger.warn(
                        "ConfigMigrator: left ${unrecognised.size} unrecognised service id(s) unchanged: " +
                            unrecognised.joinToString()
                    )
                }

                config.copy(
                    configVersion = 4,
                    servicePresets = upgradedPresets,
                    disabledServices = upgradedDisabled
                )
            }
            4 -> {
                // v4 → v5: SHOW_IMAGES was added. Same patch as v1 → v2 and v2 → v3: a user with
                // a saved hotkey list keeps exactly that list, so without this step the new
                // binding would exist only for people installing fresh.
                logger.info("ConfigMigrator: migrating v4 → v5 — patching missing hotkey defaults")
                val existingActions = config.hotkeys.map { it.action }.toSet()
                val missingDefaults = HotkeyBinding.DEFAULTS.filter { it.action !in existingActions }
                config.copy(
                    configVersion = 5,
                    hotkeys = config.hotkeys + missingDefaults
                )
            }
            5 -> {
                // v5 → v6: the popup auto-hide defaults were too short to read the result before
                // it vanished. Raised only for users still sitting on the old defaults — anyone
                // who chose their own value keeps it, since a migration that overrode a deliberate
                // choice would be worse than the short timeout ever was.
                logger.info("ConfigMigrator: migrating v5 → v6 — raising untouched popup auto-hide timeouts")
                config.copy(
                    configVersion = 6,
                    popupIdleTimeoutSeconds =
                        if (config.popupIdleTimeoutSeconds == LEGACY_POPUP_IDLE_SECONDS) {
                            Configuration.DEFAULT.popupIdleTimeoutSeconds
                        } else {
                            config.popupIdleTimeoutSeconds
                        },
                    quickDictionaryIdleTimeoutSeconds =
                        if (config.quickDictionaryIdleTimeoutSeconds == LEGACY_DICTIONARY_IDLE_SECONDS) {
                            Configuration.DEFAULT.quickDictionaryIdleTimeoutSeconds
                        } else {
                            config.quickDictionaryIdleTimeoutSeconds
                        }
                )
            }
            6 -> {
                // v6 → v7: replace the old icon-only switch with the complete selection action.
                // The nullable compatibility field exists solely because Configuration is
                // decoded before this migrator runs. Clearing it prevents obsolete state from
                // being emitted by the next save.
                logger.info("ConfigMigrator: migrating v6 → v7 — converting mouse-selection behavior")
                config.copy(
                    configVersion = 7,
                    selectionBehavior = config.legacySelectionIconEnabled?.let { enabled ->
                        if (enabled) SelectionBehavior.SHOW_ICON else SelectionBehavior.OFF
                    } ?: config.selectionBehavior,
                    legacySelectionIconEnabled = null
                )
            }
            7 -> {
                // v7 → v8: SWITCH_KEYBOARD_LAYOUT was added. Patched in like the other new
                // actions, except that the accelerator has to be chosen: Legacy's Ctrl+Shift+L is
                // Modern's LISTEN_TO_TEXT, so the plain default would put two global actions on
                // one key for anyone on Modern. The first preset's binding that is still free wins,
                // which also keeps a Legacy or Modern user identified as that preset afterwards.
                logger.info("ConfigMigrator: migrating v7 → v8 — adding the keyboard layout hotkey")
                val hasIt = config.hotkeys.any { it.action == HotkeyAction.SWITCH_KEYBOARD_LAYOUT }
                config.copy(
                    configVersion = 8,
                    hotkeys = if (hasIt) config.hotkeys else config.hotkeys + switchLayoutBindingFor(config.hotkeys)
                )
            }
            else -> {
                logger.warn("ConfigMigrator: unknown configVersion ${config.configVersion} — returning unchanged")
                config
            }
        }

    /**
     * The keyboard-layout binding to hand someone who already has [existing] bindings.
     *
     * Tries each preset's choice in turn and takes the first accelerator nobody else is using.
     * If every one is taken the action arrives unbound, which the hotkey settings show as such:
     * a missing shortcut is easy to notice and fix, a stolen one is not.
     */
    private fun switchLayoutBindingFor(existing: List<HotkeyBinding>): HotkeyBinding {
        val taken = existing.filter { it.hasBinding }.map { it.keyCode to it.modifiers }.toSet()
        val candidates = listOf(HotkeyPresets.LEGACY, HotkeyPresets.MODERN)
            .map { preset -> preset.single { it.action == HotkeyAction.SWITCH_KEYBOARD_LAYOUT } }
        return candidates.firstOrNull { (it.keyCode to it.modifiers) !in taken }
            ?: candidates.first().copy(keyCode = 0, modifiers = 0)
    }
}
