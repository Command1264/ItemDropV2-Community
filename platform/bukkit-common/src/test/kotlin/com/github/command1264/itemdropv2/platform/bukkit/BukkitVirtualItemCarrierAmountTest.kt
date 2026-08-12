package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.VirtualCarrierAmountMode
import com.github.command1264.itemdropv2.core.VirtualItemAmount
import com.github.command1264.itemdropv2.core.VirtualItemStackingSettings
import com.github.command1264.itemdropv2.core.VirtualStackingRuntimeModeResolver
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class BukkitVirtualItemCarrierAmountTest {
    @Test
    fun `legacy drain preserves the existing carrier amount while virtual amount remains larger`() {
        val fixture = item(ItemStack(Material.STONE, 17))

        fixture.item.applyVirtualCarrierAmount(
            VirtualItemAmount.of(8_128),
            settings(enabled = false),
            VirtualStackingRuntimeModeResolver(creationCapabilityAvailable = true),
        )

        assertEquals(17, fixture.amount())
    }

    @Test
    fun `legacy drain caps the carrier when remaining virtual amount falls below it`() {
        val fixture = item(ItemStack(Material.STONE, 64))

        fixture.item.applyVirtualCarrierAmount(
            VirtualItemAmount.of(40),
            settings(enabled = false),
            VirtualStackingRuntimeModeResolver(creationCapabilityAvailable = true),
        )

        assertEquals(40, fixture.amount())
    }

    private fun settings(enabled: Boolean): VirtualItemStackingSettings =
        VirtualItemStackingSettings(
            enabled = enabled,
            maximumAmountPerEntity = VirtualItemAmount.of(8_192),
            carrierAmountMode = VirtualCarrierAmountMode.PROPORTIONAL,
        )

    private fun item(initialStack: ItemStack): ItemFixture {
        var stack = initialStack
        val item =
            Proxy.newProxyInstance(
                Item::class.java.classLoader,
                arrayOf(Item::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "getItemStack" -> stack
                    "setItemStack" -> {
                        stack = arguments.orEmpty().single() as ItemStack
                        null
                    }
                    else -> null
                }
            } as Item
        return ItemFixture(item) { stack.amount }
    }

    private class ItemFixture(
        val item: Item,
        private val amountProvider: () -> Int,
    ) {
        fun amount(): Int = amountProvider()
    }
}
