package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class BukkitItemLifetimeSettingsLoaderTest {
    @Test
    fun `loads immediate forever positive and Long max values`() {
        val configuration =
            yaml(
                """
                schema-version: 1
                default-seconds: 9223372036854775807
                materials:
                  stone: 0
                  DIAMOND: -1
                  DIRT: 45
                """,
            )

        val loaded =
            assertInstanceOf(
                BukkitItemLifetimeSettingsLoadResult.Loaded::class.java,
                BukkitItemLifetimeSettingsLoader().load(configuration),
            )

        assertEquals(Long.MAX_VALUE, loaded.settings.defaultLifetimeSeconds)
        assertEquals(0, loaded.settings.resolve("STONE"))
        assertEquals(-1, loaded.settings.resolve("diamond"))
        assertEquals(45, loaded.settings.resolve("DIRT"))
        assertEquals(Long.MAX_VALUE, loaded.settings.resolve("COBBLESTONE"))
    }

    @Test
    fun `rejects values below minus one and duplicate normalized materials`() {
        val result =
            assertInstanceOf(
                BukkitItemLifetimeSettingsLoadResult.Invalid::class.java,
                BukkitItemLifetimeSettingsLoader().load(
                    yaml(
                        """
                        schema-version: 1
                        default-seconds: -2
                        materials:
                          stone: 1
                          STONE: 2
                        """,
                    ),
                ),
            )

        assertEquals(
            listOf(
                "default-seconds: expected -1, 0, or a positive number of seconds",
                "materials.STONE: duplicate Material after case normalization",
            ),
            result.errors,
        )
    }

    private fun yaml(raw: String): YamlConfiguration = YamlConfiguration().apply { loadFromString(raw.trimIndent()) }
}
