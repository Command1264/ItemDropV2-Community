package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Container
import org.bukkit.entity.Entity
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class BukkitInventoryMutationNotifierTest {
    @Test
    fun `block inventory refreshes a fresh block state with physics`() {
        val capture = BlockRefreshCapture()
        val inventory = blockInventory(capture)

        val result = BukkitInventoryMutationNotifier().notifyMutation(inventory)

        assertInstanceOf(InventoryMutationNotificationResult.Applied::class.java, result)
        assertEquals(listOf(false, true), capture.updateArguments)
        assertEquals(7, capture.snapshotContents.single()?.amount)
        assertEquals(Material.STONE, capture.snapshotContents.single()?.type)
    }

    private fun blockInventory(capture: BlockRefreshCapture): Inventory {
        val freshState = container(capture)
        val ordinary = block(Material.STONE, proxy { method, _ -> defaultValue(method.returnType) })
        val world =
            proxy<World> { method, _ ->
                when (method.name) {
                    "getBlockAt" -> ordinary
                    else -> defaultValue(method.returnType)
                }
            }
        val block =
            proxy<Block> { method, _ ->
                when (method.name) {
                    "getState" -> freshState
                    "getWorld" -> world
                    "getX", "getY", "getZ" -> 0
                    else -> defaultValue(method.returnType)
                }
            }
        val capturedState =
            multiProxy<InventoryHolder>(BlockState::class.java, InventoryHolder::class.java) { method, _ ->
                when (method.name) {
                    "getBlock" -> block
                    else -> defaultValue(method.returnType)
                }
            }
        return inventory(capturedState)
    }

    private fun container(capture: BlockRefreshCapture): Container {
        val liveInventory =
            proxy<Inventory> { method, _ ->
                when (method.name) {
                    "getStorageContents" -> arrayOf<ItemStack?>(ItemStack(Material.STONE, 7))
                    else -> defaultValue(method.returnType)
                }
            }
        val snapshotInventory =
            proxy<Inventory> { method, arguments ->
                when (method.name) {
                    "setStorageContents" -> captureSnapshotContents(capture, arguments)
                    else -> defaultValue(method.returnType)
                }
            }
        return proxy { method, arguments ->
            when (method.name) {
                "getInventory" -> liveInventory
                "getSnapshotInventory" -> snapshotInventory
                "update" -> captureUpdateArguments(capture, arguments)
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun captureSnapshotContents(
        capture: BlockRefreshCapture,
        arguments: Array<out Any?>?,
    ) {
        @Suppress("UNCHECKED_CAST")
        capture.snapshotContents = (arguments?.single() as Array<ItemStack?>)
    }

    private fun captureUpdateArguments(
        capture: BlockRefreshCapture,
        arguments: Array<out Any?>?,
    ): Boolean {
        capture.updateArguments = arguments.orEmpty().toList()
        return capture.updateResult
    }

    @Test
    fun `entity inventory refreshes detector rail below the entity`() {
        var detectorUpdates = 0
        val detectorState =
            proxy<BlockState> { method, _ ->
                when (method.name) {
                    "update" -> {
                        detectorUpdates++
                        true
                    }
                    else -> defaultValue(method.returnType)
                }
            }
        val detector =
            block(
                Material.DETECTOR_RAIL,
                detectorState,
            )
        val ordinary = block(Material.STONE, proxy { method, _ -> defaultValue(method.returnType) })
        val world =
            proxy<World> { method, arguments ->
                when (method.name) {
                    "getBlockAt" -> {
                        val coordinates = arguments.orEmpty().map { it as Int }
                        if (coordinates == listOf(10, 64, 10)) detector else ordinary
                    }
                    else -> defaultValue(method.returnType)
                }
            }
        val entity =
            multiProxy<InventoryHolder>(Entity::class.java, InventoryHolder::class.java) { method, _ ->
                when (method.name) {
                    "getLocation" -> Location(world, 10.5, 65.1, 10.5)
                    else -> defaultValue(method.returnType)
                }
            }

        val result = BukkitInventoryMutationNotifier().notifyMutation(inventory(entity))

        assertInstanceOf(InventoryMutationNotificationResult.Applied::class.java, result)
        assertEquals(1, detectorUpdates)
    }

    @Test
    fun `inventory without a world backed holder needs no redstone refresh`() {
        val holder = proxy<InventoryHolder> { method, _ -> defaultValue(method.returnType) }

        val result = BukkitInventoryMutationNotifier().notifyMutation(inventory(holder))

        assertInstanceOf(InventoryMutationNotificationResult.NotRequired::class.java, result)
    }

    @Test
    fun `rejected block state update returns an explicit failure`() {
        val result =
            BukkitInventoryMutationNotifier().notifyMutation(
                blockInventory(BlockRefreshCapture(updateResult = false)),
            )

        val failed = assertInstanceOf(InventoryMutationNotificationResult.Failed::class.java, result)
        assertEquals("BlockStateUpdateRejected", failed.errorType)
    }

    @Test
    fun `runtime exception returns a bounded failure type`() {
        val inventory =
            proxy<Inventory> { method, _ ->
                if (method.name == "getHolder") error("fixture failure") else defaultValue(method.returnType)
            }

        val result = BukkitInventoryMutationNotifier().notifyMutation(inventory)

        val failed = assertInstanceOf(InventoryMutationNotificationResult.Failed::class.java, result)
        assertEquals("IllegalStateException", failed.errorType)
    }

    private fun inventory(holder: InventoryHolder): Inventory =
        proxy { method, _ ->
            when (method.name) {
                "getHolder" -> holder
                else -> defaultValue(method.returnType)
            }
        }

    private fun block(
        material: Material,
        state: BlockState,
    ): Block =
        proxy { method, _ ->
            when (method.name) {
                "getType" -> material
                "getState" -> state
                else -> defaultValue(method.returnType)
            }
        }

    private inline fun <reified T> proxy(crossinline answer: (Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
            answer(method, arguments)
        } as T

    private inline fun <reified T> multiProxy(
        vararg interfaces: Class<*>,
        crossinline answer: (Method, Array<out Any?>?) -> Any?,
    ): T =
        Proxy.newProxyInstance(T::class.java.classLoader, interfaces) { _, method, arguments ->
            answer(method, arguments)
        } as T

    private companion object {
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

    private data class BlockRefreshCapture(
        var updateArguments: List<Any?> = emptyList(),
        var snapshotContents: Array<ItemStack?> = emptyArray(),
        val updateResult: Boolean = true,
    )
}
