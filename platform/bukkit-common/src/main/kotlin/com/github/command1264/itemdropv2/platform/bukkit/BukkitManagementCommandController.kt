package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ManagementCommandOutcome
import com.github.command1264.itemdropv2.core.ManagementCommandService
import com.github.command1264.itemdropv2.core.PluginMessageLanguage
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader

public enum class ManagementMessageKey(
    public val path: String,
) {
    NO_PERMISSION("command.no-permission"),
    HELP_HEADER("command.help.header"),
    HELP_TOGGLE("command.help.toggle"),
    HELP_RELOAD("command.help.reload"),
    HELP_INFO("command.help.info"),
    INFO("command.info"),
    TOGGLE_ENABLED("command.toggle.enabled"),
    TOGGLE_DISABLED("command.toggle.disabled"),
    TOGGLE_ALREADY_ENABLED("command.toggle.already-enabled"),
    TOGGLE_ALREADY_DISABLED("command.toggle.already-disabled"),
    TOGGLE_USAGE("command.toggle.usage"),
    TOGGLE_FAILED("command.toggle.failed"),
    RELOAD_SUCCESS("command.reload.success"),
    RELOAD_FAILED("command.reload.failed"),
    FAILURE_REASON("command.failure-reason"),
    RELOAD_LANGUAGE_FALLBACK_CREATED("command.reload.language-fallback-created"),
    CONFIG_PARSE_RECOVERED("command.config-parse-recovered"),
    ROOT_USAGE("command.root-usage"),
}

public class BukkitManagementMessageCatalog private constructor(
    private val renderer: (PluginMessageLanguage, ManagementMessageKey, Map<String, String>) -> String,
    private val fallbackLanguageConsumer: () -> PluginMessageLanguage?,
) {
    internal constructor(messages: Map<PluginMessageLanguage, Map<ManagementMessageKey, String>>) : this(
        renderer = { language, key, placeholders ->
            val template = requireNotNull(messages[language]?.get(key)) { "missing message ${language.code}:${key.path}" }
            renderMessage(template, placeholders)
        },
        fallbackLanguageConsumer = { null },
    )

    public fun render(
        language: PluginMessageLanguage,
        key: ManagementMessageKey,
        placeholders: Map<String, String> = emptyMap(),
    ): String = renderer(language, key, placeholders)

    public fun consumeGeneratedFallbackLanguage(): PluginMessageLanguage? = fallbackLanguageConsumer()

    public companion object {
        public fun fromStore(store: BukkitMessageCatalogStore): BukkitManagementMessageCatalog =
            BukkitManagementMessageCatalog(
                renderer = { language, key, placeholders -> store.render(language, key.path, placeholders) },
                fallbackLanguageConsumer = store::consumeGeneratedFallbackLanguage,
            )

        public fun load(resourceLoader: ClassLoader): BukkitManagementMessageCatalog {
            val catalogs =
                PluginMessageLanguage.BUILT_IN.associateWith { language ->
                    val path = "config/languages/${language.code}.yml"
                    val stream = requireNotNull(resourceLoader.getResourceAsStream(path)) { "missing resource $path" }
                    stream.use {
                        val yaml = YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8))
                        ManagementMessageKey.entries.associateWith { key ->
                            requireNotNull(yaml.getString(key.path)) { "missing message ${key.path} in $path" }
                        }
                    }
                }
            return BukkitManagementMessageCatalog(catalogs)
        }
    }
}

@Suppress("TooManyFunctions")
public class BukkitManagementCommandController(
    private val service: ManagementCommandService,
    private val language: () -> PluginMessageLanguage,
    private val messages: BukkitManagementMessageCatalog,
    private val version: String,
    backendId: String? = null,
    private val backendIdProvider: () -> String = { requireNotNull(backendId) },
    private val failureLogger: (String) -> Unit,
    private val configParseRecoveryConsumer: () -> ConfigParseRecoveryReport? = { null },
    private val additionalYamlRecoveryConsumer: () -> List<ConfigParseRecoveryReport> = { emptyList() },
) : CommandExecutor,
    TabCompleter {
    @Suppress("ReturnCount")
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (!sender.hasPermission(BASIC_PERMISSION)) return deny(sender)
        if (args.isEmpty()) return help(sender)
        return when (args[0].lowercase()) {
            "help" -> if (args.size == 1) help(sender) else usage(sender)
            "info", "about" -> if (args.size == 1) info(sender) else usage(sender)
            "reload" -> if (args.size == 1) reload(sender) else usage(sender)
            "toggle" -> toggle(sender, args)
            else -> usage(sender)
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (!sender.hasPermission(BASIC_PERMISSION)) return emptyList()
        val candidates =
            when (args.size) {
                1 ->
                    buildList {
                        if (sender.hasPermission(HELP_PERMISSION)) add("help")
                        if (sender.hasPermission(INFO_PERMISSION)) {
                            add("info")
                            add("about")
                        }
                        if (sender.hasPermission(RELOAD_PERMISSION)) add("reload")
                        if (sender.hasPermission(TOGGLE_PERMISSION)) add("toggle")
                    }
                2 ->
                    if (args[0].equals("toggle", ignoreCase = true) && sender.hasPermission(TOGGLE_PERMISSION)) {
                        listOf("on", "off")
                    } else {
                        emptyList()
                    }
                else -> emptyList()
            }
        val prefix = args.lastOrNull().orEmpty()
        return candidates.filter { it.startsWith(prefix, ignoreCase = true) }
    }

    private fun help(sender: CommandSender): Boolean {
        if (!sender.hasPermission(HELP_PERMISSION)) return deny(sender)
        send(sender, ManagementMessageKey.HELP_HEADER)
        if (sender.hasPermission(TOGGLE_PERMISSION)) send(sender, ManagementMessageKey.HELP_TOGGLE)
        if (sender.hasPermission(RELOAD_PERMISSION)) send(sender, ManagementMessageKey.HELP_RELOAD)
        if (sender.hasPermission(INFO_PERMISSION)) send(sender, ManagementMessageKey.HELP_INFO)
        return true
    }

    private fun info(sender: CommandSender): Boolean {
        if (!sender.hasPermission(INFO_PERMISSION)) return deny(sender)
        send(sender, ManagementMessageKey.INFO, mapOf("version" to version, "backend" to backendIdProvider()))
        return true
    }

    private fun reload(sender: CommandSender): Boolean {
        if (!sender.hasPermission(RELOAD_PERMISSION)) return deny(sender)
        val outcome = service.reload()
        val responded = respond(sender, outcome, isReload = true)
        if (outcome == ManagementCommandOutcome.Reloaded) notifySuccessfulReload(sender)
        return responded
    }

    @Suppress("ReturnCount")
    private fun toggle(
        sender: CommandSender,
        args: Array<out String>,
    ): Boolean {
        if (!sender.hasPermission(TOGGLE_PERMISSION)) return deny(sender)
        val requested =
            when {
                args.size == 1 -> null
                args.size != 2 -> return send(sender, ManagementMessageKey.TOGGLE_USAGE)
                args[1].equals("on", ignoreCase = true) -> true
                args[1].equals("off", ignoreCase = true) -> false
                else -> return send(sender, ManagementMessageKey.TOGGLE_USAGE)
            }
        val outcome = service.toggle(requested)
        val responded = respond(sender, outcome, isReload = false)
        if (outcome == ManagementCommandOutcome.Enabled || outcome == ManagementCommandOutcome.Disabled) {
            notifySuccessfulReload(sender)
        }
        return responded
    }

    private fun notifySuccessfulReload(sender: CommandSender) {
        notifyGeneratedFallback(sender)
        (listOfNotNull(configParseRecoveryConsumer()) + additionalYamlRecoveryConsumer()).forEach { report ->
            send(
                sender,
                ManagementMessageKey.CONFIG_PARSE_RECOVERED,
                mapOf(
                    "backup" to report.backupFileName,
                    "items" to recoveryItems(report),
                ),
            )
        }
    }

    private fun notifyGeneratedFallback(sender: CommandSender) {
        messages.consumeGeneratedFallbackLanguage()?.let { language ->
            send(
                sender,
                ManagementMessageKey.RELOAD_LANGUAGE_FALLBACK_CREATED,
                mapOf("language" to language.code),
            )
        }
    }

    private fun respond(
        sender: CommandSender,
        outcome: ManagementCommandOutcome,
        isReload: Boolean,
    ): Boolean =
        when (outcome) {
            ManagementCommandOutcome.Enabled -> send(sender, ManagementMessageKey.TOGGLE_ENABLED)
            ManagementCommandOutcome.Disabled -> send(sender, ManagementMessageKey.TOGGLE_DISABLED)
            ManagementCommandOutcome.AlreadyEnabled -> send(sender, ManagementMessageKey.TOGGLE_ALREADY_ENABLED)
            ManagementCommandOutcome.AlreadyDisabled -> send(sender, ManagementMessageKey.TOGGLE_ALREADY_DISABLED)
            ManagementCommandOutcome.Reloaded -> send(sender, ManagementMessageKey.RELOAD_SUCCESS)
            is ManagementCommandOutcome.Failed -> {
                failureLogger(outcome.reason)
                send(sender, if (isReload) ManagementMessageKey.RELOAD_FAILED else ManagementMessageKey.TOGGLE_FAILED)
                send(
                    sender,
                    ManagementMessageKey.FAILURE_REASON,
                    mapOf("reason" to senderSafeFailureReason(outcome.reason)),
                )
            }
        }

    private fun usage(sender: CommandSender): Boolean = send(sender, ManagementMessageKey.ROOT_USAGE)

    private fun deny(sender: CommandSender): Boolean = send(sender, ManagementMessageKey.NO_PERMISSION)

    private fun send(
        sender: CommandSender,
        key: ManagementMessageKey,
        placeholders: Map<String, String> = emptyMap(),
    ): Boolean {
        sender.sendMessage(messages.render(language(), key, placeholders))
        return true
    }

    private companion object {
        private const val BASIC_PERMISSION = "itemdrop.commands.basic"
        private const val HELP_PERMISSION = "itemdrop.commands.help"
        private const val INFO_PERMISSION = "itemdrop.commands.info"
        private const val RELOAD_PERMISSION = "itemdrop.commands.reload"
        private const val TOGGLE_PERMISSION = "itemdrop.commands.toggle"
    }
}

private fun recoveryItems(report: ConfigParseRecoveryReport): String =
    when {
        report.documentRecreated -> report.fileName
        report.fileName == "config.yml" -> report.repairedPaths.joinToString(", ")
        else -> report.repairedPaths.joinToString(", ") { path -> "${report.fileName}:$path" }
    }

private fun renderMessage(
    template: String,
    placeholders: Map<String, String>,
): String =
    ChatColor.translateAlternateColorCodes(
        '&',
        placeholders.entries.fold(template) { text, (name, value) -> text.replace("%$name%", value) },
    )

private fun senderSafeFailureReason(reason: String): String {
    val normalized =
        reason
            .map { character ->
                when {
                    character == '&' -> '＆'
                    character == '§' || character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt() -> ' '
                    else -> character
                }
            }.joinToString("")
            .trim()
            .replace(Regex("\\s+"), " ")
            .ifEmpty { UNKNOWN_FAILURE_REASON }
    return if (normalized.length <= MAX_FAILURE_REASON_LENGTH) {
        normalized
    } else {
        normalized.take(MAX_FAILURE_REASON_LENGTH - 1).trimEnd() + ELLIPSIS
    }
}

private const val MAX_FAILURE_REASON_LENGTH = 240
private const val UNKNOWN_FAILURE_REASON = "unknown failure"
private const val ELLIPSIS = "…"
