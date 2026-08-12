package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.platform.bukkit.yaml.ScannedYamlDocument
import com.github.command1264.itemdropv2.platform.bukkit.yaml.StrictYamlDocumentScanner
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlCommentMapping
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlDocumentScanResult
import com.github.command1264.itemdropv2.platform.bukkit.yaml.YamlPath
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.util.Locale

public enum class LegacyConfigSource(
    public val fileLabel: String,
) {
    ITEM_DROP_V2("itemdropv2"),
    ITEM_DROP("itemdrop"),
}

public data class LegacyConfigMigrationReport(
    public val source: LegacyConfigSource,
    public val ignoredPaths: List<String>,
    public val backupFileName: String? = null,
)

internal sealed interface LegacyConfigMigrationResult {
    data object NotLegacy : LegacyConfigMigrationResult

    data class Migrated(
        val configuration: YamlConfiguration,
        val report: LegacyConfigMigrationReport,
        val commentMappings: List<YamlCommentMapping>,
    ) : LegacyConfigMigrationResult

    data class Rejected(
        val reason: String,
    ) : LegacyConfigMigrationResult
}

@Suppress("TooManyFunctions")
internal class BukkitLegacyConfigMigrator(
    private val defaults: YamlConfiguration,
    private val loader: BukkitDisplaySettingsLoader,
) {
    private val scanner = StrictYamlDocumentScanner()

    fun migrate(
        source: YamlConfiguration,
        originalBytes: ByteArray,
    ): LegacyConfigMigrationResult =
        when (val scan = scanner.scan(originalBytes)) {
            is YamlDocumentScanResult.Rejected ->
                LegacyConfigMigrationResult.Rejected(
                    "${scan.rejection.category.name.lowercase(Locale.ROOT)}: ${scan.rejection.detail}",
                )
            is YamlDocumentScanResult.Scanned -> migrateScanned(source, scan.document)
        }

    private fun migrateScanned(
        source: YamlConfiguration,
        document: ScannedYamlDocument,
    ): LegacyConfigMigrationResult {
        val itemDropV2 = isItemDropV2(source, document)
        val itemDrop = isItemDrop(source, document)
        if (itemDropV2 && itemDrop) {
            return LegacyConfigMigrationResult.Rejected("legacy config mixes ItemDropV2 and ItemDrop schemas")
        }
        return when {
            itemDropV2 -> rejectExplicitNull(source, document, ITEM_DROP_V2_RECOGNIZED_LEAF_PATHS) ?: migrateItemDropV2(source)
            itemDrop -> rejectExplicitNull(source, document, ITEM_DROP_RECOGNIZED_LEAF_PATHS) ?: migrateItemDrop(source)
            else -> LegacyConfigMigrationResult.NotLegacy
        }
    }

    private fun rejectExplicitNull(
        source: ConfigurationSection,
        document: ScannedYamlDocument,
        recognizedLeafPaths: List<String>,
    ): LegacyConfigMigrationResult.Rejected? =
        recognizedLeafPaths
            .firstOrNull { path -> document.hasEntry(path) && source.get(path) == null }
            ?.let { path -> LegacyConfigMigrationResult.Rejected("$path: explicit null is not allowed") }

    private fun migrateItemDropV2(source: ConfigurationSection): LegacyConfigMigrationResult {
        val candidate = defaults.copy()
        val errors = mutableListOf<String>()
        val mappings = mutableListOf<YamlCommentMapping>()
        copyBoolean(source, candidate, "general.enabled", "general.enabled", errors, mappings)
        copyLocale(source, candidate, "general.language", "general.language", errors, mappings)
        copyLocale(source, candidate, "general.mc-language", "general.minecraft-language", errors, mappings)
        copyStringList(source, candidate, "general.blocked-worlds", "general.blocked-worlds", errors, mappings)
        migrateItemDropV2ItemSettings(source, candidate, errors, mappings)
        migrateItemDropV2Templates(source, candidate, errors, mappings)
        val ignoredPaths = ITEM_DROP_V2_IGNORED_PATHS.filter(source::contains)
        ignoredPaths.forEach { path -> mappings.map(path, null) }
        return finish(
            candidate,
            LegacyConfigMigrationReport(
                LegacyConfigSource.ITEM_DROP_V2,
                ignoredPaths,
            ),
            errors,
            mappings,
        )
    }

    private fun migrateItemDropV2ItemSettings(
        source: ConfigurationSection,
        candidate: YamlConfiguration,
        errors: MutableList<String>,
        mappings: MutableList<YamlCommentMapping>,
    ) {
        copyLong(source, candidate, "items.owner-owns-time", "items.ownership.protection-seconds", errors, mappings)
        copyBoolean(
            source,
            candidate,
            "items.hopper-pickup-owner",
            "items.ownership.allow-hopper-pickup",
            errors,
            mappings,
        )
        copyLong(
            source,
            candidate,
            "items.player-pickup-warning-message-delay",
            "items.ownership.pickup-warning-cooldown-seconds",
            errors,
            mappings,
        )
        copyNormalizedString(
            source,
            candidate,
            "items.player-pickup-warning-message-type",
            "items.ownership.pickup-warning-message-type",
            errors,
            mappings,
        )
        copyDouble(
            source,
            candidate,
            "items.player-damage-entity-min-health-percent",
            "items.ownership.entity.minimum-damage-percent-of-max-health",
            errors,
            mappings,
        )
        copyBoolean(
            source,
            candidate,
            "items.item-name-rarity-display",
            "items.rarity-display.enabled",
            errors,
            mappings,
        )
        mapEntityStrategy(source, candidate, "items.item-owner-decide", errors, mappings)
        mapMergeStrategy(source, candidate, "items.item-merge-lived-time-mode", errors, mappings)
    }

    private fun migrateItemDrop(source: ConfigurationSection): LegacyConfigMigrationResult {
        val candidate = defaults.copy()
        val errors = mutableListOf<String>()
        val mappings = mutableListOf<YamlCommentMapping>()
        copyBoolean(source, candidate, "General.Enable", "general.enabled", errors, mappings)
        copyLocale(source, candidate, "General.Language", "general.language", errors, mappings)
        copyLocale(source, candidate, "General.MCLanguage", "general.minecraft-language", errors, mappings)
        copyStringList(source, candidate, "General.Blocked_Worlds", "general.blocked-worlds", errors, mappings)
        copyLong(
            source,
            candidate,
            "Item_Hologram.Owner_Owns_Time",
            "items.ownership.protection-seconds",
            errors,
            mappings,
        )
        copyLong(
            source,
            candidate,
            "Item_Hologram.Player_Can_Not_PickUp_Item_Message_Delay",
            "items.ownership.pickup-warning-cooldown-seconds",
            errors,
            mappings,
        )
        copyDouble(
            source,
            candidate,
            "Item_Hologram.Player_Damage_Entity_Min_Health_Percent",
            "items.ownership.entity.minimum-damage-percent-of-max-health",
            errors,
            mappings,
        )
        copyBoolean(
            source,
            candidate,
            "Item_Hologram.Item_Name_Rarity_Display",
            "items.rarity-display.enabled",
            errors,
            mappings,
        )
        mapEntityStrategy(source, candidate, "Item_Hologram.Item_Owner_Judge", errors, mappings)
        mapNumericMergeStrategy(source, candidate, errors, mappings)
        migrateItemDropTemplates(source, candidate, errors, mappings)
        val ignoredPaths = ITEM_DROP_IGNORED_PATHS.filter(source::contains)
        ignoredPaths.forEach { path -> mappings.map(path, null) }
        return finish(
            candidate,
            LegacyConfigMigrationReport(
                LegacyConfigSource.ITEM_DROP,
                ignoredPaths,
            ),
            errors,
            mappings,
        )
    }

    private fun finish(
        candidate: YamlConfiguration,
        report: LegacyConfigMigrationReport,
        errors: List<String>,
        mappings: List<YamlCommentMapping>,
    ): LegacyConfigMigrationResult {
        if (errors.isNotEmpty()) return LegacyConfigMigrationResult.Rejected(errors.joinToString("; "))
        return when (val loaded = loader.load(candidate)) {
            is BukkitDisplaySettingsLoadResult.Loaded -> LegacyConfigMigrationResult.Migrated(candidate, report, mappings.toList())
            is BukkitDisplaySettingsLoadResult.Invalid ->
                LegacyConfigMigrationResult.Rejected(loaded.errors.joinToString("; "))
        }
    }

    private fun migrateItemDropV2Templates(
        source: ConfigurationSection,
        candidate: YamlConfiguration,
        errors: MutableList<String>,
        mappings: MutableList<YamlCommentMapping>,
    ) {
        val ownerPrefix =
            readString(source, "items.placeholder.owner-player-prefix", errors)
                ?.replace("%owner_time%", "%protection_remaining%")
        if (ownerPrefix != null) {
            candidate.set("items.ownership.display.single-owner-prefix", ownerPrefix)
            candidate.set(
                "items.ownership.display.multiple-owners-prefix",
                ownerPrefix.trimEnd() + "&e+%additional_owner_count%&r ",
            )
            mappings.map("items.placeholder.owner-player-prefix", "items.ownership.display.single-owner-prefix")
        }
        val rawTimeSuffix = readString(source, "items.placeholder.item-time-left", errors)
        val timeSuffix = rawTimeSuffix?.replace("%item_lived_time_left%", "%lifetime_remaining%").orEmpty()
        if (rawTimeSuffix != null) mappings.map("items.placeholder.item-time-left", null)
        listOf("single", "multi").forEach { type ->
            val path = "items.display-name-format.$type"
            readString(source, path, errors)?.let { raw ->
                candidate.set(
                    path,
                    raw
                        .replace("%owner_player_prefix%", "")
                        .replace("%item_time_left%", timeSuffix)
                        .replace("%owner_time%", "%protection_remaining%")
                        .replace("%item_lived_time_left%", "%lifetime_remaining%"),
                )
                mappings.map(path, path)
            }
        }
    }

    private fun migrateItemDropTemplates(
        source: ConfigurationSection,
        candidate: YamlConfiguration,
        errors: MutableList<String>,
        mappings: MutableList<YamlCommentMapping>,
    ) {
        val ownerPrefix = readString(source, "Item_Hologram.Owner_Player", errors)
        if (ownerPrefix != null) {
            candidate.set("items.ownership.display.single-owner-prefix", ownerPrefix)
            candidate.set(
                "items.ownership.display.multiple-owners-prefix",
                ownerPrefix.trimEnd() + "&e+%additional_owner_count%&r ",
            )
            mappings.map("Item_Hologram.Owner_Player", "items.ownership.display.single-owner-prefix")
        }
        val rawItemName = readString(source, "Item_Hologram.Item_Display_Name", errors)
        val itemName = rawItemName ?: "%item_display_name%"
        if (rawItemName != null) mappings.map("Item_Hologram.Item_Display_Name", null)
        listOf("Single" to "single", "Multi" to "multi").forEach { (legacyName, currentName) ->
            val path = "Item_Hologram.Display.$legacyName"
            readString(source, path, errors)?.let { raw ->
                val migrated =
                    raw
                        .replace("{Owner_Player}", "")
                        .replace("{Item_Display_Name}", itemName)
                        .replace("{Amount}", "%amount%")
                if (LEGACY_MACRO.containsMatchIn(migrated)) {
                    errors += "$path: contains unsupported legacy macro"
                } else {
                    val target = "items.display-name-format.$currentName"
                    candidate.set(target, migrated)
                    mappings.map(path, target)
                }
            }
        }
    }

    private fun mapEntityStrategy(
        source: ConfigurationSection,
        candidate: YamlConfiguration,
        path: String,
        errors: MutableList<String>,
        mappings: MutableList<YamlCommentMapping>,
    ) {
        val raw = readString(source, path, errors) ?: return
        val mapped =
            when (raw.lowercase(Locale.ROOT)) {
                "max_damage" -> "highest-damage"
                "first_damage" -> "first-hit"
                "last_damage" -> "final-hit"
                else -> {
                    errors += "$path: expected one of max_damage, first_damage, last_damage"
                    return
                }
            }
        val target = "items.ownership.entity.strategy"
        candidate.set(target, mapped)
        mappings.map(path, target)
    }

    private fun mapMergeStrategy(
        source: ConfigurationSection,
        candidate: YamlConfiguration,
        path: String,
        errors: MutableList<String>,
        mappings: MutableList<YamlCommentMapping>,
    ) {
        val raw = readString(source, path, errors) ?: return
        val mapped =
            when (raw.lowercase(Locale.ROOT)) {
                "avg", "average" -> "average"
                "max", "maximum" -> "maximum"
                "min", "minimum" -> "minimum"
                else -> {
                    errors += "$path: expected one of avg, max, min"
                    return
                }
            }
        val target = "items.merge.lifetime-strategy"
        candidate.set(target, mapped)
        mappings.map(path, target)
    }

    private fun mapNumericMergeStrategy(
        source: ConfigurationSection,
        candidate: YamlConfiguration,
        errors: MutableList<String>,
        mappings: MutableList<YamlCommentMapping>,
    ) {
        val path = "Item_Hologram.Item_Marge_Mode"
        if (source.contains(path)) {
            if (!source.isInt(path)) {
                errors += "$path: expected integer"
            } else {
                val mapped =
                    when (source.getInt(path)) {
                        0 -> "average"
                        1 -> "minimum"
                        2 -> "maximum"
                        else -> null
                    }
                if (mapped == null) {
                    errors += "$path: expected one of 0, 1, 2"
                } else {
                    val target = "items.merge.lifetime-strategy"
                    candidate.set(target, mapped)
                    mappings.map(path, target)
                }
            }
        }
    }

    private fun copyBoolean(
        source: ConfigurationSection,
        candidate: YamlConfiguration,
        sourcePath: String,
        targetPath: String,
        errors: MutableList<String>,
        mappings: MutableList<YamlCommentMapping>,
    ) {
        if (!source.contains(sourcePath)) return
        if (!source.isBoolean(sourcePath)) {
            errors += "$sourcePath: expected boolean"
        } else {
            candidate.set(targetPath, source.getBoolean(sourcePath))
            mappings.map(sourcePath, targetPath)
        }
    }

    private fun copyLong(
        source: ConfigurationSection,
        candidate: YamlConfiguration,
        sourcePath: String,
        targetPath: String,
        errors: MutableList<String>,
        mappings: MutableList<YamlCommentMapping>,
    ) {
        if (!source.contains(sourcePath)) return
        if (!source.isLong(sourcePath) && !source.isInt(sourcePath)) {
            errors += "$sourcePath: expected integer"
        } else {
            candidate.set(targetPath, source.getLong(sourcePath))
            mappings.map(sourcePath, targetPath)
        }
    }

    private fun copyDouble(
        source: ConfigurationSection,
        candidate: YamlConfiguration,
        sourcePath: String,
        targetPath: String,
        errors: MutableList<String>,
        mappings: MutableList<YamlCommentMapping>,
    ) {
        if (!source.contains(sourcePath)) return
        if (source.get(sourcePath) !is Number) {
            errors += "$sourcePath: expected number"
        } else {
            candidate.set(targetPath, source.getDouble(sourcePath))
            mappings.map(sourcePath, targetPath)
        }
    }

    private fun copyStringList(
        source: ConfigurationSection,
        candidate: YamlConfiguration,
        sourcePath: String,
        targetPath: String,
        errors: MutableList<String>,
        mappings: MutableList<YamlCommentMapping>,
    ) {
        if (!source.contains(sourcePath)) return
        val values = source.getList(sourcePath)
        if (!source.isList(sourcePath) || values == null || values.any { it !is String }) {
            errors += "$sourcePath: expected string list"
        } else {
            candidate.set(targetPath, values)
            mappings.map(sourcePath, targetPath)
        }
    }

    private fun copyLocale(
        source: ConfigurationSection,
        candidate: YamlConfiguration,
        sourcePath: String,
        targetPath: String,
        errors: MutableList<String>,
        mappings: MutableList<YamlCommentMapping>,
    ) {
        readString(source, sourcePath, errors)?.let {
            candidate.set(targetPath, normalizeLocale(it))
            mappings.map(sourcePath, targetPath)
        }
    }

    private fun copyNormalizedString(
        source: ConfigurationSection,
        candidate: YamlConfiguration,
        sourcePath: String,
        targetPath: String,
        errors: MutableList<String>,
        mappings: MutableList<YamlCommentMapping>,
    ) {
        readString(source, sourcePath, errors)?.let {
            candidate.set(targetPath, it.lowercase(Locale.ROOT).replace('_', '-'))
            mappings.map(sourcePath, targetPath)
        }
    }

    private fun readString(
        source: ConfigurationSection,
        path: String,
        errors: MutableList<String>,
    ): String? =
        when {
            !source.contains(path) -> null
            !source.isString(path) -> {
                errors += "$path: expected string"
                null
            }
            else -> source.getString(path).orEmpty()
        }

    private fun normalizeLocale(value: String): String = value.trim().lowercase(Locale.ROOT).replace('-', '_')

    private fun MutableList<YamlCommentMapping>.map(
        sourcePath: String,
        targetPath: String?,
    ) {
        this += YamlCommentMapping(YamlPath.parse(sourcePath), targetPath?.let(YamlPath::parse))
    }

    private fun isItemDropV2(
        source: ConfigurationSection,
        document: ScannedYamlDocument,
    ): Boolean =
        source.isConfigurationSection("general") &&
            source.isConfigurationSection("items") &&
            ITEM_DROP_V2_MARKERS.any { path -> document.hasEntry(path) }

    private fun isItemDrop(
        source: ConfigurationSection,
        document: ScannedYamlDocument,
    ): Boolean =
        source.isConfigurationSection("General") &&
            source.isConfigurationSection("Item_Hologram") &&
            ITEM_DROP_MARKERS.any { path -> document.hasEntry(path) }

    private fun ScannedYamlDocument.hasEntry(path: String): Boolean = entryOrNull(YamlPath.parse(path)) != null

    private fun YamlConfiguration.copy(): YamlConfiguration = YamlConfiguration().also { copy -> copy.loadFromString(saveToString()) }

    private companion object {
        private val LEGACY_MACRO = Regex("\\{[A-Za-z0-9_]+}")
        private val ITEM_DROP_V2_MARKERS =
            listOf("general.mc-language", "items.item-owner-decide", "items.owner-owns-time", "items.async")
        private val ITEM_DROP_MARKERS =
            listOf("General.Version", "Item_Hologram.Owner_Player", "Item_Hologram.Item_Marge_Mode")
        private val ITEM_DROP_V2_IGNORED_PATHS =
            listOf(
                "general.mc-language-check-updates",
                "general.mc-language-check-updates-message",
                "general.mc-language-check-updates-interval",
                "general.mc-language-auto-update",
                "general.mc-language-auto-updates-message",
                "general.mc-language-cant-find-item-in-lang-message",
                "items.async",
                "items.item-age-type",
                "items.custom-item-death-time",
                "items.item-merge-owner-time-mode",
            )
        private val ITEM_DROP_IGNORED_PATHS = listOf("General.Version")
        private val ITEM_DROP_V2_RECOGNIZED_LEAF_PATHS =
            listOf(
                "general.enabled",
                "general.language",
                "general.mc-language",
                "general.mc-language-check-updates",
                "general.mc-language-check-updates-message",
                "general.mc-language-check-updates-interval",
                "general.mc-language-auto-update",
                "general.mc-language-auto-updates-message",
                "general.mc-language-cant-find-item-in-lang-message",
                "general.blocked-worlds",
                "items.async",
                "items.item-age-type",
                "items.custom-item-death-time",
                "items.hopper-pickup-owner",
                "items.placeholder.owner-player-prefix",
                "items.placeholder.item-time-left",
                "items.display-name-format.single",
                "items.display-name-format.multi",
                "items.owner-owns-time",
                "items.item-merge-owner-time-mode",
                "items.item-merge-lived-time-mode",
                "items.player-pickup-warning-message-delay",
                "items.player-pickup-warning-message-type",
                "items.item-owner-decide",
                "items.player-damage-entity-min-health-percent",
                "items.item-name-rarity-display",
            )
        private val ITEM_DROP_RECOGNIZED_LEAF_PATHS =
            listOf(
                "General.Enable",
                "General.Version",
                "General.Language",
                "General.MCLanguage",
                "General.Blocked_Worlds",
                "Item_Hologram.Owner_Player",
                "Item_Hologram.Item_Display_Name",
                "Item_Hologram.Owner_Owns_Time",
                "Item_Hologram.Item_Marge_Mode",
                "Item_Hologram.Player_Can_Not_PickUp_Item_Message_Delay",
                "Item_Hologram.Player_Damage_Entity_Min_Health_Percent",
                "Item_Hologram.Item_Owner_Judge",
                "Item_Hologram.Item_Name_Rarity_Display",
                "Item_Hologram.Display.Single",
                "Item_Hologram.Display.Multi",
            )
    }
}
