package com.github.command1264.itemdropv2.platform.bukkit.journal

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BukkitItemStateJournalFingerprintFactoryTest {
    private val factory =
        BukkitItemStateJournalFingerprintFactory(
            stackSerializer = { stack -> mapOf("type" to stack.type.name, "amount" to stack.amount) },
            materialKey = { material -> "minecraft:${material.name.lowercase()}" },
            ownedPdcRemover = {},
        )

    @Test
    fun `uses canonical material metadata identity while excluding mutable carrier amount`() {
        val one = fingerprint(ItemStack(Material.DIAMOND, 1))
        val sixtyFour = fingerprint(ItemStack(Material.DIAMOND, 64))
        val stone = fingerprint(ItemStack(Material.STONE, 1))

        assertEquals(one, sixtyFour)
        assertEquals("minecraft:diamond", one.materialKey)
        assertNotEquals(one, stone)
        assertTrue(one.metadataSha256.matches(Regex("[0-9a-f]{64}")))
    }

    private fun fingerprint(stack: ItemStack) =
        when (val result = factory.create(stack)) {
            is BukkitItemStateJournalFingerprintResult.Created -> result.fingerprint
            is BukkitItemStateJournalFingerprintResult.Failed -> error(result.errorType)
        }
}
