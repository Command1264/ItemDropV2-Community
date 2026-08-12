package com.github.command1264.itemdropv2.platform.view.bukkit

import com.github.command1264.itemdropv2.core.ServerFingerprint
import com.github.command1264.itemdropv2.core.ServerPlatform
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BukkitPresentationBackendProviderTest {
    private val provider = BukkitPresentationBackendProvider()

    @Test
    fun `accepts supported Spigot and Paper versions`() {
        assertTrue(provider.incompatibilities(fingerprint(ServerPlatform.SPIGOT, "1.14.4")).isEmpty())
        assertTrue(provider.incompatibilities(fingerprint(ServerPlatform.PAPER, "26.2")).isEmpty())
    }

    @Test
    fun `rejects old or unknown servers`() {
        assertFalse(provider.incompatibilities(fingerprint(ServerPlatform.SPIGOT, "1.13.2")).isEmpty())
        assertFalse(provider.incompatibilities(fingerprint(ServerPlatform.UNKNOWN, "1.21.11")).isEmpty())
    }

    private fun fingerprint(
        platform: ServerPlatform,
        version: String,
    ): ServerFingerprint =
        ServerFingerprint(
            platform = platform,
            minecraftVersion = version,
            implementationVersion = "test",
        )
}
