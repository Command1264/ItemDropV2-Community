package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsManager
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsUpdateResult
import com.github.command1264.itemdropv2.core.LoadedItemRefreshView
import com.github.command1264.itemdropv2.core.ManagementCommandService
import com.github.command1264.itemdropv2.core.PluginMessageLanguage
import com.github.command1264.itemdropv2.platform.bukkit.BukkitManagementCommandController
import com.github.command1264.itemdropv2.platform.bukkit.BukkitManagementMessageCatalog
import com.github.command1264.itemdropv2.platform.bukkit.BukkitMessageCatalogReloadResult
import com.github.command1264.itemdropv2.platform.bukkit.BukkitMessageCatalogStore
import com.github.command1264.itemdropv2.platform.bukkit.ConfigParseRecoveryReport
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path

class BukkitManagementCommandControllerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `toggle accepts case insensitive state and reports repeated state in Traditional Chinese`() {
        val fixture = Fixture(PluginMessageLanguage.ZH_TW)

        fixture.execute("toggle", "OFF")
        fixture.execute("toggle", "off")

        assertEquals(false, fixture.repository.settings().enabled)
        assertEquals(1, fixture.refreshCount)
        assertTrue(fixture.messages[0].contains("已關閉"))
        assertTrue(fixture.messages[1].contains("已經是關閉狀態"))
    }

    @Test
    fun `invalid toggle arguments show English usage without changing state`() {
        val fixture = Fixture(PluginMessageLanguage.EN_US)

        fixture.execute("toggle", "maybe")

        assertEquals(true, fixture.repository.settings().enabled)
        assertEquals(listOf("§cUsage: /itemdrop toggle [on|off]"), fixture.messages)
    }

    @Test
    fun `permissions filter execution and tab completion`() {
        val fixture = Fixture(PluginMessageLanguage.EN_US, permissions = setOf(BASIC, HELP, INFO))

        fixture.execute("reload")

        assertTrue(fixture.messages.single().contains("do not have permission"))
        assertEquals(listOf("help", "info", "about"), fixture.complete(""))
        assertEquals(emptyList<String>(), fixture.complete("toggle", ""))
    }

    @Test
    fun `console sender receives root and toggle tab completion`() {
        val fixture =
            Fixture(
                PluginMessageLanguage.EN_US,
                senderType = ConsoleCommandSender::class.java,
            )

        assertEquals(listOf("help", "info", "about", "reload", "toggle"), fixture.complete(""))
        assertEquals(listOf("reload"), fixture.complete("re"))
        assertEquals(listOf("on", "off"), fixture.complete("toggle", ""))
        assertEquals(listOf("off"), fixture.complete("TOGGLE", "oF"))
    }

    @Test
    fun `about displays stable version and traceable dirty build`() {
        val fixture =
            Fixture(
                PluginMessageLanguage.EN_US,
                version = "1.0.0-SNAPSHOT (git 7123cbe0-dirty)",
            )

        fixture.execute("about")

        assertTrue(fixture.messages.single().contains("1.0.0-SNAPSHOT (git 7123cbe0-dirty)"))
    }

    @Test
    fun `info reads the current backend after a runtime switch`() {
        var backendId = "paper-client-translation"
        val fixture = Fixture(PluginMessageLanguage.EN_US, backendIdProvider = { backendId })

        fixture.execute("info")
        backendId = "bukkit-entity-name"
        fixture.execute("info")

        assertTrue(fixture.messages[0].contains("paper-client-translation"))
        assertTrue(fixture.messages[1].contains("bukkit-entity-name"))
    }

    @Test
    fun `reload tells the command sender when a custom locale received an English fallback file`() {
        val language = requireNotNull(PluginMessageLanguage.parse("ja_jp"))
        val store = BukkitMessageCatalogStore.fromResources(javaClass.classLoader, directory.toFile())
        assertEquals(BukkitMessageCatalogReloadResult.Applied, store.reload(language))
        val fixture =
            Fixture(
                language,
                catalog = BukkitManagementMessageCatalog.fromStore(store),
            )

        fixture.execute("reload")

        assertEquals(2, fixture.messages.size)
        assertTrue(fixture.messages[0].contains("configuration reloaded"))
        assertTrue(fixture.messages[1].contains("ja_jp.yml"))
        assertTrue(fixture.messages[1].contains("English"))
    }

    @Test
    fun `reload tells the command sender which invalid config paths were repaired and backed up`() {
        val fixture =
            Fixture(
                PluginMessageLanguage.ZH_TW,
                recoveryReport =
                    ConfigParseRecoveryReport(
                        repairedPaths = listOf("general.enabled", "items.merge.lifetime-strategy"),
                        documentRecreated = false,
                        backupFileName = "config.yml.parse-recovery-2026-08-10-14-00-00-UTC+08-00.bak",
                    ),
            )

        fixture.execute("reload")

        assertEquals(2, fixture.messages.size)
        assertTrue(fixture.messages[1].contains("general.enabled, items.merge.lifetime-strategy"))
        assertTrue(fixture.messages[1].contains("config.yml.parse-recovery-2026-08-10-14-00-00-UTC+08-00.bak"))
    }

    @Test
    fun `reload reports every repaired generated yaml document to the command sender`() {
        val fixture =
            Fixture(
                PluginMessageLanguage.ZH_TW,
                additionalRecoveryReports =
                    listOf(
                        ConfigParseRecoveryReport(
                            repairedPaths = listOf("default-seconds"),
                            documentRecreated = false,
                            backupFileName = "item-lifetime.yml.parse-recovery.bak",
                            fileName = "item-lifetime.yml",
                        ),
                        ConfigParseRecoveryReport(
                            repairedPaths = emptyList(),
                            documentRecreated = true,
                            backupFileName = "zh_tw.yml.parse-recovery.bak",
                            fileName = "zh_tw.yml",
                        ),
                    ),
            )

        fixture.execute("reload")

        assertEquals(3, fixture.messages.size)
        assertTrue(fixture.messages[1].contains("item-lifetime.yml:default-seconds"))
        assertTrue(fixture.messages[2].contains("zh_tw.yml"))
    }

    @Test
    fun `toggle also tells the command sender about a generated custom locale fallback`() {
        val language = requireNotNull(PluginMessageLanguage.parse("de_de"))
        val store = BukkitMessageCatalogStore.fromResources(javaClass.classLoader, directory.toFile())
        assertEquals(BukkitMessageCatalogReloadResult.Applied, store.reload(language))
        val fixture =
            Fixture(
                language,
                catalog = BukkitManagementMessageCatalog.fromStore(store),
            )

        fixture.execute("toggle", "off")

        assertEquals(2, fixture.messages.size)
        assertTrue(fixture.messages[0].contains("Dropped-item names are now disabled"))
        assertTrue(fixture.messages[1].contains("de_de.yml"))
        assertTrue(fixture.messages[1].contains("English"))
    }

    @Test
    fun `reload failure sends summary then sanitized reason to console and logs raw reason once`() {
        val raw = "structure:\nunsafe &a green §c text\u0007"
        val fixture =
            Fixture(
                PluginMessageLanguage.ZH_TW,
                failureReason = raw,
                senderType = ConsoleCommandSender::class.java,
            )

        fixture.execute("reload")

        assertEquals(2, fixture.messages.size)
        assertTrue(fixture.messages[0].contains("設定載入或寫入失敗"))
        assertEquals("§c原因：§fstructure: unsafe ＆a green c text", fixture.messages[1])
        assertEquals(listOf(raw), fixture.failureReasons)
    }

    @Test
    fun `toggle failure uses English reason label and safe blank fallback`() {
        val fixture = Fixture(PluginMessageLanguage.EN_US, failureReason = " \n\u0007 ")

        fixture.execute("toggle", "off")

        assertEquals(2, fixture.messages.size)
        assertTrue(fixture.messages[0].contains("Unable to update display state"))
        assertEquals("§cReason: §funknown failure", fixture.messages[1])
        assertEquals(listOf(" \n\u0007 "), fixture.failureReasons)
    }

    @Test
    fun `failure reason is bounded to one sender message`() {
        val fixture = Fixture(PluginMessageLanguage.EN_US, failureReason = "x".repeat(300))

        fixture.execute("reload")

        val reason = fixture.messages[1].removePrefix("§cReason: §f")
        assertEquals(240, reason.length)
        assertTrue(reason.endsWith("…"))
    }

    private class Fixture(
        language: PluginMessageLanguage,
        private val permissions: Set<String> = setOf(BASIC, HELP, INFO, RELOAD, TOGGLE),
        catalog: BukkitManagementMessageCatalog = BukkitManagementMessageCatalog.load(Fixture::class.java.classLoader),
        version: String = "1.0.0-SNAPSHOT",
        failureReason: String? = null,
        recoveryReport: ConfigParseRecoveryReport? = null,
        additionalRecoveryReports: List<ConfigParseRecoveryReport> = emptyList(),
        senderType: Class<out CommandSender> = CommandSender::class.java,
        backendIdProvider: () -> String = { "bukkit" },
    ) {
        val repository = FakeSettingsManager(settings(language), failureReason)
        val messages = mutableListOf<String>()
        val failureReasons = mutableListOf<String>()
        var refreshCount = 0
        private var pendingRecoveryReport = recoveryReport
        private var pendingAdditionalRecoveryReports = additionalRecoveryReports
        private val controller =
            BukkitManagementCommandController(
                service = ManagementCommandService(repository, LoadedItemRefreshView { refreshCount++ }),
                language = { repository.settings().messageLanguage },
                messages = catalog,
                version = version,
                backendIdProvider = backendIdProvider,
                failureLogger = failureReasons::add,
                configParseRecoveryConsumer = {
                    pendingRecoveryReport.also { pendingRecoveryReport = null }
                },
                additionalYamlRecoveryConsumer = {
                    pendingAdditionalRecoveryReports.also { pendingAdditionalRecoveryReports = emptyList() }
                },
            )
        private val sender: CommandSender = commandSender(permissions, messages, senderType)
        private val command = TestCommand()

        fun execute(vararg args: String) {
            controller.onCommand(sender, command, "itemdrop", args)
        }

        fun complete(vararg args: String): List<String> = controller.onTabComplete(sender, command, "itemdrop", args)
    }

    private class FakeSettingsManager(
        initial: ItemDisplaySettings,
        private val failureReason: String?,
    ) : ItemDisplaySettingsManager {
        private var value = initial

        override fun settings(): ItemDisplaySettings = value

        override fun setEnabled(enabled: Boolean): ItemDisplaySettingsUpdateResult {
            failureReason?.let { reason -> return ItemDisplaySettingsUpdateResult.Failed(reason) }
            value = value.copy(enabled = enabled)
            return ItemDisplaySettingsUpdateResult.Applied(value)
        }

        override fun reload(): ItemDisplaySettingsUpdateResult =
            failureReason?.let(ItemDisplaySettingsUpdateResult::Failed) ?: ItemDisplaySettingsUpdateResult.Applied(value)
    }

    private class TestCommand : Command("itemdrop") {
        override fun execute(
            sender: CommandSender,
            commandLabel: String,
            args: Array<out String>,
        ): Boolean = false
    }

    private companion object {
        private const val BASIC = "itemdrop.commands.basic"
        private const val HELP = "itemdrop.commands.help"
        private const val INFO = "itemdrop.commands.info"
        private const val RELOAD = "itemdrop.commands.reload"
        private const val TOGGLE = "itemdrop.commands.toggle"

        private fun settings(language: PluginMessageLanguage): ItemDisplaySettings =
            ItemDisplaySettings(
                enabled = true,
                blockedWorlds = emptySet(),
                singleItemTemplate = template("%item_display_name%"),
                multipleItemTemplate = template("%item_display_name% x%amount%"),
                messageLanguage = language,
            )

        private fun template(value: String): DisplayTemplate = (DisplayTemplate.parse(value) as DisplayTemplateParseResult.Valid).template

        private fun commandSender(
            permissions: Set<String>,
            messages: MutableList<String>,
            senderType: Class<out CommandSender>,
        ): CommandSender =
            Proxy.newProxyInstance(
                senderType.classLoader,
                arrayOf(senderType),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "hasPermission" -> arguments?.firstOrNull() in permissions
                    "sendMessage" -> {
                        when (val message = arguments?.firstOrNull()) {
                            is String -> messages += message
                            is Array<*> -> messages += message.filterIsInstance<String>()
                        }
                        Unit
                    }
                    "getName" -> "TestSender"
                    "isOp" -> false
                    "setOp" -> Unit
                    "toString" -> "TestCommandSender"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
            } as CommandSender

        private fun defaultValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                java.lang.Float.TYPE -> 0f
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }
    }
}
