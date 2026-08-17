package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.PluginMessageLanguage
import com.github.command1264.itemdropv2.platform.bukkit.BukkitDisplaySettingsLoadResult
import com.github.command1264.itemdropv2.platform.bukkit.BukkitDisplaySettingsLoader
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemDisplaySettingsManager
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemLifetimeSettingsLoadResult
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemLifetimeSettingsLoader
import com.github.command1264.itemdropv2.platform.bukkit.BukkitMessageCatalogReloadResult
import com.github.command1264.itemdropv2.platform.bukkit.BukkitMessageCatalogStore
import com.github.command1264.itemdropv2.platform.bukkit.ConfigKeyMigrationReport
import com.github.command1264.itemdropv2.platform.bukkit.ConfigParseRecoveryReport
import com.github.command1264.itemdropv2.platform.bukkit.ItemDisplaySettingsPublicationPreparation
import com.github.command1264.itemdropv2.platform.bukkit.LegacyConfigMigrationReport
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal data class EmbeddedYamlResource(
    val bytes: ByteArray,
    val configuration: YamlConfiguration,
)

internal data class RuntimeConfiguration(
    val settingsManager: BukkitItemDisplaySettingsManager,
    val messageCatalog: BukkitMessageCatalogStore,
)

internal class RuntimeConfigurationFactory(
    private val resourceLoader: ClassLoader,
    private val dataFolder: File,
    private val migrationReporter: (LegacyConfigMigrationReport) -> Unit = {},
    private val configKeyMigrationReporter: (ConfigKeyMigrationReport) -> Unit = {},
    private val configParseRecoveryReporter: (ConfigParseRecoveryReport) -> Unit = {},
    private val configFileReplacer: ((Path, Path) -> Unit)? = null,
    private val messageCatalogReloader: ((BukkitMessageCatalogStore, PluginMessageLanguage) -> BukkitMessageCatalogReloadResult)? = null,
    private val paperClientSideTranslationSettingSupported: Boolean = false,
    private val virtualStackingSettingSupported: Boolean = false,
    private val configurationFragmentResources: List<String> = emptyList(),
    private val additionalPublicationPreparer:
        ((ItemDisplaySettings, ItemDisplaySettings) -> ItemDisplaySettingsPublicationPreparation)? = null,
    private val countryCodeProvider: () -> String? = { Locale.getDefault(Locale.Category.FORMAT).country },
) {
    fun create(): RuntimeConfiguration {
        val templateLocale = selectTemplateLocale()
        val embeddedDefaults = loadConfigDefaults(templateLocale)
        val defaults = embeddedDefaults.configuration
        val embeddedLifetimeDefaults = loadYamlResource(templateLocale.lifetimeResource)
        val lifetimeDefaults = embeddedLifetimeDefaults.configuration
        val defaultSettings = loadDisplaySettings(defaults)
        val lifetimeSettings = loadLifetimeSettings(lifetimeDefaults)
        val messageCatalog =
            BukkitMessageCatalogStore.fromResources(
                resourceLoader,
                File(dataFolder, "languages"),
                configParseRecoveryReporter,
            )
        check(messageCatalog.reload(defaultSettings.messageLanguage) is BukkitMessageCatalogReloadResult.Applied) {
            "embedded language catalog could not be initialized"
        }
        val localizedDefaultSettings =
            defaultSettings.copy(
                lifetime = lifetimeSettings,
                placeholders = messageCatalog.displayPlaceholders(defaultSettings.messageLanguage),
            )
        val settingsManager =
            BukkitItemDisplaySettingsManager(
                initialSettings = localizedDefaultSettings,
                configFile = File(dataFolder, "config.yml"),
                defaultConfiguration = defaults,
                defaultDocumentBytes = embeddedDefaults.bytes.copyOf(),
                lifetimeConfigFile = File(dataFolder, "item-lifetime.yml"),
                defaultLifetimeConfiguration = lifetimeDefaults,
                defaultLifetimeDocumentBytes = embeddedLifetimeDefaults.bytes.copyOf(),
                settingsTransformer = { settings ->
                    settings.copy(placeholders = messageCatalog.displayPlaceholders(settings.messageLanguage))
                },
                loader =
                    BukkitDisplaySettingsLoader(
                        paperClientSideTranslationSettingSupported,
                        virtualStackingSettingSupported,
                    ),
                publicationPreparer = { previous, settings -> preparePublication(messageCatalog, previous, settings) },
                migrationReporter = migrationReporter,
                configKeyMigrationReporter = configKeyMigrationReporter,
                configParseRecoveryReporter = configParseRecoveryReporter,
                configFileReplacer = configFileReplacer,
            )
        return RuntimeConfiguration(settingsManager, messageCatalog)
    }

    private fun preparePublication(
        messageCatalog: BukkitMessageCatalogStore,
        previous: ItemDisplaySettings,
        settings: ItemDisplaySettings,
    ): ItemDisplaySettingsPublicationPreparation {
        val additional =
            when (val prepared = additionalPublicationPreparer?.invoke(previous, settings)) {
                null -> ItemDisplaySettingsPublicationPreparation.Ready(settings, commit = { null })
                is ItemDisplaySettingsPublicationPreparation.Rejected -> return prepared
                is ItemDisplaySettingsPublicationPreparation.Ready -> prepared
            }
        var catalogCommitted = false
        var additionalAttempted = false
        var additionalCommitted = false
        var rolledBack = false
        return ItemDisplaySettingsPublicationPreparation.Ready(
            settings = additional.settings,
            commit = {
                val catalogFailure = reloadCatalog(messageCatalog, settings.messageLanguage)
                if (catalogFailure != null) {
                    catalogFailure
                } else {
                    catalogCommitted = true
                    additionalAttempted = true
                    additional.commit().also { failure -> additionalCommitted = failure == null }
                }
            },
            rollback = {
                if (!rolledBack) {
                    if (additionalAttempted) additional.rollback()
                    if (catalogCommitted) {
                        reloadCatalog(messageCatalog, previous.messageLanguage)?.let(::error)
                    }
                    rolledBack = true
                }
            },
            complete = {
                if (additionalCommitted) additional.complete()
            },
        )
    }

    private fun loadConfigDefaults(templateLocale: ConfigurationTemplateLocale): EmbeddedYamlResource {
        val base = loadYamlResource(templateLocale.configResource)
        if (configurationFragmentResources.isEmpty()) return base
        val baseText = base.bytes.toString(StandardCharsets.UTF_8)
        val matches = ITEMS_ROOT.findAll(baseText).toList()
        require(matches.size == 1) {
            "embedded config.yml must contain exactly one items root"
        }
        val match = matches.single()
        val newline = if (match.value.endsWith("\r\n")) "\r\n" else "\n"
        val fragments =
            configurationFragmentResources.map { fragmentPath ->
                val localizedFragmentPath = templateLocale.localizeFragment(fragmentPath)
                requireNotNull(resourceLoader.getResourceAsStream(localizedFragmentPath)) {
                    "embedded $localizedFragmentPath is missing"
                }.use { decodeStrictUtf8(it.readBytes(), localizedFragmentPath) }
                    .replace("\r\n", "\n")
                    .trimEnd()
                    .replace("\n", newline)
            }
        val combinedFragments = fragments.joinToString(newline + newline, postfix = newline + newline)
        val combined = baseText.replaceRange(match.range, match.value + combinedFragments)
        val bytes = combined.toByteArray(StandardCharsets.UTF_8)
        val configuration =
            try {
                YamlConfiguration().apply { loadFromString(combined) }
            } catch (_: org.bukkit.configuration.InvalidConfigurationException) {
                throw IllegalArgumentException("embedded edition config fragment is invalid YAML")
            }
        return EmbeddedYamlResource(bytes, configuration)
    }

    private fun selectTemplateLocale(): ConfigurationTemplateLocale {
        val configFile = File(dataFolder, "config.yml")
        val countryLocale = ConfigurationTemplateLocaleSelector.select(countryCodeProvider())
        val configuredLanguage = readConfiguredLanguage(configFile)
        return when {
            configuredLanguage == null -> countryLocale
            PluginMessageLanguage.parse(configuredLanguage) == PluginMessageLanguage.ZH_TW ->
                ConfigurationTemplateLocale.ZH_TW
            else -> ConfigurationTemplateLocale.EN_US
        }
    }

    private fun readConfiguredLanguage(configFile: File): String? =
        if (!configFile.isFile) {
            null
        } else {
            try {
                val bytes = Files.readAllBytes(configFile.toPath())
                val text = decodeStrictUtf8(bytes, configFile.name)
                YamlConfiguration().apply { loadFromString(text) }.getString(MESSAGE_LANGUAGE_PATH)
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: org.bukkit.configuration.InvalidConfigurationException) {
                null
            }
        }

    private fun reloadCatalog(
        messageCatalog: BukkitMessageCatalogStore,
        language: PluginMessageLanguage,
    ): String? =
        when (val result = messageCatalogReloader?.invoke(messageCatalog, language) ?: messageCatalog.reload(language)) {
            BukkitMessageCatalogReloadResult.Applied -> null
            is BukkitMessageCatalogReloadResult.Failed -> result.reason
        }

    internal fun loadYamlResource(path: String): EmbeddedYamlResource {
        val bytes =
            requireNotNull(resourceLoader.getResourceAsStream(path)) { "embedded $path is missing" }
                .use { it.readBytes() }
        val text = decodeStrictUtf8(bytes, path)
        val configuration =
            try {
                YamlConfiguration().apply { loadFromString(text) }
            } catch (_: org.bukkit.configuration.InvalidConfigurationException) {
                throw IllegalArgumentException("embedded $path is invalid YAML")
            }
        return EmbeddedYamlResource(bytes.copyOf(), configuration)
    }

    private fun decodeStrictUtf8(
        bytes: ByteArray,
        path: String,
    ): String =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("embedded $path is not strict UTF-8")
        }

    private fun loadYaml(path: String): YamlConfiguration {
        val stream = requireNotNull(resourceLoader.getResourceAsStream(path)) { "embedded $path is missing" }
        return stream.use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use(YamlConfiguration::loadConfiguration)
        }
    }

    private fun loadDisplaySettings(configuration: YamlConfiguration): ItemDisplaySettings =
        when (
            val loaded =
                BukkitDisplaySettingsLoader(
                    paperClientSideTranslationSettingSupported,
                    virtualStackingSettingSupported,
                ).load(configuration)
        ) {
            is BukkitDisplaySettingsLoadResult.Loaded -> loaded.settings
            is BukkitDisplaySettingsLoadResult.Invalid ->
                error("embedded config.yml is invalid: ${loaded.errors.joinToString("; ")}")
        }

    private fun loadLifetimeSettings(configuration: YamlConfiguration) =
        when (val loaded = BukkitItemLifetimeSettingsLoader().load(configuration)) {
            is BukkitItemLifetimeSettingsLoadResult.Loaded -> loaded.settings
            is BukkitItemLifetimeSettingsLoadResult.Invalid ->
                error("embedded item-lifetime.yml is invalid: ${loaded.errors.joinToString("; ")}")
        }

    private companion object {
        private const val MESSAGE_LANGUAGE_PATH = "general.language"
        private val ITEMS_ROOT = Regex("(?m)^items:\\r?\\n")
    }
}
