package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemLifetimeSettings
import org.bukkit.configuration.ConfigurationSection

public class BukkitItemLifetimeSettingsLoader {
    public fun load(configuration: ConfigurationSection): BukkitItemLifetimeSettingsLoadResult {
        val errors = mutableListOf<String>()
        if (!configuration.isInt(SCHEMA_VERSION_PATH) || configuration.getInt(SCHEMA_VERSION_PATH) != SCHEMA_VERSION) {
            errors += "$SCHEMA_VERSION_PATH: expected integer $SCHEMA_VERSION"
        }
        val defaultSeconds = readLifetime(configuration, DEFAULT_SECONDS_PATH, errors)
        val materials = readMaterials(configuration, errors)
        return if (errors.isEmpty() && defaultSeconds != null && materials != null) {
            BukkitItemLifetimeSettingsLoadResult.Loaded(ItemLifetimeSettings(defaultSeconds, materials))
        } else {
            BukkitItemLifetimeSettingsLoadResult.Invalid(errors)
        }
    }

    private fun readLifetime(
        configuration: ConfigurationSection,
        path: String,
        errors: MutableList<String>,
    ): Long? =
        when {
            !configuration.isLong(path) && !configuration.isInt(path) -> {
                errors += "$path: expected integer"
                null
            }
            configuration.getLong(path) < ItemLifetimeSettings.NEVER_EXPIRES -> {
                errors += "$path: expected -1, 0, or a positive number of seconds"
                null
            }
            else -> configuration.getLong(path)
        }

    private fun readMaterials(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): Map<String, Long>? {
        if (!configuration.isConfigurationSection(MATERIALS_PATH)) {
            errors += "$MATERIALS_PATH: expected section"
            return null
        }
        val section = requireNotNull(configuration.getConfigurationSection(MATERIALS_PATH))
        val result = linkedMapOf<String, Long>()
        section.getKeys(false).forEach { rawName ->
            val normalized = rawName.uppercase()
            val path = "$MATERIALS_PATH.$rawName"
            when {
                !normalized.matches(MATERIAL_NAME_PATTERN) ->
                    errors += "$path: expected a Minecraft Material name"
                normalized in result ->
                    errors += "$path: duplicate Material after case normalization"
                else -> readLifetime(configuration, path, errors)?.let { result[normalized] = it }
            }
        }
        return result
    }

    private companion object {
        private const val SCHEMA_VERSION = 1
        private const val SCHEMA_VERSION_PATH = "schema-version"
        private const val DEFAULT_SECONDS_PATH = "default-seconds"
        private const val MATERIALS_PATH = "materials"
        private val MATERIAL_NAME_PATTERN = Regex("[A-Z0-9_]+")
    }
}

public sealed interface BukkitItemLifetimeSettingsLoadResult {
    public data class Loaded(
        public val settings: ItemLifetimeSettings,
    ) : BukkitItemLifetimeSettingsLoadResult

    public data class Invalid(
        public val errors: List<String>,
    ) : BukkitItemLifetimeSettingsLoadResult
}
