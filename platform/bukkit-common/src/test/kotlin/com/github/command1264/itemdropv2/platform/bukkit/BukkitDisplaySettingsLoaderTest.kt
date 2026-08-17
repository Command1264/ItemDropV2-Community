package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.VirtualCarrierAmountMode
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("LargeClass")
class BukkitDisplaySettingsLoaderTest {
    @Test
    fun `Pro policy strictly loads paper client-side translation switch`() {
        val configured =
            validConfig().replace(
                "items:\n",
                "items:\n  display:\n    paper-client-side-translation: false\n\n",
            )

        val loaded =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Loaded::class.java,
                BukkitDisplaySettingsLoader(paperClientSideTranslationSettingSupported = true).load(yaml(configured)),
            )

        assertEquals(false, loaded.settings.paperClientSideTranslationEnabled)
    }

    @Test
    fun `Pro policy rejects non-boolean paper client-side translation switch`() {
        val configured =
            validConfig().replace(
                "items:\n",
                "items:\n  display:\n    paper-client-side-translation: sometimes\n\n",
            )

        val invalid =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Invalid::class.java,
                BukkitDisplaySettingsLoader(paperClientSideTranslationSettingSupported = true).load(yaml(configured)),
            )

        assertTrue(invalid.errors.contains("items.display.paper-client-side-translation: expected boolean"))
    }

    @Test
    fun `Community policy ignores even an invalid Pro-only switch`() {
        val configured =
            validConfig().replace(
                "items:\n",
                "items:\n  display:\n    paper-client-side-translation: sometimes\n\n",
            )
        val loaded =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Loaded::class.java,
                BukkitDisplaySettingsLoader().load(yaml(configured)),
            )

        assertEquals(null, loaded.settings.paperClientSideTranslationEnabled)
    }

    @Test
    fun `rejects invalid owner rotation and misleading shared prefix`() {
        val invalid =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Invalid::class.java,
                BukkitDisplaySettingsLoader().load(
                    yaml(
                        validConfig()
                            .replace("rotation-seconds: 5", "rotation-seconds: 0")
                            .replace(
                                "multiple-owners-prefix: '&7[&a%player_name%&7]&e+%additional_owner_count%&r '",
                                "multiple-owners-prefix: '&7[&a%player_name%&7]&r '",
                            ),
                    ),
                ),
            )

        assertTrue(invalid.errors.any { it.startsWith("items.ownership.display.rotation-seconds:") })
        assertTrue(invalid.errors.any { it.contains("%additional_owner_count%") })
    }

    @Test
    fun `loads schema one into immutable domain settings`() {
        val result = BukkitDisplaySettingsLoader().load(yaml(validConfig()))
        val loaded = assertInstanceOf(BukkitDisplaySettingsLoadResult.Loaded::class.java, result)

        assertEquals(true, loaded.settings.enabled)
        assertEquals("zh_tw", loaded.settings.minecraftLanguage.value)
        assertEquals("en_us", loaded.settings.messageLanguage.code)
        assertEquals(setOf("world_nether", "event_world"), loaded.settings.blockedWorlds)
        assertEquals("Stone", loaded.settings.singleItemTemplate.render("Stone", 1))
        assertEquals("Stone &cx5", loaded.settings.multipleItemTemplate.render("Stone", 5))
        assertEquals(true, loaded.settings.ownership.enabled)
        assertEquals(256, loaded.settings.processing.maximumItemsPerTick)
        assertEquals(true, loaded.settings.rarityDisplayEnabled)
        assertEquals("average", loaded.settings.merge.lifetimeStrategy.configValue)
        assertEquals(false, loaded.settings.virtualStacking.enabled)
        assertEquals(8_192, loaded.settings.virtualStacking.maximumAmountPerEntity.value)
        assertEquals(30, loaded.settings.ownership.protectionSeconds)
        assertEquals("highest-damage", loaded.settings.ownership.entity.strategy.configValue)
        assertEquals(50.0, loaded.settings.ownership.entity.minimumDamagePercentOfMaxHealth)
        assertEquals(300, loaded.settings.ownership.entity.combatTimeoutSeconds)
        assertEquals(false, loaded.settings.ownership.pickup.allowHopperPickup)
        assertEquals("destroy", loaded.settings.ownership.pickup.creativeNoCapacityPickupMode.configValue)
        assertEquals(5, loaded.settings.ownership.pickup.warningCooldownSeconds)
        assertEquals("action-bar", loaded.settings.ownership.pickup.warningMessageType.configValue)
        assertEquals("無", loaded.settings.placeholders.noOwner)
        assertEquals("永久", loaded.settings.placeholders.lifetimePermanent)
        assertEquals("未知", loaded.settings.placeholders.lifetimeUnknown)
        assertEquals(
            "&7[&aSteve&7]&r ",
            loaded.settings.ownership.display.singleOwnerPrefixTemplate
                .render("Steve"),
        )
    }

    @Test
    fun `normalizes plugin and Minecraft language codes without case sensitivity`() {
        val configured =
            validConfig()
                .replace("language: en_us", "language: EN_US")
                .replace("minecraft-language: zh_tw", "minecraft-language: ZH_TW")

        val loaded =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Loaded::class.java,
                BukkitDisplaySettingsLoader().load(yaml(configured)),
            )

        assertEquals("en_us", loaded.settings.messageLanguage.code)
        assertEquals("zh_tw", loaded.settings.minecraftLanguage.value)
    }

    @Test
    fun `loads processing budget and rejects non-positive values`() {
        val configured =
            validConfig().replace(
                "items:\n",
                "items:\n  processing:\n    maximum-items-per-tick: 512\n",
            )
        val loaded =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Loaded::class.java,
                BukkitDisplaySettingsLoader().load(yaml(configured)),
            )

        assertEquals(512, loaded.settings.processing.maximumItemsPerTick)

        listOf("0", "-1", "1.5", "unlimited").forEach { invalidValue ->
            val invalid =
                assertInstanceOf(
                    BukkitDisplaySettingsLoadResult.Invalid::class.java,
                    BukkitDisplaySettingsLoader().load(
                        yaml(
                            configured.replace(
                                "maximum-items-per-tick: 512",
                                "maximum-items-per-tick: $invalidValue",
                            ),
                        ),
                    ),
                )
            assertTrue(
                invalid.errors.any { it.startsWith("items.processing.maximum-items-per-tick:") },
                "expected processing budget error for $invalidValue",
            )
        }
    }

    @Test
    fun `loads virtual stacking settings and rejects invalid maximum`() {
        val configured =
            validConfig().replace(
                "items:\n",
                "items:\n  virtual-stacking:\n    enabled: true\n    max-amount-per-entity: 9223372036854775807\n",
            )
        val loaded =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Loaded::class.java,
                BukkitDisplaySettingsLoader().load(yaml(configured)),
            )
        assertEquals(true, loaded.settings.virtualStacking.enabled)
        assertEquals(Long.MAX_VALUE, loaded.settings.virtualStacking.maximumAmountPerEntity.value)
        assertEquals(256, loaded.settings.virtualStacking.mergeScheduler.comparisonsPerTick)
        assertEquals(64, loaded.settings.virtualStacking.mergeScheduler.eventsPerTick)
        assertEquals(5L, loaded.settings.virtualStacking.mergeScheduler.retryBackoffSeconds)
        assertEquals(VirtualCarrierAmountMode.PROPORTIONAL, loaded.settings.virtualStacking.carrierAmountMode)

        listOf("0", "-1", "1.5", "unlimited").forEach { invalidValue ->
            val invalid =
                assertInstanceOf(
                    BukkitDisplaySettingsLoadResult.Invalid::class.java,
                    BukkitDisplaySettingsLoader().load(
                        yaml(
                            configured.replace(
                                "9223372036854775807",
                                invalidValue,
                            ),
                        ),
                    ),
                )
            assertTrue(
                invalid.errors.any { it.startsWith("items.virtual-stacking.max-amount-per-entity:") },
                "expected virtual stacking error for $invalidValue",
            )
        }
    }

    @Test
    fun `loads virtual carrier amount mode and rejects unknown values`() {
        VirtualCarrierAmountMode.entries.forEach { mode ->
            val configured =
                validConfig().replace(
                    "items:\n",
                    "items:\n  virtual-stacking:\n    carrier-amount-mode: ${mode.configValue}\n",
                )
            val loaded =
                assertInstanceOf(
                    BukkitDisplaySettingsLoadResult.Loaded::class.java,
                    BukkitDisplaySettingsLoader().load(yaml(configured)),
                )
            assertEquals(mode, loaded.settings.virtualStacking.carrierAmountMode)
        }

        listOf("native", "true", "PROPORTIONAL", "").forEach { invalidValue ->
            val invalid =
                assertInstanceOf(
                    BukkitDisplaySettingsLoadResult.Invalid::class.java,
                    BukkitDisplaySettingsLoader().load(
                        yaml(
                            validConfig().replace(
                                "items:\n",
                                "items:\n  virtual-stacking:\n    carrier-amount-mode: '$invalidValue'\n",
                            ),
                        ),
                    ),
                )
            assertTrue(
                invalid.errors.any { it.startsWith("items.virtual-stacking.carrier-amount-mode:") },
                "expected carrier amount mode error for '$invalidValue'",
            )
        }
    }

    @Test
    fun `loads native stack limit and unstackable item opt in`() {
        val configured =
            validConfig().replace(
                "items:\n",
                "items:\n" +
                    "  virtual-stacking:\n" +
                    "    enabled: true\n" +
                    "    maximum-native-stacks-per-entity: 128\n" +
                    "    unstackable-items:\n" +
                    "      enabled: true\n",
            )

        val loaded =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Loaded::class.java,
                BukkitDisplaySettingsLoader().load(yaml(configured)),
            )

        assertEquals(
            8_192L,
            loaded.settings.virtualStacking
                .maximumAmountFor(64)
                .value,
        )
        assertEquals(
            2_048L,
            loaded.settings.virtualStacking
                .maximumAmountFor(16)
                .value,
        )
        assertEquals(
            128L,
            loaded.settings.virtualStacking
                .maximumAmountFor(1)
                .value,
        )
        assertEquals(true, loaded.settings.virtualStacking.unstackableItemsEnabled)

        listOf("0", "-1", "1.5", "unlimited").forEach { invalidValue ->
            val invalid =
                assertInstanceOf(
                    BukkitDisplaySettingsLoadResult.Invalid::class.java,
                    BukkitDisplaySettingsLoader().load(
                        yaml(
                            configured.replace(
                                "maximum-native-stacks-per-entity: 128",
                                "maximum-native-stacks-per-entity: $invalidValue",
                            ),
                        ),
                    ),
                )
            assertTrue(
                invalid.errors.any {
                    it.startsWith("items.virtual-stacking.maximum-native-stacks-per-entity:")
                },
                "expected native stack limit error for $invalidValue",
            )
        }
    }

    @Test
    fun `prefers legacy absolute limit when repaired config contains both keys`() {
        val configured =
            validConfig().replace(
                "items:\n",
                "items:\n" +
                    "  virtual-stacking:\n" +
                    "    maximum-native-stacks-per-entity: 128\n" +
                    "    max-amount-per-entity: 8192\n",
            )

        val loaded =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Loaded::class.java,
                BukkitDisplaySettingsLoader().load(yaml(configured)),
            )

        assertEquals(
            8_192L,
            loaded.settings.virtualStacking
                .maximumAmountFor(64)
                .value,
        )
        assertEquals(
            8_192L,
            loaded.settings.virtualStacking
                .maximumAmountFor(1)
                .value,
        )
        assertEquals(null, loaded.settings.virtualStacking.maximumNativeStacksPerEntity)
    }

    @Test
    fun `validates bounded virtual merge scheduler budgets`() {
        val configured =
            validConfig().replace(
                "items:\n",
                "items:\n" +
                    "  virtual-stacking:\n" +
                    "    enabled: true\n" +
                    "    max-amount-per-entity: 8192\n" +
                    "    merge:\n" +
                    "      comparisons-per-tick: 8\n" +
                    "      events-per-tick: 9\n" +
                    "      retry-backoff-seconds: 0\n",
            )

        val invalid =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Invalid::class.java,
                BukkitDisplaySettingsLoader().load(yaml(configured)),
            )

        assertEquals(
            listOf(
                "items.virtual-stacking.merge.events-per-tick: expected value between 1 and 8",
                "items.virtual-stacking.merge.retry-backoff-seconds: expected value between 1 and 3600",
            ),
            invalid.errors,
        )
    }

    @Test
    fun `accepts custom plugin message locale and rejects unsafe locale syntax`() {
        val custom =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Loaded::class.java,
                BukkitDisplaySettingsLoader().load(
                    yaml(validConfig().replace("language: en_us", "language: ja_jp")),
                ),
            )
        assertEquals("ja_jp", custom.settings.messageLanguage.code)

        val invalid =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Invalid::class.java,
                BukkitDisplaySettingsLoader().load(
                    yaml(validConfig().replace("language: en_us", "language: ../../secret")),
                ),
            )
        assertEquals(
            listOf("general.language: expected locale such as zh_tw, en_us, or ja_jp"),
            invalid.errors,
        )
    }

    @Test
    fun `loads pickup policy and rejects invalid warning settings`() {
        val loaded =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Loaded::class.java,
                BukkitDisplaySettingsLoader().load(
                    yaml(
                        validConfig().replace(
                            "multiple-owners-prefix: '&7[&a%player_name%&7]&e+%additional_owner_count%&r '",
                            "multiple-owners-prefix: '&7[&a%player_name%&7]&e+%additional_owner_count%&r '\n" +
                                "    allow-hopper-pickup: true\n" +
                                "    creative-no-capacity-pickup: deny\n" +
                                "    pickup-warning-cooldown-seconds: 12\n" +
                                "    pickup-warning-message-type: chat",
                        ),
                    ),
                ),
            )
        assertEquals(true, loaded.settings.ownership.pickup.allowHopperPickup)
        assertEquals("deny", loaded.settings.ownership.pickup.creativeNoCapacityPickupMode.configValue)
        assertEquals(12, loaded.settings.ownership.pickup.warningCooldownSeconds)
        assertEquals("chat", loaded.settings.ownership.pickup.warningMessageType.configValue)

        val invalid =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Invalid::class.java,
                BukkitDisplaySettingsLoader().load(
                    yaml(
                        validConfig().replace(
                            "multiple-owners-prefix: '&7[&a%player_name%&7]&e+%additional_owner_count%&r '",
                            "multiple-owners-prefix: '&7[&a%player_name%&7]&e+%additional_owner_count%&r '\n" +
                                "    pickup-warning-cooldown-seconds: 3601\n" +
                                "    pickup-warning-message-type: title",
                        ),
                    ),
                ),
            )
        assertEquals(
            listOf(
                "items.ownership.pickup-warning-cooldown-seconds: expected value between 0 and 3600",
                "items.ownership.pickup-warning-message-type: expected one of action-bar, chat",
            ),
            invalid.errors,
        )
    }

    @Test
    fun `rejects invalid creative no capacity pickup mode`() {
        val invalid =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Invalid::class.java,
                BukkitDisplaySettingsLoader().load(
                    yaml(
                        validConfig().replace(
                            "protection-seconds: 30",
                            "protection-seconds: 30\n    creative-no-capacity-pickup: keep",
                        ),
                    ),
                ),
            )

        assertEquals(
            listOf("items.ownership.creative-no-capacity-pickup: expected one of destroy, deny"),
            invalid.errors,
        )
    }

    @Test
    fun `loads every supported merge lifetime strategy and rejects unknown values`() {
        listOf("average", "maximum", "minimum").forEach { strategy ->
            val loaded =
                assertInstanceOf(
                    BukkitDisplaySettingsLoadResult.Loaded::class.java,
                    BukkitDisplaySettingsLoader().load(
                        yaml(validConfig().replace("lifetime-strategy: average", "lifetime-strategy: $strategy")),
                    ),
                )
            assertEquals(strategy, loaded.settings.merge.lifetimeStrategy.configValue)
        }

        val invalid =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Invalid::class.java,
                BukkitDisplaySettingsLoader().load(yaml(validConfig().replace("lifetime-strategy: average", "lifetime-strategy: newest"))),
            )
        assertEquals(
            listOf("items.merge.lifetime-strategy: expected one of average, maximum, minimum"),
            invalid.errors,
        )
    }

    @Test
    fun `reports every invalid input path without silently applying defaults`() {
        val result =
            BukkitDisplaySettingsLoader().load(
                yaml(
                    """
                    schema-version: 2
                    general:
                      enabled: not-boolean
                      blocked-worlds: world
                    items:
                      display-name-format:
                        single: '%unknown%'
                        multi: 42
                    """.trimIndent(),
                ),
            )
        val invalid = assertInstanceOf(BukkitDisplaySettingsLoadResult.Invalid::class.java, result)

        assertEquals(
            listOf(
                "schema-version: expected integer 1",
                "general.enabled: expected boolean",
                "general.blocked-worlds: expected string list",
                "items.display-name-format.single: unsupported placeholder: %unknown%",
                "items.display-name-format.single: template must contain %item_display_name%",
                "items.display-name-format.multi: expected string",
            ),
            invalid.errors,
        )
    }

    @Test
    fun `accepts external PlaceholderAPI tokens in item and owner formats`() {
        val config =
            validConfig()
                .replace(
                    "single: '&f%item_display_name%'",
                    "single: '&f%item_display_name% %player_world%'",
                ).replace(
                    "single-owner-prefix: '&7[&a%player_name%&7]&r '",
                    "single-owner-prefix: '%vault_prefix%&7[&a%player_name%&7]&r '",
                )

        assertInstanceOf(
            BukkitDisplaySettingsLoadResult.Loaded::class.java,
            BukkitDisplaySettingsLoader().load(yaml(config)),
        )
    }

    @Test
    fun `loads configurable state placeholder labels and rejects unsafe values`() {
        val loaded =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Loaded::class.java,
                BukkitDisplaySettingsLoader().load(
                    yaml(
                        validConfig()
                            .replace(
                                "no-owner: '無'",
                                "no-owner: ''",
                            ).replace(
                                "lifetime-permanent: '永久'",
                                "lifetime-permanent: '永不消失'",
                            ),
                    ),
                ),
            )

        assertEquals("", loaded.settings.placeholders.noOwner)
        assertEquals("永不消失", loaded.settings.placeholders.lifetimePermanent)

        val invalid =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Invalid::class.java,
                BukkitDisplaySettingsLoader().load(
                    yaml(
                        validConfig()
                            .replace("lifetime-permanent: '永久'", "lifetime-permanent: ''")
                            .replace("lifetime-unknown: '未知'", "lifetime-unknown: 42"),
                    ),
                ),
            )
        assertEquals(
            listOf(
                "items.display-placeholders.lifetime-permanent: must not be blank",
                "items.display-placeholders.lifetime-unknown: expected string",
            ),
            invalid.errors,
        )
    }

    @Test
    fun `rejects blank duplicate and control-character world names`() {
        val config =
            validConfig().replace(
                "    - event_world",
                "    - '  world_nether  '\n    - ''\n    - \"bad\\nworld\"",
            )

        val invalid =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Invalid::class.java,
                BukkitDisplaySettingsLoader().load(yaml(config)),
            )

        assertEquals(
            listOf(
                "general.blocked-worlds[1]: duplicate world 'world_nether'",
                "general.blocked-worlds[2]: world name must not be blank",
                "general.blocked-worlds[3]: world name must not contain control characters",
            ),
            invalid.errors,
        )
    }

    @Test
    fun `rejects invalid ownership settings without partial defaults`() {
        val config =
            validConfig()
                .replace(
                    "enabled: true\n    protection-seconds: 30",
                    "enabled: not-boolean\n    protection-seconds: 604801",
                ).replace("single-owner-prefix: '&7[&a%player_name%&7]&r '", "single-owner-prefix: 'owner '")

        val invalid =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Invalid::class.java,
                BukkitDisplaySettingsLoader().load(yaml(config)),
            )

        assertEquals(
            listOf(
                "items.ownership.enabled: expected boolean",
                "items.ownership.protection-seconds: expected value between 0 and 604800",
                "items.ownership.display.single-owner-prefix: template must contain %player_name%",
            ),
            invalid.errors,
        )
    }

    @Test
    fun `loads entity attribution policy and rejects invalid values`() {
        val configured =
            validConfig().replace(
                "protection-seconds: 30",
                "protection-seconds: 30\n" +
                    "    entity:\n" +
                    "      strategy: final-hit\n" +
                    "      minimum-damage-percent-of-max-health: 25.5\n" +
                    "      combat-timeout-seconds: 60",
            )
        val loaded =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Loaded::class.java,
                BukkitDisplaySettingsLoader().load(yaml(configured)),
            )
        assertEquals("final-hit", loaded.settings.ownership.entity.strategy.configValue)
        assertEquals(25.5, loaded.settings.ownership.entity.minimumDamagePercentOfMaxHealth)
        assertEquals(60, loaded.settings.ownership.entity.combatTimeoutSeconds)

        val invalid =
            assertInstanceOf(
                BukkitDisplaySettingsLoadResult.Invalid::class.java,
                BukkitDisplaySettingsLoader().load(
                    yaml(
                        configured
                            .replace("strategy: final-hit", "strategy: weighted")
                            .replace("25.5", "101.0")
                            .replace("combat-timeout-seconds: 60", "combat-timeout-seconds: 0"),
                    ),
                ),
            )
        assertEquals(3, invalid.errors.size)
    }

    private fun yaml(content: String): YamlConfiguration = YamlConfiguration().apply { loadFromString(content) }

    private fun validConfig(): String =
        """
        schema-version: 1
        general:
          enabled: true
          language: en_us
          minecraft-language: zh_tw
          blocked-worlds:
            - world_nether
            - event_world
        items:
          merge:
            lifetime-strategy: average
          ownership:
            enabled: true
            protection-seconds: 30
            display:
              rotation-seconds: 5
              single-owner-prefix: '&7[&a%player_name%&7]&r '
              multiple-owners-prefix: '&7[&a%player_name%&7]&e+%additional_owner_count%&r '
          display-name-format:
            single: '%item_display_name%'
            multi: '%item_display_name% &cx%amount%'
          display-placeholders:
            no-owner: '無'
            lifetime-permanent: '永久'
            lifetime-unknown: '未知'
        """.trimIndent()
}
