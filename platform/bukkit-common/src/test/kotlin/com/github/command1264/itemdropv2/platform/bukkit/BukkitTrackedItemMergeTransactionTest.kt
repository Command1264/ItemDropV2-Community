package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemOwnership
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitTrackedItemMergeTransactionTest {
    @Test
    fun `merges tracked stacks without delegating a second mutation to the server`() {
        val repository = RecordingRepository()
        val refreshed = mutableListOf<UUID>()
        val source = item(SOURCE_ID, 20)
        val target = item(TARGET_ID, 30)
        val transaction =
            BukkitTrackedItemMergeTransaction(
                repository,
                ItemOwnershipRefresh(refreshed::add),
                DisplayWarningSink { error("unexpected warning: $it") },
            )

        assertTrue(transaction.merge(source.item, target.item, STATE))

        assertTrue(source.removed)
        assertEquals(50, target.stack.amount)
        assertEquals(listOf(TARGET_ID to STATE), repository.saved)
        assertEquals(listOf(TARGET_ID), refreshed)
    }

    @Test
    fun `keeps a partial source stack when the native maximum is reached`() {
        val source = item(SOURCE_ID, 20)
        val target = item(TARGET_ID, 60)
        val refreshed = mutableListOf<UUID>()
        val transaction =
            BukkitTrackedItemMergeTransaction(
                RecordingRepository(),
                ItemOwnershipRefresh(refreshed::add),
                DisplayWarningSink { error("unexpected warning: $it") },
            )

        assertTrue(transaction.merge(source.item, target.item, STATE))

        assertFalse(source.removed)
        assertEquals(16, source.stack.amount)
        assertEquals(64, target.stack.amount)
        assertEquals(listOf(SOURCE_ID, TARGET_ID), refreshed)
    }

    private fun item(
        id: UUID,
        amount: Int,
    ): ItemHolder {
        val holder = ItemHolder(ItemStack(Material.STONE, amount))
        holder.item =
            Proxy.newProxyInstance(Item::class.java.classLoader, arrayOf(Item::class.java)) { _, method, args ->
                when (method.name) {
                    "getUniqueId" -> id
                    "getItemStack" -> holder.stack
                    "setItemStack" -> {
                        holder.stack = args.orEmpty().single() as ItemStack
                        null
                    }
                    "remove" -> {
                        holder.removed = true
                        null
                    }
                    else -> defaultValue(method.returnType)
                }
            } as Item
        return holder
    }

    private class ItemHolder(
        var stack: ItemStack,
        var removed: Boolean = false,
    ) {
        lateinit var item: Item
    }

    private class RecordingRepository : ItemStateRepository {
        val saved = mutableListOf<Pair<UUID, ItemState>>()

        override fun load(entityId: UUID): ItemStateLoadResult = ItemStateLoadResult.Absent

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            saved += entityId to state
            return ItemStateWriteResult.Applied
        }
    }

    private companion object {
        private val SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003")
        private val STATE = ItemState(ItemOwnership(OWNER_ID, 30), 0, 100)

        private fun defaultValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0F
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }
    }
}
