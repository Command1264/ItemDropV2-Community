package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import org.bukkit.entity.Item
import org.bukkit.persistence.PersistentDataContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitItemStateRepositoryLeaseTest {
    @Test
    fun `overlapping transient target leases remain active until the last holder releases`() {
        val container = BukkitItemStateRepositoryTest.FakePersistentDataContainer()
        val repository = BukkitItemStateRepository({ null }, { true })
        val first = repository.leaseTransientTarget(item(container))
        val second = repository.leaseTransientTarget(item(container))
        val state = ItemState(null, remainingLifetimeSeconds = 300)

        first.release()
        assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, state))

        second.release()
        assertEquals(ItemStateLoadResult.MissingTarget, repository.load(ENTITY_UUID))
    }

    @Test
    fun `stale lease release does not remove a replacement transient target`() {
        val firstContainer = BukkitItemStateRepositoryTest.FakePersistentDataContainer()
        val replacementContainer = BukkitItemStateRepositoryTest.FakePersistentDataContainer()
        val repository = BukkitItemStateRepository({ null }, { true })
        val stale = repository.leaseTransientTarget(item(firstContainer))
        val replacement = repository.leaseTransientTarget(item(replacementContainer))
        val state = ItemState(null, remainingLifetimeSeconds = 120)

        stale.release()
        assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, state))
        assertEquals(
            state,
            assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID)).state,
        )

        replacement.release()
        assertEquals(ItemStateLoadResult.MissingTarget, repository.load(ENTITY_UUID))
    }

    @Test
    fun `releasing one lease twice does not consume another holder`() {
        val container = BukkitItemStateRepositoryTest.FakePersistentDataContainer()
        val repository = BukkitItemStateRepository({ null }, { true })
        val first = repository.leaseTransientTarget(item(container))
        val second = repository.leaseTransientTarget(item(container))
        val state = ItemState(null, remainingLifetimeSeconds = 60)

        first.release()
        first.release()

        assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, state))
        second.release()
        assertEquals(ItemStateLoadResult.MissingTarget, repository.load(ENTITY_UUID))
    }

    private fun item(container: PersistentDataContainer): Item =
        Proxy.newProxyInstance(
            Item::class.java.classLoader,
            arrayOf(Item::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getUniqueId" -> ENTITY_UUID
                "getPersistentDataContainer" -> container
                else -> null
            }
        } as Item

    private companion object {
        private val ENTITY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
