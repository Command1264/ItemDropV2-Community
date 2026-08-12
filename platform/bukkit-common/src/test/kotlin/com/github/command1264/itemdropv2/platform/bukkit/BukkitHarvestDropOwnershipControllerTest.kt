package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.BlockDropOwnershipService
import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitHarvestDropOwnershipControllerTest {
    @Test
    fun `attributes berries spawned by a mature right-click harvest`() {
        val fixture = Fixture()
        val drop = item(Material.SWEET_BERRIES, amount = 3)

        fixture.controller.onPlayerInteract(interact(blockData = "minecraft:sweet_berry_bush[age=3]"))
        fixture.controller.onItemSpawn(ItemSpawnEvent(drop))
        fixture.runTasks()

        assertEquals(
            OWNER_ID,
            fixture.repository.states
                .getValue(drop.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `ignores immature and denied interactions`() {
        val fixture = Fixture()
        fixture.controller.onPlayerInteract(interact(blockData = "minecraft:sweet_berry_bush[age=1]"))
        fixture.controller.onPlayerInteract(
            interact(blockData = "minecraft:sweet_berry_bush[age=3]").apply {
                setUseInteractedBlock(Event.Result.DENY)
            },
        )

        fixture.controller.onItemSpawn(ItemSpawnEvent(item(Material.SWEET_BERRIES, amount = 2)))
        fixture.runTasks()

        assertTrue(fixture.repository.states.isEmpty())
    }

    @Test
    fun `assigns harvest ownership without a delayed UUID lookup`() {
        val fixture = Fixture()
        val drop = item(Material.SWEET_BERRIES, amount = 3)

        fixture.controller.onPlayerInteract(interact(blockData = "minecraft:sweet_berry_bush[age=3]"))
        fixture.controller.onItemSpawn(ItemSpawnEvent(drop))
        assertEquals(
            OWNER_ID,
            fixture.repository.states
                .getValue(drop.uniqueId)
                .ownership
                ?.ownerUuid,
        )
        assertEquals(1, fixture.repository.loadCalls)
    }

    private fun interact(blockData: String): PlayerInteractEvent =
        PlayerInteractEvent(
            player(),
            Action.RIGHT_CLICK_BLOCK,
            null,
            block(blockData),
            BlockFace.UP,
            EquipmentSlot.HAND,
        )

    private fun block(blockData: String): Block =
        proxy { method, _ ->
            when (method.name) {
                "getType" -> Material.SWEET_BERRY_BUSH
                "getBlockData" -> blockData(blockData)
                "getX" -> 10
                "getY" -> 64
                "getZ" -> 20
                "getWorld" -> world()
                else -> defaultValue(method.returnType)
            }
        }

    private fun blockData(serialized: String): BlockData =
        proxy { method, _ ->
            when (method.name) {
                "getAsString" -> serialized
                "getMaterial" -> Material.SWEET_BERRY_BUSH
                else -> defaultValue(method.returnType)
            }
        }

    private fun item(
        material: Material,
        amount: Int,
    ): Item {
        val id = UUID.randomUUID()
        return proxy { method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                "getWorld" -> world()
                "getLocation" -> Location(world(), 10.5, 64.2, 20.5)
                "getItemStack" -> ItemStack(material, amount)
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun world(): World =
        proxy { method, _ ->
            when (method.name) {
                "getUID" -> WORLD_ID
                "getName" -> "world"
                else -> defaultValue(method.returnType)
            }
        }

    private fun player(): Player =
        proxy { method, _ ->
            when (method.name) {
                "getUniqueId" -> OWNER_ID
                else -> defaultValue(method.returnType)
            }
        }

    private inline fun <reified T> proxy(crossinline answer: (Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method, args)
        } as T

    private class Fixture(
        missingLoads: Int = 0,
    ) {
        val repository = RecordingRepository(missingLoads)
        private val tasks = mutableListOf<() -> Unit>()
        val controller =
            BukkitHarvestDropOwnershipController(
                service = service(repository),
                delayedTaskExecutor = DelayedMainThreadTaskExecutor { _, task -> tasks += task },
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                itemRefresh = ItemOwnershipRefresh {},
                tracker = HarvestDropContextTracker(),
            )

        fun runTasks() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).invoke()
        }
    }

    private class RecordingRepository(
        private var missingLoadsRemaining: Int,
    ) : ItemStateRepository {
        val states = mutableMapOf<UUID, ItemState>()
        var loadCalls = 0

        override fun load(entityId: UUID): ItemStateLoadResult {
            loadCalls += 1
            if (missingLoadsRemaining > 0) {
                missingLoadsRemaining -= 1
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
        private val WORLD_ID = UUID.fromString("50000000-0000-0000-0000-000000000001")
        private val OWNER_ID = UUID.fromString("50000000-0000-0000-0000-000000000002")

        private fun service(repository: ItemStateRepository): BlockDropOwnershipService {
            val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
            val settings = ItemDisplaySettings(true, emptySet(), template, template)
            return BlockDropOwnershipService(repository, ItemDisplaySettingsRepository { settings })
        }

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
