package com.github.command1264.itemdropv2.platform.view.community

import com.github.command1264.itemdropv2.core.ServerFingerprint
import com.github.command1264.itemdropv2.core.ServerPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommunityPresentationBackendProviderTest {
    private val provider = CommunityPresentationBackendProvider()

    @Test
    fun `always uses Bukkit backend on supported servers`() {
        listOf(
            fingerprint(ServerPlatform.SPIGOT, "1.14"),
            fingerprint(ServerPlatform.PAPER, "1.14.4"),
            fingerprint(ServerPlatform.PAPER, "26.2"),
        ).forEach { fingerprint ->
            assertTrue(provider.incompatibilities(fingerprint).isEmpty())
            assertEquals("bukkit-entity-name", provider.createBackend(fingerprint).id)
        }
    }

    @Test
    fun `reports community identity`() {
        assertEquals("community-bukkit", provider.id)
        assertEquals("community", provider.artifactClassifier)
        assertEquals(false, provider.supportsPaperClientSideTranslationSetting)
        assertEquals(null, provider.configurationFragmentResource)
    }

    private fun fingerprint(
        platform: ServerPlatform,
        version: String,
    ): ServerFingerprint = ServerFingerprint(platform, version, "$platform-test")
}
