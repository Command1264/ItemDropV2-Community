package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.core.ItemStatePersistenceMode
import com.github.command1264.itemdropv2.core.ServerFingerprint
import com.github.command1264.itemdropv2.core.ServerPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupSelectionMessageTest {
    @Test
    fun `reports detected server and Paper client translation plan`() {
        val message =
            StartupSelectionMessage.format(
                fingerprint =
                    ServerFingerprint(
                        platform = ServerPlatform.PAPER,
                        minecraftVersion = "26.2",
                        implementationVersion = "Paper 26.2 build 87",
                    ),
                backendId = "paper-client-translation",
                persistenceMode = ItemStatePersistenceMode.ENTITY_PDC,
            )

        assertEquals(
            "ItemDropV2 detected server: platform=PAPER, minecraft=26.2, " +
                "implementation=Paper 26.2 build 87; selected scheme: " +
                "paper-client-translation (Paper Adventure client translation); " +
                "item-state persistence: ENTITY_PDC.",
            message,
        )
    }

    @Test
    fun `reports Bukkit compatible plan`() {
        val message =
            StartupSelectionMessage.format(
                fingerprint =
                    ServerFingerprint(
                        platform = ServerPlatform.SPIGOT,
                        minecraftVersion = "1.14",
                        implementationVersion = "git-Spigot-test",
                    ),
                backendId = "bukkit-entity-name",
                persistenceMode = ItemStatePersistenceMode.ENTITY_PDC_WITH_JOURNAL,
            )

        assertEquals(
            "ItemDropV2 detected server: platform=SPIGOT, minecraft=1.14, " +
                "implementation=git-Spigot-test; selected scheme: " +
                "bukkit-entity-name (Bukkit-compatible server-side names); " +
                "item-state persistence: ENTITY_PDC_WITH_JOURNAL.",
            message,
        )
    }

    @Test
    fun `removes control characters from server supplied fields`() {
        val message =
            StartupSelectionMessage.format(
                fingerprint =
                    ServerFingerprint(
                        platform = ServerPlatform.PAPER,
                        minecraftVersion = "26.2\nforged",
                        implementationVersion = "Paper\r\nspoofed warning",
                    ),
                backendId = "paper-client-translation",
                persistenceMode = ItemStatePersistenceMode.ENTITY_PDC,
            )

        assertEquals(false, message.contains('\n'))
        assertEquals(false, message.contains('\r'))
    }

    @Test
    fun `bounds server supplied fields`() {
        val message =
            StartupSelectionMessage.format(
                fingerprint =
                    ServerFingerprint(
                        platform = ServerPlatform.PAPER,
                        minecraftVersion = "26.2",
                        implementationVersion = "x".repeat(300),
                    ),
                backendId = "paper-client-translation",
                persistenceMode = ItemStatePersistenceMode.ENTITY_PDC,
            )

        assertTrue(message.contains("x".repeat(256)))
        assertFalse(message.contains("x".repeat(257)))
    }
}
