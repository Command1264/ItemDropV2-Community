package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentService
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.entity.Vehicle
import org.bukkit.event.entity.EntityDropItemEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitVehicleDropOwnershipControllerTest {
    @Test
    fun `assigns exact vehicle item drop and ignores its nested item spawn`() {
        val repository = RecordingRepository()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = controller(repository, tasks)
        val vehicle = vehicle(contents = emptyArray())
        val item = item(Material.MINECART, 1, 0.5, 100.0, 0.5)

        controller.onVehicleDestroy(VehicleDestroyEvent(vehicle, player()))
        controller.onEntityDropItem(EntityDropItemEvent(vehicle, item))
        controller.onItemSpawn(ItemSpawnEvent(item))

        assertEquals(listOf(1L), tasks.map { it.first })
        assertEquals(
            OWNER,
            repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `assigns storage contents emitted only through item spawn`() {
        val repository = RecordingRepository()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = controller(repository, tasks)
        val vehicle = vehicle(contents = arrayOf(ItemStack(Material.DIAMOND, 3)))
        val content = item(Material.DIAMOND, 3, 0.7, 100.1, 0.6)

        controller.onVehicleDestroy(VehicleDestroyEvent(vehicle, player()))
        controller.onItemSpawn(ItemSpawnEvent(content))

        assertEquals(listOf(1L), tasks.map { it.first })
        assertEquals(
            OWNER,
            repository.states
                .getValue(content.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `aggregates similar inventory stacks and accepts split item spawns`() {
        val repository = RecordingRepository()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = controller(repository, tasks)
        val vehicle = vehicle(contents = arrayOf(ItemStack(Material.DIAMOND, 2), ItemStack(Material.DIAMOND, 3)))
        val first = item(Material.DIAMOND, 4, 0.5, 100.0, 0.5)
        val second = item(Material.DIAMOND, 1, 0.5, 100.0, 0.5)

        controller.onVehicleDestroy(VehicleDestroyEvent(vehicle, player()))
        controller.onItemSpawn(ItemSpawnEvent(first))
        controller.onItemSpawn(ItemSpawnEvent(second))
        assertEquals(
            OWNER,
            repository.states
                .getValue(first.uniqueId)
                .ownership
                ?.ownerUuid,
        )
        assertEquals(
            OWNER,
            repository.states
                .getValue(second.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `ignores cancelled destroy and destroy without player contributor`() {
        val repository = RecordingRepository()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = controller(repository, tasks)
        val vehicle = vehicle(contents = arrayOf(ItemStack(Material.DIAMOND)))

        controller.onVehicleDestroy(VehicleDestroyEvent(vehicle, player()).apply { isCancelled = true })
        controller.onVehicleDestroy(VehicleDestroyEvent(vehicle, entity(UUID.randomUUID())))
        controller.onItemSpawn(ItemSpawnEvent(item(Material.DIAMOND, 1, 0.5, 100.0, 0.5)))

        assertTrue(tasks.isEmpty())
        assertTrue(repository.states.isEmpty())
    }

    @Test
    fun `does not claim inventory item from wrong world or distant position`() {
        val repository = RecordingRepository()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = controller(repository, tasks)
        val vehicle = vehicle(contents = arrayOf(ItemStack(Material.DIAMOND)))

        controller.onVehicleDestroy(VehicleDestroyEvent(vehicle, player()))
        controller.onItemSpawn(ItemSpawnEvent(item(Material.DIAMOND, 1, 0.5, 100.0, 0.5, "other")))
        controller.onItemSpawn(ItemSpawnEvent(item(Material.DIAMOND, 1, 10.5, 100.0, 0.5)))

        assertEquals(1, tasks.size)
        assertTrue(repository.states.isEmpty())
    }

    @Test
    fun `does not claim inventory item after context expiry`() {
        val repository = RecordingRepository()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = controller(repository, tasks)
        val vehicle = vehicle(contents = arrayOf(ItemStack(Material.DIAMOND)))

        controller.onVehicleDestroy(VehicleDestroyEvent(vehicle, player()))
        tasks.single().second()
        controller.onItemSpawn(ItemSpawnEvent(item(Material.DIAMOND, 1, 0.5, 100.0, 0.5)))

        assertEquals(1, tasks.size)
        assertTrue(repository.states.isEmpty())
    }

    private fun controller(
        repository: RecordingRepository,
        tasks: MutableList<Pair<Long, () -> Unit>>,
        warnings: MutableList<String>? = null,
    ): BukkitVehicleDropOwnershipController =
        BukkitVehicleDropOwnershipController(
            assignmentService = ItemOwnershipAssignmentService(repository, ItemDisplaySettingsRepository(::settings)),
            delayedTaskExecutor = DelayedMainThreadTaskExecutor { delay, task -> tasks += delay to task },
            warningSink = DisplayWarningSink { warning -> warnings?.add(warning) ?: error("unexpected warning: $warning") },
            itemRefresh = ItemOwnershipRefresh {},
            contexts = VehicleDropContextTracker(VehicleItemStackMatcher { expected, actual -> expected.type == actual.type }),
        )

    private fun settings(): ItemDisplaySettings {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return ItemDisplaySettings(true, emptySet(), template, template)
    }

    private fun player(): Player =
        proxy(arrayOf(Player::class.java)) { method, _ ->
            if (method.name == "getUniqueId") OWNER else defaultValue(method.returnType)
        }

    private fun entity(id: UUID): Entity =
        proxy(arrayOf(Entity::class.java)) { method, _ ->
            if (method.name == "getUniqueId") id else defaultValue(method.returnType)
        }

    private fun vehicle(
        contents: Array<ItemStack?>,
        worldName: String = "world",
    ): Vehicle {
        val world = world(worldName)
        val inventory = inventory(contents)
        return proxy(arrayOf(Vehicle::class.java, InventoryHolder::class.java)) { method, _ ->
            when (method.name) {
                "getUniqueId" -> VEHICLE
                "getWorld" -> world
                "getLocation" -> Location(world, 0.5, 100.0, 0.5)
                "getInventory" -> inventory
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun inventory(contents: Array<ItemStack?>): Inventory =
        proxy(arrayOf(Inventory::class.java)) { method, _ ->
            if (method.name == "getContents") contents.copyOf() else defaultValue(method.returnType)
        }

    private fun item(
        material: Material,
        amount: Int,
        x: Double,
        y: Double,
        z: Double,
        worldName: String = "world",
    ): Item {
        val id = UUID.randomUUID()
        val world = world(worldName)
        val stack = ItemStack(material, amount)
        return proxy(arrayOf(Item::class.java)) { method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                "getWorld" -> world
                "getLocation" -> Location(world, x, y, z)
                "getItemStack" -> stack
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun world(name: String): World =
        proxy(arrayOf(World::class.java)) { method, _ ->
            if (method.name == "getName") name else defaultValue(method.returnType)
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(
        interfaces: Array<Class<*>>,
        answer: (Method, Array<out Any?>?) -> Any?,
    ): T = Proxy.newProxyInstance(javaClass.classLoader, interfaces) { _, method, args -> answer(method, args) } as T

    private class RecordingRepository(
        private var missingTargetLoadsRemaining: Int = 0,
    ) : ItemStateRepository {
        val states = mutableMapOf<UUID, ItemState>()

        override fun load(entityId: UUID): ItemStateLoadResult {
            if (missingTargetLoadsRemaining > 0) {
                missingTargetLoadsRemaining--
                return ItemStateLoadResult.MissingTarget
            }
            return states[entityId]?.let(ItemStateLoadResult::Loaded) ?: ItemStateLoadResult.Absent
        }

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            states[entityId] = state
            return ItemStateWriteResult.Applied
        }
    }

    private companion object {
        private val VEHICLE = UUID.fromString("00000000-0000-0000-0000-000000000040")
        private val OWNER = UUID.fromString("00000000-0000-0000-0000-000000000041")

        private fun defaultValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                else -> null
            }
    }
}
