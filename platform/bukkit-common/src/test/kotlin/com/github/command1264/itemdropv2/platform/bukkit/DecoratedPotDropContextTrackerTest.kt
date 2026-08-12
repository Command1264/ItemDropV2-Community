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
import org.bukkit.entity.Item
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

class DecoratedPotDropContextTrackerTest {
    @Test
    fun `claims four side materials and inventory content without duplicating amounts`() {
        val tracker = tracker()
        tracker.record(
            source(
                owner = FIRST_OWNER,
                expectedDrops =
                    listOf(
                        ItemStack(Material.BRICK),
                        ItemStack(Material.BRICK),
                        ItemStack(Material.BRICK),
                        ItemStack(Material.BRICK),
                        ItemStack(Material.DIAMOND, 32),
                    ),
            ),
        )

        assertEquals(FIRST_OWNER, tracker.claim(spawn(ItemStack(Material.BRICK, 4))))
        assertEquals(FIRST_OWNER, tracker.claim(spawn(ItemStack(Material.DIAMOND, 16))))
        assertEquals(FIRST_OWNER, tracker.claim(spawn(ItemStack(Material.DIAMOND, 16))))
        assertNull(tracker.claim(spawn(ItemStack(Material.DIAMOND, 1))))
        assertNull(tracker.claim(spawn(ItemStack(Material.BRICK, 1))))
    }

    @Test
    fun `uses the injected item similarity contract`() {
        val tracker =
            DecoratedPotDropContextTracker(
                DecoratedPotItemStackMatcher { _, _ -> false },
            )
        tracker.record(source(owner = FIRST_OWNER, expectedDrops = listOf(ItemStack(Material.DIAMOND))))

        assertNull(tracker.claim(spawn(ItemStack(Material.DIAMOND))))
    }

    @Test
    fun `keeps adjacent pots and newer same-position contexts isolated`() {
        val tracker = tracker()
        tracker.record(source(x = 10, owner = FIRST_OWNER, expectedDrops = listOf(ItemStack(Material.BRICK))))
        val oldId =
            tracker.record(source(x = 11, owner = FIRST_OWNER, expectedDrops = listOf(ItemStack(Material.DIAMOND))))
        val newId =
            tracker.record(source(x = 11, owner = SECOND_OWNER, expectedDrops = listOf(ItemStack(Material.DIAMOND))))

        tracker.expire(oldId)

        assertEquals(FIRST_OWNER, tracker.claim(spawn(ItemStack(Material.BRICK), x = 10)))
        assertEquals(SECOND_OWNER, tracker.claim(spawn(ItemStack(Material.DIAMOND), x = 11)))
        tracker.expire(newId)
        assertNull(tracker.claim(spawn(ItemStack(Material.DIAMOND), x = 11)))
    }

    @Test
    fun `rejects unknown drops and amounts above the snapshot`() {
        val tracker = tracker()
        tracker.record(source(owner = FIRST_OWNER, expectedDrops = listOf(ItemStack(Material.BRICK, 2))))

        assertNull(tracker.claim(spawn(ItemStack(Material.STONE))))
        assertNull(tracker.claim(spawn(ItemStack(Material.BRICK, 3))))
        assertEquals(FIRST_OWNER, tracker.claim(spawn(ItemStack(Material.BRICK, 2))))
    }

    @Test
    fun `reads four reflected sides and cloned inventory content`() {
        val inventory =
            proxy<Inventory> { method, _ ->
                when (method.name) {
                    "getContents" -> arrayOf(ItemStack(Material.DIAMOND, 7))
                    else -> defaultValue(method.returnType)
                }
            }
        val state =
            proxy<TestDecoratedPotState> { method, _ ->
                when (method.name) {
                    "getSherds" ->
                        linkedMapOf(
                            "front" to Material.BRICK,
                            "back" to Material.BRICK,
                            "left" to Material.BRICK,
                            "right" to Material.BRICK,
                        )
                    "getInventory" -> inventory
                    else -> defaultValue(method.returnType)
                }
            }

        val drops = requireNotNull(readDecoratedPotExpectedDrops(state))

        assertEquals(
            listOf(Material.BRICK, Material.BRICK, Material.BRICK, Material.BRICK, Material.DIAMOND),
            drops.map(ItemStack::getType),
        )
        assertEquals(7, drops.last().amount)
    }

    @Test
    fun `fails closed when a reflected pot does not expose exactly four sides`() {
        val state =
            proxy<TestDecoratedPotState> { method, _ ->
                when (method.name) {
                    "getSherds" -> mapOf("front" to Material.BRICK)
                    else -> defaultValue(method.returnType)
                }
            }

        assertNull(readDecoratedPotExpectedDrops(state))
    }

    @Test
    fun `assigns a pot drop before item spawn callback returns`() {
        val repository = RetryRecordingRepository(missingLoadsRemaining = 0)
        val tasks = mutableListOf<() -> Unit>()
        val tracker = tracker()
        tracker.record(source(owner = FIRST_OWNER, expectedDrops = listOf(ItemStack(Material.DIAMOND))))
        val controller =
            BukkitDecoratedPotDropOwnershipController(
                service = service(repository),
                delayedTaskExecutor = DelayedMainThreadTaskExecutor { _, task -> tasks += task },
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                itemRefresh = ItemOwnershipRefresh {},
                tracker = tracker,
                snapshotReader = DecoratedPotSnapshotReader { null },
            )
        val drop = item(ItemStack(Material.DIAMOND))

        controller.onItemSpawn(ItemSpawnEvent(drop))
        assertEquals(
            FIRST_OWNER,
            repository.states
                .getValue(drop.uniqueId)
                .ownership
                ?.ownerUuid,
        )
        assertEquals(1, repository.loadCalls)
        assertTrue(tasks.isEmpty())
    }

    private fun tracker(): DecoratedPotDropContextTracker =
        DecoratedPotDropContextTracker(
            DecoratedPotItemStackMatcher { expected, actual -> expected.type == actual.type },
        )

    private fun source(
        x: Int = 10,
        owner: UUID,
        expectedDrops: List<ItemStack>,
    ): DecoratedPotDropSource =
        DecoratedPotDropSource(
            worldId = WORLD_ID,
            x = x,
            y = 64,
            z = 20,
            ownerUuid = owner,
            expectedDrops = expectedDrops,
        )

    private fun spawn(
        itemStack: ItemStack,
        x: Int = 10,
    ): DecoratedPotItemSpawn =
        DecoratedPotItemSpawn(
            worldId = WORLD_ID,
            x = x,
            y = 64,
            z = 20,
            itemStack = itemStack,
        )

    private fun item(itemStack: ItemStack): Item {
        val id = UUID.randomUUID()
        return proxy { method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                "getWorld" -> world()
                "getLocation" -> Location(world(), 10.5, 64.2, 20.5)
                "getItemStack" -> itemStack
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

    private inline fun <reified T> proxy(crossinline answer: (Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method, args)
        } as T

    private interface TestDecoratedPotState : InventoryHolder {
        fun getSherds(): Map<String, Material>
    }

    private class RetryRecordingRepository(
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
        private val WORLD_ID = UUID.fromString("40000000-0000-0000-0000-000000000001")
        private val FIRST_OWNER = UUID.fromString("40000000-0000-0000-0000-000000000002")
        private val SECOND_OWNER = UUID.fromString("40000000-0000-0000-0000-000000000003")

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
