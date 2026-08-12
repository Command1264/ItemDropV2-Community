package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class BukkitMergeOwnershipSettingsLoaderTest {
    @Test
    fun `loads every ownership merge strategy case insensitively and defaults to average`() {
        val default = loaded(baseConfig())
        assertEquals("average", default.settings.merge.ownershipStrategy.configValue)

        listOf("average", "MAXIMUM", "Minimum", "reset").forEach { strategy ->
            val result = loaded(baseConfig("ownership-strategy: $strategy"))
            assertEquals(strategy.lowercase(), result.settings.merge.ownershipStrategy.configValue)
        }
    }

    @Test
    fun `rejects invalid ownership merge strategy type and value`() {
        listOf(
            "ownership-strategy: newest" to
                "items.merge.ownership-strategy: expected one of average, maximum, minimum, reset",
            "ownership-strategy: [average]" to "items.merge.ownership-strategy: expected string",
        ).forEach { (entry, expectedError) ->
            val invalid =
                assertInstanceOf(
                    BukkitDisplaySettingsLoadResult.Invalid::class.java,
                    BukkitDisplaySettingsLoader().load(yaml(baseConfig(entry))),
                )
            assertEquals(listOf(expectedError), invalid.errors)
        }
    }

    private fun loaded(raw: String): BukkitDisplaySettingsLoadResult.Loaded =
        assertInstanceOf(
            BukkitDisplaySettingsLoadResult.Loaded::class.java,
            BukkitDisplaySettingsLoader().load(yaml(raw)),
        )

    private fun baseConfig(ownershipEntry: String? = null): String =
        """
        schema-version: 1
        general:
          enabled: true
          blocked-worlds: []
        items:
          merge:
            lifetime-strategy: average
        ${ownershipEntry?.let { "    $it" }.orEmpty()}
          display-name-format:
            single: '%item_display_name%'
            multi: '%item_display_name% x%amount%'
        """.trimIndent()

    private fun yaml(raw: String): YamlConfiguration = YamlConfiguration().apply { loadFromString(raw) }
}
