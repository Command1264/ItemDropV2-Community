package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.MinecraftItemRarity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MinecraftRarityCatalogTest {
    private val catalog = MinecraftRarityCatalog()

    @Test
    fun `keeps 1_14 rarity profile separate from 1_17 technical items`() {
        assertEquals(MinecraftItemRarity.COMMON, catalog.resolve("BARRIER", false, "1.14.4"))
        assertEquals(MinecraftItemRarity.COMMON, catalog.resolve("LIGHT", false, "1.16.5"))
        assertEquals(MinecraftItemRarity.EPIC, catalog.resolve("BARRIER", false, "1.17.1"))
        assertEquals(MinecraftItemRarity.EPIC, catalog.resolve("LIGHT", false, "1.17.1"))
    }

    @Test
    fun `classifies every music disc present on a legacy server without enumerating each disc`() {
        assertEquals(MinecraftItemRarity.RARE, catalog.resolve("MUSIC_DISC_13", false, "1.14.4"))
        assertEquals(MinecraftItemRarity.RARE, catalog.resolve("MUSIC_DISC_PIGSTEP", false, "1.16.5"))
        assertEquals(MinecraftItemRarity.RARE, catalog.resolve("MUSIC_DISC_OTHERSIDE", false, "1.18.2"))
        assertEquals(MinecraftItemRarity.RARE, catalog.resolve("MUSIC_DISC_5", false, "1.19.4"))
        assertEquals(MinecraftItemRarity.RARE, catalog.resolve("MUSIC_DISC_RELIC", false, "1.20.4"))
    }

    @Test
    fun `changes spawner from epic to common at the exact 1_19_3 boundary`() {
        assertEquals(MinecraftItemRarity.EPIC, catalog.resolve("SPAWNER", false, "1.19.2"))
        assertEquals(MinecraftItemRarity.COMMON, catalog.resolve("SPAWNER", false, "1.19.3"))
        assertEquals(
            MinecraftItemRarity.COMMON,
            catalog.resolve("SPAWNER", false, "1.19.3-R0.1-SNAPSHOT"),
        )
        assertEquals(MinecraftItemRarity.COMMON, catalog.resolve("SPAWNER", false, "1.20.4"))
        assertEquals(MinecraftItemRarity.RARE, catalog.resolve("BEACON", false, "1.20.6"))
    }

    @Test
    fun `tracks the 1_21 and 1_21_2 rarity rework boundaries`() {
        assertEquals(MinecraftItemRarity.EPIC, catalog.resolve("TRIDENT", false, "1.21.1"))
        assertEquals(MinecraftItemRarity.UNCOMMON, catalog.resolve("PIGLIN_BANNER_PATTERN", false, "1.21.1"))
        assertEquals(MinecraftItemRarity.UNCOMMON, catalog.resolve("ELYTRA", false, "1.21.1"))
        assertEquals(MinecraftItemRarity.EPIC, catalog.resolve("ELYTRA", false, "1.21.2"))
        assertEquals(MinecraftItemRarity.UNCOMMON, catalog.resolve("MUSIC_DISC_13", false, "1.21.2"))
        assertEquals(MinecraftItemRarity.RARE, catalog.resolve("BEACON", false, "1.21.2"))
        assertEquals(MinecraftItemRarity.RARE, catalog.resolve("ENCHANTED_GOLDEN_APPLE", false, "1.21.2"))
    }

    @Test
    fun `tracks post rework deltas without enumerating Material enums`() {
        assertEquals(MinecraftItemRarity.UNCOMMON, catalog.resolve("ENCHANTED_BOOK", false, "1.21.8"))
        assertEquals(MinecraftItemRarity.RARE, catalog.resolve("ENCHANTED_BOOK", false, "1.21.9"))
        assertEquals(MinecraftItemRarity.UNCOMMON, catalog.resolve("FLOW_POTTERY_SHERD", false, "26.2"))
        assertEquals(MinecraftItemRarity.UNCOMMON, catalog.resolve("MUSIC_DISC_BOUNCE", false, "26.2"))
        assertEquals(MinecraftItemRarity.RARE, catalog.resolve("MUSIC_DISC_LAVA_CHICKEN", false, "26.2"))
        assertEquals(MinecraftItemRarity.EPIC, catalog.resolve("DRAGON_EGG", false, "26.2"))
    }

    @Test
    fun `uses a safe common fallback for invalid or unsupported versions`() {
        assertEquals(MinecraftItemRarity.COMMON, catalog.resolve("BEACON", false, "invalid"))
        assertEquals(MinecraftItemRarity.COMMON, catalog.resolve("BEACON", false, "1.13.2"))
    }

    @Test
    fun `applies legacy enchantment rarity elevation`() {
        assertEquals(MinecraftItemRarity.RARE, catalog.resolve("STONE", true, "1.14.4"))
        assertEquals(MinecraftItemRarity.RARE, catalog.resolve("ELYTRA", true, "1.20.4"))
        assertEquals(MinecraftItemRarity.EPIC, catalog.resolve("ENCHANTED_GOLDEN_APPLE", true, "1.14.4"))
    }

    @Test
    fun `uses exact established rarity for representative vanilla items`() {
        assertEquals(MinecraftItemRarity.UNCOMMON, catalog.resolve("TOTEM_OF_UNDYING", false, "1.14.4"))
        assertEquals(MinecraftItemRarity.UNCOMMON, catalog.resolve("WITHER_SKELETON_SKULL", false, "1.14.4"))
        assertEquals(MinecraftItemRarity.RARE, catalog.resolve("BEACON", false, "1.14.4"))
        assertEquals(MinecraftItemRarity.EPIC, catalog.resolve("DRAGON_EGG", false, "1.14.4"))
        assertEquals(MinecraftItemRarity.UNCOMMON, catalog.resolve("PIGLIN_HEAD", false, "1.20.4"))
    }
}
