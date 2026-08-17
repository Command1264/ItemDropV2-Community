package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.core.PluginMessageLanguage
import com.github.command1264.itemdropv2.platform.bukkit.BukkitPickupMessageCatalog
import com.github.command1264.itemdropv2.platform.bukkit.PickupMessageKey
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader

class PluginDescriptorTest {
    @Test
    fun `declares stable command aliases and management permissions`() {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("plugin.yml"))
        val descriptor = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }

        assertEquals(
            listOf("idrop", "drop"),
            descriptor.getStringList("commands.itemdrop.aliases"),
        )
        assertEquals(true, descriptor.getBoolean("permissions.itemdrop.commands.basic.default"))
        assertEquals(true, descriptor.getBoolean("permissions.itemdrop.commands.help.default"))
        assertEquals(true, descriptor.getBoolean("permissions.itemdrop.commands.info.default"))
        assertEquals("op", descriptor.getString("permissions.itemdrop.commands.reload.default"))
        assertEquals("op", descriptor.getString("permissions.itemdrop.commands.toggle.default"))
        assertEquals(true, descriptor.getBoolean("permissions.itemdrop.event.pickup.default"))
        assertEquals("op", descriptor.getString("permissions.itemdrop.event.pickup.other.default"))
    }

    @Test
    fun `embeds git build provenance without changing stable plugin version`() {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("plugin.yml"))
        val descriptor = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
        val shortCommit = requireNotNull(descriptor.getString("build.git-commit"))
        val dirty = requireNotNull(descriptor.getString("build.git-dirty"))

        assertEquals("1.0.0", descriptor.getString("version"))
        assertEquals(false, descriptor.contains("build.git-commit-full"))
        assertTrue(shortCommit == "unknown" || shortCommit.matches(Regex("[0-9a-f]{7}")))
        assertTrue(dirty in setOf("true", "false", "unknown"))
    }

    @Test
    fun `packages complete localized pickup messages`() {
        val catalog = BukkitPickupMessageCatalog.load(javaClass.classLoader)

        PluginMessageLanguage.BUILT_IN.forEach { language ->
            assertEquals(
                false,
                catalog.render(language, PickupMessageKey.NO_PERMISSION, mapOf("permission" to "node")).contains("%"),
            )
            assertEquals(
                false,
                catalog
                    .render(
                        language,
                        PickupMessageKey.OTHER_OWNER,
                        mapOf("player_name" to "Steve", "owner_names" to "Steve", "seconds" to "17"),
                    ).contains("%"),
            )
        }
    }

    @Test
    fun `packages configuration resources below the config directory`() {
        listOf(
            "config/config.yml",
            "config/config.en_us.yml",
            "config/item-lifetime.yml",
            "config/item-lifetime.en_us.yml",
            "config/languages/zh_tw.yml",
            "config/languages/en_us.yml",
        ).forEach { path ->
            requireNotNull(javaClass.classLoader.getResource(path)) { "missing resource $path" }
        }
        assertEquals(null, javaClass.classLoader.getResource("config.yml"))
        assertEquals(null, javaClass.classLoader.getResource("item-lifetime.yml"))
        assertEquals(null, javaClass.classLoader.getResource("languages/zh_tw.yml"))
    }
}
