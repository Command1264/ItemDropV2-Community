package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.OfflinePlayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitPlaceholderApiTextExpanderTest {
    @Test
    fun `uses the selected owner as PlaceholderAPI player context`() {
        val ownerId = UUID.randomUUID()
        val owner = offlinePlayer(ownerId)
        val expander =
            BukkitPlaceholderApiTextExpander(
                offlinePlayerResolver = { id -> if (id == ownerId) owner else error("unexpected player") },
                placeholderExpansion = { player, text ->
                    assertSame(owner, player)
                    text.replace("%player_name%", "Steve")
                },
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
            )

        assertEquals("Owner: Steve", expander.expand(ownerId, "Owner: %player_name%"))
    }

    @Test
    fun `supports null context and safely preserves text after expansion failure`() {
        val warnings = mutableListOf<String>()
        val nullContext =
            BukkitPlaceholderApiTextExpander(
                offlinePlayerResolver = { error("resolver must not be called") },
                placeholderExpansion = { player, text ->
                    assertEquals(null, player)
                    text.replace("%server_online%", "3")
                },
                warningSink = DisplayWarningSink(warnings::add),
            )
        val failing =
            BukkitPlaceholderApiTextExpander(
                offlinePlayerResolver = { error("resolver failure") },
                placeholderExpansion = { _, _ -> error("expansion must not run") },
                warningSink = DisplayWarningSink(warnings::add),
            )

        assertEquals("Online: 3", nullContext.expand(null, "Online: %server_online%"))
        assertEquals("Owner: %player_name%", failing.expand(UUID.randomUUID(), "Owner: %player_name%"))
        assertEquals(listOf("PlaceholderAPI expansion failed (IllegalStateException)"), warnings)
    }

    @Test
    fun `safely preserves text after PlaceholderAPI linkage failure`() {
        val warnings = mutableListOf<String>()
        val expander =
            BukkitPlaceholderApiTextExpander(
                offlinePlayerResolver = { error("resolver must not be called") },
                placeholderExpansion = { _, _ -> throw NoClassDefFoundError("missing expansion") },
                warningSink = DisplayWarningSink(warnings::add),
            )

        assertEquals("Online: %server_online%", expander.expand(null, "Online: %server_online%"))
        assertEquals(listOf("PlaceholderAPI linkage failed (NoClassDefFoundError)"), warnings)
    }

    private fun offlinePlayer(id: UUID): OfflinePlayer =
        Proxy.newProxyInstance(
            OfflinePlayer::class.java.classLoader,
            arrayOf(OfflinePlayer::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                "toString" -> "OfflinePlayer($id)"
                else -> null
            }
        } as OfflinePlayer
}
