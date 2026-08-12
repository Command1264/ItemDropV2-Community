package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ServerPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BukkitServerPlatformDetectorTest {
    @Test
    fun `accepts the official modern Paper brand`() {
        val detector = detector(classes = setOf(SERVER_BUILD_INFO), brandId = PAPER_BRAND_ID)

        assertEquals(ServerPlatform.PAPER, detector.detect(classLoader, "Paper implementation"))
    }

    @Test
    fun `rejects a modern Paper fork brand`() {
        val detector = detector(classes = setOf(SERVER_BUILD_INFO), brandId = "purpurmc:purpur")

        assertEquals(ServerPlatform.UNKNOWN, detector.detect(classLoader, "Purpur implementation"))
    }

    @Test
    fun `fails closed when a modern Paper brand cannot be read`() {
        val detector = detector(classes = setOf(SERVER_BUILD_INFO), brandId = null)

        assertEquals(ServerPlatform.UNKNOWN, detector.detect(classLoader, "git-Paper-unknown"))
    }

    @Test
    fun `accepts legacy Paper only with its marker and official version prefix`() {
        val official = detector(classes = setOf(LEGACY_PAPER_MARKER))
        val fork = detector(classes = setOf(LEGACY_PAPER_MARKER))

        assertEquals(ServerPlatform.PAPER, official.detect(classLoader, "git-Paper-245 (MC 1.14.4)"))
        assertEquals(ServerPlatform.UNKNOWN, fork.detect(classLoader, "git-Purpur-1234 (MC 1.16.5)"))
    }

    @Test
    fun `accepts official legacy and modern Spigot version formats`() {
        val detector = detector(classes = setOf(SPIGOT_MARKER))

        assertEquals(
            ServerPlatform.SPIGOT,
            detector.detect(classLoader, "git-Spigot-c8d4cef-1fee35b (MC: 1.14.4)"),
        )
        assertEquals(
            ServerPlatform.SPIGOT,
            detector.detect(classLoader, "4643-Spigot-8db49a2-08de3aa (MC: 26.2)"),
        )
        assertEquals(
            ServerPlatform.SPIGOT,
            detector.detect(classLoader, "3284a-Spigot-3892929-0ab8487 (MC: 1.17.1)"),
        )
        assertEquals(
            ServerPlatform.SPIGOT,
            detector.detect(classLoader, "9999-Spigot-deadbee-cafebabe (MC: 26.2)"),
        )
    }

    @Test
    fun `rejects CraftBukkit and Spigot shaped versions without the official marker`() {
        val officialMarker = detector(classes = setOf(SPIGOT_MARKER))
        val noMarker = detector()

        assertEquals(
            ServerPlatform.UNKNOWN,
            officialMarker.detect(classLoader, "4643-Bukkit-8db49a2-08de3aa (MC: 26.2)"),
        )
        assertEquals(
            ServerPlatform.UNKNOWN,
            noMarker.detect(classLoader, "4643-Spigot-8db49a2-08de3aa (MC: 26.2)"),
        )
        assertEquals(
            ServerPlatform.UNKNOWN,
            officialMarker.detect(classLoader, "release-Spigot-8db49a2-08de3aa (MC: 26.2)"),
        )
        assertEquals(
            ServerPlatform.UNKNOWN,
            officialMarker.detect(classLoader, "32ab-Spigot-8db49a2-08de3aa (MC: 1.17.1)"),
        )
    }

    @Test
    fun `extracts Minecraft version from legacy and date-based Bukkit versions`() {
        assertEquals("1.14.4", extractMinecraftVersion("1.14.4-R0.1-SNAPSHOT"))
        assertEquals("1.21.11", extractMinecraftVersion("1.21.11-R0.1-SNAPSHOT"))
        assertEquals("26.2", extractMinecraftVersion("26.2.build.62-beta"))
        assertEquals("1.21.11-pre5", extractMinecraftVersion("1.21.11-pre5-R0.1-SNAPSHOT"))
        assertEquals("1.21.11-rc3", extractMinecraftVersion("1.21.11-rc3-R0.1-SNAPSHOT"))
        assertEquals("26.2-rc-2", extractMinecraftVersion("26.2-rc-2.build.7-beta"))
        assertEquals("unknown-build", extractMinecraftVersion("unknown-build"))
    }

    private fun detector(
        classes: Set<String> = emptySet(),
        brandId: String? = null,
    ): BukkitServerPlatformDetector =
        BukkitServerPlatformDetector(
            classProbe = { _, className -> className in classes },
            paperBrandProbe = { brandId },
        )

    private companion object {
        private val classLoader = BukkitServerPlatformDetectorTest::class.java.classLoader
        private const val SERVER_BUILD_INFO = "io.papermc.paper.ServerBuildInfo"
        private const val LEGACY_PAPER_MARKER = "com.destroystokyo.paper.PaperConfig"
        private const val SPIGOT_MARKER = "org.spigotmc.SpigotConfig"
        private const val PAPER_BRAND_ID = "papermc:paper"
    }
}
