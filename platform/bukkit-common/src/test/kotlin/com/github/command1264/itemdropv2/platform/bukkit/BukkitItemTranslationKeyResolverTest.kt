package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BukkitItemTranslationKeyResolverTest {
    @Test
    fun `derives stable key on Spigot 1_14`() {
        val resolver = BukkitItemTranslationKeyResolver()

        assertEquals("block.minecraft.stone", resolver.resolve(ItemStack(Material.STONE)))
        assertEquals("item.minecraft.diamond_sword", resolver.resolve(ItemStack(Material.DIAMOND_SWORD)))
    }

    @Test
    fun `prefers modern Adventure translation key then Bukkit compatibility method`() {
        assertEquals(
            "item.minecraft.potion.effect.healing",
            resolveRuntimeTranslationKey(AdventureStack(), "item.minecraft.potion"),
        )
        assertEquals(
            "item.minecraft.legacy",
            resolveRuntimeTranslationKey(BukkitStack(), "item.minecraft.fallback"),
        )
    }

    @Suppress("FunctionOnlyReturningConstant")
    private class AdventureStack {
        fun translationKey(): String = "item.minecraft.potion.effect.healing"
    }

    @Suppress("FunctionOnlyReturningConstant")
    private class BukkitStack {
        fun getTranslationKey(): String = "item.minecraft.legacy"
    }
}
