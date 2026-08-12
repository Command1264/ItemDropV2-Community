package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ItemStatePersistenceModeSelectorTest {
    @Test
    fun `selects journal for affected Spigot and Paper releases`() {
        val selector = ItemStatePersistenceModeSelector()

        listOf("1.14", "1.14.0", "1.14.1").forEach { version ->
            assertEquals(ItemStatePersistenceMode.ENTITY_PDC_WITH_JOURNAL, selector.select(fingerprint(ServerPlatform.SPIGOT, version)))
        }
        listOf("1.14.2", "1.14.4", "1.16.5", "26.2").forEach { version ->
            assertEquals(ItemStatePersistenceMode.ENTITY_PDC, selector.select(fingerprint(ServerPlatform.SPIGOT, version)))
        }
        listOf("1.14", "1.14.0", "1.14.1").forEach { version ->
            assertEquals(ItemStatePersistenceMode.ENTITY_PDC_WITH_JOURNAL, selector.select(fingerprint(ServerPlatform.PAPER, version)))
        }
        listOf("1.14.2", "1.14.4", "26.2").forEach { version ->
            assertEquals(ItemStatePersistenceMode.ENTITY_PDC, selector.select(fingerprint(ServerPlatform.PAPER, version)))
        }
    }

    @Test
    fun `rejects unknown malformed prerelease and below baseline fingerprints`() {
        val selector = ItemStatePersistenceModeSelector()

        listOf(
            fingerprint(ServerPlatform.UNKNOWN, "1.14"),
            fingerprint(ServerPlatform.SPIGOT, "1.13.2"),
            fingerprint(ServerPlatform.SPIGOT, "1.14.1-pre1"),
            fingerprint(ServerPlatform.SPIGOT, "unknown"),
        ).forEach { fingerprint ->
            assertEquals(ItemStatePersistenceMode.UNSUPPORTED, selector.select(fingerprint))
        }
    }

    private fun fingerprint(
        platform: ServerPlatform,
        version: String,
    ): ServerFingerprint = ServerFingerprint(platform, version, "test-implementation")
}
