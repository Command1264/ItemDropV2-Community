package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import com.github.command1264.itemdropv2.core.VirtualItemAmount
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitLegacyDrainVirtualItemMergeOperationsTest {
    @Test
    fun `ordinary pair remains available to native merge policy`() {
        val operations = operations(nativeState(), nativeState())

        assertEquals(VirtualItemMergePairMode.NotVirtual, operations.mode(item(SOURCE_ID), item(TARGET_ID)))
    }

    @Test
    fun `existing virtual pair is always legacy drain`() {
        val operations = operations(virtualState(128), virtualState(64))
        val source = item(SOURCE_ID)
        val target = item(TARGET_ID)

        assertEquals(VirtualItemMergePairMode.LegacyDrain, operations.mode(source, target))
        assertEquals(false, operations.canAttempt(source, target))
        assertEquals(
            VirtualItemMergeTransactionOutcome.Rejected("LegacyDrain"),
            operations.merge(source, target),
        )
    }

    @Test
    fun `virtual and absent pair fails closed as mixed state`() {
        val repository =
            ResultRepository(
                mapOf(
                    SOURCE_ID to ItemStateLoadResult.Loaded(virtualState(128)),
                    TARGET_ID to ItemStateLoadResult.Absent,
                ),
            )
        val operations = BukkitLegacyDrainVirtualItemMergeOperations(repository, { true })

        assertEquals(
            VirtualItemMergePairMode.Rejected("MixedVirtualState"),
            operations.mode(item(SOURCE_ID), item(TARGET_ID)),
        )
    }

    @Test
    fun `invalid state prevents merge without repository writes`() {
        val repository =
            ResultRepository(
                mapOf(
                    SOURCE_ID to ItemStateLoadResult.Invalid(listOf("bad state")),
                    TARGET_ID to ItemStateLoadResult.Loaded(nativeState()),
                ),
            )
        val operations = BukkitLegacyDrainVirtualItemMergeOperations(repository, { true })

        assertEquals(
            VirtualItemMergePairMode.Rejected("InvalidState"),
            operations.mode(item(SOURCE_ID), item(TARGET_ID)),
        )
        assertEquals(0, repository.saveCalls)
    }

    private fun operations(
        source: ItemState,
        target: ItemState,
    ): BukkitLegacyDrainVirtualItemMergeOperations =
        BukkitLegacyDrainVirtualItemMergeOperations(
            ResultRepository(
                mapOf(
                    SOURCE_ID to ItemStateLoadResult.Loaded(source),
                    TARGET_ID to ItemStateLoadResult.Loaded(target),
                ),
            ),
            { true },
        )

    private fun item(id: UUID): Item =
        Proxy.newProxyInstance(Item::class.java.classLoader, arrayOf(Item::class.java)) { _, method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                "isValid" -> true
                "isDead" -> false
                "getItemStack" -> ItemStack(Material.STONE, 1)
                else -> null
            }
        } as Item

    private fun nativeState(): ItemState = ItemState(null, 0, 300)

    private fun virtualState(amount: Long): ItemState = ItemState(null, 0, 300, VirtualItemAmount.of(amount))

    private class ResultRepository(
        private val results: Map<UUID, ItemStateLoadResult>,
    ) : ItemStateRepository {
        var saveCalls: Int = 0
            private set

        override fun load(entityId: UUID): ItemStateLoadResult = results.getValue(entityId)

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            saveCalls++
            return ItemStateWriteResult.Applied
        }
    }

    private companion object {
        private val SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000062")
        private val TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000063")
    }
}
