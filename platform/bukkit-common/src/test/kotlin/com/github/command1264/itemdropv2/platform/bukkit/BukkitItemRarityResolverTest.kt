package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.MinecraftItemRarity
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BukkitItemRarityResolverTest {
    @Test
    fun `uses server native rarity before versioned catalog`() {
        val resolver =
            VersionAwareBukkitItemRarityResolver(
                minecraftVersion = "1.14.4",
                nativeRarityAccess = NativeItemRarityAccess { MinecraftItemRarity.EPIC },
            )

        assertEquals(MinecraftItemRarity.EPIC, resolver.resolve(ItemStack(Material.STONE)))
    }

    @Test
    fun `falls back to the matching legacy version profile`() {
        val resolver =
            VersionAwareBukkitItemRarityResolver(
                minecraftVersion = "1.17.1",
                nativeRarityAccess = NativeItemRarityAccess { null },
            )

        assertEquals(MinecraftItemRarity.EPIC, resolver.resolve(ItemStack(Material.BARRIER)))
    }

    @Test
    fun `uses the versioned catalog when modern native access is unavailable on Spigot`() {
        val resolver =
            VersionAwareBukkitItemRarityResolver(
                minecraftVersion = "1.21.2",
                nativeRarityAccess = NativeItemRarityAccess { null },
            )

        assertEquals(MinecraftItemRarity.RARE, resolver.resolve(ItemStack(Material.BEACON)))
    }
}
